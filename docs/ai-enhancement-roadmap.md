# AI-Enhancement Roadmap & Data-Source Catalog

**Status:** **AIE-1…6 + Themes C & D all shipped (2026-07-23→24).** AIE-1 annotated charts (v0.25.0),
AIE-2 congress, AIE-5 market-now, AIE-6 seasonality, AIE-3 daily-brief push (v0.26.0), AIE-4 news→move
(v0.27.0); a **refresh-all-AI button** on the detail screen (v0.28.0); **Theme C — portfolio rebalance
plan** (concrete sized SELL/BUY moves, POST /portfolio/rebalance, v0.29.0); **Theme D — market-regime
banner** (GET /regime, trend/vol + positioning note on the watchlist, v0.30.0). Remaining un-built:
Theme C's follow-the-smart-money screen, Theme D's weekly-macro read + the 200-week value screener
(mungbeans — see that memory). Companion to `ai-signals-roadmap.md` and `options-roadmap.md`.

## The reframe: inference is now ~free

The signals backend can run the Claude analyst on the **subscription** (headless CLI, `llm_provider=cli`)
instead of the metered API — see the `signals-llm-provider-toggle` memory. That flips the cost/benefit of
AI in the app: we can afford **Opus on every scan, nightly synthesis, cross-source reasoning, and narrative
layers everywhere**. The only real constraint is the Max **rate-limit budget** (shared with interactive
Claude Code), so anything per-view must **cache** (generate once, reuse).

**Discipline to keep:** the analyst prompt is honest about evidence (it treats short-interest per the
academic base rate, labels speculative dates as such). Everything below keeps that — AI here is for
*synthesis, correlation, explanation, and visual narration*, never for pretending to predict prices. Most
alt-data "alpha" is weak/lagging; we frame these as **context**, not signals.

---

## Feature themes

| Theme | What | Newly cheap? |
|---|---|---|
| **A · Visual AI feedback** ⭐ | Annotated charts (entry/stop/target/invalidation bands, catalyst pins), a cached "what am I looking at" read, conviction-as-color | Reuses data the analyst already emits |
| **B · Smart notifications** | AI daily brief; "why did X move?"; confluence alerts | 1 Opus call/day = free |
| **C · Recommendations** | Nightly cached best-entries; portfolio-aware rebalance; follow-the-smart-money screen | Nightly Opus now affordable |
| **D · Trend (short + long)** | Regime labels; a long-term 200-week view (mungbeans thesis); weekly watchlist macro read | Multi-source synthesis now affordable |
| **E · News→move correlation** | Driver-vs-noise; catalyst extraction to chart; cross-name headline linking | Per-move Opus now affordable |
| **G · Market-now pulse** ⭐ | On-demand button → instant AI overview of what the whole market is doing *right now* (indices, VIX, sector rotation, watchlist movers, session phase) | On-demand Opus now affordable |
| **F · New data sources** | Congressional trades + the context-block expansion below | — |

**Chosen build threads (tasks AIE-1…6) — ALL SHIPPED 2026-07-23:** A annotated charts (AIE-1, v0.25.0),
F-congress (AIE-2), B-daily-brief (AIE-3, v0.26.0), E news→move (AIE-4, v0.27.0), G-market-now (AIE-5),
seasonality (AIE-6). Each runs on the `llm_provider=cli` subscription path (≈$0/token) and caches server-
side. Themes C and D ride on the same new blocks and are the natural next wave (un-started).

---

## Recommended FREE data stack

Everything here is **$0** and legally usable (official-gov / free API / free bulk / local compute). This is
the target stack — paid options are upgrades, not requirements.

| Need | Adopt (free) | Powers |
|---|---|---|
| **Congressional trades** | `kadoa-org/congress-trading-monitor` (GitHub JSON, House+Senate+cabinet, daily) + **Official House Clerk** bulk ZIP as upstream backup | AIE-2, daily brief |
| **Insider / institutional** | **SEC EDGAR** direct (Form 3/4/5, Schedule 13D/13G activist, 13F institutional) via **`edgartools`** (MIT parser) | analyst blocks, smart-money |
| **News + corporate events** | **Finnhub** (have) + **Alpha Vantage `NEWS_SENTIMENT`** + **SEC EDGAR 8-K** item codes + **API-Ninjas** transcripts | AIE-4, daily brief, deep-dive |
| **Social / retail sentiment** | **StockTwits** public API/MCP + **ApeWisdom** | `social_sentiment` block, brief |
| **Options positioning** | **Yahoo** chains (have) + **self-computed GEX / max-pain / put-call** (~50 lines, Black-Scholes) | options feature, positioning block |
| **Macro / regime** | **FRED** (yields, VIX, NFCI) + **FRED release calendar** + **US Treasury** yield curve + **sector-ETF** RS | theme D, news→macro, brief |
| **Alt-data catalysts** | **USAspending.gov** (federal contracts) + **openFDA / ClinicalTrials.gov** (biotech) | `catalyst_calendar` block, brief |
| **Adjusted price + crypto** | **Tiingo** (adjusted close — unblocks the 200-week screener) + **DefiLlama** + **mempool.space** + **Binance/Bybit** funding/OI | theme D long-term, crypto blocks |

