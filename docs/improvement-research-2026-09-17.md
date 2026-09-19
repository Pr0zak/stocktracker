# What is still worth improving — research of 2026-09-17

App **v1.6.0**, signals **0.30.0**. Six independent lenses (widgets and background work; data layer and
offline; portfolio and money features; backend operations; test coverage; platform, accessibility and
first run) were run over both repositories, each blind to the others and each told what `docs/TODO.md`
already tracks so nothing done or open would be re-proposed. They returned 51 findings. The ones below
survived de-duplication and a hand check of every file:line cited in the top two tiers, plus a read of
the container's journal for the two live incidents. Where two or three lenses found the same thing
independently, that is noted — it is the closest thing to verification this pass has.

The pattern across the whole set: the paper sandbox and the analyst prompts are more careful than the
surfaces the user actually acts on. The sandbox knows a position's holding period; the real rebalance
does not. The sandbox tick marks a stale price as stale; the sandbox *read* route values it at cost.
The app's screens say when data is old; the home-screen widgets do not. The fixes are mostly small,
and most of them are a matter of wiring a value that already exists to a renderer that never reads it.

---

## Status — 28 of 43 built, 0 deployed

Tracked as **#F51** in `docs/TODO.md`; this table, that section, and the published page are kept
in step — when one changes, all three change. "Status" is `open` → `built` (code written, suites
green, nothing committed) → `shipped` (deployed to CT 237, or in a tagged app release). "Version"
is the release that carried the fix; a rejected item says so instead.

| Mnemonic | Effort | Status | Version |
|---|---|---|---|
| OPS-1 | S | built | — |
| OPS-2 | S | built | — |
| OPS-3 | S | built | — |
| OPS-4 | S | built | — |
| WGT-1 | S | built | — |
| WGT-2 | S | built | — |
| WGT-3 | S | built | — |
| DATA-1 | S | built | — |
| DATA-2 | S | built | — |
| DATA-3 | S | built | — |
| NOTIF-1 | M | built | — |
| SEC-2 | M | built | — |
| CI-1 | S | built | — |
| CI-2 | M | open | — |
| CI-3 | S | built | — |
| OPS-5 | S | built | — |
| OPS-6 | S | built | — |
| DATA-4 | S | built | — |
| DATA-5 | M | built | — |
| DATA-6 | M | open | — |
| DATA-7 | S | built | — |
| DATA-8 | M | open | — |
| MONEY-2 | L | open | — |
| MONEY-1 | M | open | — |
| MONEY-3 | M | open | — |
| MONEY-4 | S | open | — |
| MONEY-5 | S/M | open | — |
| MONEY-6 | M | open | — |
| MONEY-7 | S/M/— | open | — |
| NOTIF-2 | S | built | — |
| WGT-4 | S | built | — |
| WGT-5 | M | open | — |
| WGT-6 | S | built | — |
| WGT-7 | L | open | — |
| PLAT-1 | S | built | — |
| PLAT-2 | S | built | — |
| PLAT-3 | S | built | — |
| PLAT-4 | M | open | — |
| PLAT-5 | S | open | — |
| DATA-9 | S | open | — |
| DATA-10 | S | built | — |
| DATA-11 | S | built | — |
| DATA-12 | S | built | — |

---

## Tier 0 — it already happened, and nothing said so

These are not risks. They are in the journal on CT 237.

### OPS-1 · An all-failure scan is published as a success, wipes the diff baseline, and re-fires stale dip pushes

*Found by the backend lens; verified against `journalctl -u signals-scan`.*

On 2026-09-10 at 06:31–06:32 every one of the 54 analyst calls failed with `You've hit your session
limit · resets 9:10am` — the Claude subscription budget shared with interactive Claude Code sessions on
the laptop. The scan then logged `scanned 54 · flips [] · $0.0` and `dips 0 · near miss 0 · no dip 0 ·
unmeasured 54`, systemd recorded `Deactivated successfully`, and `scan_latest.json` was overwritten with
the empty night.

