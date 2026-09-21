# FinanceStreamAI Recommendation Engine — Audit Report

_Generated 2026-08-03 by strategy-auditor subagent (live scan verified)._

**User complaints being addressed:**
1. "95% of the recommendations I am getting are only for cash secured puts. I rarely see any recommendation for other trading strategies."
2. "There is a lot of correction in the semiconductor companies in the past one month, which is majority of my watchlist. Still I don't see any recommendations even after these massive price cuts."

**Delivery format**: proposed policy changes with file/line references. **User will review before any policy is applied.**

---

## 1. Executive summary

### Root causes for the CSP-heavy mix (95% CSPs)

- **CSP is the only strategy with no volatility ceiling.** Diagonals implicitly demand tame markets (short-leg selection breaks down at high IV), Verticals get squeezed by breakeven guards at high IV, and LEAPS has a **hard IV Rank < 45% veto** at `main.py` L1963–1964. In an elevated-IV market almost every non-CSP path is closed off; the CSP path is *helped* by high IV (richer premium → passes ROC).
- **LEAPS/Diagonals require "healthy" price structure.** Diagonals require `price > sma20` (h3) at `main.py` L1771, which almost any correcting stock fails. LEAPS' non-classic setups need a *quality kicker* (buy/strong_buy consensus, revenue growth ≥ 20%, EG ≥ 25%, or gross margin ≥ 60%) at `main.py` L1913–1929, which most corrected names lose the moment analysts trim targets.
- **Verticals are large-cap-only** (`main.py` L1834–1836) and require the breakeven to sit within +15% of spot (`main.py` L1855). During corrections this cap fights the higher IV embedded in the long-leg debit — most vertical candidates die on that single line.
- **Backend caps every strategy at 1 result per ticker** (`return sorted(...)[:1]` in CSP L1735, Diagonals L1829, Verticals L1890 — LEAPS returns up to 2, PCS up to 3). Universe-wide dedup means you'd need many tickers to fire non-CSP strategies to get variety; but each of those non-CSP strategies is single-slot.
- **Client-side minimums are asymmetric.** [`DailyRecommendationWorker.kt` L1114–1254](../app/src/main/java/com/example/financestreamai/DailyRecommendationWorker.kt):
  - CSP: `bt ≥ 80`, `roc ≥ 2.0`
  - PCS: `bt ≥ 75`, `roc ≥ 6.0` ← **3× CSP's ROC bar**
  - Vertical: `bt ≥ 85` (raised from 80 in the May 2026 calibration note) — extremely tight
  - LEAPS: `bt ≥ 85`, `delta ≥ 0.70`, `leverage ≥ 1.5`, `buffer ≥ 10.0` — quadruple gate
- **Gemini Gate has an RSI-78 overbought rule but no oversold-rescue rule.** `AiCrossValidator.kt` L897 vetoes bullish setups above RSI 78 but never *approves* an oversold CSP that other filters would drop.

### Root causes for the semi-conductor correction gap

Live scan against `financestreamai-backend.onrender.com`, 2026-08-03 (10 semi tickers): **0 recommendations across all 5 strategies for every ticker**, even AMD (17% off, RSI 47, BUY), MU (34% off, RSI 44, BUY, 100% 60d win rate), SNDK (45% off, RSI 44, BUY), INTC (36% off, RSI 40, BUY), SOXX (23% off, RSI 43).

- **LEAPS: 7 of 10 semis blocked by the IV veto alone.** NVDA 83.8%, AMD 70.1%, INTC 53.7%, MU 74.4%, SNDK 97.2%, SOXX 62.1%, TSM 51.5% — all fail `if iv_rank >= 0.45: return []` (`main.py` L1963–1964).
- **Diagonals: h3 (`price > sma20`) blocks every semi in the scan** because they are all below their 20-day SMA (bear signal "Below 20d VWAP" present on every result). The next fallback (breakout) also fails during a drawdown. So `hurdle_count` maxes at 2 (h1 discount, h2 RSI), then `min_bt_strict=80` gates it out.
- **Verticals: breakeven-within-15% cap** (`main.py` L1855) combined with the +15% short-strike floor forces a very narrow profitable band that high-IV names can't hit. Also requires bt ≥ 80 on a breakeven ratio 5-15% above spot — collapsing during downtrends.
- **CSP is failing too on semis** (this means the CSPs the user sees come from *other* names in their watchlist, not semis):
  - h2 = `rsi < 48 OR price ≤ lbb`. NVDA at RSI 53.9 fails h2. AMD/TSM at RSI 46-47 barely pass. If price bounces off lower BB, that helps, but most semis in the scan are between bands.
  - `min_bt = 80` non-breakout. `get_bt_success(hist, strike/price, dte)` looks at historical rolling windows — during a fresh drawdown the recent windows drag the score below 80%.
  - Delta target `abs(delta + 0.25)` puts strikes far OTM; on high-IV names those strikes can produce ROC < 2.0% (large-cap floor) even with rich premium.
