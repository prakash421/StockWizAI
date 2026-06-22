# Recommendation Rules

This file is the authoritative reference for the rules and thresholds that drive every recommendation. When a constant or threshold changes, update both the code and this file.

## 1. Stock verdict ladder

`evaluate_signals` produces a list of `signals[]` (positive) and `warnings[]` (negative) using technical, fundamental and analyst inputs, then derives the final verdict.

### Long-side (BUY ladder)

| Signals vs warnings | Verdict |
| --- | --- |
| `buy_score ≥ warn_score + 3` *and* MACD or volume confirmation | **STRONG BUY** |
| `buy_score > warn_score` | **BUY** |
| `buy_score == warn_score` | **HOLD** |
| otherwise | **AVOID** |

Additional noise filter: a `STRONG BUY` requires either MACD bullish confirmation or above-average volume. Without one of these the rating is capped at `BUY`.

### Short-side (SELL ladder)

| Signals vs warnings | Verdict |
| --- | --- |
| `warn_score ≥ buy_score + 3` | **STRONG SELL** |
| `warn_score > buy_score` | **SELL** |
| otherwise | **HOLD** |

### Confidence

`_confidence(signals, warnings)` returns `Low`, `Medium`, `High` or `Very High` based on the count and quality of contributing signals. `INCONCLUSIVE` is returned when there is no historical data.

## 2. Trade levels (entry / stop / target)

Implemented in `trade_levels` with internal helpers `_apply_stop` and `_apply_target`.

### Guards (added 2026-06)

```
MIN_STOP_PCT = 0.03   # stop must sit at least 3% away from the anchor
MIN_STOP_ATR = 1.5    # stop must sit at least 1.5 × ATR away
MIN_TGT_PCT  = 0.05   # target must sit at least 5% away
MIN_TGT_ATR  = 2.0    # target must sit at least 2 × ATR away
```

The wider of the two candidates wins. The `risk_note` returned to clients ends with the binding label (`min 3% guard` or `min 1.5xATR guard`, etc.) so downstream consumers can see which rule was active.

## 3. Cash-Secured Put (CSP)

- DTE filter: **30 ≤ DTE ≤ 70**, prefer the expiration closest to **60 DTE**.
- Strike: OTM, screened by IV rank and underlying technical posture.
- Verdict ladder uses an internal `bt` (backtest score) plus `signals[]` count:

| Condition | Verdict |
| --- | --- |
| `bt ≥ 75` and `len(signals) ≥ 4` | **STRONG SELL** (sell the put) |
| `bt ≥ 65` and `len(signals) ≥ 2` | **SELL** |
| `bt ≥ 55` | **HOLD** |
| otherwise | **AVOID** |

## 4. Debit Vertical

- Applied to confirmed bullish setups.
- Result fields used by the Android client: `strikes: String?` (e.g. `"100/105"`) and `netDebit: Double`.
- Verdict logic in `_get_vertical_logic` follows the long-side BUY ladder, with breakout-aware fallbacks:
  - `STRONG BUY` if `bs ≥ ws + 3`.
  - `BUY` if `bt ≥ 70` and `bs > ws`.
  - `BUY` if a breakout is active and `bt ≥ 60` and `bs > ws`.
  - Else `AVOID`.

## 5. Diagonal

- Sell a short-dated call against a longer-dated long call on the same underlying.
- Verdict logic: `STRONG BUY` only when `bt ≥ 80` and `len(signals) ≥ 5`; otherwise `BUY` when long-side conditions are met, else `AVOID` (buy intent) or `SELL`/`HOLD` (sell intent).

## 6. LEAPS (`_get_long_leaps_logic`)

- DTE filter: **DTE > 180**.
- Expiration filter (added 2026-06): only **January** or **June** expirations are eligible. Picks the maximum DTE among the eligible set.
- Verdict ladder is fundamentals-first:
  - `STRONG BUY` if `bs ≥ ws + 3` and analyst consensus is buy or strong buy.
  - `BUY` if technicals and consensus agree.
  - `HOLD` if fundamentals are weak (refuse to endorse a LEAP on a low-quality name).
  - Else `AVOID` (buy intent) or `SELL`/`HOLD` (sell intent).

