import pytest

from app.scrapers.base import BaseScraper, ScrapeResult

PRICES = {"call": 0}


@pytest.fixture(autouse=True)
def fake_scraper(monkeypatch):
    """Every scrape returns a controllable price without touching the network."""
    PRICES["value"] = 100.0

    async def fake_scrape(self, url):
        return ScrapeResult(price=PRICES["value"], currency="EUR")

    monkeypatch.setattr(BaseScraper, "scrape", fake_scrape)


def make_product(client, name="Test Laptop"):
    response = client.post(
        "/products",
        json={
            "name": name,
            "urls": [
                {"url": "https://www.amazon.ie/dp/B0TEST"},
                {"url": "https://www.currys.ie/products/test-1.html"},
            ],
        },
    )
    assert response.status_code == 201, response.text
    return response.json()


def test_create_and_list_product(client):
    product = make_product(client)
    assert product["name"] == "Test Laptop"
    sites = {u["site"] for u in product["urls"]}
    assert sites == {"amazon", "currys"}
    # Initial scrape ran on creation:
    assert all(u["latest_price"]["price"] == 100.0 for u in product["urls"])

    listed = client.get("/products").json()
    assert len(listed) == 1


def test_price_change_creates_notification(client):
    product = make_product(client)

    PRICES["value"] = 80.0
    response = client.post(f"/products/{product['id']}/refresh")
    assert response.status_code == 200
    assert all(u["latest_price"]["price"] == 80.0 for u in response.json()["urls"])

    notifications = client.get("/notifications").json()
    assert any(n["kind"] == "price_change" for n in notifications)
    body = next(n for n in notifications if n["kind"] == "price_change")["body"]
    assert "€100.00" in body and "€80.00" in body


def test_alert_fires_when_target_met(client):
    product = make_product(client)

    response = client.post(f"/products/{product['id']}/alerts", json={"target_price": 90})
    assert response.status_code == 201
    assert response.json()["triggered"] is False  # current price 100 > 90

    PRICES["value"] = 85.0
    client.post(f"/products/{product['id']}/refresh")

    notifications = client.get("/notifications").json()
    alerts = [n for n in notifications if n["kind"] == "alert"]
    assert len(alerts) == 1
    assert "target" in alerts[0]["body"]

    # No duplicate alert while the price stays below target.
    client.post(f"/products/{product['id']}/refresh")
    notifications = client.get("/notifications").json()
    assert len([n for n in notifications if n["kind"] == "alert"]) == 1


def test_alert_created_below_current_price_fires_immediately(client):
    product = make_product(client)
    client.post(f"/products/{product['id']}/alerts", json={"target_price": 150})
    notifications = client.get("/notifications").json()
    assert any(n["kind"] == "alert" for n in notifications)


def test_notification_delivery_flow(client):
    product = make_product(client)
    PRICES["value"] = 50.0
    client.post(f"/products/{product['id']}/refresh")

    undelivered = client.get("/notifications", params={"undelivered_only": True}).json()
    assert undelivered
    ids = [n["id"] for n in undelivered]

    assert client.post("/notifications/mark-delivered", json=ids).status_code == 204
    assert client.get("/notifications", params={"undelivered_only": True}).json() == []


def test_delete_product(client):
    product = make_product(client)
    assert client.delete(f"/products/{product['id']}").status_code == 204
    assert client.get("/products").json() == []
    assert client.get(f"/products/{product['id']}").status_code == 404
