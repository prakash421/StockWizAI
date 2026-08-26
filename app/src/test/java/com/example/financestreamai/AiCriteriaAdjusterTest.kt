package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AiCriteriaAdjuster.computeAdjustments]. Verifies that the
 * client-side backtest-percent floors are auto-raised only when a strategy's
 * observed win rate has drifted below its May 2026 calibration baseline —
 * and that CSPs are never present in the returned map (user request
 * 2026-08-17: CSP hurdles stay hardcoded).
 */
class AiCriteriaAdjusterTest {

    private fun stat(win: Double, total: Int): StrategyStats =
        StrategyStats(winning = (total * win / 100.0).toInt(), losing = 0, neutral = 0, total = total, winRate = win)

    @Test
    fun `null stats produces empty adjustment map`() {
        assertTrue(AiCriteriaAdjuster.computeAdjustments(null).isEmpty())
    }

    @Test
    fun `disabled stats produces empty adjustment map`() {
        val stats = RecommendationStats(enabled = false, byStrategy = mapOf("diagonal" to stat(20.0, 100)))
        assertTrue(AiCriteriaAdjuster.computeAdjustments(stats).isEmpty())
    }

    @Test
    fun `csp is never in the adjustment map even when underperforming`() {
        val stats = RecommendationStats(
            enabled = true,
            byStrategy = mapOf(
                "csp" to stat(10.0, 500),
                "diagonal" to stat(40.0, 100),
            )
        )
        val out = AiCriteriaAdjuster.computeAdjustments(stats)
        assertNull("CSP must not receive an adjustment", out["csp"])
        assertNotNull(out["diagonal"])
    }

    @Test
    fun `insufficient samples produces no adjustment for that strategy`() {
        val stats = RecommendationStats(
            enabled = true,
            byStrategy = mapOf("vertical" to stat(20.0, 5))
        )
        assertTrue(AiCriteriaAdjuster.computeAdjustments(stats).isEmpty())
    }

    @Test
    fun `win rate at or above baseline never loosens the floor`() {
        val stats = RecommendationStats(
            enabled = true,
            byStrategy = mapOf(
                "diagonal" to stat(70.0, 200),   // baseline 60
                "vertical" to stat(50.0, 200),   // baseline 50
                "long_leap" to stat(65.0, 200),  // baseline 55
                "pcs" to stat(60.0, 200),        // baseline 60
            )
        )
        val out = AiCriteriaAdjuster.computeAdjustments(stats)
        assertTrue("Outperforming strategies must not receive a bump: $out", out.isEmpty())
    }

    @Test
    fun `underperforming vertical raises bt floor by the deficit`() {
        val stats = RecommendationStats(
            enabled = true,
            byStrategy = mapOf("vertical" to stat(42.0, 200))  // baseline 50, deficit 8pp
        )
        val out = AiCriteriaAdjuster.computeAdjustments(stats)
        val adj = out["vertical"] ?: error("expected vertical adjustment")
        assertEquals(8.0, adj.btFloorBumpPct, 0.001)
        assertEquals(4.0, adj.btExceptionalBumpPct, 0.001)
    }

    @Test
    fun `bump is capped at 10 percentage points`() {
        val stats = RecommendationStats(
            enabled = true,
            byStrategy = mapOf("diagonal" to stat(20.0, 500))  // deficit 40pp
        )
        val out = AiCriteriaAdjuster.computeAdjustments(stats)
        val adj = out["diagonal"] ?: error("expected diagonal adjustment")
        assertEquals(10.0, adj.btFloorBumpPct, 0.001)
        assertEquals(5.0, adj.btExceptionalBumpPct, 0.001)
    }

    @Test
    fun `plural key alias is honored`() {
        // Backend sometimes emits singular keys, sometimes plural. Both must resolve.
        val stats = RecommendationStats(
            enabled = true,
            byStrategy = mapOf(
                "long_leaps" to stat(45.0, 100),           // baseline 55 -> deficit 10
                "put_credit_spreads" to stat(55.0, 100),   // baseline 60 -> deficit 5
            )
        )
        val out = AiCriteriaAdjuster.computeAdjustments(stats)
        assertEquals(10.0, out["long_leap"]?.btFloorBumpPct ?: -1.0, 0.001)
        assertEquals(5.0, out["pcs"]?.btFloorBumpPct ?: -1.0, 0.001)
    }
}
