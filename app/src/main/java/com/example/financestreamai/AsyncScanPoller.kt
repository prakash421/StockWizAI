package com.example.financestreamai

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Progress callback for [runAsyncWatchlistScan]. Invoked on whatever
 * coroutine context the caller runs the scan on (typically Main from a
 * Composable). `done < 0` signals a transient-error phase where the
 * `phase` string is the user-facing "Reconnecting…" label.
 */
fun interface ScanProgressListener {
    fun onProgress(done: Int, total: Int, phase: String)
}

/**
 * Thrown when the backend accepts the async scan job and then stops
 * advancing the `tickers_scanned` counter for longer than
 * [runAsyncWatchlistScan]'s `stagnationTimeoutMs`.
 *
 * Distinct from a generic transient IOException because the caller
 * (a) already has [ticker] / [total] progress worth reporting, and
 * (b) MUST NOT fall back to a blocking sync `/scan` — the sync call
 * would land on the same jammed backend and burn another 2+ minutes
 * before its own SocketTimeout. Better to surface the stall immediately
 * so the user can retry once the backend's `_engine_scan_lock` frees up.
 */
class ScanStalledException(
    val ticker: Int,
    val total: Int,
    val stalledForSec: Long,
    message: String,
) : IOException(message)

/**
 * Runs a full watchlist scan against `/scan/async` and polls
 * `/scan/status/{jobId}` until results are ready.
 *
 * Tolerates transient poll errors: up to [maxConsecutivePollErrors]
 * back-to-back [IOException]s are absorbed (the counter resets on any
 * successful poll). Overall wall-clock is capped at
 * [overallPollTimeoutMs] so a truly-stuck backend job can't wedge the
 * UI forever. Additionally, if the backend keeps returning 200 OK but
 * `tickers_scanned` stops advancing for [stagnationTimeoutMs], throws
 * a [ScanStalledException] — this is the common "scheduled scan is
 * holding `_engine_scan_lock`" failure mode where the poll succeeds
 * but nothing is actually happening.
 *
 * Extracted from the Scan-tab watchlist button in MainActivity for
 * testability — see AsyncScanPollerTest which uses MockWebServer to
 * simulate mid-scan DNS blips and validates the retry recovery path.
 *
 * @throws ScanStalledException when progress freezes for
 *         [stagnationTimeoutMs].
 * @throws IOException on other unrecoverable poll failures
 *         ([maxConsecutivePollErrors] consecutive network errors OR the
 *         [overallPollTimeoutMs] elapsed).
 */
suspend fun runAsyncWatchlistScan(
    apiService: JPFinanceApi,
    tickers: String,
    strategy: String?,
    scanListType: java.lang.reflect.Type,
    gson: Gson,
    onProgress: ScanProgressListener,
    maxConsecutivePollErrors: Int = 8,
    overallPollTimeoutMs: Long = 10L * 60_000L,
    reconnectBackoffMs: Long = 2_000L,
    stagnationTimeoutMs: Long = 90_000L,
    // 2026-07-04: user-triggered scans (UI buttons) pass "high" so the
    // backend preempts any currently-running scheduled scan. Scheduled
    // WorkManager jobs (DailyRecommendationWorker etc.) pass null so
    // they default to "normal" server-side and don't fight each other.
    priority: String? = null,
    // Injectable wall-clock — tests that virtualize delay() via
    // kotlinx-coroutines-test can pass a controllable counter here so
    // the stagnation detector fires deterministically.
    nowMs: () -> Long = { System.currentTimeMillis() },
): List<ScanResultItem> {
    val startResp = withContext(Dispatchers.IO) {
        apiService.scanAsync(tickers = tickers, strategy = strategy, priority = priority)
    }
    val jobId = startResp.jobId
    val declaredTotal = startResp.totalTickers ?: 0
    val displayTotal = if (declaredTotal > 0) {
        declaredTotal
    } else {
        tickers.split(",").count { it.isNotBlank() }.coerceAtLeast(1)
    }

    var pollCount = 0
    val pollStartMs = nowMs()
    var consecutivePollErrors = 0
    // Progress-stagnation tracker: latch the highest `tickers_scanned`
    // we've seen and remember when it last advanced. If the backend
    // holds the same value for longer than [stagnationTimeoutMs] we
    // conclude the job is jammed (typically because a scheduled scan
    // is holding `_engine_scan_lock`) and give up with a specific
    // exception that instructs the caller NOT to retry via the sync
    // fallback — same backend, same lock, same wait.
    var lastProgressDone = -1
    var lastProgressAdvanceMs = nowMs()

    while (true) {
        if (nowMs() - pollStartMs > overallPollTimeoutMs) {
            throw IOException("Scan didn't finish within ${overallPollTimeoutMs / 60_000}min.")
        }
        val pollDelay = when {
            pollCount < 4 -> 500L
            pollCount < 10 -> 1_200L
            else -> 2_500L
        }
        delay(pollDelay)
        pollCount++

        val body = try {
            withContext(Dispatchers.IO) {
                apiService.getScanStatus(jobId).string()
            }
        } catch (pollErr: IOException) {
            consecutivePollErrors++
            Log.w(
                "SCAN_POLL",
                "Poll #$pollCount transient error " +
                    "(${pollErr.javaClass.simpleName}: ${pollErr.message}); " +
                    "consecutive=$consecutivePollErrors/$maxConsecutivePollErrors"
            )
            if (consecutivePollErrors >= maxConsecutivePollErrors) {
                throw pollErr
            }
            onProgress.onProgress(
                -1,
                displayTotal,
                "Reconnecting ($consecutivePollErrors/$maxConsecutivePollErrors)…"
            )
            delay(reconnectBackoffMs)
            continue
        }
        // Reset the transient-error counter on ANY successful poll so
        // we tolerate intermittent failures over the full scan window
        // rather than only within one short burst.
        consecutivePollErrors = 0

        if (body.trimStart().startsWith("[")) {
            val results: List<ScanResultItem> = gson.fromJson(body, scanListType)
            onProgress.onProgress(displayTotal, displayTotal, "Done")
            return results
        }
        val status = gson.fromJson(body, AsyncScanStatus::class.java)
        val done = (status.tickersScanned ?: 0).coerceAtMost(displayTotal)
        onProgress.onProgress(done, displayTotal, "Scanning")
        if (status.status == "complete" || status.status == "failed") {
            // Terminal without a JSON list means the job returned an
            // error object (e.g. lock timeout). Return empty; the caller
            // reports the empty-results branch.
            return emptyList()
        }
        // Update stagnation tracker. First poll (lastProgressDone=-1)
        // always counts as "advanced" so we start the stagnation clock
        // from the first real status snapshot.
        if (done > lastProgressDone) {
            lastProgressDone = done
            lastProgressAdvanceMs = nowMs()
        } else {
            val stalledMs = nowMs() - lastProgressAdvanceMs
            if (stalledMs > stagnationTimeoutMs) {
                val stalledSec = stalledMs / 1_000L
                throw ScanStalledException(
                    ticker = done,
                    total = displayTotal,
                    stalledForSec = stalledSec,
                    message = "Scan stalled at $done/$displayTotal — no progress for ${stalledSec}s.",
                )
            }
        }
    }
}
