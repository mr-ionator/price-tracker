import pytest
from bs4 import BeautifulSoup

from app.scrapers import site_for_url
from app.scrapers.amazon import AmazonScraper
from app.scrapers.base import ScrapeError, extract_jsonld_price, parse_price_text
from app.scrapers.generic import GenericScraper


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("€149.00", 149.0),
        ("149,00 €", 149.0),
        ("€1,299.99", 1299.99),
        ("1.299,99", 1299.99),
        ("EUR 89", 89.0),
        ("Now €59.99 was €79.99", 59.99),
        ("1.299", 1299.0),
    ],
)
def test_parse_price_text(text, expected):
    assert parse_price_text(text) == expected


def test_parse_price_text_rejects_garbage():
    with pytest.raises(ScrapeError):
        parse_price_text("out of stock")


def test_site_detection():
    assert site_for_url("https://www.amazon.ie/dp/B0ABC") == "amazon"
    assert site_for_url("https://www.paradigit.ie/x/1/product") == "paradigit"
    assert site_for_url("https://www.currys.ie/products/laptop-123.html") == "currys"
    assert site_for_url("https://example.com/item") == "generic"


JSONLD_HTML = """
<html><head>
<script type="application/ld+json">
{"@context":"https://schema.org","@type":"Product","name":"Test Laptop",
 "offers":{"@type":"Offer","price":"1049.99","priceCurrency":"EUR"}}
</script>
</head><body></body></html>
"""


def test_jsonld_extraction():
    soup = BeautifulSoup(JSONLD_HTML, "html.parser")
    result = extract_jsonld_price(soup)
    assert result is not None
    assert result.price == 1049.99
    assert result.currency == "EUR"


def test_generic_scraper_parses_jsonld():
    result = GenericScraper().parse(JSONLD_HTML)
    assert result.price == 1049.99


AMAZON_HTML = """
<html><body>
<div id="corePriceDisplay_desktop_feature_div">
  <span class="a-price"><span class="a-offscreen">€329,99</span>
    <span class="a-price-whole">329<span class="a-price-decimal">,</span></span>
    <span class="a-price-fraction">99</span>
  </span>
</div>
</body></html>
"""


def test_amazon_buybox_price():
    result = AmazonScraper().parse(AMAZON_HTML)
    assert result.price == 329.99


def test_amazon_captcha_detection():
    html = '<html><body><form action="/errors/validateCaptcha"></form></body></html>'
    with pytest.raises(ScrapeError, match="captcha"):
        AmazonScraper().parse(html)
