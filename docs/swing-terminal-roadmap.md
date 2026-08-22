# Breadth, Gate & Journal Roadmap (SWT-1…10)

**Status: SWT-1…10 shipped 2026-08-21/22.** Signals `0.19.0` (deployed), app `0.99.0`. All ten
items are committed on main; the app is not released to a device yet. Companion to `ai-enhancement-roadmap.md`, `ai-signals-roadmap.md` and
`options-roadmap.md`.

| | What landed | Where |
|---|---|---|
| SWT-1 | Full-market mechanical scan — 3,101 of 3,147 symbols in ~48s on the container, zero throttles | `market_scan_job.py`, `swing.py`, `scan_store.py` |
| SWT-2 | Five-leg regime gate, three-valued, + a `gated` sandbox arm cloned from `main` | `gate.py` |
| SWT-3 | Chase status on the entry plan | `chase.py` |
| SWT-4 | Percentile rank for ten metrics against the night's cross-section | `percentiles.py` |
| SWT-5 | Dip-radar reject reasons, and four honest states where there was one | `scan_job.py`, `DipRadar.kt` |
| SWT-6 | R multiples, and the risk capture that makes them possible at all | `RiskMultiple.kt` |
| SWT-7 | Exit taxonomy; hard win rate never shown without profitable exit rate | `ExitTaxonomy.kt` |
| SWT-8 | Verdict journal — what you did with each verdict, drawn against the plan replayed | `VerdictJournal.kt`, `JournalComparison.kt`, `journal_replay.py` |
| SWT-9 | Every performance number paired with its opposite half, each with its n | `PairedStat.kt`, `PairedStatBlock.kt` |
| SWT-10 | "How these numbers are made" — the page a sceptic can check the work against | `MethodologyScreen.kt` |

**Four defects surfaced by running this against live data, none of which the tests would have
caught.** Yahoo serves some reverse-split names as interleaved pre- and post-split bars with no
adjustment applied (BYND oscillating 0.59/17.85/0.56/16.98; WETO reporting 25,652% 20-day momentum),
so a 10× single-bar guard rejects about twelve names a night. An upsert store had no way to express
"stopped being measurable", so the first twelve corrupt rows survived a forced re-scan. A 52-week-high
count compared a close against an intraday high and would have printed 0 every night forever. And an
exit taxonomy scored plan-less expiries as losses while discarding plan-less sales, so a history of
nine winning trades and one expiry reported 0% finished green.

The recurring shape: **a number that is technically true and reads as a stronger claim than it
supports.** Every item below is now written to prefer null over a confident zero.

