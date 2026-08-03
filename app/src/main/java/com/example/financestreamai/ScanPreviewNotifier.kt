package com.example.financestreamai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * ScanPreviewNotifier — interim "results-so-far" notification during a
 * long-running scan (Daily / Send-Today's-Picks-Now).
 *
 * Prior UX: the scan runs for 3-8 minutes (see notes in
 * [DailyRecommendationWorker]) and the user only sees a single
 * notification at the very end. During that window the progress
 * notification just says "Scanning X of Y symbols…" with no hint of
 * WHAT is being found. Users reported this as unacceptable latency.
 *
 * New UX (2026-08-02): as partial results stream back from the
 * backend's /scan/status?partial_results endpoint (already exposed by
 * [AsyncScanPoller.runAsyncWatchlistScan] via its `onPartialResults`
 * callback), we post a lightweight PREVIEW notification showing the
 * most notable movers from the scanned-so-far set. The preview is
 * posted to a distinct channel + notification-id so it does NOT
 * replace or interfere with the final full report. Once the final
 * report is posted the preview is dismissed.
 *
 * Design decisions:
 *   * Distinct low-importance channel — no buzz, just a quiet chip in
 *     the shade.
 *   * Idempotent: [maybePost] can be called on every streaming callback;
 *     it internally rate-limits to a minimum interval + minimum delta
 *     of new tickers so we don't spam the shade.
 *   * Purely additive — no existing DailyRecommendationWorker code
 *     path is modified beyond a small onPartialResults hook.
 *   * Failure-safe — every operation is wrapped in try/catch; the
 *     preview must never abort the scan.
 */
object ScanPreviewNotifier {
    private const val TAG = "ScanPreviewNotifier"
    const val CHANNEL_ID = "daily_scan_preview"
    const val CHANNEL_NAME = "Scan preview (early results)"
    const val NOTIFICATION_ID = 9020

    // Rate-limit: don't repost the preview more than once per this
    // interval. Streaming can fire many callbacks per second on a fast
    // backend and we don't want the shade animation flickering.
    private const val MIN_POST_INTERVAL_MS = 15_000L

    // Only post once we have at least this many tickers so the preview
    // isn't just 1 stock out of 40 (which reads as useless noise).
    private const val MIN_TICKERS_FOR_FIRST_POST = 4

    // After the first post, require at least this many NEW tickers
    // before reposting (so we only refresh when meaningful progress
    // happened between callbacks).
    private const val MIN_TICKERS_DELTA_FOR_REPOST = 5

    // Cap the number of preview lines to keep the notification tidy.
    private const val MAX_PREVIEW_LINES = 6

    // Thread-safe state: last time we posted and how many tickers were
    // included then. Reset in [dismiss].
    @Volatile private var lastPostAtMs: Long = 0L
    @Volatile private var lastPostedCount: Int = 0

    /**
     * Post an interim preview if enough new data has arrived since the
     * last post. Silently no-ops if rate-limits are not met or if
     * required permissions are missing.
     */
    fun maybePost(
        context: Context,
        allResults: List<ScanResultItem>,
        totalSymbols: Int,
        forcePost: Boolean = false,
    ) {
        try {
            if (allResults.isEmpty()) return
            val nowMs = System.currentTimeMillis()
            val distinctCount = allResults.distinctBy { it.ticker.uppercase() }.size
            if (!forcePost) {
                if (lastPostedCount == 0 && distinctCount < MIN_TICKERS_FOR_FIRST_POST) return
                if (lastPostedCount > 0 && distinctCount - lastPostedCount < MIN_TICKERS_DELTA_FOR_REPOST) return
                if (nowMs - lastPostAtMs < MIN_POST_INTERVAL_MS) return
            }

            val body = buildPreviewBody(allResults, totalSymbols)
            val title = "Scan preview — $distinctCount of $totalSymbols scanned"
            postSystemNotification(context, title, body)

            lastPostAtMs = nowMs
            lastPostedCount = distinctCount
        } catch (t: Throwable) {
            // Never let a preview error abort the scan.
            Log.w(TAG, "maybePost failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Dismiss the preview (called once the final full-fidelity report
     * has been posted to the primary notification channel). Also resets
     * internal rate-limit state so the next scan starts clean.
     */
    fun dismiss(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "dismiss failed: ${t.message}")
        } finally {
            lastPostAtMs = 0L
            lastPostedCount = 0
        }
    }

    // ----------------------------------------------------------------
    // Rendering
    // ----------------------------------------------------------------
    private fun buildPreviewBody(
        allResults: List<ScanResultItem>,
        totalSymbols: Int,
    ): String {
        // Rank picks by absolute price-change magnitude so the user sees
        // the most-notable movers first. We deliberately avoid the
        // heavy filterTopCsps / filterTopPcs / … pipeline here because
        // (a) it costs time we're trying to save the user, and (b) the
        // preview is meant to answer "what's happening right now?"
        // rather than "what should I trade?".
        val ranked = allResults
            .distinctBy { it.ticker.uppercase() }
            .sortedByDescending { kotlin.math.abs(it.changePercent ?: 0.0) }
            .take(MAX_PREVIEW_LINES)

        val sb = StringBuilder()
        sb.append("📊 First ${ranked.size} notable movers (of ${allResults.size} scanned, $totalSymbols total):\n")
        for (r in ranked) {
            val pctStr = r.changePercent?.let { "%+.2f%%".format(it) } ?: "—"
            val priceStr = "$%.2f".format(r.price)
            val rec = (r.stockRecommendation ?: r.overall)?.take(60) ?: ""
            sb.append("• ${r.ticker}  $priceStr  ($pctStr)")
            if (rec.isNotBlank()) sb.append(" — $rec")
            sb.append('\n')
        }
        sb.append("\nFull report (with strategy picks + AI ranking) will follow.")
        return sb.toString().trimEnd()
    }

    // ----------------------------------------------------------------
    // Notification plumbing (self-contained — does not depend on
    // DailyRecommendationWorker's helpers so it can be invoked from any
    // future scan surface without a refactor).
    // ----------------------------------------------------------------
    private fun postSystemNotification(context: Context, title: String, body: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Interim scan results shown while the full daily scan is still running."
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Runtime permission check (Android 13+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.d(TAG, "POST_NOTIFICATIONS not granted — preview suppressed")
                return
            }
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.d(TAG, "Notifications disabled at app level — preview suppressed")
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "notifications")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, n)
    }
}
