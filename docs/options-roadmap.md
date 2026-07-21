# Calls (Options) — Roadmap & Design

Status: **proposed** (2026-07-20). Personal app; **not investment advice.**

This is the plan for a beginner-friendly "buy calls" helper. You already do all the
directional work in the app (Signals card, Entry Plan `target`/`horizon`/`conviction`,
catalyst calendar). This feature is a thin **structuring + tracking layer** on top: it
tells you *which contract, what date, what price, how many, and how much you can lose* —
and then, once you've bought on Fidelity, it **tracks the position and reminds you when to
get out.**

**Three rules that shape the whole thing:**
1. **You execute on Fidelity, by hand.** The app never places orders. Its job ends at a
   copy-pasteable order ticket; it resumes when you type in what you actually filled.
2. **A long call's max loss is the premium you paid — but that can be 100%.** Every screen
   shows the dollar you can lose, in red, before anything else.
3. **It defers to the direction layer.** If the Signals card says *wait / sell*, the calls
   helper says the same. Options don't fix a wrong direction — they amplify it.

---

## Part 1 — Plain-English primer (what the app assumes you may not know)

A **call** is the right (not obligation) to **buy 100 shares** at a fixed **strike** price
any time before **expiry**. You pay a **premium** per share, ×100 per contract.

Only **four numbers** matter, and the app will always show these four first:

