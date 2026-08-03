package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive coverage for the two critical features fixed on 2026-08-02:
 *
 *   1. **Daily-scan pipeline** — [ManualDailyPicksScan] powers the "Send
 *      Today's Picks Now" button and the scheduled 7 AM path. Regression
 *      here would break the primary revenue-generating feature.
 *
 *   2. **Hourly-scan disable + safe bootstrap** — [HourlyScanFeature]
 *      and [bootstrapWorkManagerSafely] together stop the app-open
 *      crash the user reported.  We can't touch WorkManager from a JVM
 *      unit test, but we CAN pin every contract the disable + bootstrap
 *      helpers rely on (constants, tag names, universe merging,
 *      notification body construction) so a rename or subtle regression
 *      surfaces immediately.
 *
 * Everything here is pure Kotlin / JVM — no Robolectric, no
 * `androidx.test`, no `WorkManagerTestInitHelper`.  Where a piece of
 * production code touches Android [android.content.Context] or the
 * WorkManager API, we have extracted a testable pure variant and test
 * THAT instead, then also assert its invariants via string / value
 * checks on the constants the untestable code depends on.
 */
class DailyScanAndHourlyDisableTest {

    // ═════════════════════════════════════════════════════════════════
    // ManualDailyPicksScan.mergeScanUniverse — pure universe builder
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun mergeUniverse_combinesAllThreeSourcesAndDeduplicates() {
        val universe = ManualDailyPicksScan.mergeScanUniverse(
            watchlist = listOf("AAPL", "MSFT", "NVDA"),
            portfolio = listOf("TSLA", "AAPL"), // AAPL overlaps watchlist
            etfs = listOf("SOXX", "MSFT"),      // MSFT overlaps watchlist
        )
        // 5 unique symbols — dupes stripped, order preserved.
        assertEquals(listOf("AAPL", "MSFT", "NVDA", "TSLA", "SOXX"), universe)
    }

    @Test
    fun mergeUniverse_deduplicationIsCaseInsensitive() {
        // Real-world: user's watchlist has lowercase, portfolio has UPPER.
        val universe = ManualDailyPicksScan.mergeScanUniverse(
            watchlist = listOf("aapl", "msft"),
            portfolio = listOf("AAPL", "TSLA"),
            etfs = listOf("MSFT"),
        )
        // First occurrence wins (watchlist lowercase); dupes stripped.
        assertEquals(listOf("aapl", "msft", "TSLA"), universe)
    }

    @Test
    fun mergeUniverse_stripsBlankAndWhitespaceOnlyEntries() {
        val universe = ManualDailyPicksScan.mergeScanUniverse(
            watchlist = listOf("AAPL", "", "  "),
            portfolio = listOf("\t", "TSLA"),
            etfs = listOf("SOXX", ""),
        )
        assertEquals(listOf("AAPL", "TSLA", "SOXX"), universe)
    }

    @Test
    fun mergeUniverse_alwaysIncludesWatchedEtfsEvenWithEmptyWatchlist() {
        // Regression guard: a user with an empty watchlist must still get
        // the ETFs scanned so the noon ETF alert path has data.
        val universe = ManualDailyPicksScan.mergeScanUniverse(
            watchlist = emptyList(),
            portfolio = emptyList(),
            etfs = DailyRecommendationWorker.WATCHED_ETFS,
        )
        assertEquals(DailyRecommendationWorker.WATCHED_ETFS, universe)
    }

    @Test
    fun mergeUniverse_preservesInsertionOrder() {
        // The scan poller emits progress in universe-order; a random shuffle
        // would degrade the UX for the top-of-watchlist symbols users care
        // about most.
        val universe = ManualDailyPicksScan.mergeScanUniverse(
            watchlist = listOf("Z", "Y", "X"),
            portfolio = listOf("W", "V"),
            etfs = listOf("U", "T"),
        )
        assertEquals(listOf("Z", "Y", "X", "W", "V", "U", "T"), universe)
    }

    @Test
    fun mergeUniverse_emptyInputsReturnEmptyList() {
        val universe = ManualDailyPicksScan.mergeScanUniverse(
            watchlist = emptyList(),
            portfolio = emptyList(),
            etfs = emptyList(),
        )
        assertTrue(universe.isEmpty())
    }

    @Test
    fun mergeUniverse_singleSymbolAcrossAllThreeSourcesDedupesToOne() {
        val universe = ManualDailyPicksScan.mergeScanUniverse(
            watchlist = listOf("AAPL"),
            portfolio = listOf("aapl"),
            etfs = listOf("AAPL"),
        )
        assertEquals(listOf("AAPL"), universe)
    }

    // ═════════════════════════════════════════════════════════════════
    // ManualDailyPicksScan.buildSummary — notification body builder
    // ═════════════════════════════════════════════════════════════════

