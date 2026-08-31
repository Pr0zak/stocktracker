# TradingView (github.com/tradingview) — what StockTracker can use

**Researched 2026-08-29.** Method: 12 parallel survey agents (9 web deep-dives on the org, 3 mapping
this codebase), 4 ideation lenses, a merge pass, then 3 independent adversarial verifiers per
candidate (facts / codebase / value) — 87 agents. One agent build-tested `lightweight-charts-android`
against a copy of this repo rather than reasoning about it. Companion to `ai-signals-roadmap.md`,
`ai-enhancement-roadmap.md`, `options-roadmap.md` and `swing-terminal-roadmap.md`.

Every claim below is sourced. Where something could not be verified it is marked as such rather than
smoothed over — see the closing paragraph for the carried-forward gaps.

---

## 1. The honest headline

The TradingView org has **39 public repos, and exactly two contain anything a personal Android app can legally and usefully take**: `lightweight-charts` (Apache-2.0, JS, v5.2.1 released 2026-08-12, actively maintained — 17.1k stars, master last pushed 2026-08-21) and its official Kotlin wrapper `lightweight-charts-android` (Apache-2.0, v5.2.0, tagged 2026-06-17 but only published to Maven Central 2026-08-12, after a user filed an issue asking for it; zero commits in all of 2024, 42 open issues, two 2026 PRs from one contributor). Everything else is internal webpack/asset tooling dormant since a bulk 2023-04-07 push, pinned third-party forks, iOS components, unlicensed internal data dumps, or three empty placeholders.

**What is NOT available, so nobody chases it:** *Advanced Charts* and *Trading Platform* are **not public repos at all** (`tradingview/charting_library`, `tradingview/advanced-charts`, `tradingview/charting-library` all 404 to an authenticated token — I could not determine whether they exist privately or not at all, since a 404 is identical for both). They are proprietary, gated behind an approval form and a signed agreement whose §2.4 excludes "private, personal or internal uses" and whose §2.5 forbids hosting the library "in any public code repository"; TradingView's own FAQ says flatly they do not license them "for personal use, hobbies, studies, or testing." **Pine Script is closed** — there is no `tradingview/pine-script-docs` repo; only `documentation-guidelines` (a prose style guide, abandoned 2023-06-27) is public, and the reference manual is copyrighted client-rendered pages on tradingview.com. **`scanner_data`** — the tempting screener field lists — is **archived (2025), unlicensed (all rights reserved), Russian-language and internal**; reference only, never a dependency. **`awesome-tradingview`, `saveload_backend`, `yahoo_datafeed`, `rest_integrations_docs`, `scanner-check`, `study_repo_data`** all carry **no LICENSE file whatsoever**, so none of their code is safely copyable into an MIT repo.

The wrapper was **build-tested against a copy of this repo and rejected on measurement, not taste**: at `compileSdk 35` it fails `:app:checkDebugAarMetadata` (the AAR declares `minCompileSdk=36`); at 36 with Kotlin 2.0.20 it still fails ("binary version of its metadata is 2.2.0"). It builds only after bumping compileSdk 35→36 **and** Kotlin 2.0.20→2.2.10 across the whole app — buying a WebView, Gson beside kotlinx.serialization, an Apache-2.0 NOTICE + tradingview.com link obligation, and a renderer that **cannot** replace `Charts.kt` (Glance/RemoteViews has no WebView view type, so `widget/SparklineRenderer.kt` stays hand-written regardless) and cannot replace it on failure either (`ChartsView.State.Ready` is set from `onPageFinished` with no JS handshake — an ES2020-incapable WebView yields a blank chart reported as Ready, a textbook UI-honesty violation).

**So the answer is: take the specifications, not the dependency.** What TradingView actually gives this project is a set of precise, published, reference-implementation-grade specs — Pine's exact indicator recurrences, the value-area walk, the alert-condition model, the broker object model's "explain yourself on the object" discipline — plus one measured correctness bug they expose in code already shipping.

---

## 2. The full org inventory

39 public repos, complete (org API reports `public_repos = 39`; page 1 returned 39, pages 2–3 returned 0). Licences read from LICENSE/NOTICE/package.json/setup.py/podspec directly, not from GitHub's classifier — which corrected eight repos.

| Repo | Licence (as filed) | Activity | Usable here? |
|---|---|---|---|
| **lightweight-charts** | Apache-2.0 + NOTICE (attribution + tradingview.com link required) | v5.2.1, 2026-08-12; master 2026-08-21 | **Yes, as a design reference only.** Ships no indicators, no drawing tools, no datafeed. Concept port to `Charts.kt` triggers no obligation. |
| **lightweight-charts-android** | Apache-2.0 | v5.2.0, tag 2026-06-17, Maven 2026-08-12; **0 commits in 2024** | **No.** WebView shim; forces compileSdk 36 + Kotlin 2.2.10; +Gson; can't reach Glance widgets; silent Ready-but-blank failure path. |
| **charting-library-examples** | MIT (examples only — library not in repo) | last commit 2025-12-02 | No. Every framework dir has a placeholder `put-datafeeds-here`. Inert without the gated library. |
| **charting-library-tutorial** | MIT (glue only) | 2026-08-11; "last tested June 2026 w/ 31.2.0" | Marginal. The `countBack`-outranks-`from` datafeed pattern is a good spec; nothing runs on Android. |
| **awesome-tradingview** | **No LICENSE at all** | 2026-06-01 | Index only. Fastest route to community wrappers; copy nothing. |
| **LightweightChartsIOS** | Apache-2.0 | v5.2.0, 2026-06-12 | No — iOS 15+/Swift 6. |
| **fancy-canvas** | MIT | master dormant since 2022-11-16 | No — only matters if forking LWC internals. |
| **saveload_backend** | **No LICENSE** | stale, 2024-04-22 | No — Advanced Charts layout persistence, unlicensed. |
| **yahoo_datafeed** | **No LICENSE** (fork of drbeep/) | stale, 2024-10-15 | No, but its 1000-bar cap and `no_data`/`nextTime` semantics are a useful read. |
| **tradingview.github.io** | **No LICENSE** | 2025-05-29 | No — featureset-flag docs aid. |
| **documentation-guidelines** | **No LICENSE** | abandoned 2023-06-27 | No — Pine *writing style* guide. This is the only public "Pine" repo. |
| **scanner_data** | **No LICENSE, ARCHIVED (2025)** | master 2025-09-22 (bot) | **No.** Reference only. Its `scanner.*.qf.json` files (472 fields) contain **no 52-week high/low pair** — that lives in the screener's scan column set. |
| **scanner-check** | **No LICENSE, no README** | branches pushed 2026-08-29; master 2025-01-28 | No — ~9GB of internal ban lists. |
| **s3-groundskeeper** | MIT | dormant 2024-02-15 | No — deploy plumbing. |
| **rest_integrations_docs** | **No LICENSE** | abandoned 2023-08-08 | Read-only. Broker-API prose; the OpenAPI YAML is not in it. |
| **django-hstore** | MIT text (© F. Capoano); GitHub says NOASSERTION | fork, 2023-04-07 | No. |
| **retry-ensure-webpack-plugin** | No LICENSE; package.json "WTFPL" | dormant 2023-04-07 | No. |
| **griddb_nosql** | No LICENSE file; ships AGPL-3.0 **and** Apache-2.0 texts + a EULA PDF | snapshot fork, 2019-02-20 | No — licensing situation to stay away from. |
| **css-file-rules-webpack-separator** | MIT | dormant 2023-04-07 | No. |
| **svgasset** | No LICENSE; package.json "MIT" | dormant 2023-04-07 | No. |
| **less-sprites** | No LICENSE; package.json "MIT" | dormant fork 2023-04-07 | No. |
| **h2database** | MPL-2.0 **or** EPL-1.0 (dual) | pinned fork 2023-04-07 | No. |
| **tv-polyfill** | No LICENSE; package.json "ISC" | dormant 2023-04-07 | No. |
| **dynamic-dual-import-webpack-plugin** | No LICENSE; package.json "MIT" | dormant 2023-04-07 | No. |
| **erlang-json** | Per-path MIT-style grants; NOASSERTION | pinned fork 2023-04-07 | No. |
| **msixty** | MIT | abandoned 2021-11-19 | No. |
| **UltraDrawerView** | MIT | snapshot fork 2022-03-18 | No — iOS UIKit. |
| **study_repo_data** | **No LICENSE** | very active internally, 2026-08-26 | No — 1.3GB internal Pine/study SDK artifacts. |
| **NukeWebP** | No LICENSE in fork; README claims MIT | snapshot fork 2023-04-17 | No — iOS image loading. |
| **tv-webpack-svg-loader** | No LICENSE; package.json "WTFPL" | dormant 2023-04-07 | No. |
| **tv-pylint-translations-rule** | No LICENSE; setup.py `license='MIT'` | dormant 2023-12-28 | No. |
| **rabbitmq-message-deduplication** | MPL-2.0 | pinned fork 2023-04-07 | No. |
| **pomo-iphone** | No LICENSE; podspec "Beerware" | snapshot fork 2021-11-11 | No. |
| **LiteRoute** | MIT | snapshot fork 2022-08-30 | No — iOS routing. |
| **jewel-case** | MIT | dormant 2024-05-02 | No — artifact publishing. |
| **webpack-uglify-parallel** | MIT, **ARCHIVED**, self-deprecated | last commit 2019-09-19 | No. |
| **inspector-maintenance** | Empty repo | created 2022-11-11 | Nothing in it. |
| **qa-interview** | Empty repo | created 2026-08-11 | Nothing in it. |
| **WAP_tm_tool** | Empty repo | created 2026-08-27 | Nothing in it. |

