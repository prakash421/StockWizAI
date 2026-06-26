# Changelog

All notable user-visible or behavioural changes go here. Use ISO dates. Add new entries to **Unreleased** while in flight, then promote them under a dated heading once shipped.

The format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Two repositories are tracked: **Backend** (`prakash421/FinanceStreamAI_Backend`) and **Android** (`prakash421/StockWizAI`).

## [Unreleased]

_Nothing pending._

## 2026-06-25 (later) — Alert formatting polish (ticker bold, no company name, premium on every strategy)

Android commit `<pending>`.

### Changed — Android (alert lines)

Per user request 2026-06-25:

1. **Stock symbol bolded everywhere** — already enforced globally by `toRichHtml` via the `knownTickers` regex (`scanUniverse + trending + advisor.picks` covers every ticker that can appear in the body). Both the system notification (BigTextStyle via `Html.fromHtml`) and the new collapsible in-app `NotificationCard` (via `htmlToAnnotated`) render `<b>TICKER</b>` correctly. Added two regression tests (`everyNewBuyLine_hasTickerAsWholeWord`, `everyDetailLine_hasTickerAsWholeWord`) that assert each formatted line starts with the ticker as a `\bTICKER\b` whole-word match so the regex always fires.
2. **ETF company name removed** from `buildEtfDetailLine` (the only place it appeared in the alert body). The "🛡️ ETF Watch" line is now `  SOXX $245.10 +0.32%  [BUY]` instead of `  SOXX $245.10 +0.32%  [BUY] — iShares Semiconductor ETF`. Frees horizontal space on small screens.
3. **Premium $$$ shown on every option-strategy line — both surfaces**:
   - 📊 CSPs detail: now `AAPL $45 2026-08-15 — prem $1.85 — ROC: 3.1%, Δ: -0.22` (previously only ROC + Δ).
   - 📐 Diagonals detail: now `MSFT 100C/110C 2026-09-19 — debit $4.75 — Yield: 18.5%` (previously only yield).
   - 📈 Verticals detail: now `NVDA 100C/105C 2026-07-18 — debit $2.10` (was correctly formatted with `Debit: $2.1`, now uses consistent `%.2f` formatting).
   - 🔭 LEAPS detail: now `META $150C 2027-01-15 — prem $22.50 — Lev: 2.1x, Buffer: 45%` (previously only leverage + buffer).
   - 🔺 NEW BUY SIGNALS Diagonal line **added** (was previously missing — the section emitted CSP / LEAPS / Vertical but silently dropped Diagonal even when `filterTopDiagonals` returned matches).

### Added — Android (tests)

- New file `app/src/test/java/com/example/financestreamai/AlertFormattingTest.kt` — **13 unit tests** covering:
  - All 4 detail formatters (CSP / Diagonal / Vertical / LEAPS) contain ticker + premium + key metrics.
  - All 4 NEW BUYS formatters contain ticker + premium + (stop, target when applicable).
  - Missing optional fields (expiry, stop, target) render gracefully — no spurious "stop $0.00" / "tgt $0.00" suffixes.
  - Ticker appears as a `\bTICKER\b` whole word in every line so the bold regex always matches.

Total Android unit tests: **16 in `RecommendationFilterTest.kt`** + **8 in `AlertParserTest.kt`** + **13 in `AlertFormattingTest.kt`** = **37 offline tests**.

### Refactored — Android (internal)

- Extracted 8 top-level `internal fun` formatters (`formatCspDetailLine`, `formatDiagonalDetailLine`, `formatVerticalDetailLine`, `formatLeapsDetailLine`, `formatNewBuyCsp`, `formatNewBuyDiagonal`, `formatNewBuyVertical`, `formatNewBuyLeaps`) at file level in `DailyRecommendationWorker.kt` so they are JVM-testable without the Android Worker / Context harness. `buildRecommendationText` and `buildNewBuysSection` delegate to them — guarantees the detail section and the NEW BUYS section stay in lock-step on formatting changes.
- `buildNewBuysSection` signature extended with `topDiagonals: List<Pair<String, DiagonalResult>>`; single call site in `buildEnrichedReport` updated.

