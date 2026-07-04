package com.example.financestreamai

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Live-backend integration test: exercises the exact production code
 * path ([runAsyncWatchlistScan]) against the real Render backend.
 *
 * This is the strongest possible proof that a 10-symbol scan works
 * end-to-end for a real user — but it takes 2-3 minutes per run and
 * requires an internet connection to https://financestreamai-backend.onrender.com,
 * so it is OPT-IN.
 *
 * To run it:
 * ```pwsh
 * $env:RUN_LIVE_BACKEND_TESTS = "1"
 * .\gradlew.bat :app:testDebugUnitTest --tests "com.example.financestreamai.AsyncScanPollerLiveTest"
 * ```
 *
 * When the env var is not set the tests are skipped (via JUnit
 * [Assume.assumeTrue]), so a regular `gradlew testDebugUnitTest` stays
 * fast and offline-friendly.
 */
class AsyncScanPollerLiveTest {

    private lateinit var api: JPFinanceApi
    private val gson = GsonBuilder().create()
    private val scanListType: java.lang.reflect.Type =
        object : TypeToken<List<ScanResultItem>>() {}.type

    @Before
    fun requireLive() {
        Assume.assumeTrue(
            "Set RUN_LIVE_BACKEND_TESTS=1 to enable — hits the real Render backend and takes 2-3 min per test",
            System.getenv("RUN_LIVE_BACKEND_TESTS") == "1"
        )
        val client = OkHttpClient.Builder()
            // Generous timeouts to survive Render free-tier cold start (30-60s)
            // and the ~2-min end-to-end 10-symbol scan.
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        api = Retrofit.Builder()
            .baseUrl("https://financestreamai-backend.onrender.com/api/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(JPFinanceApi::class.java)
    }

    /**
     * Scans 10 real symbols against the real backend. Success criteria:
     *   1. Poller returns without throwing (network resilient).
     *   2. Every requested symbol is present in the response.
     *   3. Every returned item has a non-blank ticker and a positive price.
     * We do NOT assert on the number of CSPs/Diagonals/etc. because those
     * are market-condition dependent (the point of THIS test is to prove
     * the client wiring works, not to backtest the strategy screens).
     */
    @Test(timeout = 5 * 60_000L)
    fun scan10Symbols_liveBackend_returnsAllTickers() = runBlocking {
        val tickers = listOf(
            "AAPL", "MSFT", "NVDA", "GOOGL", "META",
            "AMZN", "TSLA", "AVGO", "CRM", "ORCL"
        )
        val progressLog = mutableListOf<String>()

        val results = runAsyncWatchlistScan(
            apiService = api,
            tickers = tickers.joinToString(","),
            strategy = null,
            scanListType = scanListType,
            gson = gson,
            onProgress = { done, total, phase ->
                val label = when {
                    done < 0 -> phase
                    phase == "Done" -> "Done $done/$total"
                    else -> "$phase $done/$total"
                }
                if (progressLog.lastOrNull() != label) progressLog.add(label)
            },
            // Match production defaults (see MainActivity scan-tab watchlist).
            overallPollTimeoutMs = 4 * 60_000L,
        )

        println("[LIVE] progress trail: $progressLog")
        println("[LIVE] returned ${results.size} items:")
        results.forEach { r ->
            println(
                "  ${r.ticker.padEnd(6)} price=\$${"%.2f".format(r.price)} " +
                    "csps=${r.csps?.size ?: 0} " +
                    "diag=${r.diagonals?.size ?: 0} " +
                    "vert=${r.verticals?.size ?: 0} " +
                    "leaps=${r.longLeaps?.size ?: 0} " +
                    "pcs=${r.putCreditSpreads?.size ?: 0}"
            )
        }

        assertEquals("all 10 tickers returned", 10, results.size)
        val returned = results.map { it.ticker }.toSet()
        val missing = tickers.filter { it !in returned }
        assertTrue("no missing tickers: $missing", missing.isEmpty())
        results.forEach { r ->
            assertTrue("ticker non-blank for $r", r.ticker.isNotBlank())
            assertTrue("price positive for ${r.ticker}", r.price > 0.0)
        }
        assertTrue("at least one progress update fired", progressLog.isNotEmpty())
    }
}
