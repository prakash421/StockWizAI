package com.example.financestreamai

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
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
 * Thrown when `/scan/status/{jobId}` returns 404 — meaning the backend
 * lost track of the job. In practice this happens when the Render free
 * tier restarts the Python process (a redeploy or an idle-timeout scale-
 * down), which drops the in-memory `scan_jobs` dict. The client's `jobId`
 * is then unknown to the fresh process → 404.
 *
 * [runAsyncWatchlistScan] catches this internally and auto-restarts the
 * scan ONCE with the same ticker list. If the second attempt ALSO 404s
 * during polling (very rare — implies the server bounced twice within the
 * same scan window) the exception surfaces to the caller so the UI can
 * show a specific "backend restarted mid-scan — please tap Scan again"
 * message instead of the generic "Server returned error 404".
 */
class ScanJobLostException(
    val jobId: String,
    val restartsAttempted: Int,
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
    // Extra grace window applied while the backend is in the "queued" or
    // "prefetching" phase (see AsyncScanStatus.phase). During these
    // phases `tickers_scanned` is legitimately stuck at 0 — the backend
    // is either waiting on _engine_scan_lock behind another scan or
    // batch-downloading Yahoo history for all tickers. Applying the
    // normal 90s stagnation timeout here produces false-positive stalls
    // on a cold Render worker (user report 2026-07-04: "The scan stalled
    // at 0/33 symbols (no progress of 92s)"). 4 minutes covers a cold-
    // worker prefetch (~60s) plus one contended-lock wait behind another
    // user's scan without incorrectly failing.
    initialProgressGraceMs: Long = 4L * 60_000L,
    // 2026-07-04: user-triggered scans (UI buttons) pass "high" so the
    // backend preempts any currently-running scheduled scan. Scheduled
    // WorkManager jobs (DailyRecommendationWorker etc.) pass null so
    // they default to "normal" server-side and don't fight each other.
    priority: String? = null,
    // Optional STREAMING callback: fired every time the /scan/status
    // response contains MORE completed tickers than we saw on the
    // previous poll. Delivers ONLY the new results (delta) so the UI
    // can append rows to a growing list instead of re-diffing the whole
    // partial-results snapshot each time. When null (default), streaming
    // is disabled and the caller receives all results only at scan
    // completion (backward compatible with older call sites).
    onPartialResults: ((List<ScanResultItem>) -> Unit)? = null,
    // Injectable wall-clock — tests that virtualize delay() via
    // kotlinx-coroutines-test can pass a controllable counter here so
    // the stagnation detector fires deterministically.
    nowMs: () -> Long = { System.currentTimeMillis() },
): List<ScanResultItem> {
    // Bounded outer loop that lets us restart the whole (submit + poll)
    // sequence when the backend loses the job (404 during poll — see
    // ScanJobLostException). Render free-tier redeploys or idle-timeout
    // restarts drop the in-memory scan_jobs dict, so any in-flight jobId
    // becomes unknown to the fresh process. Restarting the scan is the
    // only clean recovery path (the ticker list is safe to re-submit;
    // the old job's partial results are unreachable anyway).
    val maxRestarts = 1
    var restartsUsed = 0
    var currentJobId = ""
    try {
      while (true) {
        val startResp = withContext(Dispatchers.IO) {
            apiService.scanAsync(tickers = tickers, strategy = strategy, priority = priority)
        }
        val jobId = startResp.jobId
        currentJobId = jobId
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
        // Streaming: track how many partial results we've already delivered
        // to onPartialResults so we only emit the delta each poll (avoid
        // re-emitting the same tickers over and over — the UI would
        // otherwise flicker or double-render).
        var streamedSoFar = 0

        try {
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
                } catch (httpErr: HttpException) {
                    // 404 on status poll = backend restarted (Render free-tier
                    // scale-down or a redeploy) and forgot our job. Escalate
                    // to the outer restart handler; other HTTP errors bubble
                    // up unchanged.
                    if (httpErr.code() == 404) {
                        Log.w(
                            "SCAN_POLL",
                            "Job $jobId returned 404 on poll #$pollCount — " +
                                "backend likely restarted; will attempt scan restart",
                        )
                        throw ScanJobLostException(
                            jobId = jobId,
                            restartsAttempted = restartsUsed,
                            message = "Backend lost job $jobId (HTTP 404 on status poll).",
                        )
                    }
                    throw httpErr
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
                // STREAMING: forward newly-completed tickers to the UI as
                // they arrive. Delta = partial_results[streamedSoFar..] so
                // we never re-emit the same ticker twice. Guard with the
                // callback being non-null so older call sites (that
                // rely on the batch-at-end behavior) are unaffected.
                if (onPartialResults != null) {
                    val partial = status.partialResults
                    if (partial != null && partial.size > streamedSoFar) {
                        val delta = partial.subList(streamedSoFar, partial.size).toList()
                        streamedSoFar = partial.size
                        try {
                            onPartialResults(delta)
                        } catch (cbErr: Throwable) {
                            // Callback exceptions must NOT abort the scan
                            // — the poller is authoritative for the final
                            // result list; the UI can recover on the next
                            // callback or via the returned list.
                            Log.w("SCAN_POLL", "onPartialResults threw ${cbErr.javaClass.simpleName}: ${cbErr.message}")
                        }
                    }
                }
                // Map backend sub-phase to a user-facing label. We pack it
                // into the `phase` arg of the progress callback (older
                // callers that just check `phase == "Done"` are unaffected).
                val phaseLabel = when (status.phase) {
                    "queued" -> "Waiting for backend (another scan in progress)…"
                    "prefetching" -> "Fetching market data for $displayTotal symbols…"
                    "scanning", null -> "Scanning"
                    else -> "Scanning"
                }
                onProgress.onProgress(done, displayTotal, phaseLabel)
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
                    // Use a larger grace window while the backend hasn't
                    // reported any completed ticker yet, OR while the
                    // backend explicitly reports it's in queued/prefetching
                    // phase. Once we see done >= 1 the normal 90s stagnation
                    // window applies — a mid-scan plateau at, say, 7/33
                    // really is an engine stall (worker holding _engine_scan_
                    // _lock and not yielding). Older backends won't emit
                    // `phase` at all, in which case done<=0 alone triggers
                    // the longer grace window.
                    val inPreScanPhase = lastProgressDone <= 0 ||
                        status.phase == "queued" ||
                        status.phase == "prefetching"
                    val effectiveTimeout = if (inPreScanPhase) {
                        initialProgressGraceMs
                    } else {
                        stagnationTimeoutMs
                    }
                    if (stalledMs > effectiveTimeout) {
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
            @Suppress("UNREACHABLE_CODE")
            emptyList<ScanResultItem>()
        } catch (lost: ScanJobLostException) {
            if (restartsUsed >= maxRestarts) {
                // Bumped restart budget; propagate to the caller so it can
                // show a specific "backend restarted mid-scan" message
                // rather than the generic 404 text.
                throw ScanJobLostException(
                    jobId = lost.jobId,
                    restartsAttempted = restartsUsed + 1,
                    message = "Backend restarted twice during this scan (last job $currentJobId). " +
                        "Please tap Scan again in a moment.",
                )
            }
            restartsUsed++
            Log.w(
                "SCAN_POLL",
                "Auto-restarting scan after lost job (restart $restartsUsed/$maxRestarts)",
            )
            onProgress.onProgress(-1, 0, "Backend restarted — retrying scan…")
            // Small backoff so we don't hammer a still-booting instance.
            delay(reconnectBackoffMs)
            continue
        }
      }
      // Unreachable — the inner while(true) either returns results or
      // throws. Kept only so the outer try's expression type resolves.
      @Suppress("UNREACHABLE_CODE")
      emptyList<ScanResultItem>()
    } catch (ce: CancellationException) {
        // Something upstream cancelled us — WorkManager killing the worker,
        // the user hitting the Alerts-tab Stop button, or a sibling scan
        // starting up and cancelling this one. Best-effort: tell the
        // backend to release _engine_scan_lock so the next scan doesn't
        // have to wait the full 6-7 min for the ghost job to finish on
        // its own. We do this under `NonCancellable` (suspending calls in
        // a cancelled coroutine throw immediately otherwise) and with a
        // short timeout so we never delay the cancellation propagation
        // by more than a second or two.
        //
        // If currentJobId is empty the cancellation fired before we even
        // received the scanAsync response — nothing to cancel server-side.
        if (currentJobId.isNotBlank()) {
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000L) {
                    try {
                        apiService.cancelScanJob(currentJobId).close()
                        Log.i(
                            "SCAN_POLL",
                            "Cancelled backend scan job $currentJobId on client abort.",
                        )
                    } catch (e: Throwable) {
                        // Best-effort — a failed cancel doesn't change the
                        // outcome from the user's perspective; the backend
                        // will eventually finish on its own.
                        Log.w(
                            "SCAN_POLL",
                            "cancelScanJob($currentJobId) failed on abort: ${e.message}",
                        )
                    }
                }
            }
        }
        throw ce
    }
}