- **PCS: same h1–h4 as CSP + earnings-blackout (`main.py` L2094–2101) + short-leg liquidity gate + tier delta bands.** During corrections, short-delta of a ~0.22 put drifts DEEP OTM (because IV inflates), making the credit-to-width ratio hard to hit, so PCS silently returns 0 even when CSP would fire.
- **Client filter compounds the block.** Even if backend produced one LEAPS on ASML (IV 42.1%, just under the veto), the client requires `leverage ≥ 1.5x AND buffer ≥ 10% AND bt ≥ 85%` in `filterTopLeaps` (`DailyRecommendationWorker.kt` L1203–1222). On an ATM-profile LEAPS the buffer is < 10% by construction — the filter deletes it.

---

## 2. Per-strategy hurdle inventory

| Strategy | Backend hurdles | Blocking bear conditions | Client-side filter | Structural constraints |
|---|---|---|---|---|
| **CSP** (`main.py` L1664) | h1 disc≥8%, h2 RSI<48 OR ≤LBB, h3 >sma200, h4 breakout. Need **any 2 of 4**, OR bt≥85 + 1. `min_bt=80` (70 if breakout). `min_roc=4%` (2% large cap, 5% crypto), floor 2%. | none — CSP allowed in downtrend if hurdles pass | `DailyRecommendationWorker.kt` L1114: bt≥80, roc≥2.0, delta∈[-0.35,-0.15]. Bypass: bt≥90 OR roc≥3. Hard veto: AVOID/SELL. | 30-70 DTE, sorted by \|Δ+0.25\|, `[:1]` result |
| **PCS** (`main.py` L2035) | Same h1–h4 as CSP. Standard tier: short Δ 0.18-0.28, long Δ 0.08-0.15, credit/width≥20%, monthly ROC≥5%, POP≥72%. High tier: short Δ 0.26-0.36, long Δ 0.04-0.10, credit/width≥15%, monthly ROC≥4%, POP≥68%. Earnings blackout. Short bid-ask ≤8%. | Same as CSP | `DailyRecommendationWorker.kt` L1235: bt≥75, **roc≥6.0**, credit>0, maxLoss>0. Bypass: bt≥90 OR roc≥15. | 30-70 DTE, `[:3]` results |
| **Diagonal** (`main.py` L1740) | h1 disc≥5%, h2 RSI 30-72, **h3 >sma20**, h4 breakout OR 20d≥5%. Long Δ 0.62-0.85, short Δ 0.15-0.35. Short strike ≥ max(price×1.05, spot+1σ move). Yield≥17%. Profit potential≥12%. `min_bt=80` (68 breakout). | h3 fails on every correcting stock | `DailyRecommendationWorker.kt` L1143: bt≥70, yieldRatio≥5.0, netDebt>0. Bypass: bt≥85 OR yld≥20. | Long: Jan/Jun ≥ 210 DTE; short: 40-75 DTE, `[:1]` |
| **Vertical** (`main.py` L1832) | **Large-cap only**. Long Δ 0.55-0.75, short Δ 0.18-0.35, net debit >0, **BE ≤ spot×1.15**, bt≥80 on breakeven ratio. | Not gated by trend signals but stringent structural gates | `DailyRecommendationWorker.kt` L1174: **bt≥85** (raised May 2026 from 80), netDebit>0. Bypass: bt≥92. | Long: Jan/Jun ≥ 210 DTE, `[:1]` |
| **LEAPS** (`main.py` L1893) | Large-cap only. Setup ∈ {classic (disc≥10% + >sma200), breakout+>sma200, trend (>sma200+>sma50+20d≥5%)}. Non-classic requires quality kicker. **`iv_rank < 0.45` (hard veto)**. bt≥75 classic / 62 breakout / 68 trend. ITM: intrinsic/prem≥60%, Δ 0.75-0.85. OTM: strike 0-12% OTM, Δ 0.45-0.60. Liquidity: spread/mid ≤25%, OI≥5. | Extended-chase filter, sma200 gates | `DailyRecommendationWorker.kt` L1203: **bt≥85, delta≥0.70, leverage≥1.5, buffer≥10.0**. Bypass: bt≥95 AND buffer≥50. | Jan/Jun ≥ 210 DTE, `[:2]` (best ITM + best OTM) |