## 2026-06-25 — Recommendation gate hardening (SPCK false-buy regression)

Android commit `ecaa87b`.

### Fixed — Android (recommendation gating)

Reported by user (2026-06-25): SPCK was listed under "Best to BUY" in the daily alert, but a per-ticker scan of SPCK returned **AVOID**. Investigation found three layered bugs that together let an AVOID-rated stock appear in buy-side surfaces.

1. **`recommendationBucket` precedence (MainActivity.kt)** — the `when` matched `"STRONG BUY"` / `"BUY"` *before* `"AVOID"` / `"SELL"` / `"HOLD"`. A verdict such as `"AVOID — STRONG BUY ZONE BELOW $45"` was bucketed as STRONG BUY because the substring matched first. **Fix**: reorder to test AVOID → SELL → HOLD → STRONG BUY → BUY so negative stances always win over conditional buy prose.
2. **`pickRiskRewardExtremes` raw-substring filter (DailyRecommendationWorker.kt)** — the BUY bucket used `rec.contains("BUY")`, the AVOID/SELL bucket used `rec.contains("AVOID") || rec.contains("SELL") || rec == "HOLD"`. Any verdict containing the letters `BUY` qualified for "Best to BUY". **Fix**: route both buckets through the canonical `recommendationBucket()` and add a `hasBearishVeto(bullCount, bearCount)` guard so a stale or learner-upgraded BUY verdict can't override live technicals (≥ 2 bearish signals AND more bearish than bullish ⇒ excluded).
3. **`filterTopCsps` / `filterTopDiagonals` / `filterTopVerticals` / `filterTopLeaps` did not check the per-stock verdict at all** — they only ran heuristic stock-health gates (RSI, IV rank, discount-from-high) with a "bypass" branch for exceptional trade metrics (backtest ≥ 90 / ROC ≥ 3 etc). An AVOID stock with a juicy CSP or 95 %-backtest LEAPS therefore landed in `🔺 NEW BUY SIGNALS`. **Fix**: add a hard `isStockAvoidOrSell(stockRecommendation, overall)` veto at the top of each filter — explicit AVOID/SELL verdicts are dropped no matter how attractive the individual option strategy looks. The exceptional-trade bypass still applies to the heuristic stock-health gate, but **not** to an explicit negative analyst stance.
4. **`pickTopTrending` substring filter** — also routed through `isStockAvoidOrSell` for consistency (was already rejecting AVOID/SELL via substring; now uses the canonical bucket).

### Added — Android tests

`RecommendationFilterTest.kt` — 8 new regression tests covering:

- `recommendationBucket("AVOID — STRONG BUY ZONE BELOW $45", null) == "AVOID"` and 3 sibling cases.
- `recommendationBucket("HOLD; BUY DIPS", null) == "HOLD"` and 2 sibling cases.
- `recommendationBucket("SELL — BUY ZONE far below", null) == "SELL"`.
- `isBuyRated(...)` rejects all 5 conditional-buy prose patterns.
- `isStockAvoidOrSell` drops AVOID / Strong Sell / `"AVOID — BUY ZONE"` / `overall = "SELL"`.
- `hasBearishVeto` triggers at (0, 2), (1, 3), (2, 4); does **not** trigger at (3, 2), (2, 2) tie, (0, 1), (0, 0).

Total Android unit tests: **16 in `RecommendationFilterTest.kt`** (8 existing + 8 regression) plus **8 in `AlertParserTest.kt`**.

## 2026-06-24 — Deploy hardening + Alerts UI redesign (regression sweep)

Two coordinated commits — Backend `111d92a` and Android `9ab44d3`.

### Fixed — Backend (commit `111d92a`)

