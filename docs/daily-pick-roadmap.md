# Daily Pick Roadmap (DP-1…16)

**Status: DP-1…8 and DP-10…16 built 2026-09-22; DP-9 not started.** Backend deployed to CT 237 with
the `signals-daily-pick` timer enabled (first scheduled run Wed 2026-09-23 07:05 CT). App changes are
committed on main and not yet released to a device. Companion to `ai-signals-roadmap.md`,
`ai-enhancement-roadmap.md` and `swing-terminal-roadmap.md`.

### Where the build differs from the plan below

- **A shut market gate raises the conviction bar to 70; it does not force "no pick".** The gate failed
  17 of the 22 sessions from 2026-08-21 to 2026-09-22, almost always on breadth alone. A hard block
  would have produced "no pick" nearly every day and, worse, left no gate-shut picks to grade, so the
  gate's value could never be measured. The shut gate is always added as a reason against.
- **Stocks only.** The nightly market scan excludes ETFs by design (`universe.py`), so the pick never
  names one. "Watchlist only" means the watchlist stocks the scan covers.
- **Intraday alerts on earlier picks** that were logged as bought fire on stop and target only; the
  zone alerts are for today's pick.
- **Found and fixed while building:** the market scan (05:45) refused a stale universe that the
  watchlist scan (06:30) only rebuilt afterwards, so one morning in eight had no scan. The 2026-09-21
  session was lost this way. The universe is now rebuilt a day before it goes stale.
- **First live run (2026-09-22, forced after the close):** 3,067 names scanned, 2,025 eligible,
  eight finalists (three of them oil refiners). Result: no pick. DK came closest at conviction 64,
  under the gate-shut bar of 70. The simple rule's pick (DK) was recorded for grading.

## What it is

One card, once a trading day: **the single best buy candidate the backend can find, why, and what
would make it wrong**, drawn so the reasoning can be read in a few seconds without knowing what an
RSI of 63 means. It sits at the top of the Watchlist tab, can push a morning notification, and taps
through to the existing Detail screen for the full chart.

The card is a *reading*, not an order. Nothing in it places a trade, and the sandbox does not act on
it (see DP-9 for the optional arm that would).

## Three rules the design is built around

These come from things this repo has already measured or already got wrong. They are not style
preferences, and the plan changes shape if any of them is dropped.

1. **"No pick today" is a valid answer, and the card must be able to say it.** A feature that must
   name a buy every day will name a bad one on the days nothing qualifies. When the regime gate is
   shut, or no name clears the bar, the card says so and shows what came closest and why it failed.
   Forcing a pick would also make the pick's track record meaningless, because it would mix real
   convictions with filler.
2. **The model chooses and explains; the server owns every number.** Same rule as
   `rebalance_check.py` and `validate_and_fill`. The model returns a symbol, a conviction, and
   reasons that each point at a *factor key* from a fixed list. The server fills in each factor's
   value and percentile from the scan. A reason that points at a factor the scan did not measure is
   dropped rather than rendered, so the card can never show a number the model invented.
3. **Absent is not zero, stale is not fresh** (see the app UI honesty invariants). Every factor that
   could not be measured renders as "—" with the word "unmeasured", never as a 0th-percentile bar.
   The card always shows when the pick was made and when its price was last read, and a failed load
   shows as a failure, never as yesterday's card.

Also worth stating up front, because the card's framing depends on it: `entry-timing` measurements
on 2026-08-26 showed that waiting for a dip loses money on this universe even though it wins 74% of
the time. So the card's entry plan is "buy zone and invalidation", not "wait for $X". Chase status
(`chase.py`) says when price has run past the zone; it does not tell the reader to wait.

## What the card looks like

Collapsed, as it sits at the top of the Watchlist tab:

