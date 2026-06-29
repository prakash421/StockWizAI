package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the bull/bear recommendation classifier helpers used by
 * [DailyRecommendationWorker.detectPortfolioFlips].
 *
 * Captures the SPCK regression class (2026-06-25) where prose like
 * "AVOID — BUY ZONE BELOW \$45" was treated as bullish because the
 * substring "BUY" appears. The portfolio-flip detector relies on these
 * three predicates so a wrong classification produces phantom flip
 * notifications.
 */
class BullBearClassifierTest {

    // ---- isBullishRecommendation ----

    @Test
    fun `plain BUY is bullish`() {
        assertTrue(isBullishRecommendation("BUY"))
    }

    @Test
    fun `STRONG BUY is bullish`() {
        assertTrue(isBullishRecommendation("STRONG BUY"))
    }

    @Test
    fun `lowercase buy is bullish`() {
        assertTrue(isBullishRecommendation("buy"))
    }

    @Test
    fun `DO NOT BUY is not bullish`() {
        assertFalse(isBullishRecommendation("DO NOT BUY"))
    }

    @Test
    fun `DONT BUY is not bullish`() {
        assertFalse(isBullishRecommendation("DON'T BUY"))
    }

    @Test
    fun `HOLD is not bullish`() {
        assertFalse(isBullishRecommendation("HOLD"))
    }

    @Test
    fun `SELL is not bullish`() {
        assertFalse(isBullishRecommendation("SELL"))
    }

    // ---- isBearishRecommendation ----

    @Test
    fun `SELL is bearish`() {
        assertTrue(isBearishRecommendation("SELL"))
    }

    @Test
    fun `STRONG SELL is bearish`() {
        assertTrue(isBearishRecommendation("STRONG SELL"))
    }

    @Test
    fun `AVOID is bearish`() {
        assertTrue(isBearishRecommendation("AVOID"))
    }

    @Test
    fun `BEARISH stance is bearish`() {
        assertTrue(isBearishRecommendation("BEARISH MOMENTUM"))
    }

    @Test
    fun `BUY is not bearish`() {
        assertFalse(isBearishRecommendation("BUY"))
    }

    @Test
    fun `HOLD is not bearish`() {
        assertFalse(isBearishRecommendation("HOLD"))
    }

    // ---- isBullBearShiftBetween ----

    @Test
    fun `BUY to SELL counts as flip`() {
        assertTrue(isBullBearShiftBetween("BUY", "SELL"))
    }

    @Test
    fun `SELL to BUY counts as flip`() {
        assertTrue(isBullBearShiftBetween("SELL", "BUY"))
    }

    @Test
    fun `BUY to STRONG BUY does not flip`() {
        assertFalse(isBullBearShiftBetween("BUY", "STRONG BUY"))
    }

    @Test
    fun `HOLD to BUY does not flip`() {
        // Intentional: HOLD→BUY is an upgrade but not a bull↔bear flip,
        // and the portfolio-flip notifier should not alert on it (would be noisy).
        assertFalse(isBullBearShiftBetween("HOLD", "BUY"))
    }

    @Test
    fun `BUY to HOLD does not flip`() {
        assertFalse(isBullBearShiftBetween("BUY", "HOLD"))
    }

    @Test
    fun `AVOID to STRONG BUY flips`() {
        assertTrue(isBullBearShiftBetween("AVOID", "STRONG BUY"))
    }

    @Test
    fun `SPCK-style AVOID with conditional BUY zone exposes substring fragility`() {
        // The exact SPCK string that caused phantom-flip alerts in 2026-06-25.
        // The current substring-based classifier marks this string as BOTH
        // bullish (contains "BUY") AND bearish (contains "AVOID").
        //
        // Consequence: a previous plain "BUY" verdict transitioning to this
        // string CURRENTLY fires the flip predicate (because today's verdict
        // is also bearish per the substring check), so the user could see
        // a spurious "BULL→BEAR" alert.
        //
        // This test PINS today's broken-but-known behaviour. A future fix
        // should switch the worker to use recommendationBucket() instead of
        // the raw substring predicates, at which point this test should be
        // updated to assert the corrected behaviour. Until then this
        // documents the limitation and prevents accidental regressions in
        // the opposite direction.
        val rec = "AVOID — STRONG BUY ZONE BELOW \$45"
        assertTrue("substring matcher fires on 'BUY' — known limitation", isBullishRecommendation(rec))
        assertTrue("substring matcher fires on 'AVOID'", isBearishRecommendation(rec))
        // BUY → (this both-bullish-and-bearish string) still triggers the
        // flip predicate because isBearish(rec) returns true. Treat this as
        // the bug-for-bug current behaviour.
        assertTrue(
            "current substring predicate raises a false BULL→BEAR flip on conditional-BUY-ZONE strings",
            isBullBearShiftBetween("BUY", rec)
        )
    }

    // ---- parseBacktestPercent ----

    @Test
    fun `parseBacktestPercent strips percent sign`() {
        assertEquals(90.6, parseBacktestPercent("90.6%"), 0.0001)
    }

    @Test
    fun `parseBacktestPercent handles 100`() {
        assertEquals(100.0, parseBacktestPercent("100.0%"), 0.0001)
    }

    @Test
    fun `parseBacktestPercent handles whitespace`() {
        assertEquals(85.0, parseBacktestPercent("  85.0% "), 0.0001)
    }

    @Test
    fun `parseBacktestPercent returns 0 on null`() {
        assertEquals(0.0, parseBacktestPercent(null), 0.0)
    }

    @Test
    fun `parseBacktestPercent returns 0 on garbage`() {
        assertEquals(0.0, parseBacktestPercent("N/A"), 0.0)
    }

    @Test
    fun `parseBacktestPercent returns 0 on empty`() {
        assertEquals(0.0, parseBacktestPercent(""), 0.0)
    }
}
