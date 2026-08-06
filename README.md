# Price Tracker

Track product prices across **amazon.ie**, **paradigit.ie** and **currys.ie**,
set target-price alerts, and get notified of every price change. It comes in two
independent, fully self-contained flavours — pick whichever suits you:

- **[Android app](android)** — runs on your phone.
- **[Browser extension](extension)** — runs in Chrome/Edge.

Both store everything **locally** (on the phone / in the browser) and fetch
prices themselves: **no server, no account, nothing to configure**.

## Android app

A standalone app that keeps all data **on the phone**. Install it and it just
works.

| | |
|---|---|
| Platform | Android 8.0+ (API 26) |
| UI | Kotlin · Jetpack Compose · Material 3 |
| Storage | Room (local SQLite on the device) |
| Fetching | OkHttp + Jsoup, on-device |
| Background | WorkManager |

## How it works

1. Add a product and paste its page URL from one or more of the supported
   shops.
2. The app fetches each page directly from the phone, extracts the price, and
   stores it in a local database with full price history.
3. A background job re-checks every tracked price on a schedule you choose in
   Settings (from every 15 minutes up to once a day), while you're online. You
   can also pull a fresh check anytime.
4. When a price **changes** or drops **below a target you set**, the app raises
   a **local notification** — no cloud service involved.

Price extraction uses site-specific selectors first (e.g. the Amazon buy-box),
then falls back to schema.org **JSON-LD** and price metadata, so minor site
redesigns usually don't break it. Amazon and Currys run bot protection; if a
check is blocked, the error is stored per-URL and shown in the app, and the
next scheduled check retries automatically.

Everything — products, price history, alerts, notification history — lives in
the app's private storage on the device and never leaves the phone.

## Build & install

Open the [`android/`](android) folder in Android Studio (Hedgehog or newer,
JDK 17), let it sync, and press **Run** with your phone connected — or build an
APK from the command line:

```bash
cd android
./gradlew assembleDebug
# APK at android/app/build/outputs/apk/debug/app-debug.apk
```

Install the APK on your phone (`adb install app-debug.apk`, or copy it over and
open it). On first launch, allow notifications so alerts can reach you when the
app is closed. That's the entire setup.

## Using it

- **Add a product** (＋): give it a name and paste product-page URLs from
  amazon.ie / paradigit.ie / currys.ie. The first prices are fetched
  immediately.
- **Product detail**: per-shop current prices, a price-history chart, and
  target-price **alerts** (you're notified when any shop hits the target;
  you won't be re-notified while it stays low, and the alert re-arms if the
  price climbs back up).
- **Settings**: choose how often prices are checked in the background (every
  15 minutes up to once a day), plus a "Check all prices now" button.

## Project layout

```
android/app/src/main/java/com/pricetracker/app/
├── data/
│   ├── db/            Room entities, DAOs, database
│   ├── scrape/        On-device scrapers (Amazon/Paradigit/Currys/generic)
│   ├── Models.kt      UI models
│   └── Repository.kt  Scrape + store + evaluate alerts
├── notifications/     WorkManager price-check worker + local notifications
├── ui/screens/        Compose screens (list, add, detail, notifications, settings)
└── MainActivity.kt    Navigation
```

## Development

```bash
cd android
./gradlew testDebugUnitTest   # unit tests for price parsing / JSON-LD extraction
./gradlew assembleDebug       # build the debug APK
```

## Notes & limits (Android)

- Scraping retail sites is for **personal use**; the app checks a few times a
  day and spaces requests out to stay polite.
- Amazon/Currys occasionally serve bot-check pages; those checks are skipped
  (error stored, visible in the product detail) and retried next cycle.
- Background timing follows Android's WorkManager (15-minute minimum, and it
  may batch work), so alerts arrive within the next check cycle rather than
  instantly. Use **Check all prices now** for an immediate refresh.

---

# Browser extension

A Manifest V3 extension for **Chrome / Edge** that does the same job in your
browser. All data lives in `chrome.storage.local` — **no server, no account**.
Because an extension isn't bound by the browser's CORS rules (a plain website
would be), it can fetch the shops' pages itself.

It gives you two surfaces over the same local data:

- **Popup** (toolbar icon): a **Track this page** button that reads the price
  off the shop tab you're on, a quick list of tracked products, and buttons to
  check now or open the dashboard.
- **Dashboard** (a full page bundled in the extension, opened in its own tab):
  add products by URL, see per-shop prices and **price-history charts**, manage
  **target-price alerts**, choose the **check frequency**, and view recent
  activity.

A background **service worker** re-checks prices on your chosen schedule (via
`chrome.alarms`) and raises desktop **notifications** for price changes and
alerts — even when no tab is open.

### Getting Amazon & Currys prices reliably

Amazon and Currys don't expose prices to a plain background `fetch` — Currys is
bot-walled (returns 403) and Amazon renders prices with JavaScript. So the
extension reads prices from **real, rendered pages** two ways:

- **Passively, as you browse** — a content script on the three shops reads the
  price whenever you open a product page you're tracking and updates it
  silently. Invisible, instant, and immune to bot checks (it's your own
  browsing).
- **On the schedule** — for anything not refreshed by browsing, the service
  worker opens each URL in a **hidden, minimized background window**, lets it
  fully render, scrapes the price, and closes it. Paradigit/generic URLs skip
  this and use a fast `fetch` first (they ship the price in HTML).

The trade-off: scheduled checks briefly open a background window per cycle —
Manifest V3 has no fully invisible way to render a third-party page.

### Install (unpacked)

1. Open `chrome://extensions` (or `edge://extensions`).
2. Enable **Developer mode**.
3. Click **Load unpacked** and select the [`extension/`](extension) folder.
4. Pin the Price Tracker icon, and allow notifications if prompted.

### Using it

- On an amazon.ie / paradigit.ie / currys.ie **product page**, click the
  toolbar icon → **Track this page** (reads the live, rendered price).
- Or open the **dashboard** and add a product by pasting its URL(s).
- Set a **target price** on any product; you'll be notified when any shop hits
  it (and it re-arms if the price climbs back up).
- Change the background **check frequency** in the dashboard (15 minutes → once
  a day; 15 min is the browser's alarm minimum).

### Layout

```
extension/
├── manifest.json     MV3 manifest (storage, alarms, notifications, scripting)
├── background.js      Service worker: scheduled checks + notifications
├── extract-core.js    Shared in-page extractor (content script + injected)
├── capture.js         Content script: capture prices as you browse
├── popup.html/.js/.css        Toolbar popup
├── dashboard.html/.js/.css    Full-page local dashboard
└── lib/
    ├── pricing.js     Price parsing: JSON-LD / metadata / site selectors
    ├── store.js       chrome.storage.local data layer
    └── checker.js     Render/fetch prices, compare, evaluate alerts
```

### Notes & limits (extension)

- Scheduled checks briefly open a **hidden background window** to render
  Amazon/Currys pages (see above). If you'd rather avoid that entirely, the
  passive "update as you browse" capture keeps visited products fresh on its
  own — you'd just miss auto-updates for products you don't open.
- Data lives in this browser profile only; it doesn't sync across machines.
- Targets Chromium browsers (Chrome/Edge). Firefox would need minor tweaks.