```
┌─────────────────────────────────────────────────────┐
│ TODAY'S PICK                 picked 07:10 · 2m ago  │
│                                                     │
│  BRK-B  Berkshire Hathaway          $504.80  +0.4%  │
│  ◔ 72  conviction          ● gate open  ● no earnings│
│                                                     │
│  "Quality compounder holding its trend while the    │
│   market wobbles; the 50-day is support."           │
│                                                     │
│  WHY                                                │
│  ▲ Trend        ████████████████░░  88th   MAs stacked│
│  ▲ Rel strength ██████████████░░░░  74th   vs S&P    │
│  ▲ Long cycle   17% above 200-week line             │
│  ▼ Momentum     ███████░░░░░░░░░░░  38th   cooling   │
│                                                     │
│  PLAN   stop 488 ─┤▓▓▓ zone 498–510 ▓▓▓│●─── 540 target│
│         risk $17 for $35 upside  ·  2.1R  · in zone │
│                                                     │
│  SETUPS LIKE THIS  beat the S&P 58% of the time     │
│  over 20 days  ·  n=312 across 41 names             │
│                                   [ Why · Details ▸]│
└─────────────────────────────────────────────────────┘
```

Expanded (the "Why" sheet), which adds:

- **Every reason in full**, green for "supports" and red for "against". At least one reason against
  is required: an analysis that finds nothing against a buy has not looked.
- **The two range bars the app already draws** for this name: `FiftyTwoWeekRangeBar` and
  `TwoHundredWeekLineBar`.
- **An RSI dial** with the overbought and oversold bands shaded (`ThresholdMeter`), so a reader can
  see "stretched" without needing the number.
- **Context chips:** regime gate legs (pass, fail, or unknown), macro read (or "no macro read", which
  is not the same as calm), next earnings date, and sector.
- **What would make it wrong:** the invalidation condition in words and the stop level on the plan
  ladder.
- **The runners-up:** the two or three names that came closest, each with one line on why it lost.
- **Past picks:** the last 20 picks, each with its 5-day and 20-day return against the S&P over the
  same window. Losers are shown as plainly as winners, and each horizon carries its n.

The "no pick" state keeps the same frame, with the header reading **"No pick today"**, one sentence
on why (for example "regime gate shut: SPY below its 50-day, breadth 31%"), and the closest
candidate shown greyed with its failing reason.

## Pipeline

```
05:45  market scan (existing)  ─┐
06:15  macro read (existing)    ├─► 07:05 daily-pick timer
06:30  watchlist scan (existing)┘        │
                                         ├─ DP-1 shortlist   (pure, no model, no network)
                                         ├─ DP-2 analyst picks 1 of ≤8, or none
                                         ├─ DP-2 reconcile   (server fills numbers, drops orphans)
                                         ├─ DP-3 persist     data/daily_picks.jsonl + memory row
                                         └─ served by GET /daily_pick
   during the session: the app re-reads price + chase status only; the pick itself does not change
```

The run happens pre-market from the prior close, on purpose. A pick that changed during the day
would have no fixed point to grade from.

## Work items

| | What | Where |
|---|---|---|
| DP-1 | Shortlist: mechanical filter + a *stated* score over the night's scan | `app/daily_pick.py` (pure) |
| DP-2 | Analyst schema (`DailyPick`) + server reconciliation | `app/analyst.py`, `app/daily_pick.py` |
| DP-3 | `GET /daily_pick`, `POST /daily_pick/run`, timer, JSONL history | `app/main.py`, `deploy/signals-daily-pick.*` |
| DP-4 | Grading: picks recorded in memory as `origin='daily_pick'`; `GET /daily_pick/history` | `app/memory.py` |
| DP-5 | The card: API models, ViewModel with five states, collapsed card | `ui/pick/` |
| DP-6 | The "Why" sheet: factor bars, dial, plan ladder, runners-up, past picks | `ui/pick/` |
| DP-7 | Morning notification, **on by default**, sent with the daily brief | `notify/` |
| DP-10 | Intraday price alerts on today's pick: entered zone, ran past zone, hit stop, hit target | `notify/` |
| DP-8 | Honesty tests on both sides | tests |
| DP-9 | *Later:* a `daily_pick` sandbox arm, home-screen widget, and watch tile | — |
| DP-11 | Mechanical shadow pick, graded beside the AI's pick | `app/daily_pick.py`, `app/memory.py` |
| DP-12 | "Fits your portfolio" line: already owned, exposure-group weight before and after | `app/daily_pick.py`, `ui/pick/` |
| DP-13 | "I bought it" button → verdict journal entry with the plan's stop and target | `ui/pick/`, `VerdictJournalStore` |
| DP-14 | 5-day and 20-day report-card notifications on past picks | `notify/` |
| DP-15 | Tap-to-explain on every factor | `ui/pick/` |
| DP-16 | Repeat-pick visibility ("picked 3× this month") | `app/daily_pick.py`, `ui/pick/` |

