# AI Buy/Sell Signals — Roadmap & Findings

Status: **Tier 1 in progress** (started 2026-07-16). Personal app; not investment advice.

This document captures the plan for adding "AI intelligence" for buy/sell signals to
StockTracker. It's a personal, single-user app, so we can experiment freely — but the
guiding principle is **decision support, not an oracle**. No signal engine reliably beats
buy-and-hold after costs; the value is in ranking + explaining what the numbers already say,
and catching setups across the whole watchlist unattended.

## What already exists (the foundation)

The heavy lifting is already in the codebase:

- `app/.../util/ChartMath.kt` — SMA, EMA, Bollinger Bands, VWAP, RSI, Stochastic, MACD (all
  causal / on-device).
- `app/.../data/MarketRepository.kt` — quotes, history, 52-week, dividends, VIX (Yahoo),
  crypto (CoinGecko), `benchmark(^GSPC)` for relative strength, optional Finnhub.
- `app/.../notify/AlertChecker.kt` + `AlertNotifier.kt` — push-notification plumbing to reuse
  for signal alerts.
- A small self-hosted backend service is the natural home for any server-side LLM work (keeps the
  API key off the device).

## The four tiers

### Tier 1 — Rule-based composite signal (ON-DEVICE) — *building now*
A scoring layer on top of `ChartMath`. Deterministic, free, offline, transparent, and
**backtestable** (the thing that separates a real signal from vibes).

- Component signals: RSI zones, MACD line/signal cross + histogram, price vs SMA/EMA,
  MA crossovers, Bollinger %B, Stochastic %K/%D, volume confirmation, relative strength vs
  `^GSPC`, VIX-regime gating.
- Composite score 0–100 → label (Accumulate / Hold / Trim), with per-component breakdown so
  the "why" is always visible.
- Weights/thresholds are **configurable** (`SignalWeights`) so the deep-research findings can
  tune them without a rewrite.
- Backtest harness: walk-forward, causal (act on bar i, realize i→i+1), metrics vs
  buy-and-hold (total return, max drawdown, win rate, trade count).

### Tier 2 — Claude "analyst" layer (server-side) — *the real "AI"*
A small self-hosted FastAPI service holds the `ANTHROPIC_API_KEY` (never in the APK), assembles a
context packet per ticker (OHLCV + Tier-1 indicator values + 52wk position + rel-strength +
VIX regime + earnings/dividends + recent headlines) and asks Claude for a **structured
verdict**: `signal`, `conviction 0–100`, `horizon`, `rationale[]`, `key_risks[]`,
`invalidation` price, `catalysts[]`.

- Structured outputs (`output_config.format`) → always parseable; adaptive thinking on.
- Models: `claude-haiku-4-5` for the nightly watchlist scan, `claude-opus-4-8` for on-demand
  deep dives. Optionally give it the server-side `web_search` tool for fresh catalysts.
- Cost control: **Batch API** (50% off) + **prompt caching** (cache the methodology system
  prompt across all holdings in a run) make the nightly scan ~free.
- Nightly scan → push via the existing `AlertChecker`/`AlertNotifier` path.

### Tier 3 — News / sentiment / earnings features
Feed the analyst better inputs: Finnhub company-news + earnings-calendar (free tier) as LLM
context to sharpen the `catalysts` and `key_risks` fields. Additive to Tier 2.

### Tier 4 — Classical ML forecaster (optional, deferred)
Gradient-boosted / small-LSTM model predicting N-day forward-return sign. Real, but easiest
to overfit and needs a rigorous backtest harness — lower value-per-hour than Tier 2 for a
personal app. Park unless it becomes a project in its own right.

## Architecture summary

| Layer | Where | Why |
|-------|-------|-----|
| Tier 1 rule signals | On-device (Kotlin) | Free, instant, offline, no key on device |
| Tier 2 LLM analyst | Self-hosted FastAPI service | Key stays server-side; caches; batches |
| Nightly scan | Backend cron → push | Reuses AlertChecker/AlertNotifier |

## Non-negotiable caveats

- **Backtest before trusting.** No lookahead (only data available at signal time), include
  spread/fees, distrust suspiciously good results (overfitting).
- **Never auto-trade.** Signals inform; a human pulls the trigger.
- Personal app ≠ no risk. The freedom is in what to build, not the money at stake.

## Research conclusions (deep-research pass, 2026-07-16)

Fan-out web research → adversarial verification produced 13 confirmed (3-0) claims. The bottom
line for how we weight and expect from Tier 1:

- **Momentum / relative strength is the best-evidenced factor.** Jegadeesh-Titman: buying
  past-winners over a 3–12mo horizon earned ~1%/mo (1965-1989). → We raised `relativeStrength`
  weight (0.8 → 1.2) and lengthened `rsPeriod` to ~3 months (63 trading days).
- **But momentum reverses past ~12 months** (peaks ~+9.5% at 12mo, decays to ~4% by 36mo) — so
  keep the lookback in the 3–12mo band; don't chase very long windows.
- **VIX regime gating is strongly supported.** Momentum's payoff was +0.79%/mo on average but
  **−3.01%/mo in high-volatility down markets** ("momentum crashes"); VIX-conditioned overlays
  added large excess returns; lagged VIX is negatively related to next-day returns. → Our
  high-VIX conviction dampener is justified. *Follow-up:* make it regime-aware (dampen hardest in
  a high-VIX **down** market), since that's where the crash risk concentrates.
- **Trend / moving-average rules had a real gross edge historically but it decayed** — profitable
  "at least until the early 1990s," concentrated in the mid-60s–mid-80s, and time-bounded. → Keep
  MACD/MA weights modest; don't over-rely on them.
- **Overfitting / data-snooping is the #1 danger.** Much positive evidence doesn't survive
  data-snooping correction and realistic costs; TA has been more reliable in FX/futures than in
  stocks. → Keep the model simple (few components, few parameters), resist over-tuning, and treat
  the **backtest edge-vs-buy-hold (after fees)** as the acceptance gate. Manage expectations:
  most rules do **not** reliably beat buy-and-hold after costs.
- **Whipsaw at reversals.** Time-series momentum makes its worst bets right at trend turning
  points (e.g. 2020). Our backtest hysteresis helps but doesn't eliminate this.

Verified sources include Brock/Lakonishok/LeBaron (1992), Jegadeesh-Titman (1993), the Park-Irwin
survey of 95 studies (56 pos / 20 neg / 19 mixed), Wang/Xu on momentum & volatility (1929-2009),
and VIX-regime studies. Full claim list + citations: task output `w2zhl85e5`.

**Net:** Tier 1 is a reasonable, evidence-informed decision-support layer — not an edge that
beats buy-and-hold on its own. Its real value is (a) surfacing/explaining what the indicators
say, and (b) feeding structured context to the Tier-2 Claude analyst.
