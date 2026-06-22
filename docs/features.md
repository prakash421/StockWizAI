# Features

This catalogue is grouped by feature area. Each entry lists the user-visible behaviour, the backend surface that supports it, and the corresponding Android touchpoint.

## 1. Stock analysis & scanning

### Per-ticker analysis
- **Backend**: `analyze_stock` pipeline → `signals[]`, `warnings[]`, `verdict` (`STRONG BUY` / `BUY` / `HOLD` / `AVOID` / `SELL` / `STRONG SELL`), `confidence`, `summary`.
- **Endpoints**: `GET /api/v1/scan?tickers=AAPL,MSFT` (sync), `GET /api/v1/scan/async`, `GET /api/v1/scan/status/{job_id}`.
- **Android**: Watchlist scan screen, ticker detail screen.

### Full-portfolio + trending scan
- Auto-expands the watchlist with the top-10 most-active and top-10 trending Yahoo tickers when no explicit `tickers` query is supplied or `include_trending=true` is passed.
- Result list is filtered to `STRONG BUY` only by default (`strong_only`).
- **Endpoints**: same as above plus `GET /api/v1/scan/trending` and `/api/v1/scan/trending/async`.

### Trending with history badges (added 2026-06)
- Snapshots the trending list once per day in Firestore (`trending_snapshots`).
- A 14-day window powers `_badge_for_ticker`:
  - 🔥 **Day N** when a ticker has been trending ≥ 7 of the last 14 days.
  - 📈 **Day N** when it has been trending ≥ 3 of the last 14 days.
- Each scan result is enriched with `trendingBadge` and `trendingHistory{appearances, consecutiveDays}`.
- **Endpoint**: `GET /api/v1/scan/trending/enhanced?limit&strong_only`.
- **Android**: Trending screen renders the badge and `(Day N)` streak in the title row of each result.

### Per-ticker scan resilience (added 2026-06)
- 25-second prefetch budget across fundamentals using `wait(FIRST_COMPLETED)`; missing data marked `None`.
- Per-ticker 70-second hard timeout via a sub-`ThreadPoolExecutor` inside `_run_scan_job`.
- After each ticker the engine pops its `_history_cache` and `_fundamentals_cache` entries; `gc.collect()` runs every 5 tickers.
- Result: no more dyno OOM-kills during long scans.

## 2. Trade levels

- `trade_levels(base, price, strategy, action, strike, strike_sell)` returns an entry, stop and target plus a `risk_note` describing which guard was binding.
- Hard floors (added 2026-06):
  - `MIN_STOP_PCT = 0.03` (3 %)
  - `MIN_STOP_ATR = 1.5×ATR`
  - `MIN_TGT_PCT  = 0.05` (5 %)
  - `MIN_TGT_ATR  = 2.0×ATR`
- The `risk_note` ends with the binding label, e.g. `min 3% guard` or `min 2xATR guard`.

## 3. Options strategy logic

Each option strategy has its own `_get_*_logic` helper inside the engine and is exposed as part of the analyse output.

| Strategy | Function | Key rules |
| --- | --- | --- |
| Cash-Secured Put (CSP) | `_get_csp_logic` | DTE in **[30, 70]**, OTM strikes, prefers ≈ 60 DTE; IV-rank weighting |
| Debit Vertical | `_get_vertical_logic` | Used on confirmed bullish setups; result exposes `strikes: String?` and `netDebit: Double` |
| Diagonal | `_get_diagonal_logic` | Long longer-dated call vs short-dated call |
| LEAPS | `_get_long_leaps_logic` | DTE > **180**; expiration restricted to **January or June** to align with cycle expirations |

## 4. Sector rotation (added 2026-06)

- **Endpoint**: `GET /api/v1/sector-rotation?period=1w|2w|4w` (legacy `1mo`, `3mo`, `6mo` aliased to `2w`/`4w`/`4w`).
- For each of 11 SPDR sector ETFs, the response includes:
  - `return_recent` over the requested window
  - `multi_window`: `r1w`, `r2w`, `r4w`, `accel_1v4`, `accel_2v4`
  - `early_signal`: `early_in` (bottoming, first leg up) or `early_out` (topping, first leg down)
- Top-level `rotation_signals[]`, `early_rotators[]`, `top_sectors`, `bottom_sectors`.
- Cache key is `v2:{period}`; always fetches a 3-month price window so all sub-windows are computed from the same data.
- **Android**: `NewScreens.kt` renders period chips (1w / 2w / 4w), 🔄 Early IN/OUT badges per sector card, and a chip with multi-window returns.

## 5. Daily brief (added 2026-06)

- **Endpoint**: `GET /api/v1/daily-brief?user_id&include_trending=true`.
- Returns a categorised payload designed to drive the morning notification:
  - `summary` (counts)
  - `new_buy_signals[]` — fresh STRONG BUY scan hits
  - `stop_loss_watch[]` — open portfolio positions within 3 % of their computed stop
  - `earnings_this_week[]` — portfolio + watchlist tickers with earnings inside the next 7 days
  - `etf_status[]`
  - `sector_rotation` — embeds `sector_rotation(period="2w")`
  - `trending_today[]` — only when `include_trending=true`