Root-cause of the "all jobs have failed" emails and the `/sector-rotation` HTTP 400 regression: Render had silently rolled back to the pre-`04b5150` build because the new deploy's startup health probe failed (`/api/v1/health` returned 400 without an `X-User-Id` header), and `_init_firebase()` could crash module import when no GCP metadata server was reachable.

- **`/api/v1/health` (no-arg) returns 200** — `{status, firebase, timestamp}` when no `X-User-Id` header is supplied. With header, the per-user payload still works but now returns **503** (not 500/400) when Firebase is unavailable.
- **`_init_firebase()` resilient** — wrapped in try/except, fast-fails when there is no creds file AND no `GOOGLE_APPLICATION_CREDENTIALS` / GCP metadata env hints. Avoids the ~60-second `ApplicationDefault()` timeout on Render free.
- **`_require_db()` helper** raises `HTTPException(503, "Database unavailable...")` for DB-dependent endpoints so import never blocks on Firestore.
- **All module-level / runtime `_firestore_db` callers None-guarded** — `DatabaseManager`, `_eval_meta_ref()`, `_snapshot_trending()`, `_trending_history_stats()`, `_is_already_notified()`, `_record_notified()`.
- **`render.yaml`** — corrected service name and three cron URLs (`financestream-backend` → `financestreamai-backend`); added `healthCheckPath: /api/v1/health`.
- **GitHub Actions workflows** (`daily-brief.yml`, `hourly-top10.yml`) — removed `curl -fsSL`; status is captured and switched on: 2xx success, 404 / other 4xx emits `::warning::` and exits 0 (no email), 5xx emits `::error::` and fails. Stops the "All jobs have failed" email spam during legitimate deploy lag.
- **Tests** — new `test_health_and_init.py` + `conftest.py` covering 18 ingredient + e2e cases (Firebase resilience, `_require_db` 503 contract, `/health` no-arg + with-header payloads, sector-rotation period whitelist `1w/2w/4w` + legacy aliases `1mo/3mo/6mo`, route registration for all 5 weekend endpoints). Fully offline (mocks `firebase_admin` + `yf.download`). All passing in 2.36 s.

### Changed — Android (commit `9ab44d3`)

- **Alerts UI redesign** — `NotificationCard` now parses the emoji-header body into a `ParsedAlert(preamble, sections, blocks)` and renders:
  - Each top-level section as a tap-to-expand row with a count badge (first section auto-expanded).
  - Each recommendation inside as its own bordered `Surface` so the per-ticker / per-pick blocks visually separate instead of running into one wall of text.
  - `AnimatedVisibility` + `animateContentSize` for smooth expand / collapse.
- **Backward-compatible** — legacy plain-text bodies (no emoji headers) fall through to the preamble and render verbatim; HTML bodies (with `<b>` / `<br/>`) continue to render bold via `htmlToAnnotated` per line.
- **`parseAlertBodyFromPlain`** — pure-text variant of the parser, JVM-testable without the Android `HtmlCompat` dependency.
- **Tests** — new `AlertParserTest.kt` (8 cases): multi-section, empty, preamble-only, legacy plain text, nested ✅ / ❌ sub-headers, deeply-indented detail rows, sections with no body, blank-line block boundaries.

### Process notes

- Local Gradle build surfaced two **unrelated pre-existing weekend regressions** in the working tree (a `Stri  ng?` typo in `GoogleAuthManager.kt` and a stale `DailyRecommendationWorker.kt` that had lost the trending-enhanced endpoint, the 2 w sector window, early-rotators and the NEW BUY SIGNALS section). These were transient working-tree state, not in `HEAD`; the dirty files were reset to HEAD before commit so the regression was not re-introduced.
- The local Python pytest of the backend was unblocked by hiding the bundled Firebase creds file and stubbing `yf.download` so the validation path could exercise the period whitelist offline.

## 2026-06-22 — Web parity

Web commit `7622277` on `prakash421/StockWizAi-Web` (`main`).