**Commonly mistaken for official, all owned by others:** `rongardF/tvdatafeed` (MIT), `AnalyzerREST/python-tradingview-ta` (MIT), `louisnw01/lightweight-charts-python` (MIT), `fabston/TradingView-Webhook-Bot` (MIT), `mnwato/tradingview-scraper` (MIT), `trash-and-fire/lightweight-charts-react-wrapper`. TradingView ships only the iOS and Android wrappers itself.

---

## 3. Recommended — ranked

Every write-up below is corrected against the verifiers. Where a candidate's stated mechanism was wrong, the corrected mechanism is what appears. Several candidates are **split**: the cheap high-value half is recommended, the expensive half is in §4.

---

### 1. Widget sparkline baseline — S

**What.** `widget/SparklineRenderer.kt` (57 lines) scales purely to the series' own min/max and draws no reference level, while the row directly above it prints `Formatting.percent(quote.changePercent)` with an arrow driven by `quote.isUp` — both measured from `prevClose`. Both `showSparkline` and `showChangePercent` default true (`WidgetConfig.kt:14-15`), so a default widget can show a line climbing left-to-right in red beside a negative number. The in-app `Sparkline` fixed exactly this once already: `Charts.kt:112` carries `previousClose` with a long comment about "▼ −213.83 (−0.10%)" on BTC.

**TV asset.** `lightweight-charts`' `BaselineSeries` — one of the six built-in types, a series drawn relative to a reference level rather than its own min. Concept only.

**How.** Add `previousClose: Double?` to `SparklineRenderer.render(...)`; fold it into min/max exactly as `Charts.kt:121-123` does; draw with `Paint` + `DashPathEffect`, stroke width scaled to `heightPx` **and dash segment lengths scaled to `widthPx`** (the 320×96 bitmap is fitted into `fillMaxWidth × 36dp` with `ContentScale.Fit`). Call site is `TickerWidget.kt:151`. **The data is already in hand** — `Quote.prevClose` (`Models.kt:62`, note the name, not `previousClose`) is already deserialized into Glance state by `TickerWidgetState.readQuote`, so there is no new fetch, no new prefs key, no schema migration. `SparklineRenderer` is a bare `object` with no `Context`, so hardcode the colour — reuse TickerWidget's existing `Muted` `0xFFCAC4D3` at ~40-45% alpha (the widget background is a fixed `#1C1B21` with no `values-night` variant, so it is always dark). If `prevClose` is null, omit the line; never substitute `values[0]`.

**Risk.** One real trap: the bitmap is memoized as `remember(spark, up)` at `TickerWidget.kt:150` — **`prevClose` must join that key** or a session rollover reuses a bitmap with the baseline at yesterday's level, which is the same class of defect being fixed. Secondary: `WidgetRefresh.kt:172-177` falls back to `priceCache.getBuffer` when the `ChartRange.DAY` fetch fails; that buffer is bounded to 24h / 40 samples (`PriceCache.kt:41-42, 70-73`) so `prevClose` stays a defensible reference, but folding it into min/max can visibly compress the curve — accept deliberately. Scope is one widget: `WatchlistWidget` and `PortfolioWidget` draw no sparkline. **Do not** ship the above/below shading — `fillPath` (lines 36-40) closes to the bitmap bottom, not to a reference level, so that is a rewrite, not an addition. Crypto `prevClose` is CoinGecko's 24h-ago price (`CoinGeckoService.kt:31`), Finnhub's is `dto.pc` — still exactly the level the % is measured from, so leave the line unlabelled as `Charts.kt` does.

*Unanimous, no kills. This is the one surface no TradingView route can ever reach — RemoteViews has no WebView view type.*

---

### 2. Fix the Stochastic against the Pine reference — S/M

**What.** `stochastic()` (`ChartMath.kt:163-183`) takes its window extremes from the **close** series; Pine's `ta.stoch` is verbatim `100 * (close - lowest(low, n)) / (highest(high, n) - lowest(low, n))` — bar lows and highs. `app/market.py:242` `_stochastic_k()` does the identical thing over `series.closes`, and hands the result to the Claude analyst as `stochastic_k`.

**TV asset.** Pine Script v6 reference semantics for `ta.stoch`. Formula only — Pine's implementation is closed and the reference prose is © TradingView; reimplement and cite by URL, never paste. (TradingView published scripts default to MPL-2.0, so the same rule applies to any Pine source text.)

**How much it matters — measured, not estimated.** Over 1–2y of daily bars (AAPL/NVDA/MSFT/AMD/SPY/BTC-USD), mean |Δ| between close-basis and true-range %K is **5.6–9.0 points**, p90 13–19, max ~41. More decision-relevant: **9–19% of bars flip the 20/80 zone call**, and close-only %K reads **exactly 0 or 100 on ~30% of bars** (160/488 AAPL, 214/716 BTC) versus 0–1% for a true stochastic. So `SignalEngine`'s `stochComp` — which scores `(20-k)/20` and `(80-k)/20` — saturates at maximum conviction on roughly a third of bars. It is a binary flag wearing an oscillator's label. **This has already shown up in a real trade:** `sandbox_job.py:302-305` and `tests/test_churn_guard.py:105` record a 2026-08-20 sandbox order trimming GLD as a "parabolic run: RSI 67, stochastic 100, mayer 1.0" — a Mayer of 1.0 is price *at* its 200-day average. `EXTREME_MAYER = 1.15` was added as the workaround. A %K pinned at 100 on any new 14-day *closing* high is the plausible root cause.

**How.** App: `stochastic(points: List<PricePoint>, period, smoothD)` using `barHigh()`/`barLow()` (`ChartMath.kt:37-38`). **There are two production call sites, not one** — `DetailScreen.kt:816` *and* `SignalEngine.kt:49` inside `prepare(prices, volumes, bench)`, reached from `DetailViewModel.kt:675/685` and `Backtest.kt:59`, plus `SignalEngineTest.kt:73/100`. Both `SignalEngine.evaluate` and `Backtest.run` already hold `List<PricePoint>`, so threading extremes through `prepare`/`SignalContext` is tractable — but it is a signature change across three files plus tests. Backend: `_stochastic_k(closes, highs, lows)`, reading fields the way `swing.py` already does (`getattr(series, "highs", None) or []`) and reusing `_complete_ohlc_tail()` (`swing.py:406-431`), **not** `series.highs` directly — `swing.py:10-15` documents that `summarize()` is called with duck-typed stubs (`tests/test_portfolio_snapshot.py:21` is `class _Series: closes = [100.0]*60`).