Why that is worse than one lost night: `scan_job._prev_state()` (`app/scan_job.py:343-354`) keeps
only rows with a `signal` key, so after an all-error night the previous state is `{}`. On 2026-09-11,
`r["dip_new"] = r.get("dip") is not None and p.get("dip") != r.get("dip")` (`:569`) was true for every
standing dip, and the phone received "Good time to add" for names that had been dipping for weeks,
presented as new. Any genuine flip across the outage was lost for good (`flipped` needs `prev_signal`).
`LATEST.write_text(...)` at `:643` is also non-atomic; `market_scan_job.py` already writes its own
summary via tmp + `os.replace` and calls this out.

**Fix (S):** carry a `last_measured` map forward inside the payload so one bad night cannot erase the
baseline; when `unmeasured == scanned` keep the previous payload and set `last_run_failed`/`last_error`
the way `macro_job.py` does; `sys.exit(1)` so systemd records the failure; write via tmp + replace.
Unit-test `_prev_state` and the diff after an all-error night — no test covers either today.

### OPS-2 · The CLI provider has no fallback to the configured API key, and a session limit is retried as if transient

*Backend lens; verified.*

`analyst.py:369-371` is a hard branch — `if llm_provider == "cli": return await llm_cli.structured(...)`
— so the API key that is configured on the container (`anthropic_api_key: True` in settings.json) is
never consulted. `llm_cli._RATE_HINTS` (`:160`) does not recognise "session limit" / "resets at", so all
54 concurrent symbol calls retried after 4 s against a wall three hours away. The same limit killed
on-demand app verdicts at 07:31, 07:46, 07:47, 08:06, 08:09, 08:28 and 08:46 that morning, and again at
16:58. A heavy evening of coding is now a way to lose the next morning's scan, brief and every verdict.

**Fix (S):** classify session-limit messages as non-retryable budget exhaustion; on that class, when an
API key is set and a new `cli_fallback_to_api` setting is on, run the API path once and tag the usage
row `provider: api, fallback_from: cli` so the cost card shows what the fallback spent. Optionally probe
once per scan (`auth_probe` exists at `main.py:115-120`) and fall back for the whole run.

### OPS-3 · Watchlist sync is last-writer-wins with no client identity — the emulator re-scoped a production scan to 14 names

*Backend lens; verified against `journalctl -u signals`.*

`settings_store.update()` (`app/settings_store.py:83-92`) replaces `watchlist` wholesale and records
nothing about who sent it. On 2026-09-11 two clients alternated `POST /api/settings` every 10–20
minutes: the phone via the Tailscale router (10.0.0.117) and the Windows host running the emulator
(10.0.0.172). The last sync before the 06:30 scan was `06:10:35 10.0.0.172`. The scan logged
`scanned 14 · flips [] · $0.240444`; the phone restored 54 names at 06:36. Forty symbols have no verdict,
no memory row and no diff for that night, and the next night re-entered the diff with no baseline.
Nothing on the phone or the dashboard said the universe had changed.

**Fix (S):** stamp each sync with an install id and store `watchlist_synced_by`; log
`n_before → n_after` and the removed names; refuse (409) a sync that would remove more than N names or
that comes from a different client than the last one unless it carries `replace=true`; show the refusal
in Settings → Data. Debug builds should default the backend URL to empty, not to the production CT.

### OPS-4 · Nothing pages

*Backend lens; verified.*

No unit has `OnFailure=`. `signals-sandbox.timer`, `signals-parked.timer`, `signals-check-open.timer`
and `signals-check-tick.timer` are `Persistent=false`, so a restart at 14:35 loses the day's tick.
`signals-monday-check.sh` and `signals-swt-check.sh` write their `!!` lines to a log file on the CT.
Every incident above was discoverable only by reading journalctl after the fact, and DASH-1 already
records that the dashboard can look healthy when it is not.

