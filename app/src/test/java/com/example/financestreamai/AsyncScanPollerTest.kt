package com.example.financestreamai

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Live-HTTP validation of [runAsyncWatchlistScan] using a real
 * [MockWebServer] on localhost. This is the ONLY test in the suite that
 * actually exercises the Retrofit / OkHttp stack end-to-end — every
 * other test hand-rolls JSON strings and drives Gson.
 *
 * The bug we're guarding against (2026-07-03): scan-tab watchlist button
 * had zero tolerance for a single transient poll IOException and thus
 * <10% success rate on scans >4 stocks. These tests prove the retry
 * logic actually recovers from mid-scan network blips.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AsyncScanPollerTest {

    private lateinit var server: MockWebServer
    private lateinit var api: JPFinanceApi
    private val gson = GsonBuilder().create()
    private val scanListType: java.lang.reflect.Type =
        object : TypeToken<List<ScanResultItem>>() {}.type

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            // Fast timeouts so a broken test fails in seconds, not minutes.
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            // MockWebServer's SocketPolicy.DISCONNECT_AT_START otherwise triggers
            // OkHttp's built-in silent retry, which consumes the NEXT enqueued
            // MockResponse and can mask the very failure we're trying to test.
            .retryOnConnectionFailure(false)
            .build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(JPFinanceApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Helper: build a 10-ticker start-scan response body.
     */
    private fun startResponse(jobId: String, total: Int): String = """
        {"status":"started","job_id":"$jobId","total_tickers":$total,
         "strong_only":false,"strategy":null,
         "poll_url":"/api/v1/scan/status/$jobId"}
    """.trimIndent()

    /**
     * Helper: build a "still running" poll response.
     */
    private fun runningResponse(scanned: Int, total: Int): String = """
        {"status":"running","progress":"$scanned/$total",
         "tickers_scanned":$scanned,"total_tickers":$total}
    """.trimIndent()

    /**
     * Helper: build a "still running" poll response including the
     * backend sub-phase field (queued / prefetching / scanning). Older
     * backends omit this field; newer ones publish it so the client
     * can distinguish "waiting for engine lock" from "genuinely stuck".
     */
    private fun runningResponseWithPhase(scanned: Int, total: Int, phase: String): String = """
        {"status":"running","progress":"$scanned/$total",
         "tickers_scanned":$scanned,"total_tickers":$total,
         "phase":"$phase"}
    """.trimIndent()

    /**
     * Helper: build a "results ready" response — a JSON array (the
     * contract the client uses to detect completion is `body.startsWith("[")`).
     */
    private fun resultsResponse(tickers: List<String>): String {
        val items = tickers.joinToString(",") { t ->
            """{"ticker":"$t","price":100.0,"beta":1.0,"csps":[],"diagonals":[],"verticals":[],"long_leaps":[],"put_credit_spreads":[]}"""
        }
        return "[$items]"
    }

    /**
     * Helper: build a running-poll response with a `partial_results`
     * array embedded — the backend publishes this incrementally as
     * each ticker finishes so the client can stream rows into the UI.
     */
    private fun runningResponseWithPartial(
        scanned: Int,
        total: Int,
        partialTickers: List<String>,
    ): String {
        val items = partialTickers.joinToString(",") { t ->
            """{"ticker":"$t","price":100.0,"beta":1.0,"csps":[],"diagonals":[],"verticals":[],"long_leaps":[],"put_credit_spreads":[]}"""
        }
        return """
            {"status":"running","progress":"$scanned/$total",
             "tickers_scanned":$scanned,"total_tickers":$total,
             "partial_results":[$items]}
        """.trimIndent()
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    fun scan10Symbols_progressesThroughAllPollsAndReturnsFullList() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = listOf(
            "AAPL", "MSFT", "NVDA", "GOOGL", "META",
            "AMZN", "TSLA", "AVGO", "CRM", "ORCL"
        )
        val jobId = "job10a"

        // scanAsync start
        server.enqueue(MockResponse().setBody(startResponse(jobId, tickers.size))
            .addHeader("Content-Type", "application/json"))
        // 3 "running" polls
        server.enqueue(MockResponse().setBody(runningResponse(3, 10))
            .addHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(runningResponse(6, 10))
            .addHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(runningResponse(9, 10))
            .addHeader("Content-Type", "application/json"))
        // Final results
        server.enqueue(MockResponse().setBody(resultsResponse(tickers))
            .addHeader("Content-Type", "application/json"))

        val progressLog = mutableListOf<Triple<Int, Int, String>>()
        val results = runAsyncWatchlistScan(
            apiService = api,
            tickers = tickers.joinToString(","),
            strategy = null,
            scanListType = scanListType,
            gson = gson,
            onProgress = { done, total, phase ->
                progressLog.add(Triple(done, total, phase))
            },
            reconnectBackoffMs = 1L, // keep test fast even on transient paths
        )

        assertEquals("full ticker list must be returned", 10, results.size)
        assertEquals("first ticker matches enqueued order",
            "AAPL", results.first().ticker)
        assertEquals("last ticker matches enqueued order",
            "ORCL", results.last().ticker)
        // Progress: 3 in-flight updates (3/10, 6/10, 9/10) + 1 final "Done" (10/10)
        assertEquals("progress callback fires exactly once per successful poll",
            4, progressLog.size)
        assertEquals(Triple(3, 10, "Scanning"), progressLog[0])
        assertEquals(Triple(6, 10, "Scanning"), progressLog[1])
        assertEquals(Triple(9, 10, "Scanning"), progressLog[2])
        assertEquals(Triple(10, 10, "Done"), progressLog[3])
    }

    // ------------------------------------------------------------------
    // Transient error resilience (the bug this fixes)
    // ------------------------------------------------------------------

    @Test
    fun scan10Symbols_recoversFromMidScanIOException() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = (1..10).map { "T$it" }
        val jobId = "job10b"

        // scanAsync start
        server.enqueue(MockResponse().setBody(startResponse(jobId, 10))
            .addHeader("Content-Type", "application/json"))
        // 2 good polls
        server.enqueue(MockResponse().setBody(runningResponse(2, 10)))
        server.enqueue(MockResponse().setBody(runningResponse(4, 10)))
        // 3 forced disconnects (simulates DNS blip / cellular handoff).
        // Using DISCONNECT_AFTER_REQUEST (reads request then closes without
        // responding) rather than DISCONNECT_AT_START because the latter can
        // return an empty body instead of throwing IOException in some
        // OkHttp/MockWebServer combinations.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        // Recovery: 2 more good polls
        server.enqueue(MockResponse().setBody(runningResponse(7, 10)))
        server.enqueue(MockResponse().setBody(runningResponse(10, 10)))
        // Final results
        server.enqueue(MockResponse().setBody(resultsResponse(tickers)))

        val progressLog = mutableListOf<Triple<Int, Int, String>>()
        val results = runAsyncWatchlistScan(
            apiService = api,
            tickers = tickers.joinToString(","),
            strategy = null,
            scanListType = scanListType,
            gson = gson,
            onProgress = { done, total, phase ->
                progressLog.add(Triple(done, total, phase))
            },
            reconnectBackoffMs = 1L,
        )

        assertEquals("full ticker list recovered after transient errors",
            10, results.size)
        val reconnectingLabels = progressLog.filter { it.first < 0 }
        assertEquals("exactly 3 transient-error progress updates fired",
            3, reconnectingLabels.size)
        assertTrue("transient-error phase labels the retry attempt",
            reconnectingLabels[0].third.startsWith("Reconnecting (1/"))
        assertTrue(reconnectingLabels[1].third.startsWith("Reconnecting (2/"))
        assertTrue(reconnectingLabels[2].third.startsWith("Reconnecting (3/"))
        assertTrue("done+phase after recovery must be the final Done",
            progressLog.last() == Triple(10, 10, "Done"))
    }

    // ------------------------------------------------------------------
    // Give-up path
    // ------------------------------------------------------------------

    @Test
    fun scan10Symbols_givesUpAfterMaxConsecutiveErrors() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = (1..10).map { "T$it" }
        val jobId = "job10c"

        // scanAsync start
        server.enqueue(MockResponse().setBody(startResponse(jobId, 10)))
        // Queue MORE consecutive disconnects than the max tolerance
        // (test uses maxConsecutivePollErrors=3 for speed).
        // See note above re DISCONNECT_AFTER_REQUEST vs DISCONNECT_AT_START.
        repeat(6) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        }

        var caught: Exception? = null
        try {
            runAsyncWatchlistScan(
                apiService = api,
                tickers = tickers.joinToString(","),
                strategy = null,
                scanListType = scanListType,
                gson = gson,
                onProgress = { _, _, _ -> },
                maxConsecutivePollErrors = 3,
                reconnectBackoffMs = 1L,
            )
            fail("Expected IOException after N=3 consecutive failures")
        } catch (e: IOException) {
            caught = e
        }
        assertNotNull(caught)
    }

    // ------------------------------------------------------------------
    // Overall timeout
    // ------------------------------------------------------------------

    @Test
    fun scan10Symbols_bailsOnOverallTimeout() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = (1..10).map { "T$it" }
        val jobId = "job10d"

        // Assigning a dispatcher REPLACES the enqueue queue, so the
        // dispatcher itself must serve both the /scan/async start AND every
        // /scan/status/{jobId} poll. Enqueued responses are ignored once
        // a dispatcher is set.
        val stuckStart = startResponse(jobId, 10)
        val stuckRunning = runningResponse(1, 10)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/scan/async") ->
                        MockResponse().setBody(stuckStart)
                    path.contains("/scan/status/") ->
                        MockResponse().setBody(stuckRunning)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        var caught: Exception? = null
        try {
            runAsyncWatchlistScan(
                apiService = api,
                tickers = tickers.joinToString(","),
                strategy = null,
                scanListType = scanListType,
                gson = gson,
                onProgress = { _, _, _ -> },
                // 500ms overall cap so the test finishes fast.
                overallPollTimeoutMs = 500L,
                reconnectBackoffMs = 1L,
            )
            fail("Expected IOException after overall poll timeout")
        } catch (e: IOException) {
            caught = e
        }
        assertNotNull(caught)
        assertTrue(
            "timeout message mentions the cap",
            caught!!.message?.contains("didn't finish") == true
        )
    }

    // ------------------------------------------------------------------
    // Terminal "complete" without results (backend returned an error obj)
    // ------------------------------------------------------------------

    @Test
    fun scan_returnsEmptyOnTerminalStatusWithoutResultsList() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = (1..10).map { "T$it" }
        val jobId = "job10e"

        server.enqueue(MockResponse().setBody(startResponse(jobId, 10)))
        // Backend marks job complete but returns an object (not a list) — e.g.
        // the "Backend busy with another scan" lock-timeout path in _run_scan_job.
        server.enqueue(MockResponse().setBody("""{"status":"complete","progress":"0/10","tickers_scanned":0,"total_tickers":10}"""))

        val results = runAsyncWatchlistScan(
            apiService = api,
            tickers = tickers.joinToString(","),
            strategy = null,
            scanListType = scanListType,
            gson = gson,
            onProgress = { _, _, _ -> },
            reconnectBackoffMs = 1L,
        )
        assertTrue("empty list surfaces the empty-results UI branch",
            results.isEmpty())
    }

    // ------------------------------------------------------------------
    // Progress stagnation (backend accepted job but stopped advancing —
    // typically `_engine_scan_lock` held by a scheduled scan). This is
    // the failure mode reported on 2026-07-03 that shipped as
    // "scanning 36/46 stuck for minutes → misleading SocketTimeout msg".
    // ------------------------------------------------------------------

    @Test
    fun scan_throwsScanStalledException_whenTickersScannedFrozen() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = (1..10).map { "T$it" }
        val jobId = "job10f"

        // Reach 4/10, then freeze at 4/10 forever. Dispatcher lets us
        // serve unlimited "still stuck" polls without re-enqueueing.
        val startBody = startResponse(jobId, 10)
        val stuckBody = runningResponse(4, 10)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/scan/async") -> MockResponse().setBody(startBody)
                    path.contains("/scan/status/") -> MockResponse().setBody(stuckBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // Fake wall clock that advances 1s per invocation. Because
        // runTest virtualizes delay(), System.currentTimeMillis()
        // barely moves during the test — inject a counter so the
        // stagnation detector fires deterministically.
        var fakeMs = 0L
        val clock: () -> Long = { fakeMs.also { fakeMs += 1_000L } }

        var caught: ScanStalledException? = null
        try {
            runAsyncWatchlistScan(
                apiService = api,
                tickers = tickers.joinToString(","),
                strategy = null,
                scanListType = scanListType,
                gson = gson,
                onProgress = { _, _, _ -> },
                reconnectBackoffMs = 1L,
                stagnationTimeoutMs = 5_000L,
                nowMs = clock,
            )
            fail("Expected ScanStalledException when progress freezes")
        } catch (e: ScanStalledException) {
            caught = e
        }
        assertNotNull(caught)
        assertEquals("stall ticker count matches last observed progress",
            4, caught!!.ticker)
        assertEquals("stall total matches watchlist size", 10, caught.total)
        assertTrue("stall duration reported in seconds is positive",
            caught.stalledForSec > 0L)
        assertTrue(
            "message names the progress it froze at",
            caught.message?.contains("4/10") == true
        )
    }

    /**
     * If the backend keeps advancing (even slowly), we must NOT falsely
     * declare stagnation. Guards against a regression where a naive
     * "no advance for X polls" check would fire even on healthy scans
     * that happen to have a slow ticker in the middle.
     */
    @Test
    fun scan_doesNotStall_whenProgressAdvancesEachPoll() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = (1..10).map { "T$it" }
        val jobId = "job10g"

        server.enqueue(MockResponse().setBody(startResponse(jobId, 10)))
        // Advance by 1 every poll, 10 polls total.
        for (n in 1..9) {
            server.enqueue(MockResponse().setBody(runningResponse(n, 10)))
        }
        server.enqueue(MockResponse().setBody(resultsResponse(tickers)))

        // Same 1s-per-tick fake clock, but progress advances each poll
        // so lastProgressAdvanceMs keeps resetting.
        var fakeMs = 0L
        val clock: () -> Long = { fakeMs.also { fakeMs += 1_000L } }

        val results = runAsyncWatchlistScan(
            apiService = api,
            tickers = tickers.joinToString(","),
            strategy = null,
            scanListType = scanListType,
            gson = gson,
            onProgress = { _, _, _ -> },
            reconnectBackoffMs = 1L,
            // 2s stagnation tolerance — well under our 10 polls * 1s/tick,
            // so a naive implementation would definitely fire.
            stagnationTimeoutMs = 2_000L,
            nowMs = clock,
        )
        assertEquals("full ticker list returned; no stall thrown", 10, results.size)
    }

    // ------------------------------------------------------------------
    // Phase-aware stagnation grace. Regression test for user report
    // (2026-07-04): "The scan stalled at 0/33 symbols (no progress of
    // 92s)." Root cause: backend legitimately spends 30-90s on
    // prefetch_market_data before the per-ticker loop starts. During
    // that window `tickers_scanned` is stuck at 0 for reasons that
    // don't warrant giving up. Fix: apply a longer grace window
    // (initialProgressGraceMs, default 4min) whenever the backend is
    // still in queued/prefetching phase OR whenever we haven't seen
    // any ticker complete yet. The normal 90s stagnation only kicks
    // in AFTER the first ticker completes.
    // ------------------------------------------------------------------

    @Test
    fun scan_doesNotStall_whileBackendIsPrefetching() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = (1..10).map { "T$it" }
        val jobId = "job10h"

        // Serve unlimited "phase=prefetching, tickers_scanned=0" polls
        // for a while, then flip to results.
        val startBody = startResponse(jobId, 10)
        val prefetchingBody = runningResponseWithPhase(0, 10, "prefetching")
        val resultsBody = resultsResponse(tickers)
        var pollCount = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/scan/async") -> MockResponse().setBody(startBody)
                    path.contains("/scan/status/") -> {
                        pollCount++
                        // First 8 polls: prefetching. Then serve results.
                        if (pollCount <= 8) {
                            MockResponse().setBody(prefetchingBody)
                        } else {
                            MockResponse().setBody(resultsBody)
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // Fake clock advances 20s per invocation → 8 polls span 160s,
        // WELL past the 5s stagnationTimeoutMs but under the 300s
        // initialProgressGraceMs. Correct behavior: no stall.
        var fakeMs = 0L
        val clock: () -> Long = { fakeMs.also { fakeMs += 20_000L } }

        val results = runAsyncWatchlistScan(
            apiService = api,
            tickers = tickers.joinToString(","),
            strategy = null,
            scanListType = scanListType,
            gson = gson,
            onProgress = { _, _, _ -> },
            reconnectBackoffMs = 1L,
            stagnationTimeoutMs = 5_000L,       // would fire in <1 poll if applied
            initialProgressGraceMs = 300_000L,  // 5min — MUST be the effective limit while phase=prefetching
            nowMs = clock,
        )
        assertEquals("scan must complete even after long prefetch phase", 10, results.size)
    }

    @Test
    fun scan_throwsScanStalledException_whenPrefetchExceedsInitialGrace() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = (1..10).map { "T$it" }
        val jobId = "job10i"

        // Never leave prefetching phase — hostile backend stuck forever.
        val startBody = startResponse(jobId, 10)
        val prefetchingBody = runningResponseWithPhase(0, 10, "prefetching")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/scan/async") -> MockResponse().setBody(startBody)
                    path.contains("/scan/status/") -> MockResponse().setBody(prefetchingBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // Fake clock advances 5s/poll. initialProgressGraceMs=10s means
        // by poll 3 we're past the grace period → must throw.
        var fakeMs = 0L
        val clock: () -> Long = { fakeMs.also { fakeMs += 5_000L } }

        var caught: ScanStalledException? = null
        try {
            runAsyncWatchlistScan(
                apiService = api,
                tickers = tickers.joinToString(","),
                strategy = null,
                scanListType = scanListType,
                gson = gson,
                onProgress = { _, _, _ -> },
                reconnectBackoffMs = 1L,
                stagnationTimeoutMs = 1_000L,        // irrelevant here
                initialProgressGraceMs = 10_000L,    // 10s cap for pre-scan phase
                nowMs = clock,
            )
            fail("Expected ScanStalledException after initialProgressGraceMs exceeded")
        } catch (e: ScanStalledException) {
            caught = e
        }
        assertNotNull(caught)
        assertEquals("stall recorded at 0 tickers", 0, caught!!.ticker)
    }

    @Test
    fun progressCallback_reportsPhaseLabelDuringPrefetching() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = (1..3).map { "T$it" }
        val jobId = "job3j"

        server.enqueue(MockResponse().setBody(startResponse(jobId, 3)))
        // 2 prefetching polls, then results.
        server.enqueue(MockResponse().setBody(runningResponseWithPhase(0, 3, "prefetching")))
        server.enqueue(MockResponse().setBody(runningResponseWithPhase(0, 3, "prefetching")))
        server.enqueue(MockResponse().setBody(resultsResponse(tickers)))

        val phaseLog = mutableListOf<String>()
        runAsyncWatchlistScan(
            apiService = api,
            tickers = tickers.joinToString(","),
            strategy = null,
            scanListType = scanListType,
            gson = gson,
            onProgress = { _, _, phase -> phaseLog.add(phase) },
            reconnectBackoffMs = 1L,
        )
        // At least the first two callbacks (before results) must carry
        // the "Fetching market data..." label so the UI can differentiate
        // from a plain "Scanning" state.
        assertTrue(
            "expected a 'Fetching market data' phase label during prefetch, got: $phaseLog",
            phaseLog.any { it.contains("Fetching market data", ignoreCase = true) }
        )
    }

    @Test
    fun progressCallback_reportsPhaseLabelDuringQueued() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = (1..3).map { "T$it" }
        val jobId = "job3k"

        server.enqueue(MockResponse().setBody(startResponse(jobId, 3)))
        server.enqueue(MockResponse().setBody(runningResponseWithPhase(0, 3, "queued")))
        server.enqueue(MockResponse().setBody(resultsResponse(tickers)))

        val phaseLog = mutableListOf<String>()
        runAsyncWatchlistScan(
            apiService = api,
            tickers = tickers.joinToString(","),
            strategy = null,
            scanListType = scanListType,
            gson = gson,
            onProgress = { _, _, phase -> phaseLog.add(phase) },
            reconnectBackoffMs = 1L,
        )
        assertTrue(
            "expected a 'Waiting for backend' phase label during queued, got: $phaseLog",
            phaseLog.any { it.contains("Waiting for backend", ignoreCase = true) }
        )
    }

    // ------------------------------------------------------------------
    // STREAMING partial results (2026-07-04 user report: "results are
    // visible only after completing the scan for all the stocks. earlier,
    // results used to be displayed immediately when the scan for a
    // particular stock is completed"). Backend now returns partial_results
    // on every /scan/status poll; poller must forward the DELTA to
    // onPartialResults so the UI can append rows incrementally.
    // ------------------------------------------------------------------

    @Test
    fun onPartialResults_receivesEachTickerExactlyOnce_asStreamGrows() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = (1..5).map { "T$it" }
        val jobId = "jobStream1"

        server.enqueue(MockResponse().setBody(startResponse(jobId, 5)))
        // Poll 1: 2 tickers streamed
        server.enqueue(MockResponse().setBody(runningResponseWithPartial(2, 5, tickers.take(2))))
        // Poll 2: 4 tickers streamed (T3 + T4 are the delta)
        server.enqueue(MockResponse().setBody(runningResponseWithPartial(4, 5, tickers.take(4))))
        // Final results
        server.enqueue(MockResponse().setBody(resultsResponse(tickers)))

        val streamed = mutableListOf<String>()
        val deltas = mutableListOf<Int>()
        val finalResults = runAsyncWatchlistScan(
            apiService = api,
            tickers = tickers.joinToString(","),
            strategy = null,
            scanListType = scanListType,
            gson = gson,
            onProgress = { _, _, _ -> },
            onPartialResults = { delta ->
                deltas.add(delta.size)
                streamed.addAll(delta.map { it.ticker })
            },
            reconnectBackoffMs = 1L,
        )
        assertEquals("final result list matches full ticker set", 5, finalResults.size)
        assertEquals(
            "each streamed ticker delivered exactly once (T1..T4 in order via streaming)",
            listOf("T1", "T2", "T3", "T4"), streamed,
        )
        assertEquals(
            "expected two streaming callbacks with delta sizes [2, 2]",
            listOf(2, 2), deltas,
        )
    }

    @Test
    fun onPartialResults_isOptional_backwardCompatibleWhenNull() = runTest(
        StandardTestDispatcher()
    ) {
        // Older call sites don't pass onPartialResults. Poller must still
        // return the full list at completion, unchanged.
        val tickers = (1..3).map { "T$it" }
        val jobId = "jobStream2"

        server.enqueue(MockResponse().setBody(startResponse(jobId, 3)))
        server.enqueue(MockResponse().setBody(runningResponseWithPartial(2, 3, tickers.take(2))))
        server.enqueue(MockResponse().setBody(resultsResponse(tickers)))

        val results = runAsyncWatchlistScan(
            apiService = api,
            tickers = tickers.joinToString(","),
            strategy = null,
            scanListType = scanListType,
            gson = gson,
            onProgress = { _, _, _ -> },
            reconnectBackoffMs = 1L,
            // onPartialResults intentionally omitted
        )
        assertEquals("full ticker set returned at completion", 3, results.size)
    }

    @Test
    fun onPartialResults_isolatesCallbackExceptions_scanStillCompletes() = runTest(
        StandardTestDispatcher()
    ) {
        // If the UI callback throws, the poller MUST NOT abort — the
        // final result list is authoritative and the user should still
        // see completion.
        val tickers = (1..3).map { "T$it" }
        val jobId = "jobStream3"

        server.enqueue(MockResponse().setBody(startResponse(jobId, 3)))
        server.enqueue(MockResponse().setBody(runningResponseWithPartial(2, 3, tickers.take(2))))
        server.enqueue(MockResponse().setBody(resultsResponse(tickers)))

        val results = runAsyncWatchlistScan(
            apiService = api,
            tickers = tickers.joinToString(","),
            strategy = null,
            scanListType = scanListType,
            gson = gson,
            onProgress = { _, _, _ -> },
            onPartialResults = { _ -> throw RuntimeException("simulated UI-side bug") },
            reconnectBackoffMs = 1L,
        )
        assertEquals(
            "scan must complete even when the streaming callback throws",
            3, results.size,
        )
    }

    // ------------------------------------------------------------------
    // Backend-restart (Render redeploy / cold-start) resilience.
    //
    // Bug (2026-07-04 user report): "When I select Scan Watchlist, I am
    // getting 'Server returned error 404. Please try again' after the
    // progress bar shows a few tickers scanned." Root cause: Render
    // free-tier redeployed the backend mid-scan, wiping the in-memory
    // scan_jobs dict. /scan/status/{jobId} then returned 404, which
    // Retrofit surfaced as HttpException — a RuntimeException that
    // bypassed the IOException retry logic in the poll loop.
    // ------------------------------------------------------------------

    /**
     * A 404 on /scan/status/{jobId} triggers exactly ONE automatic
     * restart of the scan (POST-then-poll from scratch). If the restarted
     * job runs to completion, the caller sees a normal success — no error
     * bubbles up.
     */
    @Test
    fun scan_autoRestartsOnce_whenBackendLosesJobMidScan() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = (1..5).map { "T$it" }
        val firstJob = "job-lost-1"
        val secondJob = "job-recovered-2"

        // First submit + one progressing poll…
        server.enqueue(MockResponse().setBody(startResponse(firstJob, 5))
            .addHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(runningResponse(2, 5))
            .addHeader("Content-Type", "application/json"))
        // …then backend restart: 404 on the next poll.
        server.enqueue(MockResponse().setResponseCode(404)
            .setBody("""{"detail":"Job not found"}"""))
        // Auto-restart: fresh submit + one running poll + final results.
        server.enqueue(MockResponse().setBody(startResponse(secondJob, 5))
            .addHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(runningResponse(3, 5))
            .addHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(resultsResponse(tickers))
            .addHeader("Content-Type", "application/json"))

        val progressLog = mutableListOf<Triple<Int, Int, String>>()
        val results = runAsyncWatchlistScan(
            apiService = api,
            tickers = tickers.joinToString(","),
            strategy = null,
            scanListType = scanListType,
            gson = gson,
            onProgress = { done, total, phase ->
                progressLog.add(Triple(done, total, phase))
            },
            reconnectBackoffMs = 1L,
        )

        assertEquals("recovered scan returns full ticker list", 5, results.size)
        assertTrue(
            "user sees a 'restarting' progress ping between the two attempts",
            progressLog.any { it.third.contains("restart", ignoreCase = true) },
        )
        assertTrue(
            "final progress event is the 'Done' completion",
            progressLog.last() == Triple(5, 5, "Done"),
        )

        // Sanity: we actually POSTed /scan/async twice (initial + restart).
        val startPaths = mutableListOf<String>()
        for (i in 0 until server.requestCount) {
            val rr: RecordedRequest = server.takeRequest()
            if (rr.path?.startsWith("/api/v1/scan/async") == true) {
                startPaths.add(rr.path!!)
            }
        }
        assertEquals("exactly 2 scan/async submissions (initial + 1 restart)",
            2, startPaths.size)
    }

    /**
     * When the backend loses the job TWICE within the same scan window
     * (extremely rare — implies the server bounced twice), the poller
     * gives up and surfaces [ScanJobLostException] so the UI can show
     * a specific "backend restarted mid-scan" message. Under NO
     * circumstances should the caller see the generic
     * "Server returned error 404" HttpException.
     */
    @Test
    fun scan_throwsScanJobLost_whenBackendBouncesTwice() = runTest(
        StandardTestDispatcher()
    ) {
        val tickers = (1..5).map { "T$it" }

        // First submit + 404
        server.enqueue(MockResponse().setBody(startResponse("job-a", 5))
            .addHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("gone"))
        // Restart submit + 404 again
        server.enqueue(MockResponse().setBody(startResponse("job-b", 5))
            .addHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("gone"))

        var caught: Exception? = null
        try {
            runAsyncWatchlistScan(
                apiService = api,
                tickers = tickers.joinToString(","),
                strategy = null,
                scanListType = scanListType,
                gson = gson,
                onProgress = { _, _, _ -> },
                reconnectBackoffMs = 1L,
            )
            fail("Expected ScanJobLostException when 2nd attempt also 404s")
        } catch (e: Exception) {
            caught = e
        }
        assertNotNull(caught)
        assertTrue(
            "must surface ScanJobLostException (not a raw HttpException 404) — " +
                "got ${caught!!.javaClass.simpleName}: ${caught.message}",
            caught is ScanJobLostException,
        )
        val lost = caught as ScanJobLostException
        assertEquals("restartsAttempted counts the initial + 1 restart",
            2, lost.restartsAttempted)
    }

    /**
     * `friendlyErrorMessage` must translate [ScanJobLostException] into
     * a user-facing "backend restarted mid-scan, tap Scan Watchlist
     * again" line — NOT the generic HTTP 404 text. Regression guard
     * for the exact user-visible string the 2026-07-04 report saw.
     */
    @Test
    fun friendlyErrorMessage_convertsScanJobLostToActionableText() {
        val lost = ScanJobLostException(
            jobId = "job-x",
            restartsAttempted = 2,
            message = "Backend lost job job-x",
        )
        val text = friendlyErrorMessage(lost)
        assertTrue(
            "surfaces 'backend restarted' phrasing — got: $text",
            text.contains("restarted", ignoreCase = true),
        )
        assertTrue(
            "asks user to tap Scan again — got: $text",
            text.contains("Scan Watchlist", ignoreCase = true) ||
                text.contains("tap Scan", ignoreCase = true),
        )
        assertFalse(
            "must NOT show the generic 'Server returned error 404' text",
            text.contains("404"),
        )
    }
}
