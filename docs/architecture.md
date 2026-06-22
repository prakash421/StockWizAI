# Architecture

## System overview

```
┌──────────────────────────┐         HTTPS          ┌─────────────────────────────┐
│  StockWizAI (Android)    │ ─────────────────────▶ │  FinanceStreamAI Backend    │
│  Kotlin · Jetpack Compose│ ◀───────────────────── │  FastAPI · gunicorn (1 worker)
│  Retrofit · Gson         │                        │  Hosted on Render            │
│  WorkManager (daily 6:50)│                        │                              │
└──────────────────────────┘                        │  ┌──────────────────────┐    │
                                                    │  │ engine: signals,     │    │
                                                    │  │ trade_levels, CSP,   │    │
                                                    │  │ LEAPS, verticals,    │    │
                                                    │  │ diagonals            │    │
                                                    │  └─────────┬────────────┘    │
                                                    │            │                 │
                                                    │  ┌─────────▼────────────┐    │
                                                    │  │ market data layer    │    │
                                                    │  │ yfinance + Tradier   │    │
                                                    │  │ (rate-limited, cached)│   │
                                                    │  └─────────┬────────────┘    │
                                                    │            │                 │
                                                    │  ┌─────────▼────────────┐    │
                                                    │  │ ai_feedback.py        │   │
                                                    │  │ RecommendationTracker │   │
                                                    │  │ WeeklyEvaluator       │   │
                                                    │  │ SignalLearner         │   │
                                                    │  └─────────┬────────────┘    │
                                                    └────────────┼─────────────────┘
                                                                 │
                                                    ┌────────────▼─────────────────┐
                                                    │  Google Firestore             │
                                                    │  - portfolio_<uid>            │
                                                    │  - watchlist_<uid>            │
                                                    │  - recommendations            │
                                                    │  - recommendation_evals       │
                                                    │  - signal_stats               │
                                                    │  - trending_snapshots         │
                                                    │  - notified_options           │
                                                    │  - meta/eval_status           │
                                                    └───────────────────────────────┘

External cron drivers (free tier, GitHub Actions):
   ┌──────────────────────────────────────────────────────┐
   │ daily-brief.yml   10:47/10:50 UTC Mon–Fri            │
   │ hourly-top10.yml  14:30–21:30 UTC Mon–Fri            │
   └──────────────────────────────────────────────────────┘
```

## Backend

- **Stack**: Python 3.10, FastAPI, gunicorn with `uvicorn.workers.UvicornWorker`, single worker (`-w 1`), `--timeout 600`, `--max-requests 80`.
- **Process layout** (`Procfile`): one web process. Render free tier (≈512 MB RAM, ephemeral disk, can sleep).
- **Internal scheduler**: APScheduler `BackgroundScheduler`, weekly job `WeeklyEvaluator` — Mondays 14:00 UTC.
- **External cron**: GitHub Actions workflows in `.github/workflows/` hit `/internal/wake` then the periodic endpoints.
- **Memory hygiene** (added 2026-06): per-ticker timeouts and explicit eviction of `engine._history_cache` / `engine._fundamentals_cache` after each ticker; `gc.collect()` every 5 tickers; `--max-requests 80` recycles the worker.

### Key modules

| File | Responsibility |
| --- | --- |
| `main.py` | FastAPI app, engine, market data, all endpoints |
| `ai_feedback.py` | `RecommendationTracker`, `WeeklyEvaluator`, `SchedulerManager`, `SignalLearner` (Phase 2 learning) |
| `render.yaml` | Render web service + (paid-tier) `crons:` definition |
| `.github/workflows/daily-brief.yml` | Free-tier daily brief cron |
| `.github/workflows/hourly-top10.yml` | Free-tier hourly top-10 cron |

### Engine sub-systems (inside `main.py`)