**Origin:** a research pass over [swingterminal.com](https://swingterminal.com/) on 2026-08-21.
Swing Terminal is a rules-based swing-trade signal service — it scans the US equity market nightly,
grades the survivors against two fixed plans, runs a hard market-regime gate, and journals every
signal from entry to outcome in public. Its `/api/data` endpoint is unauthenticated, so the whole
feature surface could be read directly rather than inferred from marketing copy.

**We are not consuming their API and we are not copying their strategy.** Everything below is
computed in-house on CT 237 from sources we already use (Yahoo daily bars, the Nasdaq Trader symbol
directory, FINRA/SEC). Their site was research input only; nothing here creates a runtime dependency
on it. What is worth taking is the *instrumentation* — how they measure and present a track record —
not the breakout/pullback strategy itself, which is the wrong horizon for this app.

---

## Why their strategy is not the transferable part

Swing Terminal answers "is this a tradeable setup for the next 2–10 days". StockTracker answers
"should I own this for months or years" — the 200-week line, quality meters, insider flow, dip radar
and DCA ladders are all long-horizon instruments. Grafting a ten-day swing engine on top would fight
that thesis rather than extend it.

There is also a direct evidential reason to be sceptical of their headline numbers. Their public
forward record and their own backtest of the *same two plans* disagree violently:

| Plan | Backtest | Forward (live journal) |
|---|---|---|
| Breakout — 5% flat stop, trail activates +2.0R, trails 1.0R behind peak | 514 trades · 50.0% win · 1.53 profit factor · +0.228 avg R | 23 closed · 73.9% win · 5.17 profit factor · +1.048 expectancy |
| Pullback — 1.0 ADR stop, same trailing exit | 1,165 trades · 39.2% win · 1.48 profit factor · +0.286 avg R | 72 closed · 65.3% win · 4.25 profit factor · +0.997 expectancy |

A 23-trade sample producing more than three times the backtested profit factor is a small-sample
artifact, and to their credit their own methodology page says so ("below roughly 20–30 closed trades
per plan, win rate and profit factor can swing heavily on one or two outcomes"). Copy the
measurement. Do not copy the conclusion.

The single most instructive number on their site is the gap between two win rates they publish side
by side. On the pullback plan, `profitable_exit_rate` is 65.3% but `hard_win_rate` is **12.5%** —
41 of 72 closes were ten-day time exits that happened to finish green, not trades that reached their
target. Both numbers are honest; publishing only the first would not be. That distinction is exactly
the class of defect the `app-ui-honesty-invariants` memory already tracks, and SWT-7 below imports it.

---

## The finding that reframes everything: a full-market scan is nearly free

The reason StockTracker's nightly scan is watchlist-scoped is that `scan_job.py` routes every symbol
through the Claude analyst, and the analyst is the expensive part. A *mechanical* scan has no such
constraint, and nobody had ever measured what one would actually cost on CT 237.

Measured on CT 237 (2 cores, 2048 MB RAM, 12 GB disk — 9.1 GB free, 1.79 GB RAM available) on
2026-08-21, fetching one year of daily bars and running the existing pure-Python `market.summarize()`
over each:

| Symbols | Concurrency | Wall clock | Indicator math | Peak RSS | Downloaded |
|---|---|---|---|---|---|
| 30 | 6 | 0.7 s | — | — | 814 KiB |
| 30 | 12 | 0.4 s | — | — | 814 KiB |
| 400 | 6 | 7.3 s | 0.07 s (0.2 ms/symbol) | 52 MiB | 10.5 MiB |

Extrapolated to 4,658 symbols: **≈1.4 minutes wall clock, ≈123 MiB transferred, ~1 second of CPU for
the indicator math, and RSS that never leaves double-digit megabytes.** No rate limiting, throttling
or non-200 response appeared at 400 symbols in a single burst.

Two honest caveats on that extrapolation. First, 4,658 symbols is roughly ten times the burst that
was actually measured, and Yahoo's public chart endpoint is unofficial — sustained load at that scale
may well behave differently from a 400-symbol burst, so the implementation must ramp gradually and
back off on the first sign of throttling rather than assume linear scaling. Second, the figure covers
fetch and compute only; persisting a daily row per symbol for percentile history is additional and is
sized in SWT-1 below.

### What the breadth actually buys

The cost being negligible only matters if the breadth is worth something. It is, on six counts, and
the first is a hard dependency rather than a nice-to-have.

1. **The regime gate's breadth leg is impossible without it.** "What share of the market is above its
   own 50-day average" cannot be derived from a forty-name watchlist, or from a top-600-by-market-cap
   universe, because both are biased samples of exactly the thing being measured. Today the codebase
   has no breadth measure at all — `grep -i breadth` over `app/*.py` finds only prose in analyst
   prompts telling the model to consider breadth it was never given. SWT-2 depends on SWT-1 for this
   reason and no other.

2. **Percentiles need a distribution.** "RSI 81.4" is a number; "relative volume at the 98.6th
   percentile of tonight's whole scan" is a read. Swing Terminal carries a `_percentile` twin for
   nearly every raw field — relative strength, relative volume, tightness, breakout proximity, risk
   and reward, 52-week proximity, slope. That is only meaningful against the real market
   cross-section.

3. **Relative strength is a cross-sectional factor or it is nothing.** `SignalWeights` already
   assigns relative strength the joint-highest weight in the on-device engine, citing
   Jegadeesh-Titman. Ranking a name against a forty-symbol watchlist does not measure the factor that
   literature describes; ranking it against the market does.

4. **Discovery beyond what we already track.** The dip radar and the value screen currently only see
   names already on the watchlist, or the 600 largest names by market cap. A screener that can only
   return things you already knew about is not doing the job a screener exists to do.

5. **The LLM gets better candidates at identical token cost.** The analyst keeps running on a handful
   of names per night. The difference is that those names become the top of a market-wide mechanical
   ranking rather than the top of a personal watchlist. Same spend, materially better pool — and the
   mechanical pass is what makes the shortlist defensible.

6. **The memory layer's k-NN gets a real sample.** `memory.similar_setups` refuses to emit a track
   record below `_MIN_SAMPLES` and always reports `n`. A market-wide nightly scan produces orders of
   magnitude more scored setups to draw neighbours from, which is the difference between that
   safeguard firing constantly and the feature actually working.

### What it does not buy, and what it costs

It does not produce trade signals on its own, and it must not be presented as though it does. It
produces a ranked, percentile-contextualised cross-section — context, in the same sense the value
screener's docstring is careful about ("CONTEXT, NOT A BUY SIGNAL"). The same discipline applies here.

The real costs are politeness and storage. Yahoo's chart endpoint is unofficial and free; pulling
~4,600 symbols nightly is a meaningful step up in load from today's watchlist scan and deserves
deliberate rate limiting rather than maximum concurrency. Storage needs a retention policy from day
one — `memory.py` already has `prune()` and `retention_days()` to model it on.

---

## The universe question

The universe builder already does most of the work. `nasdaqtraded.txt` yields **12,586 directory
rows**; structural filtering plus a market-cap floor of $2B and a price floor of $5 leaves **2,033
symbols passing the filter**, of which the top **600 by market cap** are stored. Reaching Swing
Terminal's ~4,658 is therefore a matter of relaxing the floors and raising `DEFAULT_LIMIT`, not of
building new infrastructure.

The floors should not simply be dropped, though. Lowering `min_cap` toward $300–500M admits genuinely
illiquid microcaps where a 50-day average is noise and a stop is unfillable. Swing Terminal filters on
**average dollar volume**, not market cap, which is the better instrument for this purpose — it asks
"can this be traded" rather than "is this company big". SWT-1 should add an average-dollar-volume
floor and treat market cap as secondary.

---

## Tasks

| Mnemonic | Subject | Effort | Blocked by |
|---|---|---|---|
| **SWT-1** | **Broad mechanical market scan.** Relax the universe floors (add an average-dollar-volume floor; market cap secondary), raise the stored limit toward ~4,500, and add a nightly LLM-free pass that fetches daily bars and computes the indicator set for the whole universe. Persist one row per symbol per day with a retention policy. Ramp concurrency gradually and back off on throttling. | L | — |
| **SWT-2** | **Hard regime gate.** Five named boolean legs — SPY > 50-EMA, QQQ > 50-EMA, breadth > 55% of names above their own 50-SMA, VIX < 20, SPY 20-day momentum positive — all of which must pass. Plus a per-day gate history with a market score, and a "stand aside" path the sandbox honours. Replaces nothing: `/regime`'s narrative label stays, this sits beside it as the checkable half. | M | SWT-1 |
| **SWT-3** | **Buy zone and chase status.** Emit `buy_zone_low`/`buy_zone_high` alongside the existing `entry_low`/`entry_high`, plus `chase_pct` and a `chase_status` of `in_zone` / `ok` / `chase_too_deep`. Answers "you are now 4.6% past the zone" — which `EntryPlan` currently cannot say. | S | — |
| **SWT-4** | **Percentile context on every metric.** Carry a percentile twin for each raw scan field, computed against that night's full cross-section, and surface it on the detail screen next to the raw number. | M | SWT-1 |
| **SWT-5** | **Show the rejects, with reasons.** Surface near-misses and blocked names with a plain-language `blocked_reason` / `top_reason` string, the way the scan already knows why something failed but never says. Makes the screen auditable instead of merely trustable. | S | SWT-1 |
| **SWT-6** | **R as the unit of account.** Normalise outcomes by risk taken rather than by percent or dollars, so a decision can be judged independently of position size. Foundational for SWT-7 and SWT-8; nothing in the codebase currently computes an R-multiple. | M | — |
| **SWT-7** | **Exit-type decomposition, and hard vs profitable win rate.** Break the track record down by how each position ended — stop, target, time exit (win/loss), trailing stop (win/loss) — with a count and average R for each. Publish `hard_win_rate` beside `profitable_exit_rate` and never show one without the other. | S | SWT-6 |
| **SWT-8** | **Signal journal with plan-vs-actual dual curves.** Record what the *user* actually did with a verdict — taken or not, fill price, share count, exit price and date — and plot that equity curve beside the mechanical one. `memory.py` scores what the analyst said; the sandbox scores what the paper trader did; nothing records what you did. This is the equity twin of the existing options call tracker, and it is the item that answers "is this app actually helping me". | L | SWT-6 |
| **SWT-9** | **Backtest and forward stats side by side, both labelled.** `Backtest.kt` runs on-device and the memory layer holds forward stats; they have never been shown together. Present both with sample sizes and an explicit small-sample caveat below ~20–30 closed trades. | S | SWT-7 |
| **SWT-10** | **"How these numbers are made" screen.** State plainly what the figures do not account for: no slippage or fees modelled, survivorship in any historical universe, in-sample versus holdout, time exits counted inside the win rate, small samples noisy. Formalises `app-ui-honesty-invariants` into user-facing text. | S | SWT-7, SWT-8 |

### Suggested build order

**SWT-1 first, and alone.** Everything with real leverage depends on it, and until the breadth
number exists the gate cannot be built honestly.

Then the cheap wins that make existing screens auditable: **SWT-3, SWT-5, SWT-2**. Each reuses data
already fetched and each converts a "trust the number" surface into a "check the number" one.

Then the measurement spine: **SWT-6 → SWT-7 → SWT-9**, in that order, because R is the unit the other
two are denominated in.

**SWT-8 last of the substantive items**, and on its own — it is the highest-value item on the list but
it is a genuine feature build (schema, UI, reconciliation of intent against fills), not a
presentation change. **SWT-10** closes the wave once there are numbers worth qualifying.

---

## Deliberately not taken

- **The breakout/pullback swing strategy.** Wrong horizon, and it would fight the 200-week value
  thesis the app is built around.
- **Their forward performance figures as evidence of anything.** See the table above — 23 closed
  trades is not a result.
- **Consuming `swingterminal.com/api/data` at runtime.** It is unauthenticated and would have been
  the fast path, but it makes a personal app depend on someone else's uptime, someone else's
  universe, and someone else's continued willingness to serve it. Every number above is computable
  in-house for about ninety seconds of nightly CPU.
- **Their access tiers, feedback widget and cookie-consent machinery.** Single-user app.
