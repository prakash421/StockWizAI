package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the recommendation-bucketing + AI auto-validate gating logic
 * used by the Scan Watchlist results filter chips and AiCrossValidator
 * fan-out.
 */
class RecommendationFilterTest {

    @Test
    fun strongBuy_isBucketedFirst() {
        assertEquals("STRONG BUY", recommendationBucket("STRONG BUY", null))
        assertEquals("STRONG BUY", recommendationBucket("Strong Buy (Confirmed)", null))
        // STRONG BUY must beat plain BUY since both contain "BUY"
        assertEquals("STRONG BUY", recommendationBucket("BUY", "STRONG OPPORTUNITY"))
    }

    @Test
    fun buy_variantsBucketed() {
        assertEquals("BUY", recommendationBucket("BUY", null))
        assertEquals("BUY", recommendationBucket(null, "OPPORTUNITY"))
        assertEquals("BUY", recommendationBucket("Buy — Trending", null))
    }

    @Test
    fun holdAndCaution() {
        assertEquals("HOLD", recommendationBucket("HOLD", null))
        assertEquals("HOLD", recommendationBucket("Neutral", null))
        assertEquals("HOLD", recommendationBucket(null, "CAUTION"))
    }

    @Test
    fun sellAndAvoid() {
        assertEquals("SELL", recommendationBucket("SELL", null))
        assertEquals("SELL", recommendationBucket("Strong Sell", null))
        assertEquals("AVOID", recommendationBucket("AVOID", null))
    }

    @Test
    fun blank_isOther() {
        assertEquals("OTHER", recommendationBucket(null, null))
        assertEquals("OTHER", recommendationBucket("", ""))
        assertEquals("OTHER", recommendationBucket("xyz", null))
    }

    @Test
    fun isBuyRated_acceptsStrongBuyAndBuy() {
        assertTrue(isBuyRated("STRONG BUY", null))
        assertTrue(isBuyRated("BUY", null))
        assertTrue(isBuyRated(null, "OPPORTUNITY"))
    }

    @Test
    fun isBuyRated_rejectsHoldSellAvoid() {
        assertFalse(isBuyRated("HOLD", null))
        assertFalse(isBuyRated("SELL", null))
        assertFalse(isBuyRated("AVOID", null))
        assertFalse(isBuyRated(null, null))
    }

    // -------- 2026-06-25 regression suite (SPCK in "Best to BUY") --------
    //
    // Root cause was twofold:
    //   (a) recommendationBucket() checked "STRONG BUY"/"BUY" before
    //       AVOID/SELL/HOLD, so prose like "AVOID — STRONG BUY ZONE BELOW
    //       $X" was bucketed as STRONG BUY.
    //   (b) pickRiskRewardExtremes() did rec.contains("BUY") directly,
    //       which matched anything with the letters "BUY" anywhere.

    @Test
    fun negativeVerdict_winsOverConditionalBuyZone() {
        // The smoking gun: prose verdict containing both AVOID and a
        // hypothetical "BUY ZONE" must bucket as AVOID, not STRONG BUY.
        assertEquals("AVOID", recommendationBucket("AVOID — STRONG BUY ZONE BELOW \$45", null))
        assertEquals("AVOID", recommendationBucket("AVOID — BUY ONLY ON DEEP DIP", null))
        assertEquals("AVOID", recommendationBucket("DO NOT BUY — AVOID", null))
        assertEquals("AVOID", recommendationBucket("AVOID", "STRONG BUY (option flow)"))
    }

    @Test
    fun holdVerdict_winsOverBuyDipsProse() {
        // "BUY DIPS" / "BUY THE PULLBACK" inside a HOLD verdict must
        // remain HOLD — a conditional buy is not an actionable buy today.
        assertEquals("HOLD", recommendationBucket("HOLD; BUY DIPS", null))
        assertEquals("HOLD", recommendationBucket("HOLD — BUY ON PULLBACK", null))
        assertEquals("HOLD", recommendationBucket(null, "Neutral; consider BUY below SMA50"))
    }

    @Test
    fun sellVerdict_winsOverConditionalBuyProse() {
        assertEquals("SELL", recommendationBucket("SELL — BUY ZONE far below current", null))
        assertEquals("SELL", recommendationBucket("Strong Sell", "BUY signal pending reversal"))
    }

    @Test
    fun isBuyRated_rejectsAllConditionalBuyProse() {
        // None of these should be auto-validated as buy candidates.
        assertFalse(isBuyRated("AVOID — STRONG BUY ZONE BELOW \$45", null))
        assertFalse(isBuyRated("DO NOT BUY — AVOID", null))
        assertFalse(isBuyRated("HOLD; BUY DIPS", null))
        assertFalse(isBuyRated("SELL — BUY ZONE far below", null))
        assertFalse(isBuyRated("OVERSOLD — wait", null))
    }

    @Test
    fun isStockAvoidOrSell_dropsExplicitNegativeVerdicts() {
        assertTrue(isStockAvoidOrSell("AVOID", null))
        assertTrue(isStockAvoidOrSell("Strong Sell", null))
        assertTrue(isStockAvoidOrSell("AVOID — BUY ZONE BELOW \$X", null))
        assertTrue(isStockAvoidOrSell(null, "SELL"))
    }

    @Test
    fun isStockAvoidOrSell_keepsBuyAndHold() {
        assertFalse(isStockAvoidOrSell("STRONG BUY", null))
        assertFalse(isStockAvoidOrSell("BUY", null))
        assertFalse(isStockAvoidOrSell("HOLD", null))
        assertFalse(isStockAvoidOrSell(null, null))
    }

    @Test
    fun hasBearishVeto_triggersWhenBearishDominates() {
        // Vetoed: >= 2 bearish AND strictly more bearish than bullish.
        assertTrue(hasBearishVeto(bullishCount = 0, bearishCount = 2))
        assertTrue(hasBearishVeto(bullishCount = 1, bearishCount = 3))
        assertTrue(hasBearishVeto(bullishCount = 2, bearishCount = 4))
    }

    @Test
    fun hasBearishVeto_doesNotTriggerOnBalancedOrBullish() {
        assertFalse(hasBearishVeto(bullishCount = 3, bearishCount = 2))
        assertFalse(hasBearishVeto(bullishCount = 2, bearishCount = 2)) // tie -> not vetoed
        assertFalse(hasBearishVeto(bullishCount = 0, bearishCount = 1)) // single bear -> not vetoed
        assertFalse(hasBearishVeto(bullishCount = 0, bearishCount = 0))
    }
}