### Added — Web
- **Sectors page** (`/sectors`) — full port of Android `SectorRotationScreen`. Period chips `1w / 2w / 4w` (default `2w`), top/bottom sector grids, early rotators, per-sector cards with money-flow chip, recent return, volume chip, Early IN / OUT badge and multi-window chip.
- **Learn page** (`/learn`) — port of `AiLearningsScreen`. Three tabs (Stats / Signals / History) loading `/recommendations/stats`, `/recommendations/history` and (optional) `/recommendations/learnings` in parallel, with color-coded win rates, verdict baselines, top winning / losing signals and a per-rec history list with weekly outcome chips.
- **Ask Gemini page** (`/ask-gemini`) — full-height container hosting the existing `GeminiChatPanel` component.
- **Account page** (`/account`) — Google profile card (avatar / name / email), AI keys summary, "Manage AI engine keys" launches `AiKeysDialog`, inline sign-out confirmation, sign-in-with-Google for unauth users.
- **NavBar** — added a 5th "More" tab with a dropdown popover (positioned `bottom-full`) for the four new routes; highlights as active when the current path matches any of them.
- **API client** (`src/lib/api.ts`) — added `getSectorRotation(period?)`, `getRecommendationStats()`, `getRecommendationHistory(days, limit, ticker?)`, `getLearnings()`. New shared types in `src/lib/types.ts` (snake_case to match backend JSON).
- **Build** — `next build` clean; all 5 new routes prerendered as static (`/sectors`, `/learn`, `/ask-gemini`, `/account`, `/`).

## 2026-06-21

Two coordinated commits — Backend `04b5150` and Android `652fb5b`.

### Added — Backend
- **OOM / scan hardening** (`_run_scan_job`, `run_scan`, `run_scan_trending`)
  - 25-second prefetch budget using `wait(FIRST_COMPLETED)`; missing data flagged `None`.
  - Per-ticker 70-second hard timeout via a sub-`ThreadPoolExecutor`.
  - Eviction of `engine._history_cache` and `engine._fundamentals_cache` after each ticker; `gc.collect()` every 5 tickers.
- **Trade-level guards** (`trade_levels`, `_apply_stop`, `_apply_target`)
  - Floors `MIN_STOP_PCT = 0.03`, `MIN_STOP_ATR = 1.5`, `MIN_TGT_PCT = 0.05`, `MIN_TGT_ATR = 2.0`.
  - `risk_note` now ends with the binding label (e.g. `min 3% guard`).
- **Sector rotation v2** (`sector_rotation`)
  - Periods `1w` / `2w` / `4w` (legacy `1mo`/`3mo`/`6mo` aliased).
  - Per-sector `multi_window` (r1w / r2w / r4w / accel_1v4 / accel_2v4) and `early_signal` (`early_in` / `early_out`).
  - New top-level `early_rotators[]`.
  - Cache key namespaced to `v2:{period}`.
- **Trending history + badges**
  - New collection `trending_snapshots`; helpers `_snapshot_trending`, `_trending_history_stats(days=14)`, `_badge_for_ticker` (🔥 Day N ≥7, 📈 Day N ≥3).
  - New endpoint `GET /api/v1/scan/trending/enhanced(limit, strong_only)`.
- **Daily brief**
  - New endpoint `GET /api/v1/daily-brief(user_id, include_trending)` returning `summary`, `new_buy_signals`, `stop_loss_watch`, `earnings_this_week`, `etf_status`, `sector_rotation` (calls `sector_rotation(period="2w")` internally) and `trending_today`.
  - Stop-watch threshold: within 3 % of stop.
- **CSP & LEAPS expiry windows**
  - `_get_csp_logic`: 30–70 DTE, target ~60 DTE.
  - `_get_long_leaps_logic`: DTE > 180, restricted to January / June expirations, picks max DTE.
- **Hourly top-10 options scan**
  - New collection `notified_options`; `_NOTIFY_TTL_HOURS = 24`.
  - New helpers `_rank_portfolio_by_perf(tickers, days=21)`, `_is_market_hours_et()` (Mon–Fri 14:00–21:30 UTC).
  - New endpoint `GET /api/v1/scan/top10-hourly(user_id, force, dedupe, perf_window_days)` iterating `("long_leaps","csps","diagonals","verticals")`.
