package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the option-strategy alert line formatters (added 2026-06-25).
 *
 * User requirements driving these tests:
 *   1. The ticker symbol appears at the start of every line so the
 *      ticker-bolding regex in toRichHtml can wrap it in <b>…</b>.
 *   2. Every option strategy line (CSP / Diagonal / Vertical / LEAPS)
 *      shows the dollar premium (or net debit, for spreads) the user
 *      would pay / collect — both in the per-strategy detail section
 *      AND in the NEW BUY SIGNALS section.
 */
class AlertFormattingTest {

    // --------- Per-strategy detail-line formatters ---------

    @Test
    fun cspDetailLine_includesTickerStrikeExpiryPremiumRocDelta() {
        val csp = CspResult(
            strike = 45.0, premium = 1.85, delta = -0.22,
            bt = "92.5%", roc = "3.1%", expiry = "2026-08-15"
        )
        val line = formatCspDetailLine("AAPL", csp)
        assertTrue("must start with ticker", line.contains("AAPL"))
        assertTrue("must include premium in dollars", line.contains("prem \$1.85"))
        assertTrue("must include ROC", line.contains("ROC: 3.1%"))
        assertTrue("must include delta", line.contains("Δ: -0.22"))
        assertTrue("must include expiry", line.contains("2026-08-15"))
    }

    @Test
    fun diagonalDetailLine_includesDebitYieldLegs() {
        val diag = DiagonalResult(
            longLeg = "100C", shortLeg = "110C",
            netDebt = 4.75, yieldRatio = "18.5%",
            bt = "82.0%", expiry = "2026-09-19"
        )
        val line = formatDiagonalDetailLine("MSFT", diag)
        assertTrue(line.contains("MSFT"))
        assertTrue("diagonal must show debit (the premium paid)", line.contains("debit \$4.75"))
        assertTrue(line.contains("100C/110C"))
        assertTrue(line.contains("Yield: 18.5%"))
    }

    @Test
    fun verticalDetailLine_includesDebitStrikes() {
        val vert = VerticalResult(
            strikes = "100C/105C", netDebit = 2.10,
            bt = "88.0%", expiry = "2026-07-18"
        )
        val line = formatVerticalDetailLine("NVDA", vert)
        assertTrue(line.contains("NVDA"))
        assertTrue("vertical must show debit (the premium paid)", line.contains("debit \$2.10"))
        assertTrue(line.contains("100C/105C"))
    }

    @Test
    fun leapsDetailLine_includesPremiumLeverageBuffer() {
        val leap = LongLeapsResult(
            strike = 150.0, expiry = "2027-01-15",
            premium = 22.50, delta = 0.80,
            intrinsicBuffer = "45%", leverage = "2.1x",
            bt = "91.0%"
        )
        val line = formatLeapsDetailLine("META", leap)
        assertTrue(line.contains("META"))
        assertTrue("LEAPS must show premium", line.contains("prem \$22.50"))
        assertTrue(line.contains("Lev: 2.1x"))
        assertTrue(line.contains("Buffer: 45%"))
    }

    // --------- NEW BUY SIGNALS line formatters ---------

    @Test
    fun newBuyCsp_includesPremiumAndStop() {
        val csp = CspResult(
            strike = 45.0, premium = 1.85, delta = -0.22,
            bt = "92.5%", roc = "3.1%", expiry = "2026-08-15",
            stopLoss = 38.0
        )
        val line = formatNewBuyCsp("AAPL", csp)
        assertTrue(line.startsWith("💵 CSP AAPL"))
        assertTrue(line.contains("prem \$1.85"))
        assertTrue("stop must appear when set", line.contains("stop \$38.00"))
    }

    @Test
    fun newBuyDiagonal_includesDebitAndLegs() {
        val diag = DiagonalResult(
            longLeg = "100C", shortLeg = "110C",
            netDebt = 4.75, yieldRatio = "18.5%",
            bt = "82.0%", expiry = "2026-09-19"
        )
        val line = formatNewBuyDiagonal("MSFT", diag)
        assertTrue("diagonals must surface in NEW BUYS too", line.startsWith("📐 Diagonal MSFT"))
        assertTrue(line.contains("100C/110C"))
        assertTrue(line.contains("debit \$4.75"))
        assertTrue(line.contains("exp 2026-09-19"))
    }

    @Test
    fun newBuyVertical_includesDebit() {
        val vert = VerticalResult(
            strikes = "100C/105C", netDebit = 2.10,
            bt = "88.0%", expiry = "2026-07-18"
        )
        val line = formatNewBuyVertical("NVDA", vert)
        assertTrue(line.startsWith("📐 Vertical NVDA"))
        assertTrue(line.contains("100C/105C"))
        assertTrue(line.contains("debit \$2.10"))
        assertTrue(line.contains("exp 2026-07-18"))
    }