**Fix (S):** a `signals-notify@.service` that POSTs to a Home Assistant webhook (VM 106 is on the LAN
and already pushes to the phone) or ntfy; `OnFailure=signals-notify@%n.service` on the five job units;
make `scan_job` and `market_scan_job` exit non-zero when `unmeasured == scanned`; `Persistent=true` on
the sandbox timer (its `already_ran` gate already dedupes).

---

## Tier 1 — the honesty invariant, broken on the home screen

The app's screens have been swept for absent-rendered-as-confident three times. The widgets — the
product's original surface, and the one with no neighbouring number to notice a shortfall against —
were not. Three lenses found each of the first two independently.

### WGT-1 · Portfolio widget renders a partial or failed total as the whole portfolio

*Widgets, data and tests lenses; verified.*

`PortfolioWidgetState.kt:17-21` declares `missingCount` and `isPartial` with a doc comment saying "the
widget has to say so". `grep -rn isPartial app/src/main/java` returns only that declaration. Commit
`41c7158` ("stop presenting partial portfolio totals") shipped the counting half and not the rendering
half. Separately, `PortfolioWidget.kt:84` tests `summary != null` before `error != null` (`:102`), and
`WidgetRefresh.kt:174-179` writes ERROR beside the old SUMMARY on exception — so after one successful
run, every later failure renders yesterday's total with today's confidence, forever. When every quote
fails, `total == 0.0` and the widget shows the portfolio worth $0.00. There is no `LAST_SUCCESS` key and
no age line; the ticker widget has both.

**Fix (S):** render `isPartial` as "N of M priced" in amber; when `missingCount == holdingCount` show
"Couldn't load portfolio" instead of a total; add `LAST_SUCCESS` and an "as of Xh ago" line mirrored from
`TickerWidgetState`; fall back to `PriceCache` gated by `STALE_QUOTE_MS` the way `AlertChecker` does.
Pin the branch with a JVM test on a pure label helper extracted from the composable.

### WGT-2 · Watchlist widget hides its own "Couldn't load prices" whenever one row loaded

*Widgets, data and tests lenses; verified.*

`WidgetRefresh.kt:117-131` sets ERROR on any partial fetch, under a comment that says a partial failure
"must surface". `WatchlistWidget.kt:88-93` orders `rows.isNotEmpty()` before `error != null`, so the
error is visible only when every row failed. A CoinGecko outage drops BTC and renders the remaining
stocks as the whole list. `rows.take(6)` also truncates a 14-name watchlist with no "+8 more" and no
size input, although `SizeMode.Exact` is already on. Rows carry no `asOfEpochMs`, so a row served from
`PriceCache` draws yesterday's +3.2% in green as today's.

**Fix (S):** render rows and an amber footer when both exist ("N of M loaded"); compute the row budget
from `LocalSize.current.height`; print "+N more"; add `asOfEpochMs` to `WatchlistRow` and a
`LAST_SUCCESS` key.

### WGT-3 · Ticker widget: a failed fetch blanks the price, and the "as of" label is unreachable when it matters

*Widgets and data lenses; verified.*

`WidgetRefresh.kt:60-68` removes QUOTE and SPARK on exception, so one transient 429 leaves "—" and "Tap
to open" for the whole interval although `PriceCache` still holds a usable quote; the error string is
never displayed. In the opposite direction, `MarketRepository.cached()` serves the last memo on error
with no age cap, the widget then stamps `LAST_SUCCESS = now`, and the "as of" label is suppressed for a
quote that is hours old. The not-due branch (`:41-42`) returns without `update()`, and the periodic job
is constrained to `NetworkType.CONNECTED`, so offline periods never re-render the age text at all.

**Fix (S):** derive staleness from `quote.asOfEpochMs`; on failure clear the payload only if the stored
symbol differs from `config.symbol`, otherwise keep it, set ERROR, and let the age label say "update
failed"; call `update()` on the not-due path when age exceeds `STALE_AFTER_MS`.

### DATA-1 · The close / after-hours recap uses PriceCache with no age gate, then locks the day