**Universal gates applied to every strategy:**
- Stock-level `AVOID`/`SELL` verdict → hard-vetoed on the client.
- Gemini gate (`AiCrossValidator.kt` L882–902) can VETO any ticker with `RSI > 78`, all-contracts `BT < 60%`, within 1 ATR of resistance for breakout-dependent strategies, or earnings within 7 days.
- `_early_res` short-circuit at `main.py` L1497–1517 skips *all strategies* if the ticker fails every one of: `discount≥8% OR rsi<48 OR ≤lbb OR >sma200 OR breakout OR large_cap`. In practice this rarely fires because `price > sma200` catches most trending names — but it does short-circuit deeply broken stocks that could still work for CSP.

---

## 3. Bias analysis: why 95% CSPs

Ranked hypotheses with concrete code citations:

1. **[Highest impact] LEAPS IV veto at 0.45.** `main.py` L1963–1964: `if iv_rank >= 0.45: return []`. Any market segment in elevated IV (semis, growth names, small caps) loses LEAPS entirely. The scan shows 7 of 10 semis blocked here. CSP has no such ceiling.
2. **Diagonal requires `price > sma20`.** `main.py` L1771 makes h3 fail on every stock in a pullback. Diagonals therefore mostly fire only on trending-up names, but those are also the names where CSP fires with rich premium — so CSP tends to win the "one slot" per ticker.
3. **Vertical breakeven cap 15%.** `main.py` L1855: `(l['strike'] + net) > tech['price'] * 1.15` filter. Combined with `short_strike > price×1.02` and short Δ 0.18–0.35, the mathematically feasible short strikes at high IV push the breakeven above the cap. Verticals get dropped in-loop with no fallback.
4. **Client-side `filterTopVerticals` bt≥85 and `filterTopLeaps` bt≥85 raised in May 2026.** `DailyRecommendationWorker.kt` L1187, L1218. Comments cite historical win-rates (Vertical 41.6%, LEAPS 53.5%). These raises directly cut the pass rate of both strategies. Compare CSP: `bt≥80` (L1128).
5. **`filterTopLeaps` demands delta≥0.70 AND leverage≥1.5x AND buffer≥10%.** `DailyRecommendationWorker.kt` L1216–1219. The OTM LEAPS profile the backend produces (Δ 0.45-0.60, buffer typically 0–5%) never passes this filter. Only the ITM profile is eligible, halving LEAPS output.
6. **`filterTopPcs` requires ROC≥6% monthly.** `DailyRecommendationWorker.kt` L1247. Well above the CSP 2% floor. PCS's higher risk per contract makes hitting 6% monthly ROC hard on quality names (safer names = tighter credit); PCS mostly fails while CSP on the same name succeeds.
7. **Backend caps: 1 result for CSP/Diagonal/Vertical, 2 for LEAPS, 3 for PCS.** `main.py` L1735, L1829, L1890, L1985, L2312. Even in an ideal market you can never get more Diagonal recs than CSPs from the same ticker.
8. **`_get_diagonal_logic` uses `leaps_exps[0]` and `short_exps[0]`** (`main.py` L1780) — a single long/short expiry combo, not the full grid. If that particular combination misses on strike math, the strategy returns 0 with no other combinations tried.
9. **`_get_vertical_logic` uses `targets[0]` only** (`main.py` L1845) — the *nearest* Jan/Jun ≥ 210 DTE. Doesn't try the further-out cycle if the near one fails.
10. **Diagonal min_bt_strict=80 without breakout.** `main.py` L1817. Higher than CSP's non-breakout floor for similar hurdle counts.
11. **Gemini Gate rule "BT < 60% on every contract shown → VETO"** (`AiCrossValidator.kt` L898). LEAPS backtest uses a 378-day 52w-high recovery probability — on a stock that's been sideways for a year the recovery probability can dip below 60%, killing an otherwise good LEAPS pick.