**Risk.** Three, all manageable and all must be planned, not discovered. (a) The fix **shifts the on-device Tier-1 score**, the user-visible reason string `"Stochastic %K nn (oversold)"` (`SignalEngine.kt:214`) and `BacktestResult.edgeVsBuyHoldPct` — the number `Backtest.kt` calls "the honesty check for Tier 1". (b) `scan_job.py:437` builds a truncated `Series` for the memory backfill with **no** highs/lows, so `_stochastic_k` must degrade to `None` there or `memory.py`'s `stochastic_k` column (schema `:92`, tolerance row `:214`, write `:241`) ends up holding backfilled close-basis rows matched against live true-range rows — verbatim the hazard `swing.py`'s docstring names. It is also pinned into `_SANDBOX_TECH_KEYS` (`main.py:2754-2760`) and the candidate fingerprint (`sandbox_job.py:1509`). Version-mark the column, re-run the backfill, or state the discontinuity at the cutover date. (c) The close-only guard: `barHigh()/barLow()` fall back silently to `price`, which is right for a high/low *marker* and wrong for an oscillator — hide the pane rather than draw an impostor. **Crypto is not the exposed path**: `MarketRepository.kt:115-130` routes crypto to `yahoo.cryptoHistory()` for *every* range and that carries high/low; the genuinely close-only paths are obscure alts falling through to `CoinGeckoService.kt:62` and the signals `/history` Webull fallback (`HistoryBar` is t/c/v only, `SignalsApiService.kt:809-812`) — i.e. warrants and OTC. Note that dropping the stoch component for a close-only crypto series **renormalises** the Tier-1 score (weight 0.6, `SignalModel.kt:56`) — make that an explicit decision.

**The existing test proves nothing:** `ChartMathTest.kt:64-71` only asserts %K ∈ [0,100] and that %D eventually appears. It passes identically before and after. Golden values on both sides, or the regression ships untested.

---

### 3. Draw armed alerts on the chart (render half only) — S

**What.** The chart draws the cost line, the 200-week line, and four AI-analyst levels — every level *except* the one the user set themselves. `AssetAlerts` (`Models.kt:42-50`) is four nullable doubles set by typing into a sheet, and once set they are invisible. "How far is price from my own trigger" is answerable nowhere (`grep priceAbove` hits only `Models.kt`, `AlertChecker.kt`, `DetailScreen.kt`).

**TV asset.** TradingView's product feature of alert lines on the price scale. **Correction to the source record:** the Apache-2.0 plugin examples `user-price-lines` and `user-price-alerts` are **not draggable** — both create a line by clicking in a narrow price-scale gutter and neither has drag state or mousemove-while-pressed handling. Nothing is ported; a labelled horizontal line at a user-chosen price is not protectable expression, so the licence question never arises.

**How.** **No new `Charts.kt` API is needed.** `DetailScreen.kt:350-364` already builds constant-value `ChartLineOverlay(label, color, List(chartPoints.size) { p })` for the AI levels, and the renderer draws, legends and y-scales them. Two more of those beside `levelOverlays` is roughly a dozen lines.

**Risk — and this is the part that must be built in, not added later.** Overlay values fold into the y-scale (`Charts.kt:309-318`), which is exactly why the AI levels are pre-filtered to a `price*0.95..price*1.05` band. An armed "below $50" on a $214 stock would compress the price series into a sliver. Apply the same band filter — **and then an off-scale alert silently draws nothing**, i.e. an armed alert rendering as absence, this app's signature failure mode. It needs an explicit edge state: a chip pinned to the plot edge with direction and distance ("Alert $180 · 14% below"). The common case for a below-alert *is* off-scale on short ranges, so the "you can finally see where your alerts sit" claim only holds once that exists. Further: only 2 of the 4 alert types are chartable (`percentUp`/`percentDown` are day-change triggers with no position on a price plot) — label each line individually ("Alert ≥ $214.30"), never collectively; keep the Alerts card (`DetailScreen.kt:2823-2900`) authoritative. `AlertChecker` keeps a persisted `fired` set (`AlertChecker.kt:18,40-45`) and only re-arms on a cross back, so a fired level can sit on the wrong side of price *looking* armed — dim it or mark it. In %-mode draw nothing (`costLine` is already nulled at `DetailScreen.kt:384`). The cost chip is right-anchored (`Charts.kt:454`) and the 200-wk chip left — two more right-edge chips collide with the cost chip whenever an alert sits near average cost, which is the common case; the clean move is to generalize `costLine` + `sma200wLine` + alert levels into one `levelLines: List<ChartLevel>` chip renderer, which shrinks `Charts.kt` instead of growing it.

*The drag half is deferred — see §4.*

---

### 4. Sub-pane crosshair values — S

**What.** `Charts.kt:650-653` draws a bare vertical crosshair line through each sub-pane with **no number attached**. With RSI and MACD enabled you can point at a bar and still not learn what RSI was. The panes' only numeric anchors are the 30/70 guide labels, so the best you can do by eye is ±5. `SignalEngine.kt:158` formats "RSI 42 (neutral)" but only for the latest bar. "What was RSI on the day I bought" is unanswerable in the app today.

**TV asset.** TradingView's documented mobile tracking mode (Legend docs, verbatim: "open, high, and low prices and indicator values are displayed in the tracking mode only") and `subscribeCrosshairMove`'s `seriesData` payload shape — a map from series to that series' value at the crosshair. Advanced Charts itself is proprietary; only the documented *behaviour* is adopted.

**How.** In `subPanes.forEach`, draw each line's value at the selected index in the pane's top-right using the existing `textMeasurer` + `drawText` pair already used at `Charts.kt:647` for the pane label — **drawn, not composed**, because `DetailScreen.kt:315` hard-codes `chartHeight = 200.dp + 18.dp + 64.dp * subPanes.size` and a composed `Text` breaks that arithmetic. Widen `onScrubChange` to carry the index (5 call sites: `DetailScreen.kt:392`, `PortfolioScreen.kt:220`, `SandboxScreen.kt:235` and `:1278`, `VixDetailScreen.kt:100`).

**Risk.** **Every sub-pane line is constructed with an empty label today** — RSI `ChartLineOverlay("", …)` at `DetailScreen.kt:798`, both MACD lines at `:808-809`, both Stoch lines at `:820-821`, BB at `:789-790`. Only the *pane* has a label. A two-line MACD readout would print two unlabeled numbers; labels must be **added** ("MACD"/"Signal", "%K"/"%D"). Warm-up nulls must print "—", never carry the last non-null value forward and never print 0 (`simpleMovingAverage` already returns nulls there). Indicators are only built when `!percentMode` (`DetailScreen.kt:313`), so in % mode the readout must render nothing rather than stale values. Layout: put values **in their pane**, not in the header — Bollinger alone contributes 3 overlays, and with SMA20+SMA50+EMA21+VWAP that is 7 values competing with OHLCV for a fixed 54.dp `ScrubStatHeader`.

*The OHLC-in-header and two-point measure halves are handled separately — see §7 and §4.*

---

### 5. Wilder RMA + True Range + ATR, as a number first — S/M

**What.** `ChartMath.kt` has SMA and EMA but no Wilder RMA — the missing primitive under ATR, ADX and Supertrend alike. `atr14`, `adr20_pct` and `adx14` exist only in the backend's `swing.py` and reach the app as read-only market-scan columns (`ScanMetrics.kt:60,69`), so they are absent when the backend is off and absent on the detail chart. The app displays stop distances and invalidation levels with **no on-device measure of what a normal day's range is for that name.**

**TV asset.** Pine `ta.rma` (α = 1/length, seeded with the SMA of the first window), `ta.tr`, `ta.atr = ta.rma(tr, length)`. The single most valuable fact recovered: **TradingView's own DMI Help Center prose says "Exponential Moving Average" while its shipped Pine uses `ta.rma`** — anyone implementing from the prose gets a different indicator. Do **not** emulate RMA as `exponentialMovingAverage(2*period-1)`: the recursion coefficient coincides but the seed does not (`ChartMath`'s EMA seeds over 2N-1 bars starting at index 2N-2; RMA seeds over N bars at index N-1).

**How — and port the backend, not Pine.** `swing.py:95-115` `_wilder`, `:234-253` `atr`, `:256-333` `adx` are already a hardened reference, and `swing.py:118-122` establishes this repo's contract: its sma/ema/rsi are ports of `ChartMath.kt` and "if one side is ever corrected, correct the other." Deriving from Pine instead silently forks the app's number from the scan column: the Pine convention sets TR(0) = high−low and feeds it into the seed, while `swing.py`'s `atr` deliberately starts at `i=1` and requires `period+1` bars. Same bars, two ATR values. Take `swing.py`'s. Note `rsi()` already contains the Wilder recurrence inline (`ChartMath.kt:145-146`) — it is unfactored, not absent, but do not naively swap it (different seed index, different series). Make `rma` `internal` so `ChartMathTest.kt` can pin the seed.

**Shape — this is the correction that changes the deliverable.** The decision value is the **ATR% number at the current bar**, not a 250-bar line. What a stop distance is compared against is one figure. Ship ATR% as a stat next to the 52-week range bar or in `ScrubStatHeader`; treat the sub-pane as optional second-half work.

