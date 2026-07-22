# Price Tracker

A standalone **Android app** that tracks product prices across **amazon.ie**,
**paradigit.ie** and **currys.ie**, lets you set target-price alerts, and
notifies you of every price change — all **on the phone**, with **no server, no
account, and nothing to configure**. Install it and it just works.

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
3. A background job re-checks every tracked price a few times a day (while
   you're online). You can also pull a fresh check anytime.
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
- **Settings**: a "Check all prices now" button and a reminder to allow
  notifications.

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

## Notes & limits

- Scraping retail sites is for **personal use**; the app checks a few times a
  day and spaces requests out to stay polite.
- Amazon/Currys occasionally serve bot-check pages; those checks are skipped
  (error stored, visible in the product detail) and retried next cycle.
- Background timing follows Android's WorkManager, so alerts arrive within the
  next check cycle rather than instantly. Use **Check all prices now** for an
  immediate refresh.