| Area | Function(s) | Notes |
| --- | --- | --- |
| Market data | `_create_yf_session`, `yf_ticker`, `safe_yf_history`, `tradier_option_chain` | Wraps yfinance + Tradier with retries, pacing, rate-limit detection |
| Stock signals | `evaluate_signals`, `analyze_stock` (full pipeline) | Produces `signals[]`, `warnings[]`, `verdict`, `confidence` |
| Trade levels | `trade_levels` | Stops/targets with `MIN_STOP_PCT`, `MIN_STOP_ATR`, `MIN_TGT_PCT`, `MIN_TGT_ATR` guards |
| CSP | `_get_csp_logic` | 30–70 DTE, OTM, IV-rank aware |
| Vertical | `_get_vertical_logic` | Debit verticals on confirmed setups |
| Diagonal | `_get_diagonal_logic` | Sell short-dated against longer-dated long call |
| LEAPS | `_get_long_leaps_logic` | DTE > 180; expirations capped to January or June |
| Scan orchestrator | `_run_scan_job`, `run_scan`, `run_scan_trending` | Per-ticker 70 s hard timeout, cache eviction, gc |
| Sector rotation | `sector_rotation` | Multi-window (1w/2w/4w), money flow, early-rotation signals |
| Trending history | `_snapshot_trending`, `_trending_history_stats`, `_badge_for_ticker` | Daily Firestore snapshots → 🔥 / 📈 badges |
| Daily brief | `/api/v1/daily-brief` | Categorised payload for the morning push |
| Hourly top-10 | `/api/v1/scan/top10-hourly` | Market-hours gate, 24 h dedupe in `notified_options` |
| Wake | `/internal/wake` | No-op endpoint used by cron to warm a sleeping dyno |

## Android client

- **Stack**: Kotlin, Jetpack Compose, Retrofit + Gson, WorkManager, Firebase Auth, Google Sign-in.
- **Key files**:
  - `MainActivity.kt` — Compose host, Retrofit API definitions, data models, WorkManager scheduling.
  - `NewScreens.kt` — Sector rotation screen (period chips 1w/2w/4w + early-rotator badges) and related screens.
  - `DailyRecommendationWorker.kt` — WorkManager job that runs daily at 06:50 local and builds the enriched morning report.
- **Daily worker**: scheduled at `HOUR_OF_DAY=6`, `MINUTE=50`; exponential backoff (`BackoffPolicy.EXPONENTIAL, 15 min`).
- **Notification flow**: worker calls `/api/v1/daily-brief?include_trending=true`, augments with `/scan/trending/enhanced` and `/sector-rotation?period=2w`, then renders the morning push with NEW BUYS, EARNINGS, STOP-LOSS WATCH, ETF status, TRENDING and SECTOR sections.

## Persistence (Firestore collections)

| Collection | Doc shape | Source |
| --- | --- | --- |
| `portfolio_<uid>` | One doc per open trade | Mobile portfolio screen |
| `watchlist_<uid>` | Ticker list per user | Mobile watchlist screen |
| `recommendations` | One doc per captured rec (scan or backtest) | `RecommendationTracker.capture_*` |
| `recommendation_evals` | Weekly check-ins keyed by `rec_id` | `WeeklyEvaluator.run` |
| `signal_stats` | Rolling per-(strategy, signal) win-rate counters | `SignalLearner.update_from_closed_recs` |
| `trending_snapshots` | One doc per day with tickers seen as trending | `_snapshot_trending` |
| `notified_options` | One doc per `(ticker, kind, expiry, strikes)` notification | Hourly top-10 dedupe |
| `meta/eval_status` | Last evaluator run timestamp | `WeeklyEvaluator` |

## Network / proxy notes

Corporate firewall (Intel) blocks direct `git push` / `pip` to public hosts. When operating on the dev workstation:

```powershell
git config --global http.proxy  http://proxy-sc.intel.com:911
git config --global https.proxy http://proxy-sc.intel.com:912

$env:HTTP_PROXY  = "http://proxy-sc.intel.com:911"
$env:HTTPS_PROXY = "http://proxy-sc.intel.com:912"
```

Outside the corporate network, unset:

```powershell
git config --global --unset http.proxy
git config --global --unset https.proxy
```

## Build prerequisites

| Component | Requirement | Notes |
| --- | --- | --- |
| Backend | Python 3.10, `pip install -r requirements.txt` | yfinance, fastapi, gunicorn, firebase-admin, apscheduler, scipy |
| Android JDK | OpenJDK 21 (Android Studio JBR) | The working JBR is at `C:\Program Files\Android\Android Studio1\jbr` (note the `1` suffix). The plain `Android Studio\jbr` install has only `java.dll` and is unusable. |
| Android build | Gradle wrapper, AGP from `gradle/libs.versions.toml` | Build/install via Android Studio when CLI builds time out behind the corporate proxy. |