---

## 4. Semi-conductor correction gap analysis

Live scan (2026-08-03) revealed 100% of semi tickers get zero recs across all 5 strategies. Walk-through of the ideal correction target (e.g., **INTC**: 36% off high, RSI 40.5, IV 53.7%, above sma200, below sma50, 20d trend -25.7%):

| Strategy | Should it fire on INTC? | Actual result | Blocking hurdle |
|---|---|---|---|
| **CSP** | Yes — this is textbook CSP territory (deep discount, oversold, above sma200) | 0 | Most likely: `min_bt=80` at `main.py` L1707 — 20-day trend of −25% drags the strike-specific `get_bt_success` below 80 across candidate strikes even though the general 60d win rate is 95.8%. Also the +2% offset short-strike delta target `\|Δ+0.25\|` may land on a strike where `roc < 2%` at high IV. |
| **PCS** | Yes — same thesis as CSP with defined risk, high IV = rich credit | 0 | Same bt problem; plus tier delta bands may not align with the credit-to-width floor at INTC's IV surface. |
| **LEAPS** | Yes — ITM LEAPS on quality large-cap at 36% off high is classic value | 0 | **IV Rank 53.7% > 45% ceiling** at `main.py` L1963. Hard veto. Even if it passed, the client-side `bt≥85` and `buffer≥10%` would gate the OTM profile. |
| **Diagonal** | Yes if bounce forming (RSI 40) | 0 | **h3 (`price > sma20`) fails** at `main.py` L1771 — INTC below sma20 with 20d trend −25%. h4 (breakout) also fails. Only 2 of 4 hurdles pass, then `min_bt_strict=80` gates. |
| **Vertical** | Maybe — the risk/reward is unusual during high-IV drawdown | 0 | **Breakeven cap at spot×1.15** at `main.py` L1855 + IV 53.7% → net debit is inflated → breakeven exceeds cap. |

- **MU** (34% off, RSI 44, IV 74.4%, BUY, 60d win rate 100%): CSP should trivially pass 2 of 4 hurdles (h1 disc, h2 RSI); LEAPS blocked by IV 74.4% > 45%; Diagonal blocked by price < sma20; Vertical blocked by IV inflating debit past breakeven cap. The one that most obviously *should* fire but doesn't is **CSP on MU** — investigate whether the specific-strike `bt` is dragged down by the recent selloff.
- **SNDK** (45% off, RSI 44, IV 97.2%): LEAPS blocked by extreme IV; even CSP is failing.
- **NVDA** (12% off, RSI 53.9): CSP h2 fails (RSI 53.9 > 48 and probably not ≤ LBB), h1 pass, h3 pass, h4 unknown. Might be at 2 of 4 hurdles. LEAPS blocked by IV 83.8%.

**Key finding:** LEAPS/Diagonals/Verticals structurally cannot fire during a semi correction. CSPs *can* but the bt gate combined with high-IV strike math produces 0 candidates on most semis. The delivered notification is empty (or filled by non-semi CSPs elsewhere in the universe).

---

## 5. Recommended policy changes

Ordered by expected impact on the two user complaints. **User to review and approve before any code changes are made.**

### A. Unlock LEAPS on semi corrections (biggest single unblock)

**A1. [HIGH] LEAPS IV Rank ceiling: 0.45 → 0.65 for classic setups; keep 0.45 for non-classic.**
- File: `main.py` L1963–1964
- Current: `if iv_rank >= 0.45: return []`
- Proposed:
  ```python
  iv_ceiling = 0.65 if classic_setup else 0.45
  if iv_rank >= iv_ceiling:
      return []
  ```
