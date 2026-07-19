# StockTracker

Configurable Android home-screen **stock & BTC/crypto price widgets**, plus a companion app to
manage a watchlist, chart tickers, and read a plain-language **buy/sell context** for each name. Each
widget is a resizable "bubble" that tracks one ticker (price, daily change, sparkline) — add as many
as you like and configure each independently.

Built with Kotlin, Jetpack Compose (Material 3 / Material You), and Jetpack Glance. The core app —
widgets, watchlist, charts, portfolio, alerts, backup — is **pure on-device, no backend required**. An
**optional [self-hosted signals service](https://github.com/Pr0zak/stocktracker-signals)** adds the
analyst layer (the Snapshot rollup, 200-week value context, dip radar, and a Claude verdict); leave it
unset and everything else still works. Design
mockups in `.stitch-mockups/` (Google Stitch, not committed).

## Screenshots

<table>
  <tr>
    <td align="center" width="33%"><img src="docs/screenshots/watchlist.png" width="200" alt="Watchlist"></td>
    <td align="center" width="33%"><img src="docs/screenshots/detail.png" width="200" alt="Ticker detail"></td>
    <td align="center" width="33%"><img src="docs/screenshots/portfolio.png" width="200" alt="Portfolio"></td>
  </tr>
  <tr>
    <td align="center"><sub>Watchlist — live stock &amp; crypto prices (no key), named lists with counts, a "Good time to add" dip radar, market-session timeline + VIX gauge</sub></td>
    <td align="center"><sub>Detail — a <b>Snapshot</b> rollup reading momentum / value / quality / smart-money at a glance (MIXED when they disagree), over a scrub-able %/$ chart with cost-basis &amp; 200-week lines</sub></td>
    <td align="center"><sub>Portfolio — total value, day change, total return vs. the S&amp;P 500, and an allocation donut</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/settings.png" width="200" alt="Settings"></td>
    <td align="center"><img src="docs/screenshots/vix.png" width="200" alt="VIX fear gauge"></td>
    <td align="center"><img src="docs/screenshots/widgets.png" width="200" alt="Widgets"></td>
  </tr>
  <tr>
    <td align="center"><sub>Settings — grouped &amp; on-device: theme, dashboard toggles, optional Finnhub key &amp; signals URL, export/import backup</sub></td>
    <td align="center"><sub>VIX "fear gauge" — tap through to the volatility history chart</sub></td>
    <td align="center"><sub>Widgets — single ticker, watchlist, or total-portfolio tile</sub></td>
  </tr>
</table>

### Signals &amp; long-term value <sub>(optional self-hosted signals service)</sub>

Point the app at the [self-hosted signals service](https://github.com/Pr0zak/stocktracker-signals) and
each stock's detail opens with a **Snapshot** — a
one-glance rollup of momentum, value (vs. its 200-week line), business quality, insider buying, and
short pressure, with a headline that calls out when the reads *agree* or *conflict* ("MIXED" here:
bullish momentum but an extended valuation). Each lens then expands into its own card — a **200-week
position bar**, a 14-week RSI gauge, an insider-buying summary, quality mini-meters (ROE / margin /
debt), a "what happened last time it was this cheap" forward-return study, and (opt-in) a **Claude
analyst verdict** plus a staged **entry plan**. The watchlist's **"Good time to add"** radar surfaces
names that have dipped into value territory.

<p align="center"><img src="docs/screenshots/analysis.png" width="230" alt="Snapshot rollup over the context cards"></p>

### Advanced charting

Pinch-to-zoom, a date/time axis, and a full indicator suite — moving averages, Bollinger Bands, VWAP,
RSI, MACD, Stochastic, ex-dividend markers, and an S&P 500 comparison line — all toggled from the chart's
**Indicators** menu.

<table>
  <tr>
    <td align="center" width="50%"><img src="docs/screenshots/indicators.png" width="230" alt="Indicators"></td>
    <td align="center" width="50%"><img src="docs/screenshots/benchmark.png" width="230" alt="S&P 500 comparison"></td>
  </tr>
  <tr>
    <td align="center"><sub>Indicators — VWAP on the price, RSI / MACD / Stochastic in stacked panes, and ex-dividend markers</sub></td>
    <td align="center"><sub>Benchmark — the ticker vs. the S&amp;P 500 in % mode (here MSFT trailing the market)</sub></td>
  </tr>
</table>

## Features

- **No API key required** — stock quotes, search, and charts all come from Yahoo (crypto from CoinGecko). A Finnhub key is optional and only adds an extra search source.
- **Home-screen widgets** (Jetpack Glance)
  - *Single ticker* — one stock or crypto, resizable 2×1 → 2×2 (shows a sparkline when larger)
  - *Watchlist* — all tracked tickers in one tile
  - *Portfolio* — your total value + today's change in one tile
  - Per-widget config: ticker, show change %, show sparkline, show name, accent color, refresh interval
- **App**
  - Watchlist with live prices, colored change, and sparklines — crypto accented in amber; **create multiple named lists** and **drag to reorder**
  - Interactive ticker detail: **pinch-to-zoom** area chart with a **date/time axis**, **%/$ toggle** (rebase to percent change), high/low markers, optional volume, drag-to-scrub, and 1D–3Y / ALL ranges. Stats read at a glance — a header strip (Open / High / Low / Volume) that **morphs into the scrub reading** as you drag, plus a **52-week range bar** showing where today sits in its year
  - **Technical indicators** (chart → Indicators): SMA / EMA / Bollinger Bands / VWAP overlays, RSI / MACD / Stochastic sub-panes, **ex-dividend markers**, and an **S&P 500 comparison** line in % mode
  - **Snapshot rollup** *(needs the signals service)*: momentum / value / quality / smart-money / short-pressure on one card with a conflict-aware headline, expanding into per-lens cards — a **200-week position bar**, RSI fear-gauge, insider-buying, quality mini-meters, and a forward-return "touch" study
  - **"Good time to add" dip radar** on the watchlist (mega-dip / below-the-line / oversold / pullback tiers) with push alerts, plus a **Below 200w** filter tab
  - **Entry plan**: a staged (dollar-cost-average) buy ladder across the entry zone; opt-in **Claude analyst verdict** on the detail screen
  - **Portfolio** tab: set **shares owned + average cost** per ticker (via a quick edit sheet) to track total value and **total return** ($ and %), reconstructed over time, with a **cost-basis line**, an **allocation donut**, and a **vs-S&P 500** benchmark + max-drawdown
  - **Backup & restore**: export your watchlist, holdings, cost, alerts, and lists to a JSON file and import it back (Settings → Backup)
  - **Price alerts**: above/below thresholds fire Android notifications
  - Market-session timeline on the dashboard (pre-market / regular / after-hours) — holiday-aware and in your phone's timezone
  - **VIX "fear gauge"** on the dashboard with inverse coloring + risk zones; tap through to the volatility history chart (toggle in Settings → Dashboard)
  - Search + add stocks, ETFs, and crypto
  - Material You dynamic color, light/dark/system theme
  - **In-app updates**: checks GitHub Releases on launch (and on demand in Settings) and installs the new APK
- Background refresh via WorkManager; last-known prices cached for offline display.
- Ticker widgets are **reconfigurable** (long-press → reconfigure on Android 12+).

## Data sources (all free)

| Data | Source | Key needed |
|------|--------|-----------|
| Stock quotes, history, intraday charts, symbol search, VIX, dividends, S&P 500 | Yahoo Finance chart endpoint | No |
| Crypto price, 24h change, 7d sparkline, charts, search | [CoinGecko](https://www.coingecko.com/en/api) | No |
| Extra stock symbol search (warrants/odd tickers) | [Finnhub](https://finnhub.io) | Optional free key |

> **Not built in:** earnings-date and news markers need a keyed feed (Yahoo's earnings endpoint now
> requires auth), so they're not included — a Finnhub key could power them later.

> **No key needed.** Stock quotes/charts/search come from Yahoo Finance's public `chart` and `search`
> endpoints (which also provide intraday data, so 1D works, plus the `^VIX` index). They're
> **unofficial** and can change without notice; failures degrade gracefully to an empty chart. Crypto
> comes from CoinGecko. A [Finnhub](https://finnhub.io) key is entirely optional — it only supplements
> symbol search for hard-to-find tickers (warrants, some class shares).

> **Signals service (optional, self-hosted):** the Snapshot rollup, 200-week value context, insider /
> quality / short-pressure cards, the "Good time to add" dip radar, and the Claude analyst verdict are
> served by a small [companion service](https://github.com/Pr0zak/stocktracker-signals) you run
> yourself (set its URL in **Settings → AI analyst**).
> Without it, the core app — widgets, watchlist, charts, portfolio, alerts, backup — works exactly as
> described above.

## Setup

**None required** — install the APK and it works: stocks and crypto both load with no API key.

A [Finnhub](https://finnhub.io/register) key is entirely optional (it only adds an extra symbol-search
source). If you want one, either:

1. **In the app:** **Settings → Data → Finnhub API key**, paste it, tap **Save key**. Stored on-device
   (DataStore).
2. **At build time:** copy `local.properties.example` → `local.properties` and set
   ```properties
   sdk.dir=/path/to/Android/Sdk
   FINNHUB_API_KEY=your_finnhub_key_here
   ```
   This becomes `BuildConfig.FINNHUB_API_KEY`, used when no in-app key is set. It is **never committed**
   (`local.properties` is gitignored) and is **not** embedded in the public debug CI artifact.

Without any key the app still works for crypto; stocks stay disabled until a key is provided. The
in-app key always takes precedence over the build-time key.

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

## Updating

The app checks `github.com/Pr0zak/stocktracker` releases on launch; if a newer `vX.Y.Z` exists it offers
to download and install the attached APK (via the system installer — requires "install unknown apps",
which Android prompts for once). You can also trigger it from **Settings → Updates → Check for updates**.
In-app updates only install cleanly over builds signed with the same release key.

## Architecture

```
app/src/main/java/com/stocktracker/app/
├── data/
│   ├── model/          Asset, Quote, PricePoint, ChartRange, SearchResult
│   ├── remote/         Finnhub / CoinGecko / Yahoo services (OkHttp + kotlinx.serialization)
│   ├── prefs/          DataStore: watchlist, settings, price cache (no Room/annotation processors)
│   └── MarketRepository combines the sources
├── di/                 ServiceLocator (tiny manual DI)
├── ui/                 Compose screens (watchlist, detail, gallery, settings, search) + theme
└── widget/             Glance widgets, config activity, WorkManager refresh, pinning helper
```

## License

MIT — see [LICENSE](LICENSE).
