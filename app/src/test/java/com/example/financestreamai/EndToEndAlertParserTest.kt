package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end-style tests for [parseAlertBodyFromPlain] using realistic
 * notification bodies that the daily/hourly workers actually produce.
 *
 * Most other AlertParser tests poke at one feature at a time. These
 * tests fuse multiple sections, sub-headers, indented detail rows, and
 * preamble text into a single body — the same shape the user sees in a
 * production notification. They catch interactions that single-feature
 * tests miss (e.g. blank-line handling colliding with sub-header
 * grouping; emoji-prefix detail rows being misclassified as headers).
 */
class EndToEndAlertParserTest {

    @Test
    fun `realistic daily scan body parses into expected section count`() {
        val body = """
            Scanned 42 of 42 symbols. 2 dropped.
            🛡️ ETF Watch (3):
              • SOXX +1.2% trend up
              • DRAM -0.4% trend down
              • AIPO unchanged
            📊 CSPs (2):
              • AAPL 185 strike — prem ${"$"}1.50 — ROC 3%
              • MSFT 350 strike — prem ${"$"}2.10 — ROC 2.5%
            📐 Diagonals (1):
              • NVDA 200/210 — debit ${"$"}5.20 — yield 12%
            ⚖️ Reward-Risk Leaders (4):
              ✅ Best to BUY (high R:R):
                • NVDA — R:R 3.2, trend up
                • AMD — R:R 2.8, trend up
              ❌ Worst to AVOID/SELL (low R:R):
                • INTC — R:R 0.6, trend down
                • F — R:R 0.7, trend down
            🛑 Stop-Loss Alert (1):
              • TSLA — broke 240 stop
            ⚠️ Skipped: AAA, BBB
            🔍 Gemini gate: all picks approved.
        """.trimIndent()

        val parsed = parseAlertBodyFromPlain(body)

        // Preamble survives intact.
        assertEquals("Scanned 42 of 42 symbols. 2 dropped.", parsed.preamble)

        // Seven sections (ETF, CSPs, Diagonals, R-R Leaders, Stop-Loss,
        // Skipped, Gemini gate).
        assertEquals(7, parsed.sections.size)

        // Section ordering preserved.
        assertEquals("🛡️ ETF Watch (3)", parsed.sections[0].header)
        assertEquals("📊 CSPs (2)", parsed.sections[1].header)
        assertEquals("📐 Diagonals (1)", parsed.sections[2].header)
        assertEquals("⚖️ Reward-Risk Leaders (4)", parsed.sections[3].header)
        assertEquals("🛑 Stop-Loss Alert (1)", parsed.sections[4].header)
        assertTrue(
            "expected mid-line-colon header to be detected — see AlertParserTest line 132 contract",
            parsed.sections[5].header.startsWith("⚠️ Skipped")
        )
        assertTrue(parsed.sections[6].header.startsWith("🔍 Gemini gate"))

        // R/R Leaders should fold the four bullets into 2 sub-header blocks.
        val rrSection = parsed.sections[3]
        assertEquals(2, rrSection.blocks.size)
        assertTrue(rrSection.blocks[0].headline.startsWith("✅"))
        assertTrue(rrSection.blocks[1].headline.startsWith("❌"))
    }

    @Test
    fun `empty section with skipped tail still parses`() {
        // Mirrors the failing AlertParserTest case at line 132. A notification
        // can carry a "(0):" header with no rows immediately followed by a
        // skipped-symbols footer.
        val body = """
            🛑 Stop-Loss Alert (0):
            ⚠️ Skipped: AAA, BBB
        """.trimIndent()

        val parsed = parseAlertBodyFromPlain(body)

        assertEquals(2, parsed.sections.size)
        // First section header is the empty Stop-Loss banner.
        assertEquals("🛑 Stop-Loss Alert (0)", parsed.sections[0].header)
        assertEquals(0, parsed.sections[0].blocks.size)
        // Second section is the Skipped line itself — colon mid-line + content.
        assertTrue(parsed.sections[1].header.startsWith("⚠️ Skipped"))
    }

    @Test
    fun `signal lines that contain a colon mid-text are not falsely detected as headers`() {
        // Detail rows in production use emojis like 📈 RSI 58 • MACD bullish
        // — these are INDENTED, so even though they contain ":" mid-line
        // they must not be treated as top-level headers (would split the
        // owning block in two).
        val body = """
            🎯 Analyst Target Changes (1):
              • TSLA
                    📈 RSI: 58 • MACD: bullish • EMA20 above
                    🛡️ Stop: 240 • Target: 295
        """.trimIndent()

        val parsed = parseAlertBodyFromPlain(body)

        // Exactly one top-level section, with one block holding all 3 lines.
        assertEquals(1, parsed.sections.size)
        val blocks = parsed.sections[0].blocks
        assertEquals(1, blocks.size)
        assertTrue("expected 3 lines under TSLA bullet", blocks[0].lines.size >= 3)
    }

    @Test
    fun `worker formatter output with leaps line is NOT misclassified as header`() {
        // Output of [formatNewBuyLeaps] starts with 🚀 and includes parens
        // and dollar amounts but has no colon. The parser must classify it
        // as a bullet within whatever section it sits in, never as a new
        // top-level section. Same invariant for the diagonal formatter.
        val body = """
            📢 NEW BUY SIGNALS (2):
              🚀 LEAPS NVDA ${"$"}200.00 (exp 2026-09-19, prem ${"$"}12.50) stop ${"$"}180.00 tgt ${"$"}250.00
              📐 Diagonal MSFT 400C/410C (exp 2026-09-19, debit ${"$"}5.25) stop ${"$"}395.00
        """.trimIndent()

        val parsed = parseAlertBodyFromPlain(body)
        assertEquals(1, parsed.sections.size)
        assertEquals("📢 NEW BUY SIGNALS (2)", parsed.sections[0].header)
    }

    @Test
    fun `mixed legacy and emoji-section body keeps legacy text as preamble`() {
        val body = """
            Trade idea — buy AAPL when it pulls back to 180.
            Holding period 5-10 days.

            📊 CSPs (1):
              • AAPL 185
        """.trimIndent()

        val parsed = parseAlertBodyFromPlain(body)
        assertTrue(parsed.preamble.startsWith("Trade idea"))
        assertEquals(1, parsed.sections.size)
    }

    @Test
    fun `gemini independent picks section parses cleanly`() {
        // The Gemini advisor section uses 🤖 with parenthetical detail —
        // mirrors what daily-scan notifications actually contain.
        val body = """
            🤖 Gemini's independent picks (next 4–12 weeks):
              1. NVDA [HIGH] ⭐ also in our picks
                 Strong AI tailwind, multiple expansion ongoing.
              2. GOOGL [MEDIUM] 🆕 not in backend list
                 Cheap relative to peers; cloud margin inflection.
              → 1/2 agree with backend (high conviction overlap).
        """.trimIndent()

        val parsed = parseAlertBodyFromPlain(body)
        assertEquals(1, parsed.sections.size)
        val section = parsed.sections[0]
        assertNotNull(section)
        assertTrue(section.blocks.isNotEmpty())
    }
}