    private fun scanItem(
        ticker: String,
        price: Double = 100.0,
        csps: List<CspResult>? = null,
        diagonals: List<DiagonalResult>? = null,
        verticals: List<VerticalResult>? = null,
        longLeaps: List<LongLeapsResult>? = null,
        pcs: List<PutCreditSpreadResult>? = null,
    ): ScanResultItem = ScanResultItem(
        ticker = ticker,
        price = price,
        rsi = 50.0,
        beta = 1.0,
        csps = csps,
        diagonals = diagonals,
        verticals = verticals,
        longLeaps = longLeaps,
        putCreditSpreads = pcs,
    )

    /** Minimal non-empty result placeholder so `!isNullOrEmpty()` is true. */
    private fun dummyCsps(): List<CspResult> = listOf(
        CspResult(
            strike = 100.0, premium = 1.0, delta = -0.25,
            bt = "88%", roc = "1.2%",
        )
    )
    private fun dummyDiagonals(): List<DiagonalResult> = listOf(
        DiagonalResult(
            longLeg = "100C 2027-01-15", shortLeg = "110C 2026-09-19",
            netDebt = 5.0, yieldRatio = "0.4", bt = "82%",
        )
    )
    private fun dummyVerticals(): List<VerticalResult> = listOf(
        VerticalResult(
            strikes = "100/105", netDebit = 2.0, bt = "80%",
        )
    )
    private fun dummyLeaps(): List<LongLeapsResult> = listOf(
        LongLeapsResult(
            strike = 80.0, expiry = "2027-06-18", premium = 25.0,
            delta = 0.75, intrinsicBuffer = "20%", leverage = "4x", bt = "85%",
        )
    )
    private fun dummyPcs(): List<PutCreditSpreadResult> = listOf(
        PutCreditSpreadResult(
            shortStrike = 100.0, longStrike = 95.0,
            credit = 1.5, maxLoss = 3.5, bt = "78%", roc = "5%",
        )
    )

    @Test
    fun buildSummary_emptyResults_reportsNoData() {
        val (title, body) = ManualDailyPicksScan.buildSummary(emptyList())
        assertEquals("Daily Scan — No Data", title)
        assertTrue(
            "empty-body must still explain the situation, got: $body",
            body.contains("Scanned 0 symbol")
        )
    }

    @Test
    fun buildSummary_resultsButNoStrategies_reportsNoStrongPicks() {
        val results = listOf(scanItem("AAPL"), scanItem("MSFT"), scanItem("NVDA"))
        val (title, body) = ManualDailyPicksScan.buildSummary(results)
        assertEquals("Daily Scan — No Strong Picks", title)
        assertTrue(
            "body must mention scanned count, got: $body",
            body.contains("Scanned 3 symbols")
        )
        assertTrue(
            "body must invite retry, got: $body",
            body.contains("Try again", ignoreCase = true) ||
                body.contains("adjust your watchlist", ignoreCase = true)
        )
    }

    @Test
    fun buildSummary_singleActionable_singularTitle() {
        val results = listOf(scanItem("AAPL", csps = dummyCsps()))
        val (title, _) = ManualDailyPicksScan.buildSummary(results)
        assertEquals("Daily Picks — 1 Recommendation", title)
    }

    @Test
    fun buildSummary_multipleActionable_pluralTitle() {
        val results = listOf(
            scanItem("AAPL", csps = dummyCsps()),
            scanItem("MSFT", diagonals = dummyDiagonals()),
        )
        val (title, _) = ManualDailyPicksScan.buildSummary(results)
        assertEquals("Daily Picks — 2 Recommendations", title)
    }

    @Test
    fun buildSummary_singleTickerMultiStrategy_countsEachStrategy() {
        // Same ticker with two strategies → 2 actionable picks total.
        val results = listOf(scanItem("AAPL", csps = dummyCsps(), diagonals = dummyDiagonals()))
        val (title, body) = ManualDailyPicksScan.buildSummary(results)
        assertEquals("Daily Picks — 2 Recommendations", title)
        assertTrue("body must call out CSP: $body", body.contains("1 CSP"))
        assertTrue("body must call out Diagonal: $body", body.contains("1 Diagonal"))
    }

    @Test
    fun buildSummary_reportsCountsPerStrategyType() {
        val results = listOf(
            scanItem("A", csps = dummyCsps()),
            scanItem("B", csps = dummyCsps()),
            scanItem("C", pcs = dummyPcs()),
            scanItem("D", verticals = dummyVerticals()),
            scanItem("E", longLeaps = dummyLeaps()),
        )
        val (_, body) = ManualDailyPicksScan.buildSummary(results)
        assertTrue(body, body.contains("2 CSPs"))
        assertTrue(body, body.contains("1 PCS"))
        assertTrue(body, body.contains("1 Vertical"))
        assertTrue(body, body.contains("1 LEAPS"))
    }