### Paid upgrades worth knowing (CONSIDER)
- **QuiverQuant Congress API** — $30/mo, turnkey House+Senate + return calcs (skip the filing-parsing).
- **Finnhub Premium** ($12–100/mo) — unlocks its congressional + institutional endpoints on your existing key.
- **WhaleWisdom** (~$300/yr) — curated 13F hedge-fund tracking (vs parsing raw 13F yourself).
- **Marketaux** (free tier → ~$29/mo) — per-entity news sentiment.
- **Polygon.io** (free Basic) / **Tradier** (free w/ account) — reliability backups for chains/greeks.
- **Unusual Whales** (~$50/mo + API) — ready-made options flow / dark-pool / GEX if self-compute isn't enough.

### Evaluated & skipped (documented so we don't re-chase)
- **Senate/House Stock Watcher** — ❌ DEAD: sites don't resolve, GitHub mirror frozen at 2021-03. Anything citing it ships stale data.
- **NewsAPI.org** ($449/mo), **Benzinga** ($100s–1000s), **Tiingo News** (paid add-on) — news is well-covered free.
- **Sensor Tower / data.ai** ($6k+/yr), **credit-card panels** (6-figure), **satellite derived feeds** (6-figure) — institutional-only.
- **SpotGamma / SqueezeMetrics / ORATS** — you buy a *view*, not a feed; self-compute GEX instead.
- **CoinGlass** ($29–699/mo) — replaced free by exchange futures APIs (Binance/Bybit/OKX).
- **Glassnode API** (~$999+/yr) — free tier is dashboard-only; use mempool.space + exchange APIs.
- **X/Twitter** — no real free tier ($200/mo Basic closed to new signups); social covered by StockTwits/Reddit.

---

## New analyst context blocks unlocked (F, expansion)

The analyst snapshot already has pluggable blocks (`short_pressure`, `insider`, `quality`,
`long_term_trend`, `btc_halving_cycle`). The free stack lets us add, in rough priority:

1. **`congress`** — recent notable House/Senate/cabinet trades in the ticker (kadoa). *Caveat baked into the
   prompt: ~45-day STOCK Act reporting lag → lagging, weak/debated alpha; the signal is committee relevance,
   cluster buying, and size — not the raw trade.* → **AIE-2**
2. **`macro_regime`** — yield-curve slope, VIX term structure, NFCI/financial-stress, sector rotation (FRED +
   Treasury + sector ETFs). Feeds theme D + news→macro. Highest-value non-congress add.
3. **`institutional`** / **`activist`** — 13F ownership + Δ, and 13D activist stakes with Item-4 intent (EDGAR).
4. **`social_sentiment`** — StockTwits bull/bear + ApeWisdom mention rank (framed as attention/noise, not signal).
5. **`catalyst_calendar`** — biotech (openFDA/ClinicalTrials), federal contracts (USAspending), FRED econ releases.
6. **`options_positioning`** — self-computed GEX / max-pain / put-call from the Yahoo chain we already fetch.
7. **`crypto_derivatives`** — funding rates + open interest (Binance/Bybit) + on-chain (mempool/DefiLlama) for BTC/crypto names.

Plus: **Tiingo adjusted-close** unblocks the long-pending **200-week value screener** (mungbeans thesis) —
a prerequisite the app has needed regardless.

---

## Suggested build order

1. **AIE-1 · Annotated charts** & **AIE-5 · Market-now pulse** — highest visual ROI, *zero new data* (annotated charts render analyst output; market-now composes VIX + benchmark + `/movers` + session clock). Ship the visual wins first.
2. **AIE-2 · Congress block** — self-contained new data path (kadoa mirror + a `congress` block + alerts); proves the ingestion pattern the other blocks reuse.
3. **AIE-3 · Daily brief** — composes existing + congress + FRED calendar; one nightly Opus call.
4. **AIE-4 · News→move** — Alpha Vantage sentiment + Finnhub + 8-K item codes; the correlation layer.
5. **Then** the expansion blocks (macro_regime first) → themes C & D.

**Sources:** kadoa-org/congress-trading-monitor · disclosures-clerk.house.gov · SEC EDGAR (data.sec.gov) ·
edgartools · Finnhub · Alpha Vantage NEWS_SENTIMENT · StockTwits · ApeWisdom · FRED · US Treasury Fiscal Data ·
USAspending.gov · openFDA · ClinicalTrials.gov · Tiingo · DefiLlama · mempool.space · Binance/Bybit futures APIs.