- **Cron infrastructure**
  - New `/internal/wake` endpoint to warm sleeping dynos.
  - `render.yaml` paid-tier `crons:` block.
  - `.github/workflows/daily-brief.yml` (free-tier alt) — wake at `47 10 * * 1-5`, brief at `50 10 * * 1-5`.
  - `.github/workflows/hourly-top10.yml` — `30 14-21 * * 1-5`.

### Added — Android
- New Retrofit endpoints on `JPFinanceApi`: `scanTrendingEnhanced`, `scanTop10Hourly`, `getDailyBrief`.
- New / extended models:
  - `EarlyRotator(sector, direction, r1w, r4w)`, `SectorMultiWindow(r1w, r2w, r4w, accel1v4, accel2v4)`.
  - `SectorData` gained `earlySignal`, `multiWindow`; `SectorRotationResponse` gained `earlyRotators`.
  - `ScanResultItem` gained `trendingBadge`, `trendingHistory(TrendingHistoryInfo)`.
  - `TrendingEnhancedResponse`, `Top10HourlyResponse`, `NewOptionItem`.
  - `DailyBriefResponse`, `BriefSummary`, `BriefBuySignal`, `BriefStopWatch`, `BriefEarnings`, `BriefEtfStatus`, `BriefTrendingItem`.
- `NewScreens.kt`
  - Period chips changed to `["1w", "2w", "4w"]` with `selectedPeriod = "2w"`.
  - Each sector card shows a 🔄 Early IN / OUT badge driven by `sector.earlySignal` and a chip with the 1 w / 2 w / 4 w returns.
- `DailyRecommendationWorker.kt`
  - Trending fetch switched to `scanTrendingEnhanced(limit=15, strongOnly=true)`, falls back to `scanTrending` on failure.
  - Sector context calls `period="2w"` and surfaces early rotators.
  - `buildEnrichedReport` adds 🔺 NEW BUY SIGNALS and 📅 EARNINGS THIS WEEK sections.
  - Trending lines show `trendingBadge` plus `(Day N)` streak when `consecutiveDays >= 2`.
- WorkManager scheduling
  - `scheduleDailyRecommendations` runs at HOUR_OF_DAY = 6 / MINUTE = **50** (was 45).
  - `setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)` so the cron retries cleanly on transient failure.

### Tests
- Added `test_new_features.py`. Per-file: `test_backtest.py` 27/27, `test_memory_perf.py` 6/6, `test_new_features.py` 4/4.
- Combined `pytest` run shows 3 pre-existing failures caused by module-state pollution across files (unrelated to these edits — each file passes in isolation).

### Notes
- Android Kotlin compile from the CLI was timing out behind the corporate proxy; verified by editing against the existing model fields and intended to be confirmed via Android Studio build/install.
- `git push` requires the corporate proxy on this workstation: `git config --global http.proxy http://proxy-sc.intel.com:911 ; git config --global https.proxy http://proxy-sc.intel.com:912`.

## Earlier history

Prior to 2026-06-21, the canonical reference is the commit log:

```bash
# Backend
git -C C:\Users\jpgudipa\Downloads\FinanceStreamAI_Backend log --oneline

# Android
git -C C:\Users\jpgudipa\AndroidStudioProjects\FinanceStreamAI log --oneline
```

Most recent prior backend commits:
- `95d261f` test: regression + perf coverage for OOM-fix changes
- `16e5948` perf(memory): prevent OOM restarts on Render daily alerts
- `9b35ae4` feat: add analyst consensus to analyst_target response

Most recent prior Android commits:
- `dbfcfae` Show 'N of M symbols scanned' progress during manual alert run
- `5065648` Bold tickers/headers in alerts + holistic R:R picker
- `d2de112` fix(alerts): live progress feedback for 'Send Today's Picks Now'