*Data lens; verified.* `MarketSummaryNotifier.kt:82-83` falls back to `priceCache.getQuote(asset.id)`
with no `asOfEpochMs` check — `AlertChecker.kt:30-31` has exactly that check — and `lastCloseSummaryDate`
is then set. If Yahoo is down at 16:05 the recap is built from last week's change and cannot be re-sent.
**Fix (S):** apply the same `takeIf` filter; skip sending and leave the date unset when the movers came
from cache.

### DATA-2 · `GET /sandbox/state` values an unpriceable holding at cost and reads +0.00%

*Tests lens; verified.* `main.py:3986` `px = price_of(p["symbol"]) or p["avg_cost"]`; the response
carries no stale marker. The tick path (`sandbox_job.mark_price`, `stale_marks`) already does this
right; the read route the app polls does not. `SandboxScreen.kt:769-774` prints it as "Last price" and
"$0.00 (+0.00%)". **Fix (S):** use `mark_price` and emit `stale_marks`; drop the row to muted ink in the
app.

### DATA-3 · CoinGecko nulls become $0.00 / +0.00% quotes

*Data and tests lenses; verified.* `CoinGeckoService.kt:88,104` default `usd` and `current_price` to
`0.0`; `:24,50,51` coerce a null change to `0.0`; `Http.json` has `coerceInputValues = true`.
`FinnhubService` refuses an all-zeros payload; CoinGecko has no guard and no test. A $0 coin passes
through the watchlist, both widgets, the portfolio total and a "below $X" alert. **Fix (S):** nullable
fields, skip on null or ≤ 0, one decode test.

---

## Tier 2 — the safety control that reads healthy while dead

### NOTIF-1 · Price alerts are marked fired whether or not they were delivered, and blocked notifications are invisible

*Platform lens; verified.* `AlertChecker.kt:42-44` runs `fired.add(key)` and then calls
`AlertNotifier.notify(...)` with the result discarded — while `AlertNotifier.post()`'s own doc comment
(`:105-114`) says a caller "must NOT record this as sent" when it returns false, and notes that the
permission is requested nowhere but the ticker-alert sheet. There is no `areNotificationsEnabled()` or
channel-importance check anywhere (grep: 0 hits), so on any Android version blocking the app or muting
the alerts channel makes `NotificationManager.notify` drop the post while `post()` returns true.
"NVDA fell below $120" is consumed silently and will not fire again until the price crosses back.
Settings meanwhile reads "Running normally" in green, computed from the worker's last-run age alone.

**Fix (M):** return false from `post()` when notifications are disabled or the channel is
`IMPORTANCE_NONE`; add the fired key only when `notify()` returned true; make the test-brief button
report "Notifications are blocked"; give `BackgroundRunStatus` a delivery leg (permission / app blocked /
channel muted / battery-optimised) with a door to the system screen for each. Related, smaller: the
"exclude from battery optimisation" advice at `SettingsScreen.kt:571-573` has no button —
`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` needs no permission.

### SEC-2 · The signals API has no authentication at all

*Backend and data lenses; verified.* `grep -c 'Depends(' app/main.py` → 0. Open to the LAN and, via
CT 444's subnet route, to the tailnet: `POST /api/settings` (Anthropic key, CLI OAuth token,
`llm_provider`, `deep_model`, the watchlist), `/api/update` (an unauthenticated restart button — see
OPS-6), `/scan/run`, `/market_scan/run`, `/sandbox/tick` with `force`, `/sandbox/fund`,
`/sandbox/reset`, and `/portfolio/review` (an Opus call on attacker-supplied holdings). `ss -ltnp` shows
`0.0.0.0:8000`. A `deep_model` silently pointed at a cheap model would not be visible in the app.

**Fix (M):** one shared secret in `/opt/signals/.env`, a FastAPI dependency on every non-GET route and
on the settings/sandbox reads, compared with `hmac.compare_digest`, loopback exempt so the systemd curl
units keep working; one field in `SettingsStore` beside the URL, sent by the existing OkHttp
interceptor; refuse to start without the variable rather than run open. Human-driven, like SEC-1b.

---

## Tier 3 — the real portfolio is behind the paper one

