# StockTracker

Configurable Android home-screen **stock & BTC/crypto price widgets**, plus a companion app to
manage a watchlist and view charts. Each widget is a resizable "bubble" that tracks one ticker
(price, daily change, sparkline) — add as many as you like and configure each independently.

Built with Kotlin, Jetpack Compose (Material 3 / Material You), and Jetpack Glance. Pure on-device —
no backend to run. Design mockups in `.stitch-mockups/` (Google Stitch, not committed).

## Features

- **Home-screen widgets** (Jetpack Glance)
  - *Single ticker* — one stock or crypto, resizable 2×1 → 2×2 (shows a sparkline when larger)
  - *Watchlist* — all tracked tickers in one tile
  - Per-widget config: ticker, show change %, show sparkline, show name, accent color, refresh interval
- **App**
  - Watchlist with live prices, colored change, and sparklines (filter All / Stocks / Crypto)
  - Ticker detail with an area chart (1D–ALL) and key stats
  - Search + add stocks and crypto
  - Material You dynamic color, light/dark/system theme
- Background refresh via WorkManager; last-known prices cached for offline display.

## Data sources (all free)

| Data | Source | Key needed |
|------|--------|-----------|
| Stock quotes + symbol search | [Finnhub](https://finnhub.io) | Free API key |
| Crypto price, 24h change, 7d sparkline, charts | [CoinGecko](https://www.coingecko.com/en/api) | No |
| Stock history + intraday charts | Yahoo Finance chart endpoint | No |

> **Why Yahoo for stock charts?** Finnhub's free tier no longer serves historical candles. Stock
> detail charts (and widget/watchlist stock sparklines) use Yahoo Finance's public `chart` endpoint,
> which also provides intraday data (so 1D works). It is an **unofficial** endpoint and can change
> without notice; failures degrade gracefully to an empty chart. Crypto charts/sparklines come from
> CoinGecko. (Stooq was the original choice but now blocks non-browser clients behind a JS wall.)

## Setup

1. Get a free Finnhub key at <https://finnhub.io/register>.
2. Copy `local.properties.example` → `local.properties` and set:
   ```properties
   sdk.dir=/path/to/Android/Sdk
   FINNHUB_API_KEY=your_finnhub_key_here
   ```
   The key is injected into `BuildConfig.FINNHUB_API_KEY`. It is **never committed** (`local.properties`
   is gitignored) and is **not** embedded in the public debug CI artifact. Without a key the app still
   works for crypto; stocks are disabled.

   > Note: because this is a pure client app, the key **is** compiled into signed release APKs (that's
   > how the shipped app talks to Finnhub). Treat the free Finnhub key as disposable and rotate it if
   > abused. For zero client-side exposure you'd need a proxy backend — out of scope for v0.1.

## Build

```bash
./gradlew assembleDebug          # debug APK → app/build/outputs/apk/debug/
./gradlew installDebug           # build + install on a connected device/emulator
```

Requirements: JDK 17, Android SDK with platform 35 + build-tools 35. Gradle wrapper (8.9) is included.

## Releases (CI)

- **`ci.yml`** builds a debug APK on every push/PR (artifact `stocktracker-debug-apk`).
- **`release.yml`** runs on a `vX.Y.Z` tag: builds + signs the release APK and attaches it to a
  GitHub Release.

Configure these repo secrets for signed releases:

| Secret | Purpose |
|--------|---------|
| `KEYSTORE_BASE64` | base64 of your release keystore (`base64 -w0 release.jks`) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |
| `FINNHUB_API_KEY` | (optional) baked into release builds for stock quotes |

Cut a release:

```bash
# bump VERSION, then:
git tag v0.1.0 && git push origin v0.1.0
```

## Architecture

```
app/src/main/java/com/stocktracker/app/
├── data/
│   ├── model/          Asset, Quote, PricePoint, ChartRange, SearchResult
│   ├── remote/         Finnhub / CoinGecko / Stooq services (OkHttp + kotlinx.serialization)
│   ├── prefs/          DataStore: watchlist, settings, price cache (no Room/annotation processors)
│   └── MarketRepository combines the sources
├── di/                 ServiceLocator (tiny manual DI)
├── ui/                 Compose screens (watchlist, detail, gallery, settings, search) + theme
└── widget/             Glance widgets, config activity, WorkManager refresh, pinning helper
```

## License

MIT — see [LICENSE](LICENSE).