## 7. Sector rotation

- Sector universe: 11 SPDR ETFs (`_SECTOR_ETFS`).
- Always fetches a 3-month bar series so 1 w / 2 w / 4 w returns share the same source data.
- Per-sector enrichment:
  - `return_recent`, `relative_strength_rank`
  - `multi_window`: `r1w`, `r2w`, `r4w`, `accel_1v4 = r1w − r4w/4`, `accel_2v4 = r2w/2 − r4w/4`
  - `early_signal`:
    - `early_in` when the 1 w window turns up while the 4 w window is still negative (bottoming, first leg up).
    - `early_out` when the 1 w window turns down while the 4 w window is still positive (topping, first leg down).
- Top-level signals:
  - Top 3 / bottom 3 by `return_recent`.
  - “Money rotating INTO …” when top 3 are accelerating and have inflow.
  - “Money rotating OUT OF …” when bottom 3 are decelerating.
  - Defensive-rotation flag when Utilities / Consumer Defensive / Healthcare avg return > Technology / Consumer Cyclical / Communication Services avg + 2 pp.
  - Risk-on flag for the symmetric case.

## 8. Daily brief

- `summary` counts derived from each section.
- `stop_loss_watch[]` includes open positions where the live price is within **3 %** of the computed stop.
- `earnings_this_week[]` includes tickers whose next earnings date falls inside the next **7 days**.
- `sector_rotation` block always calls `sector_rotation(period="2w")` so the morning brief reflects the 2-week regime.
- `trending_today[]` only present when `include_trending=true`.

## 9. Hourly top-10 options

- Market-hours gate: only runs Mon–Fri **14:00–21:30 UTC** (roughly 09:30–16:30 ET) unless `force=true`.
- Portfolio ranking: `_rank_portfolio_by_perf(tickers, days=21)` ranks the user’s open tickers by trailing 21-day return.
- Strategy iteration order: `("long_leaps", "csps", "diagonals", "verticals")`.
- Dedupe: writes one doc per `(ticker, kind, expiry, strikes)` in `notified_options` with TTL **`_NOTIFY_TTL_HOURS = 24`**. The same option will not be re-notified within 24 h while `dedupe=true` (default).
- Response shape: `{skipped, top10, candidates_evaluated, new_options, generated_at}`.

## 10. AI learning (Phase 2)

| Constant | Value | Effect |
| --- | --- | --- |
| `EVAL_HORIZON_DAYS` | 28 | Cap on follow-up window per rec |
| `EVAL_INTERVAL_DAYS` | 7 | Weekly check-ins |
| `MAX_EVAL_COUNT` | 4 | After 4 evals, close the rec |
| `WIN_THRESHOLD_PCT` | +0.02 | Above which an eval scores as winning |
| `LOSS_THRESHOLD_PCT` | −0.02 | Below which an eval scores as losing |
| `LEARN_MIN_SAMPLES_VERDICT` | 10 | Min closed recs in (strategy, verdict) before adjusting |
| `LEARN_DOWNGRADE_WINRATE` | 30 % | (strategy, verdict) below this → downgrade one rung |
| `LEARN_UPGRADE_WINRATE` | 70 % | Above this *and* `n ≥ 20` → can promote one rung |
| `LEARN_UPGRADE_MIN_SAMPLES` | 20 | Floor for promotion |
| `LEARN_STRONG_SIGNAL_WINRATE` | 65 % | Signal-level “winning” cutoff |
| `LEARN_WEAK_SIGNAL_WINRATE` | 35 % | Signal-level “losing” cutoff |

`SignalLearner.adjust_response` attaches a `learning` object to live responses and may shift the `verdict` field by one rung on the matching ladder once thresholds are met.