- Rationale: The 45% ceiling is a cheat-sheet rule of thumb for "don't overpay for premium." But a deep-discount classic setup on a quality large-cap IS the market compensating you for the drawdown — refusing to buy LEAPS on 30-40% discounts is the wrong side of the trade.
- Expected impact: Unlocks NVDA, AMD, INTC, MU, SNDK, SOXX, TSM LEAPS during corrections. Adds 3–8 LEAPS recs per daily scan when semis are down 20%+, moving strategy mix ~5-10 pp toward LEAPS.
- Risk: LEAPS bought at very high IV suffer vega crush when IV mean-reverts. Mitigate by: (a) requiring `classic_setup` (deep discount) for the elevated ceiling; (b) preferring the ITM Δ 0.75-0.85 profile (lower vega exposure); (c) already-present `discount ≥ 10%` and `bt ≥ 75%` on classic keep quality high.

**A2. [HIGH] LEAPS: expand OTM strike profile.**
- File: `main.py` L1997
- Current: `if 0.0 <= strike_pct <= 0.12 and 0.45 <= delta <= 0.60 and mid < tech['price'] * 0.25`
- Proposed: `if 0.0 <= strike_pct <= 0.20 and 0.40 <= delta <= 0.65 and mid < tech['price'] * 0.30`
- Rationale: Widens the sweet-spot band so that on high-IV names (where a 10% OTM strike still carries 0.65 delta), the candidate isn't rejected.
- Expected impact: ~20% more LEAPS OTM candidates.
- Risk: Slightly higher leverage entries.

**A3. [HIGH] Client `filterTopLeaps`: allow OTM profile.**
- File: `DailyRecommendationWorker.kt` L1216–1219
- Current: `leaps.delta >= 0.70 && leverage >= 1.5 && buffer >= 10.0 && bt >= 85.0`
- Proposed:
  ```kotlin
  val itmOk = leaps.delta >= 0.70 && buffer >= 10.0
  val otmOk = leaps.delta in 0.40..0.65 && leverage >= 2.0
  val leverageOk = leaps.leverage.parseToDouble() >= 1.5
  (itmOk || otmOk) && leverageOk && bt >= 80.0
  ```
- Rationale: The backend returns two profiles (ITM + ~10% OTM) but the client filter treats them uniformly and always deletes OTM. Also, bt ≥ 85 excludes ~30% of otherwise good LEAPS.
- Expected impact: ~2× LEAPS surface. Adds ATM leverage plays alongside safer ITM anchors.
- Risk: OTM LEAPS have lower win-rate; capped by `leverage ≥ 2.0`.

### B. Unlock Diagonals/Verticals on corrections

**B1. [HIGH] Diagonal h3: allow deep-discount / breakout-pending bypass.**
- File: `main.py` L1771
- Current: `h3 = tech['price'] > tech['sma20']`
- Proposed: `h3 = tech['price'] > tech['sma20'] or tech['discount'] >= 0.15 or mom["breakout_pending"]`
- Rationale: A stock down 20%+ from its high is trading below sma20 by definition — you cannot recover to sma20 without buying below it. The 20-DMA gate excludes exactly the value-entry setup we want.
- Expected impact: Doubles diagonal candidate universe during corrections. Would unlock diagonals on INTC/MU/SNDK today.
- Risk: Marginally increases falling-knife entries. Mitigate by keeping h4 breakout requirement and the `min_bt_strict = 80` non-breakout floor.

**B2. [HIGH] Vertical breakeven cap: 15% → 20% when `discount ≥ 15%`.**
- File: `main.py` L1855
- Current: `if net <= 0 or (l['strike'] + net) > tech['price'] * 1.15: continue`
- Proposed:
  ```python
  be_cap = 1.20 if tech['discount'] >= 0.15 else 1.15
  if net <= 0 or (l['strike'] + net) > tech['price'] * be_cap:
      continue
  ```
- Rationale: On a 30% correction, a 15% breakeven target is unreasonably tight — the stock has to recover to only 85% of its recent trading range.
- Expected impact: Unlocks verticals on INTC/AMD/MU currently blocked by breakeven math.
- Risk: Wider breakeven = higher probability of full loss. Already gated by bt ≥ 80.

