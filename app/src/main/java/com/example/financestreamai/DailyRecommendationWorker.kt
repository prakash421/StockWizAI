package com.example.financestreamai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.Html
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import java.util.Calendar
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

// ==============================
// Top-level alert formatters
// ==============================
// Extracted as top-level `internal` helpers so JVM unit tests can verify the
// exact line shapes (premium amounts, debit amounts, etc.) without needing
// the Android Context / Worker harness. Both the per-strategy detail section
// (buildRecommendationText) and the NEW BUY SIGNALS section
// (buildNewBuysSection) delegate to these so the on-screen format stays
// consistent across the two surfaces.
//
// Convention: every option-strategy line shows the dollar premium / net
// debit the user would pay (or collect, for CSPs). Requested 2026-06-25 to
// remove the implicit "what does this cost me?" lookup from the user's
// flow.
//
// 2026-07-02: CSPs no longer appear in the NEW BUY SIGNALS section — they
// live only under the dedicated "📊 CSPs" section (which already carries
// the monthly ROC). Having them in both places was a duplicate for the
// user. The per-strategy CSP detail line remains the sole surface.

/**
 * Per-strategy CSP detail line ("📊 CSPs:" section).
 *
 * Includes the monthly ROC (rate of return on capital, per month) so the
 * user can compare income yield across tickers without opening the app.
 * Backend already normalises ROC to a monthly figure — see
 * `_get_csp_logic` in the Python service.
 */
internal fun formatCspDetailLine(ticker: String, csp: CspResult): String {
    val exp = if (csp.expiry != null) " ${csp.expiry}" else ""
    val prem = " — prem \$${"%.2f".format(csp.premium)}"
    val roc = csp.roc?.takeIf { it.isNotBlank() } ?: "—"
    return "  $ticker \$${csp.strike}$exp$prem — ROC/mo: $roc, Δ: ${csp.delta}"
}

/** Per-strategy Diagonal detail line ("📐 Diagonals:" section). */
internal fun formatDiagonalDetailLine(ticker: String, diag: DiagonalResult): String {
    val exp = if (diag.expiry != null) " ${diag.expiry}" else ""
    val legs = "${diag.longLeg ?: "?"}/${diag.shortLeg ?: "?"}"
    val debit = " — debit \$${"%.2f".format(diag.netDebt)}"
    return "  $ticker $legs$exp$debit — Yield: ${diag.yieldRatio}"
}

/** Per-strategy Vertical detail line ("📈 Verticals:" section). */
internal fun formatVerticalDetailLine(ticker: String, vert: VerticalResult): String {
    val exp = if (vert.expiry != null) " ${vert.expiry}" else ""
    val strikes = vert.strikes ?: "N/A"
    val debit = " — debit \$${"%.2f".format(vert.netDebit)}"
    return "  $ticker $strikes$exp$debit"
}

/** Per-strategy LEAPS detail line ("🔭 LEAPS:" section). */
internal fun formatLeapsDetailLine(ticker: String, leap: LongLeapsResult): String {
    val prem = " — prem \$${"%.2f".format(leap.premium)}"
    return "  $ticker \$${leap.strike}C ${leap.expiry}$prem — Lev: ${leap.leverage}, Buffer: ${leap.intrinsicBuffer}"
}

/** NEW BUY SIGNALS — Diagonal line. Added 2026-06-25 (previously missing). */
internal fun formatNewBuyDiagonal(ticker: String, diag: DiagonalResult): String {
    val legs = "${diag.longLeg ?: "?"}/${diag.shortLeg ?: "?"}"
    val stop = diag.stopLoss?.let { " stop \$${"%.2f".format(it)}" } ?: ""
    return "📐 Diagonal $ticker $legs (exp ${diag.expiry ?: "—"}, debit \$${"%.2f".format(diag.netDebt)})$stop"
}

/** NEW BUY SIGNALS — Vertical line. Net-debit is the premium paid for the spread. */
internal fun formatNewBuyVertical(ticker: String, vert: VerticalResult): String {
    val strikes = vert.strikes ?: "—"
    val exp = vert.expiry ?: "—"
    return "📐 Vertical $ticker $strikes (exp $exp, debit \$${"%.2f".format(vert.netDebit)})"
}

/** NEW BUY SIGNALS — LEAPS line. Always includes premium, stop, and target. */
internal fun formatNewBuyLeaps(ticker: String, leap: LongLeapsResult): String {
    val stop = leap.stopLoss?.let { " stop \$${"%.2f".format(it)}" } ?: ""
    val target = leap.target?.let { " tgt \$${"%.2f".format(it)}" } ?: ""
    return "🚀 LEAPS $ticker \$${"%.2f".format(leap.strike)} (exp ${leap.expiry}, prem \$${"%.2f".format(leap.premium)})$stop$target"
}

/**
 * Per-strategy PCS (Put Credit Spread) detail line ("💳 PCS:" section).
 *
 * Highlights capital efficiency vs a naked CSP: shows the credit collected,
 * the max loss (== capital at risk per contract), and the monthly ROC on
 * that risk. Backend `_get_put_credit_spread_logic` normalises `roc` to a
 * 30-day ROC on max-loss so it's directly comparable across expiries.
 */
internal fun formatPcsDetailLine(ticker: String, pcs: PutCreditSpreadResult): String {
    val exp = if (pcs.expiry != null) " ${pcs.expiry}" else ""
    val credit = " — credit \$${"%.2f".format(pcs.credit)}"
    val roc = pcs.roc?.takeIf { it.isNotBlank() } ?: "—"
    return "  $ticker \$${pcs.shortStrike}/\$${pcs.longStrike}$exp$credit — max loss \$${"%.2f".format(pcs.maxLoss)} — ROC/mo: $roc"
}

/**
 * NEW BUY SIGNALS — PCS line. Defined-risk bullish spread; belongs alongside
 * Verticals and LEAPS in NEW BUYS (unlike naked CSPs which live only in the
 * CSPs section per the 2026-07-02 de-duplication).
 */
internal fun formatNewBuyPcs(ticker: String, pcs: PutCreditSpreadResult): String {
    val exp = pcs.expiry ?: "—"
    val stop = pcs.stopLoss?.let { " stop \$${"%.2f".format(it)}" } ?: ""
    return "💳 PCS $ticker \$${pcs.shortStrike}/\$${pcs.longStrike} (exp $exp, credit \$${"%.2f".format(pcs.credit)}, max loss \$${"%.2f".format(pcs.maxLoss)})$stop"
}

// ==============================
// Pure, JVM-testable helpers
// ==============================
// The worker's market-day / bull-bear classification logic is decision-grade
// (it gates whether scans run at all) but historically lived as private
// methods on the worker class — meaning a regression could only be caught
// by manually triggering the worker on a holiday morning. Lifting them to
// top-level `internal` functions lets the unit tests pin the contract.
//
// Behaviour MUST stay identical to the previous private methods so that
// reverting either side of the refactor is safe.

/** US market holidays for 2026 (month-day format, "MM-DD"). */
internal val US_MARKET_HOLIDAYS_2026: Set<String> = setOf(
    "01-01", "01-19", "02-16", "04-03", "05-25",
    "07-03", "09-07", "11-26", "12-25"
)

/**
 * Returns true if the given UTC-millis instant falls on a US equities
 * trading day (Mon-Fri, not a US market holiday). The evaluation is done
 * against America/New_York so that devices outside ET still get the
 * correct answer.
 *
 * The [holidays] set defaults to [US_MARKET_HOLIDAYS_2026]; tests can pass
 * a custom set to validate behaviour across years.
 */