All from the portfolio lens; the model claims verified (`Holding(symbol, shares, avg_cost)` at
`main.py:1756-1759`; `JournalViewModel.kt` imports no holdings store).

- **MONEY-1 · Rebalance is tax-blind (M).** `Holding` has no date and no account type, so the plan the
  user types into Fidelity can say "sell 40 NVDA" on a position eight weeks short of long-term. The
  sandbox got `annotate_holding_period` and the capital-gains paragraph in 0.11.x; the real book — the
  only one with a tax return — did not. Add optional `openedAt` to `Asset`/`HoldingSync`, a
  taxable / tax-advantaged toggle, reuse the sandbox annotator, port the prompt paragraph with its
  "absent means ignore" rule.
- **MONEY-2 · Three ledgers never meet (L).** Journal fills, exercised calls ("100 sh · now held") and
  holdings are three stores that never write to each other; every add-to-position is hand-averaged.
  Replace `shares`/`avgCost` with `lots` (keep the two as derived getters, migrate on decode), append a
  lot on `markTaken`/`markExercised` behind one confirm line. This one change supplies `openedAt` for
  MONEY-1, the anchor for MONEY-4, and a realised-gains history for equities.
- **MONEY-3 · The wheel's short legs cannot be tracked (M).** `CallPosition` has no side; the CSP and
  covered-call cards end in "Copy order ticket" with no Track. So the next visit shows the same 100
  shares eligible — following it again writes a naked call — and assignment cannot be recorded.
- **MONEY-4 · A split silently corrupts a real holding (S once lots exist).** No split adjustment
  anywhere on the holdings path; the morning after a 10:1 the Detail card shows ▼90.0% in red and the
  rebalance payload carries shares ten times too small. Request `events=div,splits` (the chart call
  already asks for `div`) and prompt — never auto-apply.
- **MONEY-5 · "Total return" is price-only unrealised gain, "vs S&P" is a current-book hypothetical
  (S to relabel, M for dividends).** Dividends are fetched only for chart markers. Relabel now; total-
  return series from `adjclose` later.
- **MONEY-6 · Fidelity Positions CSV import (M).** Would populate lots, accounts and per-account cash in
  one step and is the structural answer to why the ledgers drift.
- **MONEY-7 · The three open portfolio-AI ideas, re-priced.** Covered-call surfacing on Portfolio is
  now S (mark rows with ≥ 100 shares "income eligible", no premium until asked). A holdings-aware
  brief is M (`POST /daily_brief` carrying `syncPayload()`). A numeric health score should be recorded
  as rejected — the review already returns a sentence plus concentration flags, and a single confident
  number over a ten-name book is the class of thing this project's memory notes call theatre.

---

## Tier 4 — process and correctness plumbing

- **CI-1 · Neither repo runs its tests anywhere but a developer's shell (S).** `ci.yml` and
  `release.yml` run only `assembleDebug`/`assembleRelease` plus the colour grep; the signals repo has no
  `.github` at all. Both suites are green today — 667 Kotlin cases, 1,223 backend cases, 0 skipped — and
  nothing enforces that at release time. Add `testDebugUnitTest` before the build, a pytest workflow,
  and make the deploy tar step refuse when the local run is red.
- **CI-2 · No contract test between 432 `@SerialName` fields and the backend (M).** 86 numeric fields
  default to a literal under `coerceInputValues = true`; 74 of 75 routes return bare `dict`. A backend
  rename of `positions_value` or `avg_cost` decodes as 0.0 on the phone with no test failing in either
  repo. Golden JSON written by a backend test, committed, decoded by one Kotlin test.
- **CI-3 · The bundled changelog stopped at 0.75.2 — 28 releases ago (S).** `Changelog.kt:19`. "What's
  new" on v1.6.0 opens with July's notes under the current header, and the post-upgrade sheet has been
  silent since. Fail the release when the tag's version is missing from the map, or inject the entries
  at build time.