### DP-1 — Shortlist (backend, no model)

- **Input:** the latest `market_scan` night plus the watchlist scan, read from `scan.db`.
- **Hard filters, each recorded as a reject reason:** minimum price and dollar volume, the existing
  split-corruption guard, measured on the night, no earnings within 3 sessions (earnings are shown
  on the card rather than excluded if the calendar could not be read), and not a crypto spot pair.
  Crypto ETFs are allowed.
- **Score:** `percentiles.py` explicitly forbids averaging percentile ranks into a composite. The
  shortlist therefore uses a *stated* thesis with direction and weight, in the style of
  `screener.value_score`. The thesis is momentum and relative strength first (the best-evidenced
  factor, per the analyst prompt), trend structure second, and a penalty for extension (`chase`) and
  for extreme `adr20` volatility. The weights sit in one named constant with a comment saying why.
- **Output:** the top 8 plus the reject counts. A name missing any factor that carries weight is
  scored on the factors it has, and that is flagged, never filled with zero.
- **Regime gate:** if the gate is shut, the shortlist still runs, so the "closest" candidate can be
  shown, but the pick is forced to `none`. If the gate is unknown, the card says unknown.
- **Tests:** pure-function tests for filter order, score direction, absent-factor handling, and the
  gate override.

### DP-2 — The analyst's part

- **Schema:** `DailyPick{ symbol | null, conviction, thesis, reasons: [{factor, stance: supports|against, text}], invalidation, runners_up: [{symbol, why_not}], none_reason | null }`.
- **`factor` is an enum** (`trend`, `rel_strength`, `momentum`, `rsi`, `long_cycle`, `range_52w`,
  `volume`, `volatility`, `insider`, `smart_money`, `quality`, `valuetrap`, `seasonality`, `macro`,
  `earnings`). The prompt receives each candidate's full `_build_signal` summary plus its track
  record.
- **Reconciliation (pure, tested):** drop any reason whose factor is absent from that symbol's data;
  require at least one reason for and one against, otherwise downgrade to `none`; clamp conviction;
  reject a symbol that is not on the shortlist; recompute the plan levels and the R multiple from the
  signal's `VerdictLevels`; and set the conviction floor at 60. Below the floor, the result is
  `none`.
- **Model:** the scan model through the CLI provider, so it costs $0 per call. It runs once a day
  over at most 8 summaries.

### DP-3 — Endpoint and schedule

- `signals-daily-pick.timer`, Mon–Fri 07:05 CT, skipped on market holidays (`market_calendar`).
  Idempotent by ET date, like the sandbox tick.
- `GET /daily_pick` returns `{available, as_of, date, pick | none, factors{…with value + pctile |
  null}, plan, chase, track_record, gate, macro, runners_up, cached}`. The live price and chase status
  are refreshed on read with a short TTL; the pick itself is not.
- `available: false` with a reason when no run exists yet for today. It is never yesterday's pick
  relabelled as today's: yesterday's is served only with `stale: true` and its own date.

### DP-4 — Grading

- Each pick is recorded with `origin='daily_pick'` and graded at 5/20/63/252 sessions against the
  S&P by the existing nightly `score_memory`. "None" days are recorded too, so the history shows how
  often the card declined.
- `/memory/stats` gains a `daily_picks` card, kept separate from `buy_calls` and `sandbox_buys` so
  the three never blur. It reports beat rate and median excess return with n and n_symbols, and is
  greyed out under n=20 (about a month of picks).

### DP-5 / DP-6 — App

- `ui/pick/DailyPickCard.kt`, `DailyPickSheet.kt`, `DailyPickViewModel.kt`, and the API models in
  `SignalsApiService`.
- **Five explicit states:** loading; pick; no pick; unavailable (no run today); and failed (the
  error is shown and the previous value is not). A cached or stale pick carries a visible label.