internal fun isMarketDayAt(
    timeMillis: Long,
    holidays: Set<String> = US_MARKET_HOLIDAYS_2026
): Boolean {
    val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"))
    cal.timeInMillis = timeMillis
    val dow = cal.get(Calendar.DAY_OF_WEEK)
    if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
    val monthDay = "%02d-%02d".format(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    return monthDay !in holidays
}

/**
 * True when the recommendation string carries a bullish bias.
 * Conservative: "BUY" in any case, but never "DON'T BUY" / "DO NOT BUY".
 */
internal fun isBullishRecommendation(rec: String): Boolean {
    val u = rec.uppercase()
    return u.contains("BUY") && !u.contains("DON'T") && !u.contains("DO NOT")
}

/** True when the recommendation string carries a bearish bias. */
internal fun isBearishRecommendation(rec: String): Boolean {
    val u = rec.uppercase()
    return u.contains("SELL") || u.contains("AVOID") || u.contains("BEARISH")
}

/**
 * True when [prev] and [curr] sit on opposite sides of the bull/bear line.
 * Hold-to-anything or anything-to-hold transitions are NOT counted as
 * flips (matches the worker's notification policy: avoid noisy alerts).
 */
internal fun isBullBearShiftBetween(prev: String, curr: String): Boolean {
    return (isBullishRecommendation(prev) && isBearishRecommendation(curr)) ||
        (isBearishRecommendation(prev) && isBullishRecommendation(curr))
}

/** Parse backtest string like "90.6%" or "100.0%" to a Double. */
internal fun parseBacktestPercent(bt: String?): Double {
    if (bt == null) return 0.0
    return bt.replace("%", "").trim().toDoubleOrNull() ?: 0.0
}

class DailyRecommendationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "DailyRecommendation"
        const val CHANNEL_ID = "daily_recommendations"
        const val CHANNEL_NAME = "Daily Trade Recommendations"
        const val MAX_PER_STRATEGY = 5
        const val MAX_TRENDING_PICKS = 5
        private const val NOTIFICATION_ID = 9001
        private const val FLIP_PREFS = "PortfolioFlipPrefs"
        private const val FLIP_KEY = "last_recommendations"

        // ETF watch list — heavily-held positions we always include in every scan
        // and report on separately. Edit this list to change focused coverage.
        val WATCHED_ETFS = listOf("SOXX", "DRAM", "AIPO")

        // Noon ETF-only scan (runs at 12:00 PM on trading days, focused on WATCHED_ETFS)
        const val TAG_NOON_ETF = "DailyRecommendation_etf_noon"
        const val CHANNEL_ID_NOON = "etf_midday_alerts"
        const val CHANNEL_NAME_NOON = "ETF Mid-Day Alerts"
        private const val NOTIFICATION_ID_NOON = 9002
        private const val ETF_FLIP_KEY = "last_etf_recommendations"

        // Risk/Reward picks shown in the daily report
        private const val RR_TOP_N = 3

        // WorkInfo.progress data keys so the UI (NotificationsScreen) can
        // render "N of M symbols scanned" while the manual scan runs.
        const val PROGRESS_DONE = "progress_done"
        const val PROGRESS_TOTAL = "progress_total"
        const val PROGRESS_PHASE = "progress_phase"

        // Bounded parallelism for batch scans. OkHttp is configured with
        // maxRequestsPerHost=24, so 6 concurrent batches × 3 tickers = 18
        // in-flight requests, well within the dispatcher cap. Empirically
        // cuts a 42-symbol scan from ~2 minutes to ~25 seconds (warm).
        private const val SCAN_PARALLELISM = 6
        private const val RR_FLIP_KEY = "last_rr_recommendations"

        // Stop-loss watch: only alert when price is within this fraction
        // ABOVE the stop (e.g. 0.005 = within 0.5%). Anything at/below stop
        // is always reported as triggered.
        private const val STOP_NEAR_PCT = 0.005

        // Analyst target tracking — caches the previous mean target so we
        // can detect material upgrades/downgrades day-over-day.
        private const val ANALYST_TARGET_KEY = "last_analyst_targets"
        // Minimum % change vs. previous mean to consider material (avoid noise)
        private const val ANALYST_TARGET_MIN_CHG_PCT = 1.0

        // Hard timeout for the ENTIRE doWork() body. Beyond this the worker
        // gives up and returns Result.failure(), so a wedged HTTP call or a
        // runaway loop can never keep the notification progress spinner up
        // "forever". The daily scan legitimately takes 3-7 min on a warm
        // backend and up to ~10 min on Render cold-start (measured 2026-07-04:
        // 33-ticker watchlist scan took 6-7 min via the async endpoint), so
        // 12 min gives a healthy safety margin without letting a genuine
        // hang linger. Was 6 min previously — root cause of "Send Today's
        // Picks Now produced no notification" user report.
        private const val SCAN_HARD_TIMEOUT_MS = 12L * 60L * 1000L

        // US market holidays (month-day). Add/update yearly as needed.
        private val US_MARKET_HOLIDAYS_2026 = setOf(
            "01-01", "01-19", "02-16", "04-03", "05-25",
            "07-03", "09-07", "11-26", "12-25"
        )

        // ------------------------------------------------------------------
        // Foreground-service (progress) notification.
        //
        // Root-cause (2026-07-04 user report): "I clicked Send Today's Picks
        // Now, went to another app, and when I came back there was no trace
        // of the scan." A plain CoroutineWorker is subject to process
        // termination when the app is backgrounded (cached-process kill /
        // low-memory). Promoting the worker to a foreground service via
        // setForeground() makes the OS treat the process as user-visible
        // for the duration of the scan and stops it from being killed
        // silently.
        //
        // The notification uses IMPORTANCE_LOW so it doesn't buzz or
        // interrupt — it just parks a "Scanning… X of Y symbols" chip in
        // the shade for the ~6-7 min the scan runs.
        // ------------------------------------------------------------------
        const val PROGRESS_CHANNEL_ID = "daily_scan_progress"
        const val PROGRESS_CHANNEL_NAME = "Scan in progress"
        const val PROGRESS_NOTIFICATION_ID = 9010

        // Distinct id/channel for "the scan failed / timed out" so failure
        // notifications aren't collapsed into the periodic progress update.
        const val FAILURE_NOTIFICATION_ID = 9011
    }

    /**
     * Build the persistent notification that backs the foreground service
     * for the duration of the scan. WorkManager calls [getForegroundInfo]
     * both to hand a launch-time notification to the OS (via
     * [setForeground]) and, on Android 12+ expedited jobs, to satisfy the
     * expedited-work SLA.
     */
    private fun buildProgressNotification(text: String): android.app.Notification {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                PROGRESS_CHANNEL_ID,
                PROGRESS_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows scan progress while Send Today's Picks Now is running"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "notifications")
        }
        val contentPending = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Stop-scan action so the user can cancel from the notification
        // shade even when the app UI is not on screen.
        val stopPending = androidx.work.WorkManager.getInstance(ctx)
            .createCancelPendingIntent(id)
        return NotificationCompat.Builder(ctx, PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Scanning watchlist")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop scan",
                stopPending,
            )
            .build()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = buildProgressNotification("Starting…")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+ requires the foregroundServiceType be declared so
            // the OS knows what class of work is being done. DATA_SYNC
            // matches our workload (network fetch of market data).
            ForegroundInfo(
                PROGRESS_NOTIFICATION_ID,
                n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, n)
        }
    }

    /** Refresh the persistent progress notification without recreating it. */
    private fun updateProgressNotification(text: String) {
        try {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(PROGRESS_NOTIFICATION_ID, buildProgressNotification(text))
        } catch (e: Exception) {
            // Best-effort — a stale notification is not worth killing a scan for.
            Log.w(TAG, "updateProgressNotification failed: ${e.message}")
        }
    }

    /** Post a one-shot terminal-state notification so the user is never
     *  left wondering "did it just die silently?". Uses the same rich
     *  channel as the success notification. */
    private fun postTerminalStateNotification(title: String, body: String) {
        try {
            sendNotification(
                title = title,
                body = body,
                channelId = CHANNEL_ID,
                channelName = CHANNEL_NAME,
                notificationId = FAILURE_NOTIFICATION_ID,
            )
        } catch (e: Exception) {
            Log.w(TAG, "postTerminalStateNotification failed: ${e.message}")
        }
    }

    /**
     * Publish progress so the in-app NotificationsScreen can render
     * "N of M symbols scanned" via WorkInfo.progress while the scan runs.
     *
     * Serialized via [progressMutex] so the 6 parallel batch coroutines
     * cannot race each other when reporting progress. Without this lock
     * the underlying WorkSpec progress update is still safe, but the
     * emitted Flow values can land out of order (e.g. UI briefly shows
     * 12/42 after 18/42 has already been published), which looks like a
     * regression to the user.
     */
    private val progressMutex = Mutex()
    private suspend fun updateScanProgress(done: Int, total: Int, phase: String) {
        progressMutex.withLock {
            setProgress(
                workDataOf(
                    PROGRESS_DONE to done,
                    PROGRESS_TOTAL to total,
                    PROGRESS_PHASE to phase
                )
            )
            // Mirror the WorkInfo.progress data into the persistent
            // foreground-service notification so the user sees live
            // "X of Y symbols" in the shade even when the app is fully
            // backgrounded. Text kept short (Android truncates to ~one
            // line in the collapsed notification view).
            val text = when {
                total <= 0 -> phase.ifBlank { "Starting…" }
                done in 1 until total -> "$done of $total symbols · $phase"
                done >= total -> "Finalizing…"
                else -> phase.ifBlank { "Scanning $total symbols" }
            }
            updateProgressNotification(text)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Promote to a foreground service IMMEDIATELY so the OS won't kill
        // the worker process the moment the user switches apps. Without
        // this, a plain background CoroutineWorker running for 6-12 minutes
        // is a prime target for cached-process eviction and the "Send
        // Today's Picks Now" scan silently disappears (user report
        // 2026-07-04). Failure here is non-fatal — worst case is the
        // pre-2.9 fallback where the worker still runs, just without the
        // foreground-service protection.
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "setForeground failed (will run as plain background worker): ${e.message}")
        }

        // ANTI-RESTART GUARD (manual runs only).
        //
        // If the phone locks / enters doze while a manual scan is in flight,
        // the OS can kill the worker process. WorkManager then reschedules
        // the OneTimeWorkRequest — which the user perceives as "the scan
        // restarted from scratch" and often loops for hours. Manual runs
        // are explicit user actions: if the first attempt didn't complete,
        // fail hard so the user can decide whether to tap the button again
        // rather than have the app silently retry in the background.
        //
        // Scheduled runs keep the existing behaviour (retry up to twice)
        // because they need to survive transient Render cold-start / network
        // hiccups.
        val isManualEarly = tags.contains("DailyRecommendation_manual")
        if (isManualEarly && runAttemptCount > 0) {
            Log.w(TAG, "Manual scan restart detected (runAttemptCount=$runAttemptCount) — giving up so the user can re-trigger explicitly.")
            postTerminalStateNotification(
                title = "Scan didn't complete",
                body = "The previous manual scan was interrupted and WorkManager tried to restart it. " +
                        "Tap Send Today's Picks Now again when you're ready — we won't auto-restart it in the background.",
            )
            return@withContext Result.failure()
        }

        // Wrap the whole scan body in a HARD TIMEOUT so a wedged HTTP call
        // (or a runaway loop anywhere below) can't keep the progress spinner
        // up indefinitely. `withTimeout` throws TimeoutCancellationException
        // which we translate to Result.failure() below.
        try {
            withTimeout(SCAN_HARD_TIMEOUT_MS) {
                doWorkInner()
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            val mins = SCAN_HARD_TIMEOUT_MS / 60_000L
            Log.w(TAG, "Daily scan exceeded hard timeout of ${SCAN_HARD_TIMEOUT_MS / 1000}s — aborting.")
            postTerminalStateNotification(
                title = "Scan timed out",
                body = "The scan didn't finish within ${mins} minutes. The backend may be slow or unreachable. " +
                        "Tap Send Today's Picks Now to try again.",
            )
            Result.failure()
        } catch (_: kotlinx.coroutines.CancellationException) {
            // User (or WorkManager) cancelled the unique work — either via
            // the Alerts-tab Stop button, the notification-shade Stop
            // action, or via ScanCoordinator.beginManualScan firing while
            // a sibling worker was already running. Do NOT report as a
            // failure/retry — the cancellation was intentional. We DO
            // post a lightweight terminal notification so the user gets
            // explicit confirmation that the scan stopped (better than
            // silently leaving them wondering).
            Log.i(TAG, "Daily scan cancelled (user Stop or coordinator).")
            postTerminalStateNotification(
                title = "Scan stopped",
                body = "You cancelled the running scan. Tap Send Today's Picks Now to start a new one.",
            )
            Result.success()
        }
    }

    private suspend fun doWorkInner(): Result = withContext(Dispatchers.IO) {
        try {
            // Hydrate X-User-Id so calls in BOTH the main scan path and the
            // noon ETF short-circuit attach the user header. Cheap, idempotent.
            UserSession.ensureHydrated(applicationContext)

            val isManual = tags.contains("DailyRecommendation_manual")
            val isNoonEtfMode = tags.contains(TAG_NOON_ETF)
            if (!isManual && !isMarketDay()) {
                Log.d(TAG, "Not a market day — skipping scan.")
                return@withContext Result.success()
            }

            // ETF-only mode: short-circuit into the focused noon path.
            if (isNoonEtfMode) {
                return@withContext runEtfOnlyScan()
            }

            val sharedPrefs = applicationContext.getSharedPreferences("FinanceStreamPrefs", Context.MODE_PRIVATE)
            val watchlist = sharedPrefs.getString("watchlist", null)
                ?.split(",")?.filter { it.isNotBlank() }
                ?: MASTER_WATCHLIST_DEFAULT

            // Hydrate UserSession.userId so the X-User-Id header is attached
            // to every backend call from this worker. When WorkManager spins
            // up a worker after process death the in-memory singleton is
            // empty until MainActivity.onCreate runs again, so without this
            // the daily scan would hit /scan, /scan/trending/enhanced, etc.
            // anonymously and the server would silently fall back to the
            // DEFAULT_WATCHLIST. See UserSession.ensureHydrated.
            UserSession.ensureHydrated(applicationContext)

            // ----------------------------------------------------------
            // Compute the scan universe and publish initial progress
            // BEFORE any HTTP call. The earlier order made the worker do
            // a watchlist-push HTTP call to the Render free tier (30-60s
            // cold start) before reporting totalSymbols, so the in-app
            // button fell back to elapsed-time-only for up to a minute.
            // Cheap local lookups only — no I/O — so this is effectively
            // instant after the worker is dispatched.
            // ----------------------------------------------------------
            val portfolio = PortfolioCache.loadActivePositions(applicationContext)
            val portfolioTickers = portfolio.map { it.ticker }.distinct()
            val scanUniverse = (watchlist + portfolioTickers + WATCHED_ETFS).distinct()
            val totalSymbols = scanUniverse.size

            Log.d(TAG, "Starting daily scan for $totalSymbols symbols (manual=$isManual)...")

            // First progress signal — happens within milliseconds of the
            // worker starting, so the UI immediately switches from the
            // duration-only spinner to "0 of N symbols scanned".
            updateScanProgress(0, totalSymbols, "Syncing watchlist…")

            // Best-effort: push the local watchlist to the backend before
            // any scan that the web app or backend cron might later use.
            // Two cases this covers:
            //   1. User edited the watchlist while offline, app process died
            //      before the inline PUT in MainActivity could replay.
            //   2. User signed in on a new device — local list is authoritative
            //      and the server copy is stale.
            // Failures are logged but never fail the scan; the inline
            // dirty-replay on next app launch is still a fallback.
            if (!UserSession.userId.isNullOrBlank()) {
                try {
                    apiService.setWatchlist(WatchlistSetRequest(watchlist))
                    // Clear the dirty flag so MainActivity's LaunchedEffect
                    // doesn't redundantly PUT again on next foreground.
                    sharedPrefs.edit().putBoolean("watchlist_dirty", false).apply()
                    Log.d(TAG, "Pushed local watchlist (${watchlist.size} symbols) to backend")
                } catch (e: Exception) {
                    Log.w(TAG, "Pre-scan watchlist sync failed (will retry on next run / app launch): ${e.message}")
                }
            } else {
                Log.d(TAG, "No userId — skipping pre-scan watchlist push")
            }

            // Pre-warm the Render free-tier backend so the first batch doesn't
            // time out from a cold start. Best-effort, non-fatal.
            updateScanProgress(0, totalSymbols, "Pre-warming backend…")
            try { apiService.getHealth() } catch (e: Exception) { Log.w(TAG, "Pre-warm failed: ${e.message}") }
            updateScanProgress(0, totalSymbols, "Scanning symbols…")

            // ------------------------------------------------------------
            // Use the SAME async endpoint as the in-app "Scan Watchlist"
            // button (2026-07-04). The previous 6-way parallel batch
            // approach called sync /scan for each 3-ticker batch, but
            // /scan acquires _engine_scan_lock — so all 6 batches
            // serialized on the backend anyway (only one could actually
            // scan at a time). Now we:
            //   * Submit ALL tickers in ONE async job
            //   * Backend does a SINGLE prefetch_market_data across the
            //     whole universe (bulk yf.download instead of 14 tiny
            //     3-ticker downloads)
            //   * Backend's own ThreadPoolExecutor(max_workers=3) inside
            //     _run_scan_job handles per-ticker parallelism
            //   * The client polls /scan/status and receives partial
            //     results as each ticker completes — used here purely
            //     for progress reporting (the notification body still
            //     renders once at the end)
            // Empirically this cuts a 40-ticker daily scan from ~5-8 min
            // (batched) down to ~3-4 min (single-job) on warm-cache runs
            // and is the difference between "notification fires" and
            // "6-min hard timeout kills the worker" reports.
            //
            // Priority is deliberately left null (defaults server-side to
            // "normal") so scheduled runs do NOT preempt each other or
            // preempt a user-triggered Scan Watchlist — only the manual
            // "Send Today's Picks Now" button below overrides this via
            // the priority=high path in MainActivity's scan callers.
            // ------------------------------------------------------------
            val gson = GsonBuilder().create()
            val scanListType = object : TypeToken<List<ScanResultItem>>() {}.type
            val allResults = mutableListOf<ScanResultItem>()
            val droppedTickers = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            try {
                val results = runAsyncWatchlistScan(
                    apiService = apiService,
                    tickers = scanUniverse.joinToString(","),
                    strategy = null,
                    scanListType = scanListType,
                    gson = gson,
                    // Manual runs get high priority so they preempt any
                    // scheduled scan currently holding the engine lock —
                    // otherwise "Send Today's Picks Now" would wait
                    // behind a 6:45 AM daily that's still in flight and
                    // hit the 12-min hard timeout. Scheduled runs stay
                    // at normal priority so they don't fight each other.
                    priority = if (isManual) "high" else null,
                    onProgress = { done, total, phase ->
                        val safeDone = done.coerceAtLeast(0).coerceAtMost(totalSymbols)
                        val label = when {
                            done < 0 -> phase
                            phase == "Done" -> "Fetching trending + analysis…"
                            done == 0 && phase.isNotBlank() && phase != "Scanning" -> phase
                            else -> "Scanning symbols…"
                        }
                        // Best-effort progress publish — Mutex + setProgress
                        // is inherently suspend so we can't call it from a
                        // non-suspend lambda; use runBlocking on the worker
                        // dispatcher (already Dispatchers.IO).
                        kotlinx.coroutines.runBlocking {
                            updateScanProgress(safeDone, totalSymbols, label)
                        }
                    },
                    onPartialResults = { delta ->
                        // Append streamed results as each ticker completes
                        // so if a transient error later cuts the poll
                        // short we still have SOME data to notify on.
                        if (delta.isNotEmpty()) {
                            synchronized(allResults) { allResults.addAll(delta) }
                        }
                    },
                )
                // If the poller returned a fuller list than what streamed
                // (e.g. the terminal /scan/status response contained the
                // full array while partial_results lagged by one poll),
                // union them — dedup by ticker preserving order of first
                // appearance.
                synchronized(allResults) {
                    val seen = allResults.map { it.ticker.uppercase() }.toMutableSet()
                    for (r in results) {
                        val up = r.ticker.uppercase()
                        if (up !in seen) {
                            allResults.add(r)
                            seen.add(up)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Async daily scan failed: ${e.javaClass.simpleName}: ${e.message}", e)
                // Don't wipe streamed results — allResults may already
                // contain a partial set from the successful polls before
                // the failure.
            }
            // Compute dropped tickers from the coverage delta.
            val gotSet = allResults.map { it.ticker.uppercase() }.toSet()
            scanUniverse.filter { it.uppercase() !in gotSet }
                .forEach { droppedTickers.add(it) }

            // Final retry pass for any tickers that were dropped (one-by-one
            // so a single bad symbol doesn't poison its neighbours). Uses
            // the sync /scan endpoint since we only have a handful of
            // tickers to recover and the async submit+poll roundtrip
            // per ticker would be wasteful.
            if (droppedTickers.isNotEmpty()) {
                Log.w(TAG, "Retrying ${droppedTickers.size} dropped ticker(s) individually: $droppedTickers")
                updateScanProgress(totalSymbols, totalSymbols, "Retrying ${droppedTickers.size} symbol(s)…")
                val stillDropped = java.util.Collections.synchronizedSet(mutableSetOf<String>())
                val retryGate = Semaphore(SCAN_PARALLELISM)
                coroutineScope {
                    droppedTickers.toList().map { ticker ->
                        async {
                            retryGate.withPermit {
                                try {
                                    val results = apiService.getScanResults(tickers = ticker)
                                    if (results.isNotEmpty()) {
                                        synchronized(allResults) { allResults.addAll(results) }
                                    } else {
                                        stillDropped.add(ticker)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Retry failed for $ticker: ${e.message}")
                                    stillDropped.add(ticker)
                                }
                            }
                        }
                    }.awaitAll()
                }
                droppedTickers.clear()
                droppedTickers.addAll(stillDropped)
            }

            Log.d(TAG, "Scan coverage: ${allResults.size}/$totalSymbols symbols. Dropped: $droppedTickers")

            updateScanProgress(totalSymbols, totalSymbols, "Fetching trending + analysis…")

            // Trending picks (enhanced endpoint adds Day-N badges from Firestore history)
            val trending: List<ScanResultItem> = try {
                val resp = apiService.scanTrendingEnhanced(limit = 15, strongOnly = true)
                resp.results.orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Trending (enhanced) scan failed: ${e.message}; falling back")
                try {
                    apiService.scanTrending(limit = 15)
                } catch (e2: Exception) {
                    Log.w(TAG, "Trending fallback also failed: ${e2.message}")
                    emptyList()
                }
            }

            // Sector context (best-effort) — short window for early-rotation surface area
            val sectorContext: String? = try {
                val rot = apiService.getSectorRotation(period = "2w")
                val top = rot.topSectors?.take(2)?.joinToString(", ")
                val bot = rot.bottomSectors?.take(2)?.joinToString(", ")
                val early = rot.earlyRotators?.take(2)?.joinToString(", ") {
                    val arrow = if (it.direction == "in") "↑" else "↓"
                    "${it.sector} $arrow"
                }
                buildString {
                    if (!top.isNullOrBlank()) append("Leading: $top")
                    if (!bot.isNullOrBlank()) {
                        if (isNotEmpty()) append(" | ")
                        append("Lagging: $bot")
                    }
                    if (!early.isNullOrBlank()) {
                        if (isNotEmpty()) append(" | ")
                        append("Early: $early")
                    }
                }.ifBlank { null }
            } catch (e: Exception) {
                Log.w(TAG, "Sector rotation fetch failed: ${e.message}")
                null
            }

            if (allResults.isEmpty() && trending.isEmpty()) {
                sendNotification(
                    title = "Daily Scan Complete",
                    body = "Could not retrieve data for your watchlist. Server may be busy — try a manual scan later."
                )
                return@withContext Result.success()
            }

            // Filter and rank recommendations
            val topCsps = filterTopCsps(allResults)
            val topPcs = filterTopPcs(allResults)
            val topDiagonals = filterTopDiagonals(allResults)
            val topVerticals = filterTopVerticals(allResults)
            val topLeaps = filterTopLeaps(allResults)

            // Trending picks with reasoning (top 4-5 by upside potential signal strength)
            val trendingPicksRaw = pickTopTrending(trending)

            // ----------------------------------------------------------
            // Gemini Gate — pre-flight sanity check before user delivery
            // ----------------------------------------------------------
            // The user explicitly asked that every recommendation be passed
            // through Gemini first; if Gemini vetoes a ticker we drop ALL
            // strategies for it from this notification. If no Gemini key is
            // configured (UNAVAILABLE) the gate fails open and the original
            // backend recommendation goes through unchanged.
            val gateInputItems: List<ScanResultItem> = run {
                val gatedTickers = (
                    topCsps.map { it.first } + topPcs.map { it.first } +
                        topDiagonals.map { it.first } +
                        topVerticals.map { it.first } + topLeaps.map { it.first } +
                        trendingPicksRaw.map { it.first.ticker }
                ).map { it.uppercase() }.toSet()
                (allResults + trending).distinctBy { it.ticker.uppercase() }
                    .filter { it.ticker.uppercase() in gatedTickers }
            }
            val gateResults: Map<String, GeminiGate.Result> =
                if (GeminiGate.isEnabled(applicationContext) && gateInputItems.isNotEmpty()) {
                    Log.d(TAG, "Running Gemini gate on ${gateInputItems.size} unique tickers...")
                    GeminiGate.gateAll(applicationContext, gateInputItems)
                } else emptyMap()

            fun keep(ticker: String): Boolean {
                val r = gateResults[ticker.uppercase()] ?: return true
                if (r.vetoed) Log.i(TAG, "Gemini VETO $ticker: ${r.reasoning}")
                return r.approved
            }

            val gatedCsps = topCsps.filter { keep(it.first) }
            val gatedPcs = topPcs.filter { keep(it.first) }
            val gatedDiagonals = topDiagonals.filter { keep(it.first) }
            val gatedVerticals = topVerticals.filter { keep(it.first) }
            val gatedLeaps = topLeaps.filter { keep(it.first) }
            val trendingPicks = trendingPicksRaw.filter { keep(it.first.ticker) }

            val totalPicks = gatedCsps.size + gatedPcs.size + gatedDiagonals.size + gatedVerticals.size + gatedLeaps.size

            val vetoedTickers = gateResults.values.filter { it.vetoed }.map { it.ticker }.distinct()
            val gateAvailable = gateResults.values.any { it.decision != GeminiGate.Decision.UNAVAILABLE }
            if (vetoedTickers.isNotEmpty()) {
                Log.i(TAG, "Gemini gate dropped ${vetoedTickers.size} tickers: $vetoedTickers")
            }

            // Portfolio bull↔bear flips with reasoning
            val portfolioFlips = detectPortfolioFlips(allResults, portfolioTickers)

            // ETF status (always emitted at top of daily report)
            val etfItems = allResults.filter { it.ticker.uppercase() in WATCHED_ETFS.map { e -> e.uppercase() } }
            val etfFlips = detectEtfFlips(etfItems)

            // Risk/Reward extremes (top 3 best + bottom 3 worst by levels.riskReward)
            val rrPicks = pickRiskRewardExtremes(allResults)

            // Stop-loss triggers — only flag stocks in user's watchlist/portfolio
            val watchedSet = (watchlist + portfolioTickers).map { it.uppercase() }.toSet()
            val stopLossHits = detectStopLossHits(allResults, watchedSet)

            // Analyst target changes (day-over-day) for watchlist/portfolio
            val analystChanges = detectAnalystTargetChanges(allResults, watchedSet)

            val baseBody = buildEnrichedReport(
                symbolCount = allResults.size,
                universeSize = scanUniverse.size,
                droppedTickers = droppedTickers.toList(),
                topCsps = gatedCsps,
                topPcs = gatedPcs,
                topDiagonals = gatedDiagonals,
                topVerticals = gatedVerticals,
                topLeaps = gatedLeaps,
                trendingPicks = trendingPicks,
                portfolioFlips = portfolioFlips,
                sectorContext = sectorContext,
                etfItems = etfItems,
                etfFlips = etfFlips,
                rrTop = rrPicks.first,
                rrBottom = rrPicks.second,
                stopLossHits = stopLossHits,
                analystChanges = analystChanges
            )

            // ----------------------------------------------------------
            // Gemini Advisor — proactive watchlist ranker
            // ----------------------------------------------------------
            // Independent of the gate: ask Gemini to nominate its own top
            // picks from the entire scanned universe so we can (a) star the
            // backend recommendations that Gemini also liked (high
            // conviction) and (b) surface promising names the backend
            // missed.
            val advisor = if (GeminiAdvisor.isEnabled(applicationContext) && allResults.isNotEmpty()) {
                Log.d(TAG, "Asking Gemini advisor to rank ${allResults.size} tickers...")
                GeminiAdvisor.rankUniverse(applicationContext, allResults + trending, topN = 5)
            } else GeminiAdvisor.Result(emptyList(), available = false)

            val backendTickers: Set<String> = (
                gatedCsps.map { it.first } + gatedDiagonals.map { it.first } +
                    gatedVerticals.map { it.first } + gatedLeaps.map { it.first } +
                    trendingPicks.map { it.first.ticker }
            ).map { it.uppercase() }.toSet()

            val advisorSection: String = if (advisor.available && advisor.picks.isNotEmpty()) {
                val sb = StringBuilder()
                sb.append("\n\n🤖 Gemini's independent picks (next 4–12 weeks):")
                advisor.picks.forEachIndexed { idx, p ->
                    val overlap = if (p.ticker in backendTickers) " ⭐ also in our picks" else " 🆕 not in backend list"
                    sb.append("\n  ${idx + 1}. ${p.ticker} [${p.conviction}]$overlap")
                    if (p.thesis.isNotBlank()) sb.append("\n     ${p.thesis}")
                }
                val overlapCount = advisor.picks.count { it.ticker in backendTickers }
                sb.append("\n  → ${overlapCount}/${advisor.picks.size} agree with backend (high conviction overlap).")
                sb.toString()
            } else ""

            val body = buildString {
                append(baseBody)
                if (gateAvailable) {
                    append("\n\n🔍 Gemini gate: ")
                    if (vetoedTickers.isEmpty()) append("all picks approved.")
                    else append("vetoed ${vetoedTickers.size} ticker${if (vetoedTickers.size > 1) "s" else ""} — ${vetoedTickers.joinToString(", ")}")
                }
                append(advisorSection)
            }

            val title = when {
                portfolioFlips.isNotEmpty() ->
                    "⚠️ ${portfolioFlips.size} Portfolio Shift" + (if (portfolioFlips.size > 1) "s" else "") + " — $totalPicks Picks"
                totalPicks == 0 && trendingPicks.isEmpty() ->
                    "Daily Scan — No Strong Picks"
                totalPicks == 0 ->
                    "Daily Trends — ${trendingPicks.size} Movers"
                else ->
                    "Daily Picks — $totalPicks Recommendations"
            }
            val knownTickers = (
                scanUniverse +
                trending.map { it.ticker } +
                advisor.picks.map { it.ticker }
            ).map { it.uppercase() }.toSet()
            sendNotification(
                title = title,
                body = body,
                channelId = CHANNEL_ID,
                channelName = CHANNEL_NAME,
                notificationId = NOTIFICATION_ID,
                knownTickers = knownTickers
            )

            Log.d(TAG, "Daily scan complete: ${allResults.size} symbols, $totalPicks picks, ${trendingPicks.size} trending, ${portfolioFlips.size} flips.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Daily scan failed: ${e.message}")
            // Manual scans: never auto-retry. A WorkManager-driven retry from
            // the background looks IDENTICAL to "the scan restarted from
            // scratch and got stuck" to the user. They tapped the button —
            // if it failed, surface that and let them tap again.
            // Scheduled scans (6:50 AM): retry once, then give up so we don't
            // spam notifications on persistent failure.
            val isManual = tags.contains("DailyRecommendation_manual")
            val willRetry = !isManual && runAttemptCount < 2
            // Always notify manual runs and the final failure of scheduled
            // runs — never leave the user in the dark. Skip only intermediate
            // scheduled-retry attempts (a notification there would just be
            // noise since we'll try again automatically).
            if (isManual || !willRetry) {
                val hint = e.message?.take(160) ?: e::class.java.simpleName
                postTerminalStateNotification(
                    title = "Scan failed",
                    body = "The scan couldn't complete. Reason: $hint. " +
                            "Tap Send Today's Picks Now to try again.",
                )
            }
            if (willRetry) Result.retry() else Result.failure()
        }
    }

    // ==============================
    // Quality Filters per Strategy
    // ==============================
    // Calibrated against real API data (Apr 2026). Typical CSP ROC is 2-3.5%,
    // delta -0.20 to -0.26. Thresholds set to keep ~60-80% of results while
    // filtering out genuinely poor trades.
    //
    // Stock-level pre-filters gate on RSI/IV rank/discount to avoid unhealthy
    // stocks, BUT exceptional trade metrics (high backtest %, high ROC/yield)
    // can bypass the stock gate on a case-by-case basis.

    /**
     * Stock-level pre-filter for put-selling strategies (CSPs).
     * Returns true if stock conditions are normal/favorable:
     *   - RSI > 25 (not in freefall)
     *   - Discount from high < 40% (not in severe drawdown)
     *   - IV rank >= 15% (enough premium to collect)
     */
    private fun isStockFavorableForPutSelling(item: ScanResultItem): Boolean {
        val rsi = item.rsi ?: return true
        val ivr = item.ivRank.parseToDouble()
        val discount = item.discountFromHigh.parseToDouble()
        return rsi > 25 && discount < 40 && ivr >= 15.0
    }

    /**
     * Stock-level pre-filter for bullish strategies (LEAPS, Diagonals, Verticals).
     * Returns true if stock conditions are normal/favorable:
     *   - RSI < 75 (not overbought)
     *   - Price above SMA200, or discount >= 15% (value entry)
     */
    private fun isStockFavorableForBullish(item: ScanResultItem): Boolean {
        val rsi = item.rsi ?: return true
        val discount = item.discountFromHigh.parseToDouble()
        val aboveSma = if (item.sma200 != null) item.price >= item.sma200 else true
        return rsi < 75 && (aboveSma || discount >= 15.0)
    }

    /**
     * CSPs: Balanced quality filter with stock-health gate + bypass.
     * Stock must pass put-selling conditions, UNLESS the trade itself is
     * exceptional: backtest >= 90% OR ROC >= 3%.
     *
     * Hard veto added 2026-06-25: a stock with an explicit AVOID/SELL
     * verdict is dropped no matter how attractive the individual CSP is.
     * Previously an exceptional trade metric (backtest >= 90, ROC >= 3)
     * could bypass the heuristic stock-health gate AND silently override
     * an AVOID stance — caused SPCK regression on 2026-06-25.
     */
    private fun filterTopCsps(results: List<ScanResultItem>): List<Pair<String, CspResult>> {
        return results
            .filter { !isStockAvoidOrSell(it.stockRecommendation, it.overall) }
            .flatMap { item ->
                (item.csps ?: emptyList())
                    .filter { csp ->
                        val roc = csp.roc.parseToDouble()
                        val bt = parseBtPercent(csp.bt)
                        val passesStockGate = isStockFavorableForPutSelling(item)
                        val exceptionalTrade = bt >= 90.0 || roc >= 3.0
                        (passesStockGate || exceptionalTrade) &&
                        roc >= 2.0 &&
                        csp.delta in -0.35..-0.15 &&
                        bt >= 80.0
                    }
                    .map { item.ticker to it }
            }
            .sortedByDescending { it.second.roc.parseToDouble() }
            .take(MAX_PER_STRATEGY)
    }

    /**
     * Diagonals: Balanced quality filter with stock-health gate + bypass.
     * Stock must pass bullish conditions, UNLESS the trade itself is
     * exceptional: backtest >= 85% OR yield >= 20%.
     *
     * Hard veto: explicit AVOID/SELL verdicts are dropped regardless of
     * trade metrics (see SPCK regression note on filterTopCsps).
     */
    private fun filterTopDiagonals(results: List<ScanResultItem>): List<Pair<String, DiagonalResult>> {
        return results
            .filter { !isStockAvoidOrSell(it.stockRecommendation, it.overall) }
            .flatMap { item ->
                (item.diagonals ?: emptyList())
                    .filter { diag ->
                        val yld = diag.yieldRatio.parseToDouble()
                        val bt = parseBtPercent(diag.bt)
                        val passesStockGate = isStockFavorableForBullish(item)
                        val exceptionalTrade = bt >= 85.0 || yld >= 20.0
                        (passesStockGate || exceptionalTrade) &&
                        yld >= 5.0 &&
                        diag.netDebt > 0 &&
                        bt >= 70.0
                    }
                    .map { item.ticker to it }
            }
            .sortedByDescending { it.second.yieldRatio.parseToDouble() }
            .take(MAX_PER_STRATEGY)
    }

    /**
     * Verticals: Balanced quality filter with stock-health gate + bypass.
     * Stock must pass bullish conditions, UNLESS the trade itself is
     * exceptional: backtest >= 92%.
     *
     * Calibration note (May 2026): backend stats show vertical strategy
     * historical win-rate is only 41.6% over 262 samples — the worst of
     * any strategy. Raised minimum backtest from 80% to 85%, and exceptional
     * bypass from 90% to 92%, to filter out the long tail of losers.
     */
    private fun filterTopVerticals(results: List<ScanResultItem>): List<Pair<String, VerticalResult>> {
        return results
            .filter { !isStockAvoidOrSell(it.stockRecommendation, it.overall) }
            .flatMap { item ->
                (item.verticals ?: emptyList())
                    .filter { vert ->
                        val bt = parseBtPercent(vert.bt)
                        val passesStockGate = isStockFavorableForBullish(item)
                        val exceptionalTrade = bt >= 92.0
                        (passesStockGate || exceptionalTrade) &&
                        vert.netDebit > 0 &&
                        bt >= 85.0
                    }
                    .map { item.ticker to it }
            }
            .sortedWith(compareByDescending<Pair<String, VerticalResult>> { parseBtPercent(it.second.bt) }
                .thenBy { it.second.netDebit })
            .take(MAX_PER_STRATEGY)
    }

    /**
     * LEAPS: Balanced quality filter with stock-health gate + bypass.
     * Stock must pass bullish conditions, UNLESS the trade itself is
     * exceptional: backtest >= 95% AND buffer >= 50%.
     *
     * Calibration note (May 2026): backend stats show long_leap historical
     * win-rate is 53.5% over 101 samples — mediocre. Raised minimum backtest
     * from 80% to 85% to push toward higher-conviction setups.
     */
    private fun filterTopLeaps(results: List<ScanResultItem>): List<Pair<String, LongLeapsResult>> {
        return results
            .filter { !isStockAvoidOrSell(it.stockRecommendation, it.overall) }
            .flatMap { item ->
                (item.longLeaps ?: emptyList())
                    .filter { leaps ->
                        val bt = parseBtPercent(leaps.bt)
                        val buffer = leaps.intrinsicBuffer.parseToDouble()
                        val passesStockGate = isStockFavorableForBullish(item)
                        val exceptionalTrade = bt >= 95.0 && buffer >= 50.0
                        (passesStockGate || exceptionalTrade) &&
                        leaps.delta >= 0.70 &&
                        leaps.leverage.parseToDouble() >= 1.5 &&
                        buffer >= 10.0 &&
                        bt >= 85.0
                    }
                    .map { item.ticker to it }
            }
            .sortedByDescending { it.second.intrinsicBuffer.parseToDouble() }
            .take(MAX_PER_STRATEGY)
    }

    /**
     * Put Credit Spreads: same bullish stock gate as CSPs (both express a
     * "stock stays above short strike" thesis), plus a capital-efficiency
     * hurdle. Backend already applies its own filters (delta ~0.25 short,
     * bt >= 70/80, ROC on risk >= 6% monthly) so the client-side filter
     * mainly enforces stock-health veto + minimum ROC.
     *
     * ROC field from backend is the 30-day ROC on max-loss so it can be
     * compared directly against the CSP monthly ROC.
     */
    private fun filterTopPcs(results: List<ScanResultItem>): List<Pair<String, PutCreditSpreadResult>> {
        return results
            .filter { !isStockAvoidOrSell(it.stockRecommendation, it.overall) }
            .flatMap { item ->
                (item.putCreditSpreads ?: emptyList())
                    .filter { pcs ->
                        val bt = parseBtPercent(pcs.bt)
                        val roc = pcs.roc.parseToDouble()
                        val passesStockGate = isStockFavorableForPutSelling(item)
                        val exceptionalTrade = bt >= 90.0 || roc >= 15.0
                        (passesStockGate || exceptionalTrade) &&
                        roc >= 6.0 &&
                        pcs.credit > 0 &&
                        pcs.maxLoss > 0 &&
                        bt >= 75.0
                    }
                    .map { item.ticker to it }
            }
            .sortedByDescending { it.second.roc.parseToDouble() }
            .take(MAX_PER_STRATEGY)
    }

    /** Parse backtest string like "90.6%" or "100.0%" to a Double. Returns 0 if null/unparseable. */
    private fun parseBtPercent(bt: String?): Double {
        if (bt == null) return 0.0
        return bt.replace("%", "").trim().toDoubleOrNull() ?: 0.0
    }

    // ==============================
    // Trending picks + reasoning
    // ==============================

    /**
     * Pick the 4-5 strongest trending names with positive 1-2 week upside.
     * Ranked by a composite of (a) momentum quality, (b) bullish-signal count,
     * and (c) analyst upside. We deliberately drop names that look exhausted
     * (RSI > 75 with no pullback signal) or that the API itself flagged as
     * Sell/Avoid.
     */
    private fun pickTopTrending(trending: List<ScanResultItem>): List<Pair<ScanResultItem, String>> {
        if (trending.isEmpty()) return emptyList()
        return trending
            .asSequence()
            .filter { item ->
                // Use the canonical bucket so prose like "AVOID — BUY ZONE"
                // is correctly rejected (substring check on "BUY" would
                // have passed). Mirrors the SPCK fix in pickRiskRewardExtremes.
                if (isStockAvoidOrSell(item.stockRecommendation, item.overall)) return@filter false
                val rsi = item.rsi ?: 50.0
                rsi in 30.0..78.0  // exclude oversold-collapse and blow-off-top
            }
            .sortedByDescending { item -> trendingScore(item) }
            .take(MAX_TRENDING_PICKS)
            .map { it to buildReasoning(it, bullishContext = true) }
            .toList()
    }

    /**
     * Composite score that prefers names with strong technical posture and
     * room to run over the next 1-2 weeks. Higher = more upside potential.
     */
    private fun trendingScore(item: ScanResultItem): Double {
        val rsi = item.rsi ?: 50.0
        val sma50 = item.sma50
        val sma200 = item.sma200
        val price = item.price
        val analystUpside = item.analystTarget?.upsidePct ?: 0.0
        val bullCount = item.bullishSignals?.size ?: 0
        val bearCount = item.bearishSignals?.size ?: 0

        // Sweet-spot RSI band (45-65): strong-but-not-overbought
        val rsiScore = when {
            rsi in 45.0..65.0 -> 20.0
            rsi in 40.0..70.0 -> 10.0
            else -> 0.0
        }
        // Golden cross / above both MAs
        val maScore = when {
            sma50 != null && sma200 != null && price >= sma50 && sma50 >= sma200 -> 25.0
            sma50 != null && price >= sma50 -> 12.0
            else -> 0.0
        }
        // Capped analyst upside contribution (10pts per 5%, max 25)
        val upsideScore = (analystUpside / 5.0 * 10.0).coerceIn(0.0, 25.0)
        // Net signals
        val signalScore = (bullCount - bearCount).toDouble().coerceIn(-10.0, 15.0)

        return rsiScore + maScore + upsideScore + signalScore
    }

    // ==============================
    // Portfolio bull↔bear shift detection
    // ==============================

    /**
     * Compare today's stock recommendation for each portfolio ticker against
     * the last cached recommendation. Returns the list of meaningful shifts
     * (BUY ↔ SELL/AVOID, or HOLD → BUY/SELL) with technical reasoning.
     */
    private fun detectPortfolioFlips(
        results: List<ScanResultItem>,
        portfolioTickers: List<String>
    ): List<Pair<ScanResultItem, String>> {
        if (portfolioTickers.isEmpty()) return emptyList()

        val portfolioSet = portfolioTickers.toSet()
        val portfolioResults = results.filter { it.ticker in portfolioSet }
        if (portfolioResults.isEmpty()) return emptyList()

        val prefs = applicationContext.getSharedPreferences(FLIP_PREFS, Context.MODE_PRIVATE)
        val previousJson = prefs.getString(FLIP_KEY, null)
        val previousMap: Map<String, String> = if (previousJson != null) {
            try {
                gson.fromJson(
                    previousJson,
                    object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                )
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()

        val currentMap = mutableMapOf<String, String>()
        val flips = mutableListOf<Pair<ScanResultItem, String>>()

        for (item in portfolioResults) {
            val current = item.stockRecommendation ?: item.overall ?: continue
            currentMap[item.ticker] = current
            val previous = previousMap[item.ticker] ?: continue
            if (isBullBearShift(previous, current)) {
                val direction = if (isBearish(current)) "🔻 BULL→BEAR" else "🔺 BEAR→BULL"
                val reasoning = buildReasoning(item, bullishContext = !isBearish(current))
                val line = "$direction  ($previous → $current)\n    $reasoning"
                flips.add(item to line)
            }
        }

        // Persist current map for next run.
        prefs.edit().putString(FLIP_KEY, gson.toJson(currentMap)).apply()
        return flips
    }

    private fun isBullish(rec: String): Boolean {
        val u = rec.uppercase()
        return u.contains("BUY") && !u.contains("DON'T") && !u.contains("DO NOT")
    }

    private fun isBearish(rec: String): Boolean {
        val u = rec.uppercase()
        return u.contains("SELL") || u.contains("AVOID") || u.contains("BEARISH")
    }

    private fun isBullBearShift(prev: String, curr: String): Boolean {
        return (isBullish(prev) && isBearish(curr)) || (isBearish(prev) && isBullish(curr))
    }

    /**
     * Build a 1-line technical-reasoning string explaining why the stock
     * is moving. Pulls from RSI bands, SMA50/200 posture (golden/death cross,
     * pullback to MA), Bollinger-implied stretch (distance to swing/52w),
     * IV rank (vol expansion = options-friendly), volume hints from the
     * backend signals, sector context, and analyst upside.
     */
    private fun buildReasoning(item: ScanResultItem, bullishContext: Boolean): String {
        val parts = mutableListOf<String>()
        val rsi = item.rsi
        val sma50 = item.sma50
        val sma200 = item.sma200
        val price = item.price

        // RSI band
        if (rsi != null) {
            val r = "%.0f".format(rsi)
            parts += when {
                rsi >= 70 -> "RSI $r (overbought)"
                rsi >= 60 -> "RSI $r (strong momentum)"
                rsi in 45.0..60.0 -> "RSI $r (healthy uptrend)"
                rsi in 35.0..45.0 -> "RSI $r (cooling, potential dip-buy)"
                rsi < 30 -> "RSI $r (oversold)"
                else -> "RSI $r"
            }
        }

        // SMA posture
        if (sma50 != null && sma200 != null) {
            val above50 = price >= sma50
            val above200 = price >= sma200
            val golden = sma50 >= sma200
            parts += when {
                golden && above50 && above200 -> "price > SMA50 > SMA200 (golden-cross trend intact)"
                !golden && !above50 && !above200 -> "price < SMA50 < SMA200 (death-cross, downtrend)"
                golden && !above50 -> "pullback to SMA50 in uptrend"
                !golden && above50 -> "reclaim of SMA50 in downtrend (early reversal)"
                else -> "mixed MA posture"
            }
        } else if (sma50 != null) {
            parts += if (price >= sma50) "above SMA50" else "below SMA50"
        }

        // Bollinger / range stretch via 52w & swing levels
        item.levels?.let { lv ->
            val high52 = lv.high52w
            val swingLow = lv.swingLow60d
            if (high52 != null && high52 > 0) {
                val distPct = (high52 - price) / high52 * 100.0
                when {
                    distPct < 3 -> parts += "within 3% of 52w high (BB upper stretch)"
                    distPct in 3.0..10.0 -> parts += "%.0f%% off 52w high (room to run)".format(distPct)
                    distPct > 30 -> parts += "%.0f%% off 52w high (deep value or weakness)".format(distPct)
                    else -> {}
                }
            }
            if (swingLow != null && swingLow > 0 && abs(price - swingLow) / swingLow < 0.03) {
                parts += "holding 60-day swing-low support"
            }
        }

        // IV rank — options-relevant
        val ivr = item.ivRank.parseToDouble()
        if (ivr >= 60) parts += "IV rank ${ivr.toInt()}% (premium-rich, vol expansion)"
        else if (ivr in 25.0..40.0) parts += "IV rank ${ivr.toInt()}% (moderate)"

        // Volume / signal hints surfaced by backend
        val signalsSrc = if (bullishContext) item.bullishSignals else item.bearishSignals
        signalsSrc?.firstOrNull { it.contains("volume", true) || it.contains("vol", true) }
            ?.let { parts += it }

        // Sector
        item.sector?.takeIf { it.isNotBlank() }?.let { parts += "sector: $it" }

        // Analyst upside
        val up = item.analystTarget?.upsidePct
        if (up != null && up > 0) parts += "analyst upside %.0f%%".format(up)

        return parts.take(5).joinToString(" • ")
    }

    // ==============================
    // Notification Builder
    // ==============================

    private fun buildEnrichedReport(
        symbolCount: Int,
        universeSize: Int,
        droppedTickers: List<String>,
        topCsps: List<Pair<String, CspResult>>,
        topPcs: List<Pair<String, PutCreditSpreadResult>>,
        topDiagonals: List<Pair<String, DiagonalResult>>,
        topVerticals: List<Pair<String, VerticalResult>>,
        topLeaps: List<Pair<String, LongLeapsResult>>,
        trendingPicks: List<Pair<ScanResultItem, String>>,
        portfolioFlips: List<Pair<ScanResultItem, String>>,
        sectorContext: String?,
        etfItems: List<ScanResultItem> = emptyList(),
        etfFlips: Map<String, Pair<String, String>> = emptyMap(),
        rrTop: List<Pair<ScanResultItem, Boolean>> = emptyList(),
        rrBottom: List<Pair<ScanResultItem, Boolean>> = emptyList(),
        stopLossHits: List<ScanResultItem> = emptyList(),
        analystChanges: List<AnalystTargetChange> = emptyList()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Coverage: $symbolCount / $universeSize symbols.")
        if (droppedTickers.isNotEmpty()) {
            val list = droppedTickers.take(10).joinToString(", ")
            val more = if (droppedTickers.size > 10) " (+${droppedTickers.size - 10} more)" else ""
            sb.appendLine("⚠️ Skipped: $list$more")
        }
        if (sectorContext != null) sb.appendLine("🔄 Sectors — $sectorContext")
        sb.appendLine()

        // ETF Section — heavily-held positions reported first so user can
        // adjust portfolio quickly on any material change.
        if (etfItems.isNotEmpty()) {
            sb.appendLine("🛡️ ETF Watch (${etfItems.size}):")
            etfItems.forEach { etf ->
                val flip = etfFlips[etf.ticker.uppercase()]
                sb.appendLine(buildEtfDetailLine(etf, flip))
            }
            sb.appendLine()
        }

        // Risk/Reward extremes — best 3 setups + worst 3 to avoid
        if (rrTop.isNotEmpty() || rrBottom.isNotEmpty()) {
            sb.appendLine("⚖️ Reward:Risk Leaders (R:R weighted by trend + momentum + 52w-high context):")
            if (rrTop.isNotEmpty()) {
                sb.appendLine("  ✅ Best to BUY (high R:R with healthy trend & momentum):")
                rrTop.forEach { (item, flipped) ->
                    sb.appendLine("    " + formatRrLine(item, flipped))
                }
            }
            if (rrBottom.isNotEmpty()) {
                sb.appendLine("  ❌ Worst to AVOID/SELL (low R:R AND weak holistic picture):")
                rrBottom.forEach { (item, flipped) ->
                    sb.appendLine("    " + formatRrLine(item, flipped))
                }
            }
            sb.appendLine()
        }

        // Stop-loss alert — any watched stock at/near its stop trigger
        if (stopLossHits.isNotEmpty()) {
            sb.appendLine("🛑 Stop-Loss Alert (${stopLossHits.size}):")
            stopLossHits.forEach { item ->
                val price = item.price
                val stop = item.levels?.stopLoss ?: 0.0
                val triggered = price <= stop
                val diffPct = if (stop > 0) ((price - stop) / stop) * 100.0 else 0.0
                val tag = if (triggered) "🔻 TRIGGERED" else "⚠️ NEAR"
                val deltaStr = if (triggered) {
                    "${"%.2f".format(diffPct)}% below stop"
                } else {
                    "${"%.2f".format(diffPct)}% above stop"
                }
                sb.appendLine(
                    "  $tag ${item.ticker} @ $${"%.2f".format(price)} (stop $${"%.2f".format(stop)}, $deltaStr)"
                )
            }
            sb.appendLine()
        }

        // Analyst target changes — green ▲ for upgrades, red ▼ for downgrades
        if (analystChanges.isNotEmpty()) {
            sb.appendLine("🎯 Analyst Target Changes (${analystChanges.size}):")
            analystChanges.forEach { ch ->
                val arrow = if (ch.changePct >= 0) "🟢 ▲" else "🔴 ▼"
                val sign = if (ch.changePct >= 0) "+" else ""
                sb.appendLine(
                    "  $arrow ${ch.ticker}: $${"%.2f".format(ch.prev)} → $${"%.2f".format(ch.curr)} " +
                            "($sign${"%.1f".format(ch.changePct)}%)"
                )
            }
            sb.appendLine()
        }

        if (portfolioFlips.isNotEmpty()) {
            sb.appendLine("📢 Portfolio Shifts (${portfolioFlips.size}):")
            portfolioFlips.forEach { (item, line) ->
                sb.appendLine("  ${item.ticker} — $line")
            }
            sb.appendLine()
        }

        // 🔺 NEW BUYS — explicitly call out fresh STRONG BUY recommendations
        // so the user has a single "what's NEW today" view at the top.
        // CSPs are excluded here — they live under the "📊 CSPs" section
        // only, to avoid duplicating each pick in two places. PCS spreads
        // ARE included (defined-risk, capital-efficient, and distinct from
        // both the CSPs section and the naked-put case).
        val newBuys = buildNewBuysSection(topLeaps, topVerticals, topDiagonals, topPcs)
        if (newBuys.isNotEmpty()) {
            sb.appendLine("🔺 NEW BUY SIGNALS (${newBuys.size}):")
            newBuys.forEach { sb.appendLine("  $it") }
            sb.appendLine()
        }

        // 📅 EARNINGS THIS WEEK — from the scan universe
        val earningsLines = buildEarningsThisWeek(
            (topCsps.map { it.first } + topPcs.map { it.first } + topLeaps.map { it.first } + topVerticals.map { it.first } +
                trendingPicks.map { it.first.ticker } + etfItems.map { it.ticker })
                .toSet(),
            rrTop = rrTop, rrBottom = rrBottom, etfItems = etfItems
        )
        if (earningsLines.isNotEmpty()) {
            sb.appendLine("📅 EARNINGS THIS WEEK (${earningsLines.size}):")
            earningsLines.forEach { sb.appendLine("  $it") }
            sb.appendLine()
        }

        if (trendingPicks.isNotEmpty()) {
            sb.appendLine("🚀 Top Trending (next 1-2 weeks upside):")
            trendingPicks.forEach { (item, reasoning) ->
                val change = item.changePercent?.let { " %+.1f%%".format(it) } ?: ""
                val badge = item.trendingBadge?.let { " $it" } ?: ""
                val streak = item.trendingHistory?.consecutiveDays?.takeIf { it >= 2 }?.let { " (Day $it)" } ?: ""
                sb.appendLine("  ${item.ticker}$badge \$${"%.2f".format(item.price)}$change$streak")
                sb.appendLine("    $reasoning")
            }
            sb.appendLine()
        }

        sb.append(buildRecommendationText(symbolCount, topCsps, topPcs, topDiagonals, topVerticals, topLeaps, headerOnly = true))
        return sb.toString().trim()
    }

    /**
     * Build a compact multi-line block for a watched ETF describing:
     *   • Current price + day %, recommendation (with flip arrow if changed)
     *   • RSI, SMA posture (technical analysis snapshot)
     *   • Stop-loss trigger + risk/reward
     *   • One-line fundamental / signal note
     */
    private fun buildEtfDetailLine(etf: ScanResultItem, flip: Pair<String, String>?): String {
        val sb = StringBuilder()
        val change = etf.changePercent?.let { " %+.2f%%".format(it) } ?: ""
        // Company name removed 2026-06-25 per user request — the line
        // got too crowded on small screens and the ticker is already the
        // primary identifier (and is rendered bold by toRichHtml).
        val rec = etf.stockRecommendation ?: etf.overall ?: "—"
        val recDisplay = if (flip != null) "🔄 ${flip.first} → ${flip.second}" else rec
        sb.append("  ${etf.ticker} \$${"%.2f".format(etf.price)}$change  [$recDisplay]")
        // Technical line
        val tech = mutableListOf<String>()
        etf.rsi?.let { tech += "RSI ${"%.0f".format(it)}" }
        if (etf.sma50 != null && etf.sma200 != null) {
            val above50 = etf.price >= etf.sma50
            val above200 = etf.price >= etf.sma200
            val golden = etf.sma50 >= etf.sma200
            tech += when {
                golden && above50 && above200 -> "above SMA50 & SMA200 (uptrend)"
                !golden && !above50 && !above200 -> "below SMA50 & SMA200 (downtrend)"
                golden && !above50 -> "pullback to SMA50"
                !golden && above50 -> "reclaim of SMA50"
                else -> "mixed MA"
            }
        }
        etf.ivRank?.takeIf { it.isNotBlank() && it != "N/A" }?.let { tech += "IV $it" }
        if (tech.isNotEmpty()) sb.append("\n      📈 ${tech.joinToString(" • ")}")
        // Stop-loss + R:R
        val lvl = etf.levels
        val stopRrBits = mutableListOf<String>()
        if (lvl?.stopLoss != null) stopRrBits += "Stop \$${"%.2f".format(lvl.stopLoss)}"
        if (lvl?.target != null) stopRrBits += "Target \$${"%.2f".format(lvl.target)}"
        if (lvl?.riskReward != null) stopRrBits += "Reward:Risk ${"%.1f".format(lvl.riskReward)}:1"
        // Stop trigger warning when price is within 3% of stop
        if (lvl?.stopLoss != null && lvl.stopLoss > 0) {
            val distPct = (etf.price - lvl.stopLoss) / lvl.stopLoss * 100.0
            if (distPct in 0.0..3.0) stopRrBits += "⚠ near stop"
            else if (distPct < 0) stopRrBits += "🚨 STOP TRIGGERED"
        }
        if (stopRrBits.isNotEmpty()) sb.append("\n      🛑 ${stopRrBits.joinToString(" • ")}")
        // Fundamental / first significant signal
        val fundSignal = (etf.bullishSignals.orEmpty() + etf.bearishSignals.orEmpty())
            .firstOrNull { s ->
                val l = s.lowercase()
                l.contains("earnings") || l.contains("revenue") || l.contains("margin") ||
                    l.contains("analyst") || l.contains("upgrade") || l.contains("downgrade") ||
                    l.contains("guidance")
            }
        if (fundSignal != null) sb.append("\n      🏛️ $fundSignal")
        etf.nextEarningsDate?.takeIf { it.isNotBlank() }?.let { sb.append("\n      📅 Next earnings: $it") }
        return sb.toString()
    }

    private fun formatRrLine(item: ScanResultItem, flipped: Boolean): String {
        val rr = item.levels?.riskReward
        val rrStr = rr?.let { "%.1f".format(it) + ":1" } ?: "n/a"
        val rec = item.stockRecommendation ?: item.overall ?: "—"
        val change = item.changePercent?.let { " %+.1f%%".format(it) } ?: ""
        val flip = if (flipped) " 🔄" else ""
        val context = holisticContext(item)
        val ctxSuffix = if (context.isNotBlank()) "  ⓘ $context" else ""
        return "${item.ticker} \$${"%.2f".format(item.price)}$change  Reward:Risk $rrStr  [$rec]$flip$ctxSuffix"
    }

    /**
     * Parse a percent string like "3.2%" / "-12%" / "5" into a Double.
     * Returns null when unparseable. `discountFromHigh` is reported by the
     * backend as a non-negative percent below the 52-week high, e.g. "2.5%"
     * means price is 2.5% under the 52w high (i.e. very close to it).
     */
    private fun parsePctString(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val cleaned = s.trim().trimEnd('%').replace(",", "")
        return cleaned.toDoubleOrNull()
    }

    /**
     * Holistic "health" score combining trend, momentum, signal density,
     * and 52-week-high proximity. Higher = more bullish picture. Used to
     * override raw R:R sorting so that, e.g., a semiconductor name at
     * 52-week highs with strong momentum doesn't get tagged "AVOID/SELL"
     * just because reward-to-risk is mechanically low (target close to price).
     *
     * Range roughly -8..+10. Positive ≥ 3 = bullishly positioned.
     */
    private fun holisticScore(item: ScanResultItem): Int {
        var score = 0
        val price = item.price
        item.sma50?.let { if (it > 0.0 && price >= it) score += 2 else if (it > 0.0) score -= 1 }
        item.sma200?.let { if (it > 0.0 && price >= it) score += 1 else if (it > 0.0) score -= 1 }
        item.rsi?.let { rsi ->
            when {
                rsi >= 80.0 -> score -= 2  // extremely overbought — exhaustion risk
                rsi in 55.0..70.0 -> score += 2  // healthy uptrend zone
                rsi in 45.0..55.0 -> score += 1
                rsi < 30.0 -> score -= 1
                else -> {}
            }
        }
        val bull = (item.bullishSignals?.size ?: 0).coerceAtMost(4)
        val bear = (item.bearishSignals?.size ?: 0).coerceAtMost(4)
        score += bull
        score -= bear
        item.changePercent?.let { if (it >= 0.0) score += 1 else if (it <= -2.0) score -= 1 }
        val discount = parsePctString(item.discountFromHigh)
        if (discount != null) {
            when {
                discount <= 1.5 -> score += 3  // at/breaking 52w high — strong momentum tell
                discount <= 5.0 -> score += 2
                discount <= 12.0 -> score += 1
                discount >= 30.0 -> score -= 1
                else -> {}
            }
        }
        return score
    }

    /**
     * Returns true when a name is sitting at or near its 52-week high
     * (within 3%). These names often look bad on raw reward:risk (price
     * already near analyst target) but can keep extending — esp. in
     * leading themes like AI/semis. We use this as a guardrail to avoid
     * tagging them "AVOID/SELL" purely on R:R.
     */
    private fun isNearFiftyTwoWeekHigh(item: ScanResultItem): Boolean {
        val d = parsePctString(item.discountFromHigh) ?: return false
        return d in 0.0..3.0
    }

    /**
     * Short human-readable context tag attached to each R:R line so the
     * user can see WHY a name is or isn't in the list beyond raw R:R.
     * Empty string when nothing notable.
     */
    private fun holisticContext(item: ScanResultItem): String {
        val parts = mutableListOf<String>()
        val discount = parsePctString(item.discountFromHigh)
        if (discount != null && discount <= 3.0) parts += "near 52w high"
        item.rsi?.let { rsi ->
            when {
                rsi >= 80.0 -> parts += "RSI overbought (${rsi.toInt()})"
                rsi <= 30.0 -> parts += "RSI oversold (${rsi.toInt()})"
                else -> {}
            }
        }
        val bull = item.bullishSignals?.size ?: 0
        val bear = item.bearishSignals?.size ?: 0
        if (bull >= 3 && bull > bear) parts += "momentum strong"
        else if (bear >= 3 && bear > bull) parts += "momentum weak"
        val price = item.price
        item.sma50?.let { if (it > 0.0 && price < it * 0.97) parts += "below SMA50" }
        // Limit to 2 most informative tags so the line stays readable.
        return parts.take(2).joinToString(", ")
    }

    /**
     * Pick top-N and bottom-N tickers by `levels.riskReward`. Returns
     * Pair<topList, bottomList> where each item is paired with a flag
     * indicating whether its Buy/Sell stance flipped vs. the last run.
     *
     * Important: a stock with R:R = 0 but STRONG BUY verdict would otherwise
     * land in the "Worst to AVOID/SELL" bucket — which is contradictory.
     * We split the universe by recommendation stance FIRST so the "best"
     * list only contains BUY/STRONG BUY names and the "worst" list only
     * contains HOLD/AVOID/SELL names. Items with R:R <= 0 (zero reward
     * potential — e.g. price already above target) are excluded entirely
     * because they carry no actionable reward-risk signal.
     */
    private fun pickRiskRewardExtremes(
        results: List<ScanResultItem>
    ): Pair<List<Pair<ScanResultItem, Boolean>>, List<Pair<ScanResultItem, Boolean>>> {
        val withRr = results.mapNotNull { item ->
            val rr = item.levels?.riskReward ?: return@mapNotNull null
            if (rr <= 0.0) return@mapNotNull null  // skip degenerate values
            // Use the canonical recommendationBucket() so prose verdicts like
            // "AVOID — BUY ZONE BELOW $X" or "HOLD; BUY DIPS" classify as
            // AVOID/HOLD, NOT as BUY. Raw substring matching on "BUY"
            // produced the SPCK regression on 2026-06-25 where an AVOID-rated
            // ticker was promoted into "Best to BUY".
            val bucket = recommendationBucket(item.stockRecommendation, item.overall)
            Triple(item, rr, bucket)
        }
        if (withRr.isEmpty()) return emptyList<Pair<ScanResultItem, Boolean>>() to emptyList()

        // ---- BUY bucket ----
        // ONLY explicit STRONG BUY / BUY verdicts qualify. Plus a secondary
        // bearish-signal veto so a stale or learner-upgraded verdict can't
        // push a name whose live technicals are breaking down.
        val buys = withRr
            .filter { (item, _, bucket) ->
                (bucket == "STRONG BUY" || bucket == "BUY") &&
                    !hasBearishVeto(
                        item.bullishSignals?.size ?: 0,
                        item.bearishSignals?.size ?: 0
                    )
            }
            .map { (item, rr, _) -> Triple(item, rr, holisticScore(item)) }
            .filter { it.third >= 0 }
            .sortedByDescending { (_, rr, h) -> rr * (1.0 + h * 0.08) }

        // ---- AVOID/SELL bucket ----
        // Candidates: HOLD / AVOID / SELL stances with low R:R AND a weak
        // holistic picture. Crucially, names sitting at 52-week highs with
        // strong momentum (e.g. semis riding the AI build-out) are NOT
        // dumped here just because reward-to-risk is mechanically low —
        // they often extend further before resistance gives way. Same for
        // STRONG-momentum names regardless of stance.
        val avoids = withRr
            .filter { (_, _, bucket) -> bucket == "AVOID" || bucket == "SELL" || bucket == "HOLD" }
            .map { (item, rr, bucket) -> Quad(item, rr, bucket, holisticScore(item)) }
            .filter { (item, _, bucket, h) ->
                val verdictBearish = bucket == "AVOID" || bucket == "SELL"
                // Veto 1: name at 52w high with non-bearish recommendation
                // gets a momentum free pass — these are often the leaders
                // that look bad on R:R but keep extending.
                val nearHigh = isNearFiftyTwoWeekHigh(item)
                if (nearHigh && !verdictBearish) return@filter false
                // Veto 2: still-bullish holistic picture (score >= 3) and
                // a non-bearish stance — momentum trumps mechanical R:R.
                if (h >= 3 && !verdictBearish) return@filter false
                true
            }
            // Sort by composite weakness: lower R:R + lower health = worst first.
            .sortedWith(compareBy({ it.second + it.fourth * 0.15 }, { it.fourth }))

        val top = buys.take(RR_TOP_N).map { it.first }
        val bottom = avoids.take(RR_TOP_N).map { it.first }

        // Flip detection against the prior run's recommendation cache.
        val prefs = applicationContext.getSharedPreferences(FLIP_PREFS, Context.MODE_PRIVATE)
        val prevJson = prefs.getString(RR_FLIP_KEY, null)
        val prevMap: Map<String, String> = if (prevJson != null) {
            try {
                gson.fromJson(
                    prevJson,
                    object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                )
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()

        val current = mutableMapOf<String, String>()
        fun annotate(list: List<ScanResultItem>): List<Pair<ScanResultItem, Boolean>> = list.map { it ->
            val curr = it.stockRecommendation ?: it.overall ?: ""
            current[it.ticker] = curr
            val prev = prevMap[it.ticker]
            val flipped = prev != null && isBullBearShift(prev, curr)
            it to flipped
        }
        val topAnnotated = annotate(top)
        val bottomAnnotated = annotate(bottom)
        // Persist updated state for next run.
        prefs.edit().putString(RR_FLIP_KEY, gson.toJson(current)).apply()
        return topAnnotated to bottomAnnotated
    }

    /** Local 4-tuple helper (Kotlin stdlib stops at Triple). */
    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    /**
     * Day-over-day analyst target change for a watched ticker. Positive
     * [changePct] = consensus target raised (bullish), negative = lowered.
     */
    data class AnalystTargetChange(
        val ticker: String,
        val prev: Double,
        val curr: Double,
        val changePct: Double
    )

    /**
     * Find watched (watchlist + portfolio) tickers whose price is at or
     * very near their computed stop-loss level. Always reports a hit
     * when price <= stopLoss; also warns when within [STOP_NEAR_PCT]
     * above the stop. Returns most-urgent first (triggered before near,
     * then by how far below/close to stop).
     */
    private fun detectStopLossHits(
        results: List<ScanResultItem>,
        watchedSet: Set<String>
    ): List<ScanResultItem> {
        if (watchedSet.isEmpty()) return emptyList()
        return results.asSequence()
            .filter { it.ticker.uppercase() in watchedSet }
            .filter { (it.levels?.stopLoss ?: 0.0) > 0.0 }
            .filter { item ->
                val stop = item.levels!!.stopLoss!!
                val price = item.price
                price <= stop * (1.0 + STOP_NEAR_PCT)
            }
            .sortedBy { item ->
                val stop = item.levels!!.stopLoss!!
                (item.price - stop) / stop // most negative (deepest below stop) first
            }
            .toList()
    }

    /**
     * Detect material changes in the average analyst target price
     * (item.analystTarget.mean) for watchlist/portfolio tickers since the
     * last run. Caches the previous mean under [ANALYST_TARGET_KEY] and
     * filters out moves below [ANALYST_TARGET_MIN_CHG_PCT] to avoid noise.
     * Returns up to ~15 entries sorted by absolute % change (largest first).
     */
    private fun detectAnalystTargetChanges(
        results: List<ScanResultItem>,
        watchedSet: Set<String>
    ): List<AnalystTargetChange> {
        val prefs = applicationContext.getSharedPreferences(FLIP_PREFS, Context.MODE_PRIVATE)
        val prevJson = prefs.getString(ANALYST_TARGET_KEY, null)
        val prevMap: Map<String, Double> = if (prevJson != null) {
            try {
                gson.fromJson(
                    prevJson,
                    object : com.google.gson.reflect.TypeToken<Map<String, Double>>() {}.type
                )
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()

        val current = mutableMapOf<String, Double>()
        val changes = mutableListOf<AnalystTargetChange>()
        results.forEach { item ->
            val mean = item.analystTarget?.mean ?: return@forEach
            if (mean <= 0.0) return@forEach
            val key = item.ticker.uppercase()
            current[key] = mean
            if (key !in watchedSet) return@forEach
            val prev = prevMap[key] ?: return@forEach
            if (prev <= 0.0) return@forEach
            val pct = ((mean - prev) / prev) * 100.0
            if (kotlin.math.abs(pct) < ANALYST_TARGET_MIN_CHG_PCT) return@forEach
            changes += AnalystTargetChange(item.ticker, prev, mean, pct)
        }

        // Persist *all* observed means (not just watched) so a ticker added
        // to the watchlist tomorrow still has a baseline.
        prefs.edit().putString(ANALYST_TARGET_KEY, gson.toJson(current)).apply()

        return changes.sortedByDescending { kotlin.math.abs(it.changePct) }.take(15)
    }

    /**
     * Detect Buy↔Sell flips for the watched ETF set. Returns a map of
     * uppercase ticker → (previous rec, current rec) for tickers that
     * flipped since the last run. Uses a dedicated cache key so it doesn't
     * collide with the portfolio flip state.
     */
    private fun detectEtfFlips(etfItems: List<ScanResultItem>): Map<String, Pair<String, String>> {
        if (etfItems.isEmpty()) return emptyMap()
        val prefs = applicationContext.getSharedPreferences(FLIP_PREFS, Context.MODE_PRIVATE)
        val prevJson = prefs.getString(ETF_FLIP_KEY, null)
        val prevMap: Map<String, String> = if (prevJson != null) {
            try {
                gson.fromJson(
                    prevJson,
                    object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                )
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()

        val current = mutableMapOf<String, String>()
        val flips = mutableMapOf<String, Pair<String, String>>()
        for (etf in etfItems) {
            val curr = etf.stockRecommendation ?: etf.overall ?: continue
            val key = etf.ticker.uppercase()
            current[key] = curr
            val prev = prevMap[key]
            if (prev != null && isBullBearShift(prev, curr)) {
                flips[key] = prev to curr
            }
        }
        prefs.edit().putString(ETF_FLIP_KEY, gson.toJson(current)).apply()
        return flips
    }

    /**
     * Focused ETF-only scan invoked at 12:00 PM on trading days. Scans only
     * the WATCHED_ETFS set, emits a compact mid-day notification on the
     * dedicated ETF channel, and reuses the same ETF detail formatter.
     */
    private suspend fun runEtfOnlyScan(): Result {
        Log.d(TAG, "Starting noon ETF-only scan for: $WATCHED_ETFS")
        try { apiService.getHealth() } catch (_: Exception) { /* best-effort warm-up */ }

        val results = mutableListOf<ScanResultItem>()
        for (ticker in WATCHED_ETFS) {
            for (attempt in 1..2) {
                try {
                    val r = apiService.getScanResults(tickers = ticker)
                    if (r.isNotEmpty()) { results.addAll(r); break }
                } catch (e: Exception) {
                    Log.w(TAG, "Noon ETF $ticker attempt $attempt failed: ${e.message}")
                    if (attempt < 2) delay(2_000L)
                }
            }
        }
        if (results.isEmpty()) {
            sendNotification(
                title = "ETF Mid-Day Check",
                body = "Could not retrieve ETF data right now. Will retry on the next scheduled run.",
                channelId = CHANNEL_ID_NOON,
                channelName = CHANNEL_NAME_NOON,
                notificationId = NOTIFICATION_ID_NOON
            )
            return Result.success()
        }

        val flips = detectEtfFlips(results)

        val sb = StringBuilder()
        sb.appendLine("Mid-day update on ${WATCHED_ETFS.joinToString(", ")}")
        sb.appendLine()
        results.forEach { etf ->
            val flip = flips[etf.ticker.uppercase()]
            sb.appendLine(buildEtfDetailLine(etf, flip))
            sb.appendLine()
        }
        // Mid-day specific extras: highlight intraday move strength + sector context if any
        val biggestMover = results.maxByOrNull { kotlin.math.abs(it.changePercent ?: 0.0) }
        if (biggestMover != null && (biggestMover.changePercent ?: 0.0).let { kotlin.math.abs(it) } >= 1.0) {
            val dir = if ((biggestMover.changePercent ?: 0.0) >= 0) "up" else "down"
            sb.appendLine("📊 Biggest intraday mover: ${biggestMover.ticker} $dir ${"%+.2f%%".format(biggestMover.changePercent ?: 0.0)} — review stop & sizing if held.")
        }

        val flipsExist = flips.isNotEmpty()
        val title = when {
            flipsExist -> "⚠️ ETF Mid-Day — ${flips.size} Flip${if (flips.size > 1) "s" else ""}"
            else -> "ETF Mid-Day Update"
        }
        sendNotification(
            title = title,
            body = sb.toString().trim(),
            channelId = CHANNEL_ID_NOON,
            channelName = CHANNEL_NAME_NOON,
            notificationId = NOTIFICATION_ID_NOON,
            knownTickers = WATCHED_ETFS.map { it.uppercase() }.toSet()
        )
        return Result.success()
    }

    private fun buildRecommendationText(
        symbolCount: Int,
        csps: List<Pair<String, CspResult>>,
        pcs: List<Pair<String, PutCreditSpreadResult>>,
        diagonals: List<Pair<String, DiagonalResult>>,
        verticals: List<Pair<String, VerticalResult>>,
        leaps: List<Pair<String, LongLeapsResult>>,
        headerOnly: Boolean = false
    ): String {
        val sb = StringBuilder()
        if (!headerOnly) sb.appendLine("Scanned $symbolCount symbols.\n")

        if (csps.isNotEmpty()) {
            sb.appendLine("📊 CSPs (${csps.size}):")
            csps.forEach { (ticker, csp) ->
                sb.appendLine(formatCspDetailLine(ticker, csp))
            }
            sb.appendLine()
        }

        if (pcs.isNotEmpty()) {
            sb.appendLine("💳 PCS (${pcs.size}):")
            pcs.forEach { (ticker, spread) ->
                sb.appendLine(formatPcsDetailLine(ticker, spread))
            }
            sb.appendLine()
        }

        if (diagonals.isNotEmpty()) {
            sb.appendLine("📐 Diagonals (${diagonals.size}):")
            diagonals.forEach { (ticker, diag) ->
                sb.appendLine(formatDiagonalDetailLine(ticker, diag))
            }
            sb.appendLine()
        }

        if (verticals.isNotEmpty()) {
            sb.appendLine("📈 Verticals (${verticals.size}):")
            verticals.forEach { (ticker, vert) ->
                sb.appendLine(formatVerticalDetailLine(ticker, vert))
            }
            sb.appendLine()
        }

        if (leaps.isNotEmpty()) {
            sb.appendLine("🔭 LEAPS (${leaps.size}):")
            leaps.forEach { (ticker, l) ->
                sb.appendLine(formatLeapsDetailLine(ticker, l))
            }
        }

        return sb.toString().trim()
    }

    private fun sendNotification(title: String, body: String) {
        sendNotification(title, body, CHANNEL_ID, CHANNEL_NAME, NOTIFICATION_ID)
    }

    /**
     * Convert a plain-text report body into lightly-styled HTML for the
     * notification (bold ticker tokens + bold section headers). The same
     * string is cached so the in-app NotificationCard can render it with
     * AnnotatedString.fromHtml — old plain-text entries still render fine
     * because fromHtml is forgiving of input without tags.
     */
    private fun toRichHtml(body: String, knownTickers: Set<String> = emptySet()): String {
        // 1) HTML-escape first so any literal <, >, & in the source body are safe.
        var s = body
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // 2) Bold the entire line for section headers — lines that start with
        // a recognized emoji/icon and end in a colon. Capture the trailing
        // counter "(N):" so the bold wraps it too.
        //
        // Matches things like:  "🛡️ ETF Watch (3):",  "⚖️ Reward:Risk Leaders ...:",
        // "🛑 Stop-Loss Alert (2):",  "  ✅ Best to BUY ...:",  "📊 CSPs (5):"
        val sectionRegex = Regex(
            """^(\s*)((?:🛡️|⚖️|🛑|🎯|📢|🚀|📊|📐|📈|🔭|✅|❌|⚠️|🔍|🤖|📅|🟢|🔴|🟡|🔻|🚨|💡)\s[^\r\n]*?:)""",
            RegexOption.MULTILINE
        )
        s = sectionRegex.replace(s) { m ->
            m.groupValues[1] + "<b>" + m.groupValues[2] + "</b>"
        }

        // 3) Bold known tickers (whole-word). Sort by length descending so a
        // ticker like "META" isn't shadowed by a partial match. Tickers come
        // from the scan universe so we never accidentally bold words like
        // "RSI" / "BUY" / "SELL".
        if (knownTickers.isNotEmpty()) {
            val sorted = knownTickers
                .filter { it.isNotBlank() }
                .map { it.uppercase() }
                .distinct()
                .sortedByDescending { it.length }
            if (sorted.isNotEmpty()) {
                val pattern = sorted.joinToString("|") { Regex.escape(it) }
                val tickerRegex = Regex("""\b($pattern)\b""")
                s = tickerRegex.replace(s) { m -> "<b>${m.value}</b>" }
            }
        }

        // 4) Newlines → <br/> so the notification's BigTextStyle preserves layout.
        s = s.replace("\n", "<br/>")
        return s
    }

    private fun sendNotification(
        title: String,
        body: String,
        channelId: String,
        channelName: String,
        notificationId: Int,
        knownTickers: Set<String> = emptySet()
    ) {
        // Build the rich HTML version once and use it for BOTH the system
        // notification (parsed via Html.fromHtml) AND the in-app history
        // (NotificationCard renders via AnnotatedString.fromHtml).
        val htmlBody = toRichHtml(body, knownTickers)
        NotificationCache.save(applicationContext, title, htmlBody)

        // Permission / channel check (Android 13+ requires runtime POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted — system will drop notify(). Saved to in-app history only.")
                return
            }
        }
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled at app level. Saved to in-app history only.")
            return
        }

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Daily high-confidence trade recommendations"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Tap notification to open the app on the Alerts tab
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "notifications")
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val styledBody = Html.fromHtml(htmlBody, Html.FROM_HTML_MODE_LEGACY)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(styledBody.toString().lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(styledBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    // ==============================
    // Market Day Check
    // ==============================

    private fun isMarketDay(): Boolean {
        // Evaluate against US/Eastern, not device-local — a device on the
        // far-east side of the dateline could otherwise read e.g. Saturday
        // here while ET is still Friday (or vice versa) and skip a valid
        // trading day.
        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"))
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

        // Weekend check
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            return false
        }

        // Holiday check (simple month-day format)
        val monthDay = "%02d-%02d".format(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        if (US_MARKET_HOLIDAYS_2026.contains(monthDay)) {
            return false
        }

        return true
    }

    // ==============================
    // Feature 4: NEW BUYS + EARNINGS THIS WEEK helpers
    // ==============================

    /**
     * Build a compact list of fresh STRONG BUY-grade signals that the user
     * should evaluate today: one-shot view across Diagonals / Verticals /
     * LEAPS. Every line includes the dollar premium / net debit so the
     * user can size the trade without re-opening the app.
     *
     * CSPs are intentionally excluded here (2026-07-02) — they were
     * duplicating the dedicated "📊 CSPs" section below. All CSP picks
     * (with monthly ROC) live in that section only.
     *
     * Diagonals added 2026-06-25 (previously omitted, causing an
     * inconsistency between this section and the per-strategy detail
     * section).
     */
    private fun buildNewBuysSection(
        topLeaps: List<Pair<String, LongLeapsResult>>,
        topVerticals: List<Pair<String, VerticalResult>>,
        topDiagonals: List<Pair<String, DiagonalResult>>,
        topPcs: List<Pair<String, PutCreditSpreadResult>> = emptyList()
    ): List<String> {
        val lines = mutableListOf<String>()
        topLeaps.take(5).forEach { (tk, leap) ->
            lines += formatNewBuyLeaps(tk, leap)
        }
        topPcs.take(3).forEach { (tk, spread) ->
            lines += formatNewBuyPcs(tk, spread)
        }
        topVerticals.take(3).forEach { (tk, vert) ->
            lines += formatNewBuyVertical(tk, vert)
        }
        topDiagonals.take(3).forEach { (tk, diag) ->
            lines += formatNewBuyDiagonal(tk, diag)
        }
        return lines
    }

    /**
     * Surface earnings reports within the next 7 days for any ticker in the
     * scan universe. We pull dates from the API response (next_earnings_date)
     * and only include items dated in (today, today+7].
     */
    private fun buildEarningsThisWeek(
        tickers: Set<String>,
        rrTop: List<Pair<ScanResultItem, Boolean>>,
        rrBottom: List<Pair<ScanResultItem, Boolean>>,
        etfItems: List<ScanResultItem>
    ): List<String> {
        if (tickers.isEmpty()) return emptyList()
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val weekLater = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 7) }

        // Source items: anything we already pulled from the scan response
        val pool: List<ScanResultItem> = (
            rrTop.map { it.first } + rrBottom.map { it.first } + etfItems
        ).distinctBy { it.ticker.uppercase() }

        val out = mutableListOf<String>()
        for (item in pool) {
            if (item.ticker.uppercase() !in tickers.map { it.uppercase() }) continue
            val raw = item.nextEarningsDate?.takeIf { it.isNotBlank() } ?: continue
            val parsed = parseEarningsDate(raw) ?: continue
            if (parsed.timeInMillis !in today.timeInMillis..weekLater.timeInMillis) continue
            val daysOut = ((parsed.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
            val whenStr = when (daysOut) {
                0 -> "TODAY"
                1 -> "TOMORROW"
                else -> "in $daysOut days ($raw)"
            }
            out += "📅 ${item.ticker} earnings $whenStr"
        }
        return out.take(8)
    }

    private fun parseEarningsDate(raw: String): Calendar? {
        // Accept "YYYY-MM-DD" or "YYYY-MM-DDTHH:MM:SS..."
        val datePart = raw.substring(0, minOf(10, raw.length))
        val parts = datePart.split("-")
        if (parts.size < 3) return null
        return try {
            val y = parts[0].toInt()
            val m = parts[1].toInt() - 1
            val d = parts[2].toInt()
            Calendar.getInstance().apply {
                set(y, m, d, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
        } catch (_: Exception) { null }
    }
}