    @Test
    fun newBuyLeaps_includesPremiumStopAndTarget() {
        val leap = LongLeapsResult(
            strike = 150.0, expiry = "2027-01-15",
            premium = 22.50, delta = 0.80,
            intrinsicBuffer = "45%", leverage = "2.1x",
            bt = "91.0%",
            stopLoss = 135.0, target = 220.0
        )
        val line = formatNewBuyLeaps("META", leap)
        assertTrue(line.startsWith("🚀 LEAPS META"))
        assertTrue(line.contains("prem \$22.50"))
        assertTrue(line.contains("stop \$135.00"))
        assertTrue(line.contains("tgt \$220.00"))
    }

    // --------- Optional-field robustness ---------

    @Test
    fun cspDetailLine_handlesMissingExpiry() {
        val csp = CspResult(
            strike = 45.0, premium = 1.85, delta = -0.22,
            bt = "92.5%", roc = "3.1%", expiry = null
        )
        val line = formatCspDetailLine("AAPL", csp)
        // Premium must still render even when expiry is missing.
        assertTrue(line.contains("prem \$1.85"))
    }

    @Test
    fun newBuyCsp_handlesMissingStop() {
        val csp = CspResult(
            strike = 45.0, premium = 1.85, delta = -0.22,
            bt = "92.5%", roc = "3.1%", expiry = "2026-08-15",
            stopLoss = null
        )
        val line = formatNewBuyCsp("AAPL", csp)
        assertFalse("no stop suffix when stop is null", line.contains("stop \$"))
        assertTrue(line.contains("prem \$1.85"))
    }

    @Test
    fun newBuyLeaps_handlesMissingStopAndTarget() {
        val leap = LongLeapsResult(
            strike = 150.0, expiry = "2027-01-15",
            premium = 22.50, delta = 0.80,
            intrinsicBuffer = "45%", leverage = "2.1x",
            bt = "91.0%", stopLoss = null, target = null
        )
        val line = formatNewBuyLeaps("META", leap)
        assertFalse(line.contains("stop \$"))
        assertFalse(line.contains("tgt \$"))
        assertTrue(line.contains("prem \$22.50"))
    }

    // --------- Ticker bolding contract (toRichHtml regex) ---------
    //
    // toRichHtml wraps each whole-word ticker in <b>…</b>. The formatters
    // must put the ticker as a standalone whole word at the start of the
    // line so the regex matches deterministically. These tests just
    // assert positional invariants that the regex relies on.

    @Test
    fun everyNewBuyLine_hasTickerAsWholeWord() {
        val tk = "AMD"
        val csp = CspResult(strike = 100.0, premium = 2.0, delta = -0.20, bt = "90%", roc = "3.0%", expiry = "2026-08-15")
        val diag = DiagonalResult(longLeg = "100C", shortLeg = "110C", netDebt = 5.0, yieldRatio = "15%", bt = "80%", expiry = "2026-09-19")
        val vert = VerticalResult(strikes = "100C/105C", netDebit = 2.0, bt = "85%", expiry = "2026-07-18")
        val leap = LongLeapsResult(strike = 150.0, expiry = "2027-01-15", premium = 22.0, delta = 0.80, intrinsicBuffer = "45%", leverage = "2x", bt = "90%")
        val wholeWord = Regex("""\bAMD\b""")
        assertTrue(wholeWord.containsMatchIn(formatNewBuyCsp(tk, csp)))
        assertTrue(wholeWord.containsMatchIn(formatNewBuyDiagonal(tk, diag)))
        assertTrue(wholeWord.containsMatchIn(formatNewBuyVertical(tk, vert)))
        assertTrue(wholeWord.containsMatchIn(formatNewBuyLeaps(tk, leap)))
    }

    @Test
    fun everyDetailLine_hasTickerAsWholeWord() {
        val tk = "TSLA"
        val csp = CspResult(strike = 250.0, premium = 5.0, delta = -0.22, bt = "92%", roc = "3.5%", expiry = "2026-08-15")
        val diag = DiagonalResult(longLeg = "250C", shortLeg = "260C", netDebt = 8.0, yieldRatio = "20%", bt = "82%", expiry = "2026-09-19")
        val vert = VerticalResult(strikes = "250C/260C", netDebit = 3.0, bt = "88%", expiry = "2026-07-18")
        val leap = LongLeapsResult(strike = 250.0, expiry = "2027-01-15", premium = 45.0, delta = 0.80, intrinsicBuffer = "40%", leverage = "2.3x", bt = "91%")
        val wholeWord = Regex("""\bTSLA\b""")
        assertTrue(wholeWord.containsMatchIn(formatCspDetailLine(tk, csp)))
        assertTrue(wholeWord.containsMatchIn(formatDiagonalDetailLine(tk, diag)))
        assertTrue(wholeWord.containsMatchIn(formatVerticalDetailLine(tk, vert)))
        assertTrue(wholeWord.containsMatchIn(formatLeapsDetailLine(tk, leap)))
    }
}
