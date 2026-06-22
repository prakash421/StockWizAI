# Changelog

All notable user-visible or behavioural changes go here. Use ISO dates. Add new entries to **Unreleased** while in flight, then promote them under a dated heading once shipped.

The format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Two repositories are tracked: **Backend** (`prakash421/FinanceStreamAI_Backend`) and **Android** (`prakash421/StockWizAI`).

## [Unreleased]

_Nothing pending._

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