| Number | Meaning | Example (UNH $420 call, Sep '26) |
|---|---|---|
| **Cost** | premium × 100 × contracts — what leaves your account | $6.70 × 100 = **$670** per contract |
| **Max loss** | for a bought call, = the cost (you can't lose more) | **$670** (can go to $0) |
| **Breakeven** | strike + premium — where you start making money at expiry | 420 + 6.70 = **$426.70** (stock must be above this) |
| **Expiry** | last day the contract is alive | **Sep 17, 2026** |

Two beginner traps the app actively guards against:
- **Time decay (theta).** A call loses value every day the stock sits still, and the bleed
  *accelerates* in the last ~3 weeks. So the app avoids short-dated contracts for directional
  bets and reminds you to exit before the decay cliff.
- **Earnings IV crush.** Options get expensive before earnings and *deflate* right after,
  even if the stock moves your way. The app warns when an earnings date falls before expiry.

---

## Part 2 — When to BUY (a simple traffic light)

The app reduces "should I open a call" to a **green / yellow / red** light. Four of the five
inputs already exist in the app; only the last two (IV, liquidity) are new.

| Check | Where it comes from | Green if… |
|---|---|---|
| **Direction** | existing Signals card | signal = *buy*, conviction ≥ ~60 |
| **Trend** | existing ChartMath | price above rising SMA20 / SMA50 |
| **Time to be right** | existing Entry Plan `horizon` | there's a real catalyst/timeframe, not "someday" |
| **Earnings clear?** | existing catalyst calendar | **no** earnings between now and expiry |
| **IV not expensive** | *new — options chain* | IV rank < ~50 (else prefer a spread — see Part 4) |
| **Liquid enough** | *new — options chain* | open interest ≥ ~250 **and** bid/ask spread ≤ ~8% of mid |

**Green** → show the suggested contract. **Yellow** → show it with the caution called out
("IV is rich" / "wide spread — use a limit"). **Red** → don't suggest a call; say why in one
line ("direction says wait"). A beginner never has to interpret six indicators — they see a
light and one sentence.

---

## Part 3 — Costs & potential loss, laid out plainly

Every suggestion and every tracked position leads with the money, in this order:

```
You pay now ........  $670   (1 contract)
Most you can lose ..  $670   ← the whole premium; a call can expire worthless
Break-even .........  $426.70  (stock must close above this by Sep 17)
Stock today ........  $420.91  (needs +1.4% just to break even)
If target hit ($445)  ≈ +$1,830  (est., before commissions)
```

Plain-language framing baked in:
- "**Only spend what you're 100% OK losing entirely.**" The risk-budget field is the max
  loss, not a target allocation.
- Position sizing = `floor(risk_budget / cost_per_contract)`, so the number of contracts can
  never exceed the money you told it you're willing to lose.
- Fidelity's ~$0.65/contract commission is shown as a line item so estimates aren't rosy.

---

## Part 4 — Structure: plain call vs. "spread" (kept optional)

Default is the **plain long call** — simplest, one leg to buy on Fidelity. The app only
mentions a **debit call spread** (buy your call, sell a higher call near your target) when
**IV is expensive**, and explains it in one line:

> "IV is high, so a plain call is pricey. A $420/$445 spread costs **$410 instead of $670** and
> still profits up to your $445 target — the trade-off is your upside is capped at $445."

Beginners can ignore this entirely; it's a labeled "cheaper alternative," never the default.

**Intent matters — spreads can't acquire shares.** The suggester should let you tag intent:
*profit* vs *acquire shares*. A **debit spread cannot be used to accumulate shares** — the
short leg you sold means a rally past it gets you assigned (obligated to deliver), cancelling
the long leg's shares. So for the "buy more shares cheaply" goal, steer to an **ITM long call**
(exercisable into shares, little wasted time value), *never* a spread. The genuinely cheaper
share-accumulation tool is a **cash-secured put** — see OC-8; that's its own (sibling) feature,
not a call structure.

---

## Part 5 — When to SELL or EXERCISE (this is the part beginners get wrong)

**Default advice: SELL the call, don't exercise it.** Exercising means buying 100 shares
(needs strike×100 in cash) and throws away any remaining time value. You only exercise if the
call is deep in-the-money, near expiry, **and** you actually want to own the shares. The app
states this every time and defaults the exit action to "Sell to close."

The app manages the exit with **four rules**, surfaced as alerts/reminders (Part 6). These are
suggestions with sensible beginner defaults, all editable per position:

| Trigger | Default | Why |
|---|---|---|
| **Profit target** | option value **+80–100%** | take the win; don't round-trip a good call |
| **Stop loss** | option value **−50%** | cap the bleed; the thesis is likely wrong |
| **Time stop** | **21 days to expiry** | get out before theta decay accelerates — sell or roll |
| **Target reached** | underlying hits Entry-Plan `target` | the reason you bought is done |

"**Roll**" (close this call, open a later-dated one) is offered as a one-tap explanation when
the time stop fires but the thesis is intact.

---

## Part 6 — Alerts & reminders (reuses the existing notifier)

The app already has `AlertChecker` / `SignalScanNotifier` push plumbing. Call positions plug
into the same nightly/15-min worker and fire plain-language notifications:

- 🎯 "Your UNH $420 call is **+92%** — at your take-profit. Consider selling to close."
- 🛑 "Your NVDA call is **−50%** — at your stop."
- ⏳ "Your MSFT call has **21 days left** — time decay speeds up now. Sell or roll?"
- 📅 "**Earnings for AAPL is before your call's expiry** (7/30) — expect an IV drop after."
- 🔔 "Your UNH call **expires in 3 days** and is **in-the-money** — sell to capture value, or it
  auto-exercises."
- ⚠️ "Your call **expires Friday out-of-the-money** — it will likely expire worthless."

The expiry/auto-exercise reminder matters: Fidelity auto-exercises ITM options at expiry,
which can surprise a beginner with a large share purchase. The app warns 3 days out.

---

## Part 7 — Tracking a call you bought (easy manual entry)

Because you buy on Fidelity, tracking is manual — so make it **two paths, both fast:**

**Path A — one tap from a suggestion.** If the app suggested the contract, the "Track this"
button pre-fills everything (symbol, expiry, strike, contracts); you only confirm the **fill
price** you actually got and the date. Done.

**Path B — manual add.** A short form for a call you found yourself:

```
Symbol      [ UNH        ]      Type   ( Call )
Expiry      [ 2026-09-17 ▾]      Strike [ 420   ]
Contracts   [ 1          ]      Fill $ [ 6.70  ]  (premium per share)
Bought on   [ 2026-07-20 ▾]
▸ Advanced: take-profit % / stop % / notes   (defaults filled)
[ Save position ]
```

From those inputs the app computes and keeps live (re-pricing off the same chain feed):

- **Cost basis** = fill × 100 × contracts
- **Current value** = live mid × 100 × contracts → **unrealized P/L $ and %**
- **Breakeven**, **days to expiry**, **in/out-of-the-money** status
- Drives every Part-5 alert automatically

**"My Calls" list** — one row per open position:

```
UNH $420C  Sep 17     +$310  (+46%)   47 DTE   🟢 ITM
NVDA $180C Aug 15     −$140  (−41%)   26 DTE   🟡 near stop
```

Tap a row → detail with the four numbers, the P/L, the alert settings, and a **"Close
position"** action (record sell price → realized P/L moves it to history) or **"Mark
exercised"** (records the share assignment). Closed calls roll up into a simple **win-rate /
realized-P&L** summary so you learn from your own history.

---

## Part 8 — Data points (pulled vs. computed)

**Pulled** — Yahoo `v7/finance/options` (same host as charts; needs a **cookie+crumb**
handshake the chart path doesn't — verified working 2026-07-20). One call per symbol returns,
per contract: `bid, ask, lastPrice, impliedVolatility, openInterest, volume, inTheMoney,
contractSymbol` + the `expirationDates[]` / `strikes[]` lists. ATM put too (for expected move).
Risk-free rate from `^IRX` or a ~4.3% constant.

**Computed** (backend, deterministic — no new data source):
- **Greeks** (delta/gamma/theta/vega) via Black-Scholes — Yahoo gives IV but not greeks
- mid, spread %, breakeven, max loss, cost/contract, leverage vs. shares
- **Expected move** to expiry = `spot × IV × √(DTE/365)` — sanity-checks whether the target is
  even reachable in the timeframe
- **IV rank/percentile** — the one gap: Yahoo gives *current* IV, no history. Fix: the nightly
  scan (already runs 06:30 CT over these symbols) snapshots ATM IV to `data/iv_history.jsonl`;
  after ~1–3 months you have real IV rank. Until then show raw IV vs. historical vol, label
  rank "building."

**Reused from the app/backend:** directional signal + conviction, Entry-Plan target/horizon,
earnings date (calendar), historical vol from daily bars.

---

## Part 9 — Backlog (OC-# tasks)

Guiding rule: **no-LLM by default** (pure math, free), optional Claude paragraph reuses the
existing analyst like `/plan` does. Ship the tracker early — it's useful even before the
suggester is smart.

**P0 — data layer (prereq)**

| ID | Subject | Effort | Blocked by |
|---|---|---|---|
| OC-0 | Backend: Yahoo options fetcher with cookie+crumb (cache crumb, re-auth on 401) + Black-Scholes greeks module. Verify against a live chain. | M | — |

**P1 — the suggester (green/yellow/red + one contract)**

| ID | Subject | Effort | Blocked by |
|---|---|---|---|
| OC-1 | Backend `GET /options/{symbol}?budget=&style=`: pick expiry (45–90 DTE, clears horizon, skips earnings) + 3 delta-based strikes (safer/balanced/cheaper), each with cost, max loss, breakeven, delta, theta, expected move, spread%, OI; the go/no-go light; warnings. No LLM. | M | OC-0 |
| OC-2 | App: "Play with calls" card on the detail screen (shown when signal is bullish) — risk-budget field, style toggle, the four-numbers-first layout, traffic light, **Copy order ticket** button (`BUY n SYM MM/DD/YY strike C @ limit LMT` + OCC symbol for Fidelity). | M | OC-1 |

**P2 — tracking (the part you asked for; useful on its own)**

| ID | Subject | Effort | Blocked by |
|---|---|---|---|
| OC-3 | App: manual position entry (Path A pre-fill + Path B form) + DataStore persistence + "My Calls" list with live P/L / DTE / ITM status. Re-prices off the chain feed. | M | OC-1 |
| OC-4 | App+backend: exit alerts via the existing notifier — take-profit, stop, 21-DTE time stop, target-reached, earnings-before-expiry, 3-day expiry/auto-exercise warning. Sell-don't-exercise framing. | M | OC-3 |
| OC-5 | App: close/exercise a position → realized P/L + history + win-rate summary. | S | OC-3 |

**P3 — polish (optional)**

| ID | Subject | Effort | Blocked by |
|---|---|---|---|
| OC-6 | Backend: nightly ATM-IV logging → real IV rank; debit-spread structuring + auto-recommend when IV rich. | M | OC-0 |
| OC-7 | Backend: optional Claude paragraph on `/options` (deep=) tying the pick to the existing thesis; chain-explorer table in the app to override the strike manually. | S | OC-1 |

**Sibling track — the wheel (see Part 10)**

| ID | Subject | Effort | Blocked by |
|---|---|---|---|
| OC-8 | **Wheel / income helper** — the real "acquire & hold shares" toolset: (a) cash-secured put suggester (strike you'd own at + net cost/share + annualized yield + cash to reserve); (b) covered-call suggester once you hold ≥100 shares (strike at/above target + premium income + called-away P/L + ex-dividend early-assignment warning); (c) assignment hand-off tracking (put→shares→calls→called-away→repeat). Reuses OC-0 chain + the position tracker (OC-3). IRA-eligible at Fidelity. | L | OC-0, OC-3 |

---

## Part 10 — The wheel (cash-secured puts + covered calls)

The calls feature (OC-1…OC-7) is for a **leveraged directional bet**. This sibling track is the
better fit when the goal is **owning more shares** rather than a quick move. It's two halves of
one cycle, both of which **collect premium** and both **IRA-eligible at Fidelity** (cash-secured
/ covered — not naked):

- **Cash-secured put (the entry).** Sell a put at a strike you'd be happy to own at; reserve
  `strike×100` cash. If the stock stays up you keep the premium (income) and repeat; if it dips
  you're **assigned shares at `strike − premium` — below today's price.** The helper suggests a
  strike, shows **net cost/share, discount vs. spot, cash to reserve, and annualized yield**, and
  only ever suggests names the user actually wants (tie to the watchlist + directional layer).
- **Covered call (the income/exit).** Once ≥100 shares are held, sell a call at/above the
  Entry-Plan target; keep premium as income, or get **called away at the strike** (capped upside).
  The helper shows **premium income, called-away P/L, and warns on ex-dividend early-assignment**
  (deep-ITM calls get exercised for the dividend).

**Same risk shape, stated honestly:** a covered call = long stock + short call = *synthetically a
short put*, so a CSP and a covered call at the same strike are the **same bet** (mildly bullish,
capped upside, **full downside** below the strike). The UI must say this plainly — a CSP is *not*
"safe income"; the guardrail is "only sell puts on names you want at that strike."

**Assignment tracking (c):** the position tracker (OC-3) gains put/call-writing positions and the
hand-off: put assigned → shares appear in the portfolio → offer a covered call → called away →
offer a new put. That closed loop, with realized premium/P&L rolled up, is the whole point.

---

## Appendix — implementation notes

- **Crumb dance (verified 2026-07-20):** GET `https://fc.yahoo.com/` to set a cookie →
  GET `/v1/test/getcrumb` with that cookie → pass `?crumb=` to `/v7/finance/options/{sym}`.
  Without it: `401 Invalid Crumb`. Cache cookie+crumb; re-auth on 401.
- **Delayed/after-hours:** Yahoo option quotes are ~15-min delayed; bid/ask goes to 0/stale
  outside market hours — the card needs a "delayed / market closed" state and should fall back
  to lastPrice for display.
- **Risk framing is a feature, not a disclaimer.** Max-loss-in-red, "spend only what you'll
  lose," sell-don't-exercise, and the expiry auto-exercise warning are the guardrails that make
  this safe for someone new to calls.