**Risk.** (a) **The bar-interval trap, which the original framing got backwards.** `rangeParams` gives 1D=1m, 1W=5m, 1M=30m, 3M=1h; only 1Y/3Y are `1d` and ALL is `1wk`. So the pane **will** populate on intraday ranges and be read as a daily number — a "14-minute ATR" labelled `ATR 14` beside a stop denominated in days. Label with the bar size ("ATR 14 · 5m bars") or restrict to the daily ranges. (Bar size per range is also inconsistent between code paths: `YahooFinanceService.kt:91-101` maps MONTH/QUARTER to `1d` while the fragment builder at `:160-172` maps them to 30m/1h.) (b) `hasBarExtremes()` must be an **all-bars-in-window** check, not `any` — `YahooFinanceService.kt:75/:116` writes `high = highs?.getOrNull(i)` independently of the close guard on the line above, so a bar can carry a close and a null high; and because RMA is recursive a null cannot be skipped, it must reset the seed or void the series forward. (c) "The app has no volatility measure at all" is overstated — `bollingerBands` computes a population stdev. The real gap is a **gap-aware** measure; BB width misses overnight gaps entirely. (d) Panes inherit the `!percentMode` gate at `DetailScreen.kt:313` even though ATR%/ADX are scale-free — decide deliberately. (e) A second implementation **will** drift from `swing.py`'s and can legitimately disagree with the scan column on the same day; duplication is the established pattern here, but the two figures must never appear side by side without saying which is which.

**Drop or defer ADX.** It is trend strength, not volatility; `swing.py` calls it the module's most error-prone function; and the nightly scan already carries `adx14` **with a cross-sectional percentile** (`scan_store.py:196`, `adx14_pctile`) — "88th percentile" beats a raw 24. On-device ADX adds value only for backend-off, and only as an unranked absolute. If it is built anyway: match `swing.py:311-317` (return `None` for the whole reading when smoothed TR ≤ 0 — nulling one bar and continuing to smooth across the hole splices a broken chain), keep `swing.py:318-323`'s distinction between a *measured* DX = 0 and an unmeasurable one, and use `_ADX_MIN_BARS = 40` (`swing.py:61`), not the mathematical floor of 28.

**Two framing claims to drop.** "Unlocks volatility-scaled alerts" — `AlertChecker.kt` is 73 lines and fetches no history at all. "Natural foundation for a sixth gate leg" — `gate.py` runs server-side and `swing.py` already computes ADX there; no on-device code is needed. And "no library route exists" is wrong as written: `ta4j` is MIT, pure Java, maintained, and ships `ADXIndicator`/`ATRIndicator` on Wilder MMA. Justify in-house by dependency cost, not by absence.

---

### 6. Volatility basis for the entry plan — M (backend)

**What.** `market.summarize()` (`app/market.py:264-294`) — the snapshot handed to the Claude analyst and to `POST /plan` — contains RSI, MACD, two SMAs, %B, Stoch and 52-week context and **no volatility magnitude**. `_sanitize_plan()` (`app/main.py:235-256`) checks only that the entry zone is ordered and the allocation fits the cash. The analyst invents `entry_low/high/stop/target` with no idea whether the name moves 0.8% or 7% a day. `PLAN_SYSTEM` (`analyst.py:234-235`, repeated `:266`) **already instructs** "sanity-check risk:reward is at least ~1.5" — so the rule is *asked for and never verified*, which is a stronger claim than "absent". `swing.py:216-232` `adr_pct` and `:234-254` `atr` already exist; only `swing.metrics()` calls them.

**TV asset.** Pine `ta.atr`/`ta.tr` as the volatility unit, plus the Broker REST API's discipline that a rejected or caveated object carries a machine-readable reason on itself — verbatim from the v1.18.0 spec: `"message": {"text": "This order has been rejected due to the closed market", "type": "error"}`.

**How — and this is where the original mechanism was wrong in three places.**
1. **Do not widen `summarize()`.** `swing.py`'s module docstring (lines 1-30) is an explicit prior decision: "the mechanical swing-trade indicator set, deliberately NOT folded into `market.summarize()`", with three numbered reasons. Reason 2 is live: `summarize()`'s dict is the LLM-facing payload shipped on **every** analyst call (`main.py:606, 1327, 1831, 2859, 2910`, `scan_job.py:256`, and every sandbox tick), so four more keys is four more keys of prompt everywhere for a plan-path need. Attach a volatility block to the **plan path** — `_snapshot` for the interactive path, or behind a keyword flag.
2. **`plan_flags` must NOT be a field on `EntryPlan`** (`analyst.py:204-215`) — that class *is* the structured-output schema passed at `analyst.py:408`, so a field there becomes **model-authored**, inverting the whole point. Use a payload-level annotation in the mould of `chase.annotate()` (`chase.py:118+`), which already attaches derived, jointly-nullable, absent-never-zero reads onto the `/plan` payload and is rendered by `ui/detail/ChaseRead.kt`. That is the same task, already solved once in this repo.
3. **Read the persisted scan columns first.** `scan_store.py` schema lines 308-311 already have real `adr20_pct`/`atr14`/`atr14_pct`/`adx14` columns for ~3,100 symbols, and `scan_store.symbol_row(symbol)` (`:967`) returns the latest night with its date for staleness checking. Recompute only on a miss (crypto `-USD`, off-universe). And when you do recompute, call `swing.metrics(series)` rather than `swing.atr()` raw — raw `atr()` returns `None` on a single non-finite bar anywhere in the window, and `metrics()` routes through `_complete_ohlc_tail()` (`swing.py:406-431`) and returns an `unmeasured` list.

Compute two derived non-LLM numbers — `risk_atr = (entry_mid − stop) / atr14` and `reward_risk = (target − entry_mid) / (entry_mid − stop)` — and attach flags. Do **not** rewrite the analyst's numbers; a plan the app rewrote is a plan the journal cannot honestly replay. Reuse `plan_replay.py:293`'s existing wording ("stop {x} is not below the entry zone … it is not a stop") so the plan card and the journal replay describe one defect in one voice.

**Risk.** `_sanitize_plan` has **no snapshot in scope** and runs per-pick on `/recommendations` (`main.py:2132`) over *different* symbols — per-symbol snapshots live in `snaps` (`main.py:2109-2117`) and must be threaded, or the flags belong in a separate annotate step. Placement matters: `if p.action in ('wait','avoid')` returns early at `main.py:239-242`. Guard denominators against **0.0**, not just `None` — `EntryPlan` declares stop/target as non-nullable floats, so a model that cannot justify a stop emits `0.0`, which is precisely the bug documented at `SignalsApiService.kt:1639-1644` ("Stop $0 · target $0"); require `stop > 0` and `entry_mid > stop` else flags are **absent**. On the Kotlin side declare `@SerialName("plan_flags") val planFlags: List<String>? = null` — `Http.json` sets `coerceInputValues = true` alongside `ignoreUnknownKeys`, so a non-null `emptyList()` default erases the absent-vs-empty distinction at the decoder. Render at **both** sites — the detail plan card and `IdeasScreen.kt` share `ui.detail.ChaseRead` (`SignalsApiService.kt:1655-1660`), and flags on only one means the Ideas screen silently shows unflagged plans, which itself reads as "measured and clean". Print the computed `risk_atr`/`reward_risk` values beside the prose and keep thresholds in one named constant block — "0.4 ATR is inside one day's noise" is a heuristic wearing a fact's clothes. **Drop `adx14` from the ask.**

