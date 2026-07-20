# Price Tracker

Track product prices across **amazon.ie**, **paradigit.ie** and **currys.ie**,
set target-price alerts, and get notified of every price change by
**push notification** and/or **email**.

Two parts:

| Part | Tech | Where it runs |
|---|---|---|
| [`backend/`](backend) | Python · FastAPI · SQLite | Docker container on your WSL2 machine |
| [`android/`](android) | Kotlin · Jetpack Compose | Your Android phone (built with Android Studio) |

## How it works

1. You add a product in the Android app with its URL on one or more of the
   supported shops.
2. The backend scrapes each URL on a schedule (hourly by default), keeps a
   price history, and records an event whenever a price changes or drops
   below one of your alert targets.
3. Events reach you three ways:
   - **App push notifications** — the app polls the backend in the background
     (WorkManager, ~15 min) and raises Android notifications. Zero setup.
   - **Email** — configure SMTP in `.env`.
   - **Instant push via [ntfy](https://ntfy.sh)** *(optional)* — set a topic in
     `.env`, subscribe to it in the ntfy app, get pushes within seconds.

Scrapers use site-specific selectors first and fall back to schema.org
JSON-LD / metadata parsing, so minor site redesigns usually don't break them.
Amazon and Currys run aggressive bot protection; if a check is blocked the
error is stored per-URL and shown in the app, and the next scheduled check
retries automatically.

## 1. Run the backend (Docker on WSL2)

Requires Docker Desktop with WSL integration (or any Docker engine in WSL).

```bash
cp .env.example .env        # optional: fill in SMTP / ntfy settings
docker compose up -d --build
curl http://localhost:8000/health
```

The SQLite database lives in the `pricetracker-data` volume and survives
rebuilds. API docs are served at <http://localhost:8000/docs>.

### Configuration (`.env`)

| Variable | Default | Meaning |
|---|---|---|
| `CHECK_INTERVAL_MINUTES` | `60` | How often all prices are re-checked |
| `SCRAPE_DELAY_SECONDS` | `5` | Pause between scrapes in one run |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASSWORD` / `SMTP_FROM` | – | Outgoing mail server (for Gmail use an [App Password](https://myaccount.google.com/apppasswords)) |
| `ALERT_EMAIL_TO` | – | Where alert emails go; empty disables email |
| `NTFY_SERVER` / `NTFY_TOPIC` | `https://ntfy.sh` / – | ntfy instant push; empty topic disables it |

## 2. Reach the backend from your phone

WSL2 has its own virtual network, so a phone on your Wi-Fi cannot reach it
directly. Two options:

**Option A — mirrored networking (Windows 11 22H2+, easiest).**
Add to `%UserProfile%\.wslconfig` on Windows, then run `wsl --shutdown`:

```ini
[wsl2]
networkingMode=mirrored
```

The WSL container now listens on your PC's LAN IP directly
(`ipconfig` → e.g. `192.168.1.50`).

**Option B — port forwarding.** In an *administrator* PowerShell:

```powershell
netsh interface portproxy add v4tov4 listenport=8000 listenaddress=0.0.0.0 connectport=8000 connectaddress=(wsl hostname -I).Trim()
New-NetFirewallRule -DisplayName "Price Tracker 8000" -Direction Inbound -LocalPort 8000 -Protocol TCP -Action Allow
```

(Docker Desktop already publishes the port on `localhost`; the proxy + firewall
rule expose it to the LAN. Re-run the `netsh` command if the WSL IP changes
after a reboot.)

Verify from the phone's browser: `http://<PC-LAN-IP>:8000/health`.

## 3. Build & install the Android app

1. Open the [`android/`](android) folder in Android Studio (Hedgehog or newer,
   JDK 17). Let it sync; build with **Run** or `./gradlew assembleDebug`.
2. On first launch, allow notifications, then open **Settings** in the app:
   - Emulator: `http://10.0.2.2:8000`
   - Real phone: `http://<PC-LAN-IP>:8000` (from step 2)
   - Tap **Save & test connection**.
3. Add a product: give it a name and paste its product-page URLs from
   amazon.ie / paradigit.ie / currys.ie. Initial prices are fetched
   immediately.
4. Open a product to see per-site prices, the price history chart, and to set
   a **target price alert**.

## API overview

| Method & path | Purpose |
|---|---|
| `GET /health` | Status + notification channel config |
| `GET/POST /products` | List / create (creation scrapes immediately) |
| `GET/DELETE /products/{id}` | Detail incl. price history / stop tracking |
| `POST /products/{id}/refresh` | Re-check prices now |
| `POST /products/{id}/alerts` | Set target price |
| `DELETE /alerts/{id}`, `POST /alerts/{id}/toggle` | Manage alerts |
| `GET /notifications` | Event history (`?undelivered_only=true` for the app poller) |
| `POST /notifications/mark-delivered` | Ack delivered events |

## Development

```bash
cd backend
pip install -r requirements.txt pytest
python -m pytest tests/          # 19 tests: parsers, API, alerts, delivery
uvicorn app.main:app --reload    # run without Docker
```

## Notes & limits

- Scraping retail sites is for **personal use**; keep `CHECK_INTERVAL_MINUTES`
  and `SCRAPE_DELAY_SECONDS` polite so you don't hammer the shops.
- Amazon/Currys occasionally serve bot-check pages; those checks are skipped
  (error stored, visible in the product detail) and retried next cycle.
- App push latency is bounded by Android's 15-minute WorkManager minimum; use
  ntfy for near-instant alerts.
