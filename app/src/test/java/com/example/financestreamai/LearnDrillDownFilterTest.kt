package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the new drill-down filter helpers added in the
 * expandable Learn tab (see [ExpandableWinRateCard] /
 * [ExpandableSignalCard]). These are pure functions kept top-level so
 * they're testable without a Compose runtime.
 */
class LearnDrillDownFilterTest {

    private fun rec(
        ticker: String,
        strategy: String? = null,
        verdict: String? = null,
        summary: String? = null,
    ) = RecommendationItem(
        recId = "test-$ticker-${strategy ?: "-"}",
        ticker = ticker,
        strategy = strategy,
        verdict = verdict,
        stockSummary = summary,
        outcomeHistory = emptyList(),
    )

    @Test
    fun filterByStrategy_isCaseInsensitive_andMatchesOnlyStrategy() {
        val history = listOf(
            rec("AAPL", strategy = "csp", verdict = "BUY"),
            rec("MSFT", strategy = "CSP", verdict = "STRONG BUY"),
            rec("NVDA", strategy = "leaps", verdict = "BUY"),
            rec("TSLA", strategy = null),
        )
        val matched = filterByStrategy(history, "csp")
        assertEquals(2, matched.size)
        assertTrue(matched.any { it.ticker == "AAPL" })
        assertTrue(matched.any { it.ticker == "MSFT" })
    }

    @Test
    fun filterByVerdict_matchesExactAndUnderscoreVariants() {
        val history = listOf(
            rec("AAPL", verdict = "STRONG BUY"),
            rec("MSFT", verdict = "STRONG_BUY"),
            rec("NVDA", verdict = "BUY"),
            rec("TSLA", verdict = null),
        )
        val matched = filterByVerdict(history, "STRONG BUY")
        assertEquals(2, matched.size)
        assertTrue(matched.any { it.ticker == "AAPL" })
        assertTrue(matched.any { it.ticker == "MSFT" })
    }

    @Test
    fun filterByVerdict_emptyNeedle_returnsEmpty() {
        val history = listOf(rec("AAPL", verdict = "BUY"))
        assertTrue(filterByVerdict(history, "").isEmpty())
        assertTrue(filterByVerdict(history, "   ").isEmpty())
    }

    @Test
    fun filterByStrategyAndVerdict_appliesBothCriteria() {
        val history = listOf(
            rec("AAPL", strategy = "csp", verdict = "BUY"),
            rec("MSFT", strategy = "csp", verdict = "SELL"),
            rec("NVDA", strategy = "leaps", verdict = "BUY"),
        )
        val matched = filterByStrategyAndVerdict(history, "CSP", "BUY")
        assertEquals(1, matched.size)
        assertEquals("AAPL", matched.first().ticker)
    }

    @Test
    fun filterBySignal_findsRecsWhoseExtractedSignalsMatch() {
        // stockSummary "RSI 25 uptrend" → extractSignals should yield
        //   ["RSI <30 (oversold)", "Trend: uptrend"]
        val history = listOf(
            rec("AAPL", strategy = "csp", summary = "RSI 25 uptrend"),
            rec("MSFT", strategy = "csp", summary = "RSI 55 sideways"),
            rec("NVDA", strategy = "leaps", summary = "RSI 25 uptrend"),
        )
        // strategy-scoped
        val scopedMatches = filterBySignal(history, "csp", "RSI <30 (oversold)")
        assertEquals(1, scopedMatches.size)
        assertEquals("AAPL", scopedMatches.first().ticker)

        // strategy-agnostic (null / blank strategy key)
        val allMatches = filterBySignal(history, null, "RSI <30 (oversold)")
        assertEquals(2, allMatches.size)
        assertTrue(allMatches.any { it.ticker == "AAPL" })
        assertTrue(allMatches.any { it.ticker == "NVDA" })
    }

    @Test
    fun filterBySignal_noMatchReturnsEmpty() {
        val history = listOf(
            rec("AAPL", strategy = "csp", summary = "RSI 55 sideways"),
        )
        assertTrue(filterBySignal(history, "csp", "RSI <30 (oversold)").isEmpty())
    }

    @Test
    fun filterByStrategy_missingStrategyDoesNotMatch() {
        val history = listOf(
            rec("AAPL", strategy = null),
            rec("MSFT", strategy = "csp"),
        )
        val matched = filterByStrategy(history, "csp")
        assertEquals(1, matched.size)
        assertEquals("MSFT", matched.first().ticker)
        assertFalse(matched.any { it.ticker == "AAPL" })
    }
}