**Two corrections to the pitch.** "NOT ONE volatility measure" — `bollinger_pct_b` is volatility-*derived*; the accurate claim is no volatility *magnitude* (%B says where price sits in the band, never how wide it is). **The `memory.py` note is unnecessary**: `memory.record_verdict` persists a fixed 8-feature projection (`features_from_summary`, `memory.py:228-244`; `_distance` walks the same `_FEATURES` tuple at `:247-268`), and `_SANDBOX_TECH_KEYS` (`main.py:2758-2760`) is a whitelist — adding keys cannot shift the stored feature space or split the cohort. (`swing.py`'s docstring reason #3, "memory.py persists the summarize() dict", is itself out of date and worth fixing while in there.)

---

### 7. Candlestick / OHLC rendering in PriceChart — M/L

**What.** The one real capability gap. `Charts.kt:399-413` draws only a close-to-close polyline with an optional high/low annotation; grep for `candle|ohlc` across `app/src/main` returns exactly two comments about Finnhub's free tier. Each bar's **open** is fetched from Yahoo and thrown away.

**TV asset.** `lightweight-charts`' `CandlestickSeries`/`BarSeries` as a geometry reference. Nothing imported, no NOTICE obligation. **Version provenance, corrected:** v5.2.1 (2026-08-12) is the **JS** library; the Android wrapper's newest is 5.2.0 (GitHub tag 2026-06-17, Maven 2026-08-12); wrapper v5.0.0 was tagged but **never published** to Maven Central.

**How.** Add `val open: Double? = null` to `PricePoint` (`Models.kt:104-122`); map it at the two construction sites (`YahooFinanceService.kt:73` and `:114`, ~4 lines total — each needs a `val opens = quote0.open` binding plus the argument). The DTO field `YahooQuote.open: List<Double?>` already exists at `:393` and is already read for the quote at `:228`; the comment at `:51-56` records that high/low were added the same way, so this is a proven-shape edit. Rebase `open` in `asPercentChange()` (`ChartMath.kt:12-25`) alongside high/low — that function's own comment warns the un-rebased case "blows the axis out by three orders of magnitude". Then in `Charts.kt` add `style: ChartStyle = Area` and, inside the existing plot loop that already computes `xg(k)`/`y(v)`/`stepX` (lines 320-322), draw a wick `drawLine` plus a body `drawRect` per visible bar. Picker near the indicator sheet (`DetailScreen.kt:720`, toggle rows at `:742`).

**Risk — five, and they are what moves this from M to M/L.**
1. **Bar density is the thing that decides whether it reads.** `stepX = size.width / (visN - 1)`. 1D is 1-minute bars (~390), 1Y is ~252 daily, 3Y ~750 — on a ~360dp canvas that is well under 1px and bodies degenerate to a smear. Reuse the volume-bar clamp already at `Charts.kt:330` (`bw = (stepX * 0.6f).coerceIn(1f, 6.dp.toPx())`) and auto-fall back to Area below a minimum stepX **with the reason shown**, never silently.
2. **The intervals limit where "read a session's range" is even true.** `rangeParams` (`YahooFinanceService.kt:161-173`) requests 30m for 1M and 1h for 3M; only 1Y/3Y are `1d` and ALL is `1wk`. Daily candles on the swing-relevant ranges would require *also* changing those intervals, stripping intraday detail those views deliberately carry — uncosted work.
3. **The y-scale must change.** `Charts.kt:303-307` folds `barLow`/`barHigh` into `dataMin`/`dataMax` **only when `showHighLow` is true** (the comment says the plot must not "reserve headroom for a wick that never appears"). Candle mode must fold them unconditionally or every wick clips at the plot edge.
4. **The gate is per-bar, not per-source.** The crypto framing was wrong — `MarketRepository.kt:114-131` routes crypto to `yahoo.cryptoHistory()` for every range and that carries high/low. The close-only paths are the CoinGecko fallback and, for **stocks**, the signals-backend fallback for warrants/OTC (`DetailViewModel.kt:661/:747` building from `HistoryBar(t, c, v)`; `SignalsApiService.kt:807-812` carries no OHL and the backend's `/history` at `main.py:411` does not serve them). Worse, `history()` skips a bar only on a **null close** (`YahooFinanceService.kt:64`), so a rendered bar can carry a null open or high; `barHigh()/barLow()` fall back silently. A bar missing OHL must not render as a doji — a doji is a specific, confident reading.
5. **Four other `PriceChart` call sites pass close-only synthetic series** — `PortfolioScreen.kt:220` (equity curve), `SandboxScreen.kt:235` and `:1278` (NAV), `VixDetailScreen.kt:100`. `style` must default to Area with no path to Candle there. And candle mode must decide the fate of the gradient fill (`389-396`), the dashed extended-hours segments (`401-413`) and `shadeDrawdown` — all close-line constructs.

**Correction to drop:** the backup-compatibility rationale is inert. `PricePoint` is `@Serializable` but is **not** part of the backup payload (`BackupManager.kt:28-34` serializes `BackupData{assets, groups, calls, …}`) and there is no on-disk cache of it. The default is safe, just not for that reason — don't let it become a precedent.

**Cheaper alternative worth costing first (one verifier's refutation, and it is a fair one):** add `open` and surface O/H/L/C in the **scrub readout** instead of adding a second rendering mode. No legibility floor, no doji hazard, and absent values render "—". Note this partly exists and goes *backwards* today: `ScrubStatHeader` already renders Open/High/Low/Volume at `DetailScreen.kt:940-943` — but from the **live quote**, and the `Crossfade` at `:946` **replaces** it with price+time the moment you scrub. So scrubbing a past bar currently blanks an OHLC row exactly when it would be most useful. Swapping the live quote's OHLCV for the scrubbed bar's is the smallest honest version of this whole candidate.

---

### 8. Log price scale + a reset-zoom affordance — M

**What.** Three gaps. (a) There are **no axis price labels** — the `showAxis` block (`Charts.kt:658/659-684`) draws x-axis dates only; every price on the plot is attached to an opt-in feature (high/low chips `:500-512`, cost chip `:449`, 200-wk chip `:478`, AI levels in the legend). (b) No log scale, which is what makes 3Y/ALL legible — `ChartRange.ALL` is `range=max&interval=1wk` from 2010-01-01, and on a linear min/max axis a 40% drawdown at $30 draws shorter than a 10% one at $300. The %/$ toggle does **not** substitute: `asPercentChange()` is an affine transform and `y()` autoscales, so percent mode is pixel-identical to dollar mode with relabelled numbers. (c) No deliberate way back from a pinch.

**TV asset.** `PriceScaleMode` (exactly four members: Normal/Logarithmic/Percentage/IndexedTo100, verified in typings) and `ITimeScaleApi.fitContent()`/`resetTimeScale()`. Apache-2.0; concept port, nothing vendored.

**How.** Swap the local `fun y(v: Double)` at `Charts.kt:322` for ln-interpolation. **The "invert the mapping too" warning is factually wrong for this codebase and must be dropped** — there is no coordinate→price inverse anywhere; the scrub maps touch-x to an index (`idxAt`, `:227-232`) and the readout at `:210` prints `points[i].price` straight from the data. Building an inverse to satisfy that warning would *manufacture* the risk it guards against. So log is a pure forward-mapping change plus a tick generator.

**Do not reserve a right-hand gutter.** The x-axis label Canvas (`:659-684`) and each sub-pane Canvas (`:596-608`) independently compute `stepX = size.width / (vN - 1)` from their own full width; narrowing only the plot desyncs every date tick and every sub-pane crosshair from the point it names. Draw tick labels as **inset chips at the right edge inside the plot**, reusing the cost-line chip pattern (`:436-463`) — same visual language, zero geometry change. That is what makes the M estimate hold; with a real gutter this is L for the same benefit.

**Risk.** The strictly-positive guard must test the **composed** bounds, not the price series: `Charts.kt:317-318` does `min = minOf(dataMin, costLine ?: dataMin, sma200wLine ?: dataMin)`, and Bollinger's lower band (`mid − mult*sd`, `ChartMath.kt:112`) goes ≤ 0 on a volatile sub-$5 ticker, and AI level overlays are arbitrary backend numbers. Re-evaluate per visible window, since the range recomputes on every zoom. Disable with a stated reason; never fall back silently under a "LOG" label. The 1-2-5×10ⁿ tick generator degenerates on narrow ranges (a $180-$220 stock contains at most one such tick), so either use an adaptive nice-number generator or gate the LOG toggle to appear only when visible max/min exceeds ~3× — otherwise the control is a visible no-op on most charts. **Reconsider persisting the mode globally**: a stored LOG that disables itself on the next symbol leaves a control whose state does not match what is drawn — the same class of mismatch the invariant exists to prevent.

For the reset: prefer a **visible "Reset zoom" chip shown while `winSize < 1f`** over a hidden double-tap. Same cost, discoverable, and it doubles as the missing zoom indicator — while zoomed, the labelled high/low are *visible-window* extremes (`highIndexIn`/`lowIndexIn(points, startIdx, endIdx)`, `:519-521`) and nothing currently says so. A double-tap is the risky option: `awaitEachGesture` (`:245-285`) is hand-rolled with `awaitFirstDown` + a raw event loop, a mode flag and cumulative-slop tracking, with **no tap timing to hang a branch on**; you would either add timestamp/slop bookkeeping yourself or attach a second `pointerInput` competing with a loop that consumes on scrub. Gate on `zoomable` — only `DetailScreen.kt:383` passes true. Also note `winStart`/`winSize` are `remember(points)` (`:196-197`), so the zoom already resets on any range change, %/$ toggle, or background reload — the missing affordance is *within* a range, and that silent reset on refresh is arguably its own defect worth fixing alongside.

---

### 9. Condition alerts — reduced vocabulary — M

**What.** `AssetAlerts` is four nullable doubles, so the only question the app can be asked overnight is "did it touch $N". Add a small set of conditions over what `ChartMath` already computes causally. The only condition-shaped notifications today are `SignalScanNotifier`, which relays the **backend's** nightly flips and no-ops on a blank `signalsApiUrl` — nothing on-device notifies on a technical condition.

**TV asset.** TradingView's alert model (condition + trigger frequency + expiry) and Pine's `barstate.isconfirmed` as the documented non-repainting gate. **Corrected:** TradingView never offers one dropdown of Only Once / Once Per Bar Close / Every Time — plain price alerts get two options (Only Once, Every Time); interval-dependent alerts get four (Once only, Once per bar, Once per bar close, Once per minute). And **"Every Time" must not be offered here at all** — it means per-tick, and evaluation runs inside a 15-minute `PeriodicWorkRequest` (`WidgetRefreshWorker.kt:82`, `AlertChecker` at `:51`). The two modes this cadence can honestly support are *once per crossing* (what the fired-key set already implements) and *once per bar close*.

**Cut the vocabulary to what carries decision weight:** price closes above/below SMA(50|200), and close above the 52-week high. **Drop MACD signal cross and the Bollinger lower band** — highest-frequency, lowest-conviction, and this repo's own 20,768-episode study measured buying general weakness as negative. RSI(14)<30 is defensible but is a daily near-duplicate of the backend's weekly `oversold` dip tier.

**How.** New `AlertCondition` + `conditions: List<AlertCondition> = emptyList()` on `AssetAlerts`; new `notify/AlertConditions.kt` evaluating against `List<PricePoint>`; second loop in `AlertChecker` reusing the `AlertStateStore` fired-key set for edge detection. Consider reusing `SignalEngine.prepare()` (`SignalEngine.kt:43-66`), which already builds a context of exactly these arrays.

**Risk — two of these are silent-failure bugs that must ship in the same commit as the model change.**
1. **`AssetAlerts.isEmpty` (`Models.kt:48-49`) tests only the four doubles, and `AlertChecker.kt:13` filters on it.** A condition-only asset is never evaluated — armed in the UI, never runs, no error. Same for the badge count at `DetailScreen.kt:2825`.
2. **`DetailScreen.kt:2883/2887/2891/2895` and the Save at `:3018-3023` each construct a *fresh* `AssetAlerts`.** Any new field resets to its default every time the user flips a toggle or taps Save. All five must become `alerts.copy(...)`.
3. **Cadence.** `MarketRepository.HISTORY_TTL` is 5 minutes and the WorkManager process is usually cold, so a naive loop refetches a year of daily bars ~96×/symbol/day to detect at most one state change. Gate on a stored last-evaluated trading-day key.
4. **Range choice is load-bearing.** Only `YEAR` (~252 daily) and `THREE_YEAR` are daily *and* long enough for SMA200; MONTH/QUARTER are intraday in the fragment path; **ALL is `1wk`, so an SMA(200) there is a 200-*week* average silently labelled 200-day.** A period-200 condition should request THREE_YEAR, and the "needs 200 bars" guard must be range-aware.
5. **A staleness hole the guards do not cover.** `MarketRepository.cached()` has an unbounded stale-while-error branch — on a thrown fetch it returns the last good value with no age check. `AlertChecker`'s `STALE_QUOTE_MS` bound applies only to the quote path. The evaluator must check the **last bar's timestamp**, not trust that the fetch succeeded.
6. **No split-basis sanity check.** The backend rejects ~12 names a night via `swing.implausible_jump()` at a 10× single-bar threshold (BYND oscillating 0.59 → 17.85 → 0.56 on Yahoo's mixed basis). The on-device path has none, so a corrupted series would produce a confident false "52-week high". Port the 10× rejection or refuse to arm.
7. The fired-key set is never pruned when a condition is deleted, so delete-while-true then re-add suppresses the first legitimate fire. Close-only guards must test `high != null` directly, **not** `barHigh()/barLow()` which fall back silently. Dedupe against `SignalScanNotifier`'s 200-week-cross push. **Expiry** is named in the TV model but absent from the proposal — add it or drop it from the framing.

**The ATR-multiple leg should be trimmed to its cheap form.** A bare `atrAbove/atrBelow: Double?` cannot express "2 ATR below where it is *now*" — the anchor is lost at save time and recomputing from the latest close makes the target chase price forever; it needs an anchor price + timestamp. ~80% of the value is available for near-zero cost: **show the current ATR next to the existing Below-$ field** ("2 ATR = $8.40 → $172.30") and let the user type the level. No serialization field, no evaluation loop, no staleness duty, no dependency on the RMA work.

**Composable names, corrected:** `HoldingsAndAlertsSection` at `DetailScreen.kt:2744` (alerts block ~`:2851-2900`) and `EditPositionSheet` at ~`:2951`. There is no `HoldingsAndAlertsSheet`.

---

### 10. Close the low side of breadth — M (backend) — *partially unverified*

**What.** `scan_store.breadth()` returns `new_52w_lows: None` permanently (`scan_store.py:800-806`), with a comment inviting the fix. The breadth read is one-sided and the gate's participation leg (`_breadth_leg`, `gate.py:138`, `pct_above_sma50 > _BREADTH_PCT = 55.0`) cannot distinguish a market where both highs and lows are elevated from one where only highs are.

**TV asset.** The screener's symmetric high-side/low-side column model. **Sourcing corrected:** `scanner.qf.json` (472 fields) contains **no** 52-week pair — its symmetric pairs are premarket/postmarket/all-time/price-target high-low; the 52-week pair (`price_52_week_high`/`_low`) lives in the screener's *scan* column set. And `scanner_data` is **archived and unlicensed** — reference only, nothing copyable.

**How — and the stated mechanism is wrong.** "Series already carries `fifty_two_low`, so it's one line" does not hold: `swing.metrics()` deliberately does **not** read `Series.fifty_two_high` — `swing.py:518-530` builds `ref_high` from the trailing `_YEAR_BARS` window of `highs`, only when that window is complete and all-finite, else falls back to the max of the closes window; and the docstring at `:484-497` makes the duck-typing explicit (only `closes` required). Where `fifty_two_low` *does* exist the basis differs (Yahoo meta `fiftyTwoWeekLow` at `market.py:172` vs the Webull fallback at `market.py:98` taking `min(closes[-252:])` regardless of bar count). This needs the **mirror of the `ref_high` block** — ~8 lines over `lows`, clamped ≥ 0 — plus the column in the `scan` schema (`:296`) and an ALTER in `_migrate()` (`:413`, which already has the late-column pattern).

**The value claim only survives in a modified form.** `new_52w_highs` uses `_AT_52W_HIGH_PCT = -0.01`, i.e. a **closing** high measured against **intraday** highs, which reads ~0. Live counts on the deployment container's `scan.db`: **0, 5, 6, 1, 3 per night out of ~3,103**. So a `high_low_diff` built on the strict counts is a difference of two single-digit numbers — ~0 forever, the same defect the high side already had to fix with `near_52w_high`. Build the differential on the **band** form (`near_52w_low` mirroring `near_52w_high`), not the strict one.

**Risk / migration honesty.** The "live 90-night, ~288k-row scan.db" figure is **false as of 2026-08-29**: the deployed `scan.db` is 5.7 MB, created 2026-08-21, holding **15,512 rows across 5 nights** (20260821, 20260824-27). 90 nights / 288k rows is `_RETAIN_DAYS` (`scan_store.py:140`) plus the schema comment's projected steady state. So the ALTER touches ~15.5k rows and only 5 historical nights can ever read "—". That is still the requirement — pre-migration nights must render "—", never 0 (the exact defect `_no_breadth()`'s docstring was written to prevent), backfill is impossible and must not be faked, and any cumulative advance-decline line can only be labelled "since &lt;date&gt;, N nights".

**⚠️ Verification gap:** of this candidate's three adversarial verdicts, **only one was present in the record I was given, and its text was itself truncated mid-sentence**. The corrections above are everything that survived in the record. Treat this item as one-verifier-reviewed, not three, and re-run the other two lenses before building.

---

### 11. Volume profile — POC and 70% value area — L, one refutation

**What.** A horizontal volume histogram for the visible window with POC and value-area high/low as levels. Nothing like it exists — but note the existing volume rendering is a **per-bar histogram** (`Charts.kt:324-339`, height `(v/maxVol) * volArea`), not "a flat band". The accurate gap: it has no **price dimension**, so it answers *when* volume happened, never *at what price*. And `showVolume` defaults to **false** (`SettingsStore.kt:161`), so the baseline may be off entirely.

**TV asset.** The `volume-profile` plugin example (Apache-2.0) **is a pure renderer** — `volume-profile.ts` takes `{price, vol}[]` and scales bar widths; it computes no POC and no value area. All of the maths comes from TradingView's published support doc, whose value-area walk and up/down rule I verified verbatim (start at POC; repeatedly compare the next row above vs below, add the larger, advance only that side, stop at 70%; ties toward POC then upward; `close >= open` is up volume). Also: `plugin-examples`' own README says they are "provided as-is … may not receive updates" — do not describe them as maintained.

**How.** `fun volumeProfile(points, from, to, rows)` in `ChartMath.kt`, binning the visible price range into ~60-80 rows and distributing each bar's volume across `barLow..barHigh`. **No dependency on the candlestick candidate** — `PricePoint.high/low` already exist and are already populated; only the up/down colour split needs `open`. Memoise on `(points, startIdx, endIdx)`, which are derived at `Charts.kt:198-200` in composition scope, outside the Canvas lambda.

**Risk — and the honest version of it.** Uniform intra-bar spreading is **not** TradingView's method (they build profiles from lower-timeframe intrabar data), so numbers will not reconcile with a TradingView chart; label the **method**, not just the resolution. The gate must be **per-bar over the visible window** (Yahoo's arrays are `List<Double?>?`; `barHigh()/barLow()` fall back to close, reproducing the one-row degeneracy on a source that would pass a per-source gate). CoinGecko must be hard-gated for a second, independent reason: its `volume` comes from `total_volumes`, a **rolling 24h** figure sampled per point (`CoinGeckoService.kt:62/96`), so binning multiply-counts the same trades — which is also a latent honesty defect in the *existing* bottom volume band. Suppress on ALL entirely (`interval=1wk`; smearing a week's volume across a weekly range is close to meaningless) and note QUARTER is `1h`. Percent mode needs an explicit decision — a POC on rebased points is a percentage; follow the established pattern and suppress, or render through the already-percent-aware `valueFormatter`. The right gutter collides with the cost chip (`:454`) and the high/low labels (`:511`) and occludes the newest bars. Finally, `PricePoint`'s own doc records that bar extremes are resolution-dependent (GME 2026-08-11: 1D low $18.59, 1W low $18.70), so the POC will shift when the user changes range over the same window — expected, but it must be surfaced or it reads as instability.

**The refutation to weigh before building** (one of three verifiers, high confidence): the ranges where the data is honest (1D/1W, 1m/5m bars) are the ranges this user never trades, and the ranges where "is $47 a real shelf" is interesting (1Y/3Y) are daily bars where uniform spreading is a known-false model producing a POC quoted to the cent. A **visible-window** POC also moves with the pinch, so the level cannot be written into the journal or checked later — unlike every other horizontal line on this chart. And its actionable use is entry refinement, which `entry-timing-measured` (20,768 episodes) found loses on this universe.

---

### 12. As-of-verdict-date chart in the journal — M/L, scoring half rejected

**What survives.** Opening a past journal verdict shows the chart **as it looked on the verdict date**, indicators included, as pure illustration.

**TV asset.** TradingView's Bar Replay — notable because the Advanced Charts FAQ confirms verbatim that "Alerts, Range Bars, Bar Replay Tool, and patterns" are **not** in the libraries. Pure design steal; no library exists at any tier. Do not reuse the feature name verbatim in the UI (trademark, separate from copyright), and note **"replay" is already taken in this codebase** for the backend's mechanical plan replay (`JournalReplay`, `plan_replay.py`, `POST /journal/replay`, SWT-8) — pick "as-of" or "rewind".

**How.** The good news: most of the feared leak audit is **already free**. `Charts.kt:296-317` computes the y-scale over `startIdx..endIdx` only; volume max (`:330-332`), the high/low markers (`highIndexIn/lowIndexIn(points, startIdx, endIdx)`, `:495-499`), the ex-div epoch filter (`~:528-530`), every sub-pane autoscale (`:594-600`) and the x-axis ticks (`:660-676`) all already derive from the same window. Clamping `endIdx` fixes all of them.

**The leaks that actually bite, none of which were in the original list:**
- **`stepX = size.width / (visN - 1)`** (`:320`). Clamping `endIdx` alone stretches the k revealed bars across the full canvas and visibly shifts on every step — that is not what bar replay looks like. Pin the x denominator to the *unclamped* window and stop drawing at the replay index.
- **`costLine` and `sma200wLine` are folded into min/max unconditionally** (`:317-318`). They are as-of-today values that don't merely draw at the wrong height — they **stretch the axis toward a price the series has not reached**.
- **`chartUp`** (`DetailScreen.kt:311`): in % mode green/red is decided by the **last point of the whole series** — the chart is painted by the answer before a single bar is revealed. Re-derive over `0..replayIndex`.
- `shadeDrawdown`'s whole-series peak is **deliberate** (documented: a drawdown is measured from the all-time high) and it is passed `true` at exactly one call site, `SandboxScreen.kt:238`, the sandbox equity curve — never on a price chart.
- `JournalScreen.kt` **never calls `PriceChart`** — it has its own `TwoCurveChart` (`:475`, invoked `:421`) and no price-history plumbing at all. This is new wiring, not a reuse.
- Yahoo's closes are retroactively split-adjusted and `ALL` is `1wk`, so "the chart as it looked" is a **reconstruction on today's adjusted basis** whenever a split intervenes — must be labelled as such.

**The scoring half is rejected — see §4.**

---

## 4. Rejected, and why

**First, an honest statement of what the verdict record shows:** **no candidate was refuted by a majority.** Three took one refuting verdict of three (`ohlc-candlestick-series`, `bar-replay`, `volume-profile`); the rest were unanimous-not-refuted; `breadth-low-side`'s record was incomplete (1 of 3 verdicts, itself truncated). So this section lists (a) the TradingView routes rejected during research and (b) the sub-components killed inside surviving candidates — which is where the real deletions are.

### Rejected TradingView assets and routes

| Rejected | One-line reason |
|---|---|
| **Adopting `lightweight-charts-android`** | Build-verified: forces compileSdk 35→36 **and** Kotlin 2.0.20→2.2.10 app-wide, adds a WebView + Gson, ~+539 KB, an Apache-2.0 NOTICE + tradingview.com link obligation — and deletes nothing, because Glance can't host a WebView and `State.Ready` fires on `onPageFinished` with no JS handshake (blank chart reported as Ready). |
| **Advanced Charts / Trading Platform** | Proprietary, private repo behind an approval form; licence §2.4 excludes personal use, §2.5 forbids public repos and open-source projects, §2.5(c) forbids shipping library files to third parties (which every GitHub Release APK would do), §2.11 requires publishing a blog post announcing your TradingView partnership, §2.4 requires giving TradingView free access to verify compliance. Also: **Alerts are not supported in it at all.** |
| **TradingView Widgets** | Free but hosted, proprietary, branding must stay, requires a WebView, and cannot be fed your own data. |
| **Putting your data on tradingview.com** | The only path is the **Broker Integration API** — a broker partner program. The premise does not exist. |
| **A UDF datafeed on the signals backend** | The payoff (self-hosted Advanced Charts) is licence-blocked; the one real consumer is OpenBB Workspace, and even there **I could not verify that its `advanced_charting` widget requests `/marks`** — which is the only reason to build it. The backend also has **zero authentication on any endpoint** (`/api/settings` reads and writes third-party keys; `/sandbox/reset` is unauthenticated) and TradingView only posts from four fixed IPs to ports 80/443. |
| **`scanner_data` as a dependency** | Archived (2025), unlicensed (all rights reserved), internal, Russian-language. Reference only. |
| **`awesome-tradingview` / `saveload_backend` / `yahoo_datafeed` / `rest_integrations_docs` / `study_repo_data`** | No LICENSE file at all — nothing copyable into an MIT public repo. |
| **A Pine-like interpreter or expression evaluator in the app** | Needs a scripting engine dependency (against the build's stated values); reproducing Pine's actual semantics — series types, `[]`, `var`/`varip`, na propagation, bar-state gating, `request.security` — is the hard 90% for one user. The indicator **registry** captures the useful 10%. |
| **Renko** | Has no time axis, so it cannot share `Charts.kt`'s index→x mapping, date axis, scrub readout or benchmark overlay — it is a second chart engine. TradingView's own docs concede its bricks are approximated from chart-resolution closes and that the Percentage method repaints. |

### Rejected sub-components inside surviving candidates

| Rejected | One-line reason |
|---|---|
| **The bar-replay practice/calibration mode ("your 12 calls vs the engine's 12")** | A 12-observation sample is under this repo's own written floor — `PairedStat.kt` puts it at 20–30 and its rule 2 is "under the floor, the count, not the percentage" — and `PairedStat`'s `init` **requires** one `BACKTEST` and one `FORWARD` side, which a human's discretionary call on bars that already traded is neither. The untouchable leak is that the user knows the symbol and the era. `JournalComparison` already pairs real R against mechanical R over a shared population, for free, with an `Exclusion` enum. |
| **The two-point measure tool's annualised-rate leg** | An annualised figure between two user-chosen points is a confident number from a self-selected sample; a 20-session floor does not fix that, because a finger on a chart is a machine for picking the low and the high. |
| **"Max adverse excursion" between two drag points** | It is just the lowest low in the span; the MAE label imports trade semantics that only hold when point A is a real entry. Either compute against the stored entry or call it "lowest low in span". |
| **Drag-to-arm alert levels (the drag half)** | On a 200dp plot over a year a finger lands *near* a level, so you open the sheet and type the exact number anyway; and it means hoisting the y-scale out of the Canvas DrawScope (`min`/`range`/`y()` at `:317-322` are invisible to `pointerInput`) plus hand-rolling a long-press inside the slop-tuned `awaitEachGesture` that serves scrub and pinch on five call sites. |
| **A right-hand axis gutter (log-scale candidate)** | The x-axis and every sub-pane Canvas compute `stepX` from their own full width; narrowing only the plot silently desyncs date labels and sub-pane crosshairs. Inset chips instead. |
| **The "invert the y-mapping" work item** | There is no coordinate→price conversion anywhere in `Charts.kt`. Building one to satisfy the warning would create the exact risk being guarded against. |
| **Splitting the Indicator enum into VWAP_SESSION / VWAP_ANCHORED with a DataStore key shim** | A session reset on 1Y/3Y (`1d`) and ALL (`1wk`) degenerates to per-bar hlc3 — a jagged copy of the price line — so mapping the legacy `vwap` key to the session variant makes those ranges *worse*. `ChartMath.kt:117`'s KDoc already says "Anchored (cumulative)"; the defect is the **UI label** at `DetailScreen.kt:760/793`. Ship a label fix plus a session reset on intraday ranges only. |
| **`adx14` in the entry-plan snapshot** | Trend strength, not volatility; the stated hole is volatility. |
| **On-device ADX/DMI (in the RMA candidate)** | The nightly scan already carries `adx14` **with a cross-sectional percentile** (`scan_store.py:196`); "88th percentile" beats a raw 24, and the on-device version only adds an unranked absolute for backend-off. |
| **MACD signal cross and Bollinger-lower-band alert conditions** | Highest-frequency, lowest-conviction triggers in the set; this repo's own 20,768-episode study measured buying general weakness as negative. |
| **"Every Time" alert trigger mode** | Means per-tick on TradingView; evaluation here is a 15-minute worker, so shipping a mode named after a cadence the app cannot deliver is itself a UI-honesty violation. |
| **The `memory.py` snapshot-change note (entry-plan candidate)** | `memory` persists a fixed 8-feature projection and `_SANDBOX_TECH_KEYS` is a whitelist — adding snapshot keys cannot shift the stored feature space. Nothing to migrate. |
| **The backup-compatibility rationale for adding `PricePoint.open`** | `PricePoint` is never persisted; `BackupManager.kt:28-34` never references it. The default is safe, just not for that reason. |
| **Above/below baseline shading in the widget sparkline** | `fillPath` closes to the bitmap bottom, not to a reference level — that is a rewrite, and at 36dp of rendered height it adds noise for no gain. |
| **`high_low_diff` built on the strict new-high/new-low counts** | `_AT_52W_HIGH_PCT = -0.01` compares a *closing* high against *intraday* highs and reads ~0 (live: 0/5/6/1/3 per night of ~3,103). Build the differential on the band form. |

---

## 5. What I'd do first

**Write the failing golden-value test for the Stochastic, in both languages, before touching either implementation.**

Concretely, one commit that adds nothing but tests:
- `app/src/test/java/com/stocktracker/app/util/ChartMathTest.kt` — a fixture of ~30 hand-computed `PricePoint`s where the window's lowest **low** and highest **high** differ from the lowest and highest **close**, asserting exact %K/%D values. The existing test (`:64-71`) only asserts %K ∈ [0,100] and that %D eventually appears; it passes identically before and after the fix, so without this the regression ships untested.
- The mirror fixture in `stocktracker-signals/tests/`, asserting `_stochastic_k` on the same bars, plus a case where `highs`/`lows` are absent (the `scan_job.py:437` truncated-`Series` shape) asserting `None` rather than a close-basis fallback.
- A second assertion that captures the measured pathology directly: over a synthetic ramp, close-basis %K hits exactly 0 or 100 on ~a third of bars while true-range %K does not — so the test documents *what* was wrong, not just that a number moved.

**Why this and not something bigger.** Four reasons, in order.

1. **It is the only item in the set where the defect was measured rather than argued.** Mean |Δ| 5.6–9.0 points across six symbols, 9–19% of bars flipping the 20/80 call, and ~30% of bars pinned at exactly 0 or 100 versus 0–1%. Everything else on the ranked list is a judgement about value; this is arithmetic.
2. **It has already cost something.** The 2026-08-20 sandbox order trimming GLD on "RSI 67, stochastic 100, mayer 1.0" — where the Mayer multiple said price was exactly at its 200-day average — is on the record in `sandbox_job.py:302-305` and `tests/test_churn_guard.py:105`, and the project's response was to add the `EXTREME_MAYER = 1.15` guard. A %K that pins to 100 on any new 14-day *closing* high is the plausible root cause. Fixing it is upstream of a workaround already paid for.
3. **It is the item with the widest silent blast radius, so it is the one that most needs a test written first.** The fix moves the on-device Tier-1 composite (`SignalEngine.kt:49` is a second production caller the proposal missed), the user-visible reason string at `:214`, `BacktestResult.edgeVsBuyHoldPct` — which `Backtest.kt` itself calls "the honesty check for Tier 1" — and a persisted `stochastic_k` column that feeds `memory.py`'s similarity match, the sandbox fingerprint, and the analyst's snapshot. A change that touches a track record needs its before/after pinned in a test, not in a screenshot.
4. **It resolves the one thing that makes every other recommendation on this list safer to build.** Candidate #4 (sub-pane crosshair values) exists to make indicator panes interrogable — "RSI was 28 on the day I bought". Shipping a readout that displays a %K computed against a private dialect no reference agrees with would be a new instance of exactly the defect class this project tracks. Correctness before legibility.

**Land alongside it, same session, because it costs an hour and carries no risk:** the widget sparkline baseline (§3.1). It is ~20 lines, needs no new data (`quote.prevClose` is already in Glance state), and removes a self-contradicting render — a red line climbing beside a negative number — from the surface the user sees most often. If the stochastic work stalls on the memory-column discontinuity decision, the day still ships a fix.

**Explicitly do not open with:** candlesticks (the density and interval caveats mean the visible payoff lands on 2 of 7 ranges), the log scale (real but narrow — visually indistinguishable from linear whenever visible max/min is under ~2×, which is most single-stock 3Y charts), or the bar-replay work (its scoring half is rejected and its surviving half is illustration).

---

*Verification gaps carried forward, stated rather than smoothed over: `breadth-low-side` has 1 of 3 adversarial verdicts on record and that one is truncated mid-sentence. Whether `tradingview/charting_library` exists as a private repo could not be determined (a 404 is identical to non-existence). Pine's `ta.vwap` returning `na` before the first anchor, `ta.wpr`'s exact body, and the first-defined-bar index for ADX could not be confirmed from the primary reference (it is a client-rendered SPA); TradingView's own DMI Help Center prose contradicts its shipped Pine, and `ta.ema`'s warm-up is ambiguous in TradingView's own docs. The `lightweight-charts-android` build experiment compiled but was never run on a device, and its +552,266-byte APK delta is a debug build that also includes the Kotlin toolchain bump — the AAR itself is 511,275 bytes. Whether OpenBB's `advanced_charting` widget requests `/marks` was not verified.*