    @Test
    fun buildSummary_pluralizesCspAndPcsAtTwoOrMore() {
        val results = listOf(
            scanItem("A", csps = dummyCsps()),
            scanItem("B", csps = dummyCsps()),
            scanItem("C", pcs = dummyPcs()),
            scanItem("D", pcs = dummyPcs()),
        )
        val (_, body) = ManualDailyPicksScan.buildSummary(results)
        assertTrue(body, body.contains("2 CSPs"))
        assertTrue(body, body.contains("2 PCSs"))
    }

    @Test
    fun buildSummary_listsTopTickers_maxFive() {
        val results = (1..8).map {
            scanItem(ticker = "T$it", price = 100.0 + it, csps = dummyCsps())
        }
        val (_, body) = ManualDailyPicksScan.buildSummary(results)
        val bullets = body.lines().count { it.trim().startsWith("• ") || it.contains("• T") }
        assertTrue("expected 5 top-ticker bullets, saw $bullets in: $body", bullets >= 5)
        // 6th+ tickers must NOT appear.
        assertFalse("T6 should have been truncated, got: $body", body.contains("• T6"))
        assertFalse("T7 should have been truncated, got: $body", body.contains("• T7"))
    }

    @Test
    fun buildSummary_topTickerListsStrategyAcronyms() {
        val results = listOf(
            scanItem("AAPL", csps = dummyCsps(), diagonals = dummyDiagonals()),
            scanItem("MSFT", pcs = dummyPcs(), longLeaps = dummyLeaps()),
        )
        val (_, body) = ManualDailyPicksScan.buildSummary(results)
        assertTrue(body, body.contains("AAPL"))
        assertTrue(body, body.contains("MSFT"))
        // Each bullet must list ALL strategies for that ticker.
        val aaplLine = body.lines().first { it.contains("AAPL") }
        assertTrue(aaplLine, aaplLine.contains("CSP") && aaplLine.contains("Diagonal"))
        val msftLine = body.lines().first { it.contains("MSFT") }
        assertTrue(msftLine, msftLine.contains("PCS") && msftLine.contains("LEAPS"))
    }

    @Test
    fun buildSummary_formatsPriceWithTwoDecimals() {
        val results = listOf(scanItem("AAPL", price = 234.567, csps = dummyCsps()))
        val (_, body) = ManualDailyPicksScan.buildSummary(results)
        assertTrue("expected \$234.57 in body, got: $body", body.contains("\$234.57"))
    }

    @Test
    fun buildSummary_bodyDoesNotEndWithComma() {
        // Regression guard for the stripTrailingComma cleanup in the impl.
        val results = listOf(scanItem("AAPL", csps = dummyCsps()))
        val (_, body) = ManualDailyPicksScan.buildSummary(results)
        val summaryLine = body.lineSequence().first()
        assertFalse(
            "summary line must not end with a stray comma, got: $summaryLine",
            summaryLine.trimEnd().endsWith(",")
        )
    }

    // ═════════════════════════════════════════════════════════════════
    // DailyRecommendationWorker / PortfolioFlipWorker — tag/constant
    // contracts.  These are the values every OS-scoped surface
    // (WorkManager DB rows, notification channel IDs, scheduler unique
    // names) depends on — a rename silently invalidates queued work.
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun dailyWorkerTag_isStable() {
        assertEquals("DailyRecommendation", DailyRecommendationWorker.TAG)
    }

    @Test
    fun noonEtfTag_isStableAndDifferentFromDailyTag() {
        assertEquals("DailyRecommendation_etf_noon", DailyRecommendationWorker.TAG_NOON_ETF)
        assertNotEquals(DailyRecommendationWorker.TAG, DailyRecommendationWorker.TAG_NOON_ETF)
    }

    @Test
    fun dailyChannelId_isStable() {
        assertEquals("daily_recommendations", DailyRecommendationWorker.CHANNEL_ID)
    }

    @Test
    fun watchedEtfs_pinnedList_forNoonEtfScan() {
        // 2026-08-02 baseline. Extending the list is intentional and requires
        // a corresponding test update — this guard forces that intent.
        assertEquals(listOf("SOXX", "DRAM", "AIPO"), DailyRecommendationWorker.WATCHED_ETFS)
    }

    @Test
    fun portfolioFlipWorkerTags_pinned_forHourlyScanDisable() {
        // HourlyScanFeature.disable() cancels both of these. If either
        // name changes without updating the disable() call, stale work
        // will keep firing forever.
        assertEquals("PortfolioFlipScan", PortfolioFlipWorker.TAG)
        assertEquals("PortfolioFlipScan_manual", PortfolioFlipWorker.TAG_MANUAL)
        assertNotEquals(PortfolioFlipWorker.TAG, PortfolioFlipWorker.TAG_MANUAL)
    }
}