- **New visual pieces:**
  - `FactorBar`: a percentile bar that is directional (▲/▼, green/red) and renders "—" when the
    percentile is absent.
  - `ConvictionRing`.
  - `PlanLadder`: stop, entry zone, current-price dot and target on one horizontal scale, with the R
    multiple and a chase chip.
- **Reused as-is:** `FiftyTwoWeekRangeBar`, `TwoHundredWeekLineBar`, `ThresholdMeter`,
  `PairedStatBlock` (past-pick record), and `ChaseLine`.
- **Accessibility:** every bar and dial carries a content description that reads the value in words.
  `ChartAccessibility.kt` already has the pattern.
- **Placement:** top of the Watchlist tab, collapsible, with the collapsed state remembered. It does
  not add a seventh bottom-nav tab.

### DP-7 — Notification

A morning push: "Today's pick: BRK-B (72) — quality compounder holding trend", or "No pick today —
regime gate shut". It is sent from the same 15-minute `WidgetRefreshWorker` as the AI daily brief,
in the same 08:30–10:00 ET window, deduped by ET date. It is **on by default** (decided 2026-09-22)
with its own switch in Settings, and it still respects the master AI switch and needs a configured
Signals URL. If the day's pick is unavailable or failed to load, no notification is sent; the card
shows the failure instead. A notification that said "no pick" when the truth was "could not check"
would be the absent-as-empty defect again.

### DP-10 — Intraday price alerts on the pick

The pick never changes during the day, but where its price sits relative to the plan does. The same
15-minute worker compares the live price with the pick's plan levels during market hours and sends a
notification when the price crosses into a new state:

| State | Fires when | Example |
|---|---|---|
| Entered zone | price moves from above the zone into it | "BRK-B is back in its buy zone ($498–510), now $506" |
| Ran past zone | price rises above `entry_high` by more than the `chase.py` threshold | "BRK-B ran past its buy zone — now $517, 1.4% above" |
| Hit stop | price trades at or below the stop / invalidation level | "BRK-B broke its stop ($488) — today's pick is invalidated" |
| Hit target | price reaches the target | "BRK-B reached its target ($540)" |

- **One alert per state per day,** deduped by ET date and symbol, so a price hovering on a boundary
  cannot ring every 15 minutes. "Hit stop" and "hit target" are final for the day.
- **Timing is best-effort.** Android can defer the worker in Doze, so an alert may arrive more than
  15 minutes after the crossing. The notification says the price and the time it was read, never
  just "now".
- **Uses the same price the card shows** (the refreshed read from `GET /daily_pick`), so the
  notification and the card can never disagree.
- **A pick with no stop or no target** (the analyst may return null for a level it cannot justify)
  simply has no alert for that level. It is never compared against 0.
- **Separate switch in Settings**, on by default together with DP-7. It fires only on trading days
  during regular hours.
- **Tests:** state transitions (including skipping from above-zone straight to below-stop), per-day
  dedupe, null levels never fire, and nothing fires outside market hours.

### DP-8 — Honesty tests

- **Backend:** absent factor → reason dropped; shut gate → `none`; model names an off-list symbol →
  rejected; no reason against → `none`; yesterday's pick is never served as today's.
- **DP-11…16:** shadow pick recorded on "none" days; identical AI and rule picks count as ties;
  portfolio fit with an unpriced holding reports unknown, not a weight; a report card with no mark
  sends nothing; repeat counts carry `n_symbols`.
- **App:** a null percentile renders "—", not 0; a failed pick load sends no morning notification; a failed refresh does not show the prior pick; the
  cached and stale labels render; the no-pick state renders its reason; content descriptions are
  present.

### DP-11 — Mechanical shadow pick

Every run also records the DP-1 shortlist's top-ranked name, chosen with no model, as
`origin='daily_pick_rule'`. It is graded exactly like the AI pick at 5/20/63/252 sessions.

- **Why:** without it there is no way to say whether the model adds anything over sorting a list.
  If after a few months the model's pick does not beat the rule's pick, the model step can be
  dropped. The comparison is the point of the feature, not an extra.
- **Honesty:** two picks graded on the same days share market conditions, so the comparison is
  paired by date. It is reported as "AI better on X of N days, median difference Y pp", with N,
  and greyed out under N=20. On days when both chose the same name, the day is counted as a tie,
  not as evidence either way.