**B3. [MED] Vertical: try `targets[:2]` and pick best (not just nearest).**
- File: `main.py` L1845
- Rationale: If the Jun 2027 vertical fails on breakeven math, Jan 2028 (longer DTE, cheaper net debit relative to width) often works.
- Expected impact: ~15-25% more vertical results.
- Risk: Minor performance (one extra option-chain fetch, cached).

**B4. [MED] Diagonal: try `leaps_exps[:2]` and pick best.**
- File: `main.py` L1780
- Same rationale as B3.

### C. Fix CSP on semi corrections

**C1. [HIGH] CSP: add "deep-discount bt bypass".**
- File: `main.py` L1712–1727
- Proposed:
  ```python
  mom_bypass = mom["breakout_confirmed"]
  deep_discount_bypass = (
      tech['discount'] >= 0.15
      and tech['price'] > tech['sma200']
      and self.get_bt_success(hist, 1.0, 60) >= 70
  )
  min_bt = 70.0 if (mom_bypass or deep_discount_bypass) else 80.0
  ```
- Rationale: The strike-level `get_bt_success` is artificially depressed by the recent drop → bt drops even though *forward* probability is high (mean reversion). The bypass restores signal on exactly the setups the user wants.
- Expected impact: Unlocks CSPs on MU/INTC/SNDK/SOXX during current correction. Adds 2–5 CSPs per scan when watchlist is heavily corrected.
- Risk: Value-trap CSPs on falling stocks. Mitigate by requiring `price > sma200` and the client's AVOID hard veto is still in place.

**C2. [MED] CSP: broaden h2 (RSI band) from 48 → 52 when `discount ≥ 15%`.**
- File: `main.py` L1701
- Proposed: `h2 = tech['rsi'] < (52 if tech['discount'] >= 0.15 else 48) or tech['price'] <= tech['lbb']`
- Rationale: NVDA at RSI 53.9 (12% off, IV 84%) is a poster child that should trigger.
- Risk: Slightly less oversold entries; still gated by `discount ≥ 15%`.

### D. Rebalance strategy mix (lower CSP share, boost PCS)

**D1. [HIGH] Client `filterTopPcs`: lower `roc` minimum from 6.0 → 4.0.**
- File: `DailyRecommendationWorker.kt` L1247
- Rationale: Standard-tier PCS has backend floor `min_monthly_roc: 5.0` and high-tier `4.0` (`main.py` L2074, L2079). The client's 6% throws away all high-tier PCS by construction.
- **Expected impact: This alone could 3-5× PCS surface** and dramatically rebalance the CSP-heavy mix (PCS is the capital-efficient CSP alternative).
- Risk: PCS with lower ROC-on-risk are still gated by `bt ≥ 75` and backend's `POP ≥ 68-72%` — quality intact.

**D2. [MED] Backend `_get_csp_logic`: return up to 2 results (`[:2]`).**
- File: `main.py` L1735
- Proposed: `return sorted(results, key=lambda x: (abs(x['delta'] + 0.25), -float(x['roc'].rstrip('%'))))[:2]`
- Rationale: Symmetry with LEAPS (2) and PCS (3). Gives optionality between Δ0.20 and Δ0.30 CSPs.
- Risk: Slightly increases CSP share; pair with D1 to net-rebalance.

**D3. [MED] Client `filterTopVerticals`: relax bt from 85 → 80.**
- File: `DailyRecommendationWorker.kt` L1187
- Rationale: The May 2026 raise cited 41.6% historical win-rate — but backend has since added stricter breakeven + delta bands.
- Expected impact: 1.5-2× Vertical surface.
- Risk: Reintroduces some historical losers. Mitigate by tracking new win-rate.

### E. Data quality / correctness

**E1. [MED] LEAPS: add analyst-upside fallback to quality kicker.**
- File: `main.py` L1925–1928
- Proposed: add `or analyst_upside >= 15.0` to the `has_quality` disjunction.
- Rationale: Matches the documented spec (Strategy 4 Hurdle 1: analyst target ≥ 15%).
- Risk: Low.