- **Cron**: `daily-brief.yml` (GitHub Actions) hits `/internal/wake` at `47 10 * * 1-5` UTC and `/api/v1/daily-brief?include_trending=true` at `50 10 * * 1-5` UTC.
- **Android**: `DailyRecommendationWorker` is scheduled at 06:50 local with exponential backoff (15 min). It assembles a single push including:
  - 🔺 NEW BUY SIGNALS section
  - 📅 EARNINGS THIS WEEK section
  - Trending section with badges + `(Day N)` streak when consecutive days ≥ 2
  - Sector context with early rotators

## 6. Hourly top-10 options scan (added 2026-06)

- **Endpoint**: `GET /api/v1/scan/top10-hourly?user_id&force&dedupe&perf_window_days`.
- Behaviour:
  - Returns `{skipped: true}` outside market hours (Mon–Fri 14:00–21:30 UTC) unless `force=true`.
  - Ranks the user portfolio by recent performance (`_rank_portfolio_by_perf`, default 21-day window).
  - Iterates the kinds `("long_leaps", "csps", "diagonals", "verticals")` and gathers candidates.
  - Dedupes per `(ticker, kind, expiry, strikes)` against `notified_options` for `_NOTIFY_TTL_HOURS = 24` (skip when `dedupe=true`, the default).
  - Returns `top10[]`, `candidates_evaluated`, `new_options[]`, `generated_at`.
- **Cron**: `hourly-top10.yml` runs `30 14-21 * * 1-5` UTC.
- **Android**: models `Top10HourlyResponse`, `NewOptionItem` available via Retrofit (`scanTop10Hourly`). UI integration can consume `new_options` for an in-app surface.

## 7. AI feedback loop

- **Capture**: every scan match and backtest call is persisted to `recommendations`.
- **Re-evaluation**: `WeeklyEvaluator` runs each Monday 14:00 UTC and writes to `recommendation_evals`. Up to 4 weekly evals per rec, then the rec is closed with a final outcome (`winning` / `losing` / `neutral`).
- **Learning (Phase 2)**: `SignalLearner` rolls outcomes into `signal_stats` and emits `learning` metadata attached to live responses; it can shift verdicts up or down one rung on a strategy-aware ladder once enough samples accumulate.
- **Thresholds**:
  - `EVAL_HORIZON_DAYS = 28`, `EVAL_INTERVAL_DAYS = 7`, `MAX_EVAL_COUNT = 4`
  - `WIN_THRESHOLD_PCT = +2 %`, `LOSS_THRESHOLD_PCT = −2 %`
  - `LEARN_MIN_SAMPLES_VERDICT = 10`, `LEARN_DOWNGRADE_WINRATE = 30 %`, `LEARN_UPGRADE_WINRATE = 70 %` (`LEARN_UPGRADE_MIN_SAMPLES = 20`)
  - `LEARN_STRONG_SIGNAL_WINRATE = 65 %`, `LEARN_WEAK_SIGNAL_WINRATE = 35 %`

## 8. Portfolio & watchlist

- Stored per user under `portfolio_<uid>` / `watchlist_<uid>`.
- Endpoints: `POST /api/v1/portfolio/add`, `DELETE /api/v1/portfolio/remove/{pos_id}`, `GET/PUT /api/v1/watchlist`, `POST /api/v1/watchlist/add`, `DELETE /api/v1/watchlist/remove`.
- The watchlist feeds full-portfolio scans, the daily brief and the hourly top-10.

## 9. Web parity (added 2026-06-22)

The Next.js web app (`prakash421/StockWizAi-Web`) is now at feature parity with the Android "More" menu. All web pages call the existing backend `/api/v1` surface through the `/proxy/:path*` rewrite — no new backend endpoints required.

| Feature                  | Web route       | Backend endpoint(s)                                                                        | Android counterpart       |
| ------------------------ | --------------- | ------------------------------------------------------------------------------------------- | ------------------------- |
| Sector rotation          | `/sectors`      | `GET /sector-rotation?period=1w|2w|4w`                                                      | `SectorRotationScreen`    |
| AI learnings (stats etc) | `/learn`        | `GET /recommendations/stats`, `GET /recommendations/history`, `GET /recommendations/learnings` | `AiLearningsScreen`     |
| Conversational AI        | `/ask-gemini`   | `POST /api/gemini-chat` (Next.js server route → Gemini)                                     | `GeminiChatScreen`        |
| Account / sign-out / keys| `/account`      | NextAuth Google + client-side `AiKeysDialog`                                                | `AccountScreen`           |

- `NavBar` exposes the four routes via a "More" dropdown (active highlight when `pathname` matches any of them).
- Shared TypeScript types live in `src/lib/types.ts` (snake_case to match backend JSON); the axios client wrapping `/proxy` lives in `src/lib/api.ts`.