- **OPS-5 · `settings.json` is written non-atomically, and a corrupt file falls back to env defaults
  silently (S).** `settings_store.py:90-92` truncate-and-write, ~70 rewrites a day; `_load()` swallows
  the parse error. The file is the sole home of the Finnhub key, the CLI token, `llm_provider` and the
  watchlist. `sandbox_store._atomic_write_json` is the pattern to reuse, with a `.bak` and a
  `settings_source` field on `/health`.
- **OPS-6 · `POST /api/update` is an unauthenticated restart that cannot update and would roll back
  the rsync deploy if it could (S).** `/api/version` reports `git: false` because the service's
  environment hits `dubious ownership`; the container is 72 commits behind origin with 67 untracked
  files. Delete the route, or make it require auth and refuse on a dirty tree.
- **DATA-4 · `gaps.py` measures a raw open against an adjusted close (S).** Acknowledged in a comment
  at `market.py:144-147` as "a real latent bug" that "gets its own commit"; not in the TODO; no
  `tests/` file imports `gaps`. On a split day the sandbox prompt carries a fabricated >5% gap with an
  `edge` tilt. Rescale opens or return `None` past the implausible-jump threshold; add a test.
- **DATA-5 · 429 handling amplifies (M).** `Http.kt:68-86` retries three times with 1+2+3 s, no
  `Retry-After`, then the same again on query2, inside a two-permit gate; a 50-stock cold load is 100
  calls, so a 429 storm becomes a ~10-minute stall that doubles the volume Yahoo sees. Honour
  `Retry-After`, do not retry a 429 across hosts, add a per-host breaker.
- **DATA-6 · Duplicate Yahoo fetches per symbol (M, IDEA-5).** Quote, sparkline and 52-week range each
  re-download chart metadata the DAY response already carries. Parse them from one call.
- **DATA-7 · Call and journal stores lack `WatchlistStore`'s corruption guard (S).** An undecodable list
  is replaced by a one-element list on the next `add`, silently. Port the Empty/Unreadable decode.
- **DATA-8 · Backup restore: no confirmation, errors collapsed to "Import failed", cancellable half-way,
  no version check (M).** Six sequential writes in a `rememberCoroutineScope`; `parseBackup` never
  reads `version`. Non-cancellable scope, confirm dialog, pre-import snapshot for undo.

---

## Tier 5 — smaller, listed so they are not re-found

- **NOTIF-2** Five scan-family pushes (flips, 200-week crosses, dips, calendar, digest) and call exits
  ride the HIGH-importance price-alert channel, ungrouped; muting "Market dates to watch" mutes "NVDA
  fell below $120". Own DEFAULT channel plus `setGroup`. (S)
- **WGT-4** Widget tap is the bare `Intent(MainActivity)` CHART-5 replaced for notifications — the NVDA
  widget can land on the sandbox; "Add Widget" from NVDA's detail opens a form pre-filled with AAPL. (S)
- **WGT-5** The watchlist widget cannot be configured — no list choice, no sort, no $/% — and writes
  identical rows to every instance; the ticker widget's config machinery already exists. (M)
- **WGT-6** Portfolio widget's picker preview is the watchlist image. (S)
- **WGT-7** A Wear OS tile and complication — the user's most-glanced screen is a watch, and the
  workspace has a `wear/` module precedent in zonik. Phone stays the only fetcher; the watch renders
  what the worker last wrote, with the same age label. Only after WGT-1..3 so the honesty rules exist
  before they are mirrored. (L)
- **PLAT-1** The in-app updater downloads a 15 MB APK through the 20-second quote client, never checks
  `asset.size`, cannot be cancelled, forgets "Later" on the next cold start, and nags every 0.1.0 dev
  build with an update it cannot install. Dedicated client, size check, `BuildConfig.DEBUG` gate, R8 on
  release. (S)
- **PLAT-2** `values/themes.xml` is still `Theme.Material.Light` with `windowLightStatusBar=true`: a
  white cold-start flash on a light-system phone into a dark-only app, and `WidgetConfigActivity`'s bare
  `enableEdgeToEdge()` re-introduces the status-bar bug INK-2 fixed in `MainActivity`. (S)
