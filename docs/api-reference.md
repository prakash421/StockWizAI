# API Reference

All endpoints are served by the FastAPI app in `main.py`. Base URL is the Render service (`https://…onrender.com`) in production.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` | Health string |
| GET | `/api/v1/debug` | Diagnostic: yfinance + Tradier connectivity |
| GET | `/api/v1/health` | Lightweight health (per-user) |
| GET | `/api/v1/scan` | Synchronous scan of `tickers=` or full-portfolio mode |
| GET | `/api/v1/scan/async` | Async scan; returns `job_id` |
| GET | `/api/v1/scan/trending` | Sync scan of top-10 most-active + top-10 trending Yahoo tickers |
| GET | `/api/v1/scan/trending/async` | Async version of the trending scan |
| GET | `/api/v1/scan/trending/enhanced` | Trending scan enriched with history badges (`🔥` / `📈`) and streaks |
| GET | `/api/v1/scan/status/{job_id}` | Poll an async scan job |
| GET | `/api/v1/scan/top10-hourly` | Hourly top-10 options scan with market-hours gate and 24 h dedupe |
| POST | `/api/v1/backtest` | Run a backtest for a given strategy / ticker |
| POST | `/api/v1/portfolio/add` | Add an open position |
| DELETE | `/api/v1/portfolio/remove/{pos_id}` | Remove an open position |
| GET | `/api/v1/watchlist` | Get the user’s watchlist |
| PUT | `/api/v1/watchlist` | Replace the user’s watchlist |
| POST | `/api/v1/watchlist/add` | Append a ticker to the watchlist |
| DELETE | `/api/v1/watchlist/remove` | Remove a ticker from the watchlist |
| GET | `/api/v1/recommendations/history` | Past recommendations (Firestore) |
| GET | `/api/v1/recommendations/stats` | Aggregated win/loss rates per strategy + verdict |
| GET | `/api/v1/recommendations/{rec_id}` | A single recommendation doc |
| POST | `/api/v1/recommendations/evaluate` | Force-run the weekly evaluator |
| GET | `/api/v1/recommendations/learnings` | What `SignalLearner` has inferred |
| POST | `/api/v1/recommendations/learnings/refresh` | Force-recompute signal/verdict stats |
| GET | `/api/v1/recommendations/status` | Last eval timestamp |
| GET | `/api/v1/sector-rotation` | Multi-window sector rotation (1w / 2w / 4w) + early rotators |
| GET | `/api/v1/daily-brief` | Categorised morning brief |
| GET | `/internal/wake` | No-op endpoint used by cron to warm a sleeping dyno |

## Selected payload shapes

### `GET /api/v1/scan/trending/enhanced`
Query: `limit` (default 15), `strong_only` (default true).

Response (truncated):
```json
{
  "results": [
    {
      "ticker": "NVDA",
      "verdict": "STRONG BUY",
      "confidence": "High",
      "trendingBadge": "🔥 Day 9",
      "trendingHistory": { "appearances": 9, "consecutiveDays": 5 },
      "signals": ["…"],
      "warnings": []
    }
  ]
}
```

### `GET /api/v1/sector-rotation`
Query: `period` ∈ `1w | 2w | 4w` (legacy `1mo` / `3mo` / `6mo` aliased).

Response (truncated):
```json
{
  "period": "2w",
  "sectors": [
    {
      "sector": "Technology",
      "etf": "XLK",
      "return_recent": 0.041,
      "rank": 1,
      "early_signal": "early_in",
      "multi_window": { "r1w": 0.018, "r2w": 0.041, "r4w": 0.012, "accel_1v4": 0.015, "accel_2v4": 0.0175 }
    }
  ],
  "rotation_signals": ["Money rotating INTO: Technology, …"],
  "early_rotators": [ { "sector": "Technology", "direction": "early_in", "r1w": 0.018, "r4w": 0.012 } ],
  "top_sectors": ["Technology", "Healthcare", "Industrials"],
  "bottom_sectors": ["Utilities", "Real Estate", "Energy"]
}
```

### `GET /api/v1/daily-brief`
Query: `user_id`, `include_trending` (default false).

Response (truncated):
```json
{
  "summary": { "new_buy_signals": 3, "stop_loss_watch": 1, "earnings_this_week": 4 },
  "new_buy_signals": [ { "ticker": "AAPL", "verdict": "STRONG BUY", "summary": "…" } ],
  "stop_loss_watch": [ { "ticker": "TSLA", "price": 250.0, "stop": 255.5, "distance_pct": -0.022 } ],
  "earnings_this_week": [ { "ticker": "NVDA", "earnings_date": "2026-06-25" } ],
  "etf_status": [],
  "sector_rotation": { "...": "see /api/v1/sector-rotation" },
  "trending_today": [ { "ticker": "AMD", "badge": "📈 Day 4" } ]
}
```

### `GET /api/v1/scan/top10-hourly`
Query: `user_id`, `force` (default false), `dedupe` (default true), `perf_window_days` (default 21).

Response (truncated):
```json
{
  "skipped": false,
  "candidates_evaluated": 18,
  "top10": [ { "ticker": "AAPL", "kind": "csps", "expiry": "2026-08-15", "strikes": "190", "score": 0.82 } ],
  "new_options": [ { "ticker": "AAPL", "kind": "csps", "expiry": "2026-08-15", "strikes": "190" } ],
  "generated_at": "2026-06-21T14:30:00Z"
}
```

When the market is closed and `force` is omitted: `{ "skipped": true }`.

## Cron / scheduling

| Job | Trigger | Notes |
| --- | --- | --- |
| Weekly evaluator | APScheduler — Mon 14:00 UTC | In-process |
| Daily brief warmup | GitHub Actions — `47 10 * * 1-5` UTC | `GET /internal/wake` |
| Daily brief | GitHub Actions — `50 10 * * 1-5` UTC | `GET /api/v1/daily-brief?include_trending=true` |
| Hourly top-10 | GitHub Actions — `30 14-21 * * 1-5` UTC | `GET /api/v1/scan/top10-hourly` |