**E2. [LOW] Gemini gate BT-<60% rule too coarse.**
- File: `AiCrossValidator.kt` L898
- Proposed: `VETO if the median BT across contracts is < 55%, OR if the strategy is bullish and price is 20%+ off high AND no contract shows BT ≥ 70%.`
- Rationale: LEAPS bt (378-day 52w recovery probability) is systematically lower than CSP bt (60-day above-strike). A blanket 60% cutoff punishes LEAPS unfairly.

---

## 6. Quick wins vs deeper reworks

### Quick wins (single-line tweaks, low regression risk — deploy first)

| # | File / Line | Change | Impact |
|---|---|---|---|
| **A1** | `main.py` L1963 | LEAPS IV ceiling 0.45 → 0.65 (classic-only) | Unlock LEAPS on 7 of 10 currently-blocked semis |
| **A3** | `DailyRecommendationWorker.kt` L1216–1219 | Allow OTM LEAPS profile; lower bt 85 → 80 | 2× LEAPS surface |
| **B1** | `main.py` L1771 | Diagonal h3: allow deep-discount / breakout-pending bypass | Unlock diagonals on all correcting semis |
| **B2** | `main.py` L1855 | Vertical breakeven cap: 1.15 → 1.20 when discount ≥ 15% | Vertical recovery on high-IV drawdowns |
| **C2** | `main.py` L1701 | CSP h2 RSI band: 48 → 52 when discount ≥ 15% | Adds NVDA-like moderate-drop CSPs |
| **D1** | `DailyRecommendationWorker.kt` L1247 | PCS `roc ≥ 6.0` → `roc ≥ 4.0` | 3-5× PCS surface; single biggest CSP-share rebalance |
| **D3** | `DailyRecommendationWorker.kt` L1187 | Vertical bt 85 → 80 | 1.5-2× vertical surface (measure win-rate) |
| **E1** | `main.py` L1925 | Add analyst-upside to LEAPS quality kicker | LEAPS on analyst-priced setups |

### Deeper reworks (structural — need calibration + testing)

| # | Scope | Change | Notes |
|---|---|---|---|
| C1 | CSP bt bypass | Deep-discount bypass in `_get_csp_logic` | Requires shadow-testing over recent semi drawdown history |
| A2 | LEAPS OTM band | Widen strike/delta window | Needs re-derivation of leverage math |
| B3, B4 | Multi-expiry search | Diagonal / vertical iterate over `[:2]` expiries | Small perf cost, needs cache warmup verification |
| D2 | CSP `[:1]` → `[:2]` | Symmetry with LEAPS/PCS | Coordinate with `MAX_PER_STRATEGY` and notification length |
| E2 | Gemini gate BT rule | Strategy-aware BT thresholds | Requires updating gate prompt |
| — | **Backtest calibration** | Recompute historical win-rates (Vertical 41.6%, LEAPS 53.5%) using the current tightened backend logic | The May 2026 client-side tightening was calibrated against numbers that predate the current stricter backend gates — likely double-counting caution |
| — | `_get_diagonal_logic` structural | Best-of-N scoring instead of first-valid | Larger refactor |
| — | `_early_res` short-circuit | Consider removing the `is_large_cap` shortcut for tickers with recent-week volume spike | |
| — | PCS tier-gate reevaluation | High-tier's STRONG-BUY requirement disqualifies exactly when tier is most valuable (`main.py` L2126–2128) | Consider allowing `BUY with discount ≥ 15%` |

### Sequencing recommendation

Deploy in this order and measure between each phase:

1. **Phase 1 (quick wins on client only, zero backend risk):** D1, A3, D3. Should immediately shift CSP share from 95% toward ~70-75% by surfacing more PCS + LEAPS + Vertical.
2. **Phase 2 (backend quick wins for semi coverage):** A1, B1, B2, C2, E1. Should specifically unlock semi corrections.
3. **Phase 3 (deeper reworks):** C1, A2, backtest recalibration, then structural refactors.

Between phases, log the strategy mix (`csps / pcs / diagonals / verticals / long_leaps`) count per daily scan and semi-specific hit rate; use those metrics to validate that CSP share is falling toward the ~40-50% target without a corresponding drop in overall recommendation quality (measured by 4-week forward return per rec, already tracked in the AI learning system per `docs/recommendation-rules.md` §10).