- **When the AI says "none",** the rule's pick is still recorded. That measures what declining cost
  or saved.
- **On the card:** one line in the Why sheet: "A simple rule would have picked XOM", plus the
  running comparison in the past-picks section.

### DP-12 — "Fits your portfolio"

- The app sends the held symbols and each holding's value (the same holdings sync the daily brief
  uses, extended with value). The server groups by `_exposure_group`, so owning IBIT and FBTC counts
  as one BTC exposure. It returns: already held (yes/no, and current weight), the pick's exposure
  group's weight now, and what that weight would be after a buy of a reference size (the median
  position size in the account).
- Rendered as one line under the plan, for example "You hold CVX and XOM: energy is 14% of your
  account, about 19% after a typical-size buy."
- **Absent is not zero:** if holdings were not sent, or a holding could not be priced, the line says
  so ("portfolio fit unknown: 1 holding unpriced") rather than computing a weight from a partial
  total. This is the `_build_portfolio_snapshot` defect from 2026-07-28 in a new place.
- It never produces a share count or an order size.

### DP-13 — "I bought it"

- A button on the card and the Why sheet. It opens a small dialog pre-filled with the pick's symbol,
  the current price, the plan's stop and target, and the pick's date. The user confirms the shares
  and price they actually paid.
- It saves a verdict-journal entry (`VerdictJournalStore`, SWT-8) linked to the pick's id, so the
  journal's replay and exit taxonomy apply to it unchanged.
- Once logged, DP-10's alerts on that symbol read "your position" instead of "today's pick", and
  they keep running on later days until the position is closed in the journal.
- Separately from the pick's own record, the past-picks section can then show "picks you acted on"
  with their outcomes, counted in dollars and trades, never as a rate on a handful of trades.

### DP-14 — Report-card notifications

- When a past pick receives its 5-session and 20-session marks in the nightly grading, the next
  morning's worker sends "Last week's pick BRK-B: +2.1% vs S&P +0.8%" (or the equivalent for 20
  days). Losers are sent exactly as winners are.
- To avoid a second morning buzz, this is folded into the DP-7 notification as a second line when
  both are due the same day.
- A pick whose mark could not be written (price fetch failed) sends nothing, not a 0% line.
- Its own Settings switch, on by default with DP-7.

### DP-15 — Tap-to-explain

- Every factor row, the RSI dial, the conviction ring and the plan ladder has a small info affordance.
  Tapping shows one or two plain sentences, for example "Relative strength: how this stock has done
  compared with the S&P over the last 3 months. The 74th percentile means it did better than 74% of
  the stocks measured last night."
- The text is static, written once in the app and not generated by the model, so it cannot drift or
  contradict the numbers. It also explains what a percentile here is not: the ranks are not grades,
  per the warning in `percentiles.py`.
- It links to the existing "How these numbers are made" page (`MethodologyScreen`, SWT-10) for more.

### DP-16 — Repeat-pick visibility

- The server counts how many times the same symbol, and the same exposure group, has been picked in
  the last 20 trading days, and returns both counts.
- The card shows "Picked 3× this month" when the count is above 1.
- The past-picks record reports `n_symbols` next to `n` (the same rule as the memory layer), because
  five picks of one name are close to one observation, not five.
- The prompt is told the recent pick list, so a repeat has to be a deliberate choice, not an accident.
  It is not forbidden: the right answer can genuinely be the same name twice.

## Decisions taken by default (reversible)

- **Universe:** the whole liquid market from the full scan, with a Settings toggle for "watchlist
  only". Scanning the whole market is cheap (about 1.4 minutes on CT 237); the only cost of a wider
  universe is that the model sees 8 finalists instead of 8 of your own names.
- **Timing:** pre-market from the prior close, not intraday.
- **One pick, not a list.** The runners-up exist to show the choice was a choice, not to act as
  more picks.

## Estimate

Backend DP-1 to DP-4: about a day, most of it DP-1's tests and the reconciliation. App DP-5 to DP-8 and DP-10:
about two days, most of it the three new visual components. DP-11 to DP-16: about a day and a half
(backend half a day, app a day). Total about four and a half days. The first month of picks will
have no meaningful track record, and the card says so.