- **PLAT-3** First run without a backend: five explained dead ends with no door to Settings, and Detail
  never says the analyst layer exists (`Lens.IDLE` is defined and never rendered). (S)
- **PLAT-4** Accessibility recount: 2 `semantics` blocks, 1 `Role`, 0 `stateDescription` across 72
  raw clickables. The alert switch announces "switch, off" without naming the alert; watchlist list
  chips have no selected state; heat-map tiles below the label gate are unnamed buttons; the chart
  canvas is silent. Four local edits (`toggleable`, `selectable`, a `contentDescription` on tiles and on
  the canvas). Direction is already carried by ▲/▼ or a sign everywhere checked. (M)
- **PLAT-5** Predictive back not opted in (nothing to migrate — 0 `BackHandler`s), no app shortcuts, no
  URL scheme, dependency line a year old. (S)
- **DATA-9** Nightly scan and VIX live only in memory; an offline cold start shows the dip strip in
  Loading instead of a 30-minute-old reading with its age. (S)
- **DATA-10** `SignalsHealth` probes every 30 s in the background for the life of the process when the
  backend is unreachable. Pause on `ON_STOP`. (S)
- **DATA-11** `PriceCache.putQuote` re-encodes both maps per call — 50 DataStore edits per refresh,
  O(N²) — and never prunes removed tickers. Batch into one edit. (S)
- **DATA-12** Detail dialog drafts are `remember`, not `rememberSaveable`; a rotation loses a
  half-typed trade. (S)

---

## What was checked and found already right

So that the absence of a finding is not mistaken for the absence of a look. Notification dedupe across
the 15-minute worker (every notifier has a durable watermark); denied `POST_NOTIFICATIONS` is not
recorded as delivered by the sandbox and scan notifiers; worker step isolation and the "Alert checks"
status row; reboot and app-update re-enqueue; the `MarketRepository` 15-second TTL coalescing the
widget and alert fetches; `WatchlistStore`'s Empty/Unreadable guard; the Portfolio screen's unpriced /
stale / mixed-currency naming and the backend's mirror of it; `alignBenchmark`; `rebalance_check`;
cached-review invalidation on holdings change; `RealizedPnl`, `RiskMultiple`, `ExitTaxonomy` tests;
`sandbox.json` atomic writes with `.bak` and lock; sandbox tick idempotency; `market_scan_job`'s
refusals and atomic summary; `macro_job` degraded flags; `/scan/latest` corrupt-file handling; SEC-1
redaction; CT 237 in the PBS9000 nightly backup (28 backups, latest 2026-09-17); no `@Ignore` in either
suite; Yahoo parser nulls kept as nulls; `StochasticPineTest` golden values; gain/loss carried by a sign
or arrow on every colour-coded figure; themed launcher icon; edge-to-edge insets on every Scaffold.

## Not proposed, deliberately

FX conversion (caveat-only in both repos, honestly labelled, and a Fidelity user is USD-denominated —
note `GBp` pence would be 100× off if it ever appears). A blended benchmark from the exposure groups.
Localisation (370 hardcoded strings, 0 `stringResource`, sole US user). Screenshot tests (the widget
findings are cheaper to pin as JVM tests). `usesCleartextTraffic` (required for the self-hosted URL).
Retention of `usage.jsonl` and `sandbox_inputs.jsonl` (unbounded but slow; disk at 22%).

## Suggested order

1. OPS-1, OPS-2, OPS-3, OPS-4 together — one backend release. They are all small, they explain two
   incidents the user did not know about, and OPS-4 is what makes the next one visible.
2. WGT-1, WGT-2, WGT-3, DATA-1, DATA-2, DATA-3 together — one app release. The honesty sweep the
   widgets never got.
3. NOTIF-1 and SEC-2 — each its own change; SEC-2 needs a hand on the container.
4. CI-1 first among the plumbing, because everything after it is then enforced.
5. MONEY-2 (lots) before MONEY-1, MONEY-3 and MONEY-4, which all hang off it.
