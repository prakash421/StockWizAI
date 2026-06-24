package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [parseAlertBodyFromPlain] — the pure-text version of the
 * notification body parser used by NotificationCard to render collapsible
 * sections + per-recommendation blocks.
 *
 * These tests document the alert format CONTRACT so future regressions
 * (e.g. server-side worker tweaks that drop a colon or change indentation)
 * fail fast in CI instead of silently flattening the UI back to a
 * wall-of-text.
 */
class AlertParserTest {

    @Test
    fun `typical multi-section body produces one section per emoji header`() {
        val body = """
            🛡️ ETF Watch (2):
              • SPY +1.2% trend up
              • QQQ -0.4% trend down
            📊 CSPs (1):
              • AAPL 185 strike 7d expiry
        """.trimIndent()

        val parsed = parseAlertBodyFromPlain(body)

        assertEquals("", parsed.preamble)
        assertEquals(2, parsed.sections.size)
        assertEquals("🛡️ ETF Watch (2)", parsed.sections[0].header)
        assertEquals("📊 CSPs (1)", parsed.sections[1].header)
        // Each bullet should become its own block (visual separation requirement).
        assertEquals(2, parsed.sections[0].blocks.size)
        assertEquals(1, parsed.sections[1].blocks.size)
    }

    @Test
    fun `empty body returns empty preamble and no sections`() {
        val parsed = parseAlertBodyFromPlain("")
        assertEquals("", parsed.preamble)
        assertTrue(parsed.sections.isEmpty())
    }

    @Test
    fun `preamble-only body keeps text and emits no sections`() {
        val body = "Today's scan complete. No actionable signals."
        val parsed = parseAlertBodyFromPlain(body)
        assertEquals(body, parsed.preamble)
        assertTrue(parsed.sections.isEmpty())
    }

    @Test
    fun `legacy plain text without emoji headers falls through to preamble`() {
        val body = """
            BUY signal triggered on AAPL.
            Stop loss at 178. Target 195.
            Holding period: 5 days.
        """.trimIndent()

        val parsed = parseAlertBodyFromPlain(body)

        // No emoji headers => everything is preamble; UI renders verbatim.
        assertEquals(body, parsed.preamble)
        assertTrue(
            "legacy bodies must not be split into phantom sections",
            parsed.sections.isEmpty()
        )
    }

    @Test
    fun `nested check and cross sub-headers split into separate blocks`() {
        val body = """
            ⚖️ Reward-Risk Leaders (4):
              ✅ Best to BUY (high R:R):
                • NVDA — R:R 3.2, trend up
                • AMD — R:R 2.8, trend up
              ❌ Worst to AVOID/SELL (low R:R):
                • INTC — R:R 0.6, trend down
                • F — R:R 0.7, trend down
        """.trimIndent()

        val parsed = parseAlertBodyFromPlain(body)

        assertEquals(1, parsed.sections.size)
        val section = parsed.sections[0]
        assertEquals("⚖️ Reward-Risk Leaders (4)", section.header)
        // ✅ and ❌ sub-headers must yield two distinct blocks (the whole
        // point of the redesign — visually pair each sub-group with its rows).
        assertEquals(2, section.blocks.size)
        assertTrue(section.blocks[0].headline.startsWith("✅"))
        assertTrue(section.blocks[1].headline.startsWith("❌"))
        // Each sub-group should keep its detail rows.
        assertTrue(section.blocks[0].lines.any { it.contains("NVDA") })
        assertTrue(section.blocks[0].lines.any { it.contains("AMD") })
        assertTrue(section.blocks[1].lines.any { it.contains("INTC") })
        assertTrue(section.blocks[1].lines.any { it.contains("F ") || it.endsWith("F") || it.contains("F —") })
    }

    @Test
    fun `deeply indented detail lines stay attached to their parent block`() {
        val body = """
            🎯 Analyst Target Changes (1):
              • TSLA
                    📈 RSI 58 • MACD bullish • EMA20 above
                    🛡️ Stop 240 • Target 295
        """.trimIndent()

        val parsed = parseAlertBodyFromPlain(body)

        assertEquals(1, parsed.sections.size)
        val blocks = parsed.sections[0].blocks
        assertEquals(1, blocks.size)
        // All three lines (bullet + 2 detail rows) live in the same block —
        // never split off into the next section.
        assertTrue("expected detail rows attached", blocks[0].lines.size >= 3)
        assertTrue(blocks[0].lines[0].trim().startsWith("• TSLA"))
    }

    @Test
    fun `section with no body still appears with zero blocks`() {
        val body = """
            🛑 Stop-Loss Alert (0):
            ⚠️ Skipped: AAA, BBB
        """.trimIndent()

        val parsed = parseAlertBodyFromPlain(body)

        // Both lines are valid top-level headers (emoji + colon at column 0).
        assertEquals(2, parsed.sections.size)
        assertEquals("🛑 Stop-Loss Alert (0)", parsed.sections[0].header)
        // Empty section is allowed (collapsed UI just shows header + 0 badge).
        assertEquals(0, parsed.sections[0].blocks.size)
    }

    @Test
    fun `preamble text before first emoji header is preserved verbatim`() {
        val body = """
            Pre-market scan complete.
            🛡️ ETF Watch (1):
              • SPY trend up
        """.trimIndent()

        val parsed = parseAlertBodyFromPlain(body)

        assertEquals("Pre-market scan complete.", parsed.preamble)
        assertEquals(1, parsed.sections.size)
        assertEquals("🛡️ ETF Watch (1)", parsed.sections[0].header)
    }

    @Test
    fun `blank lines split adjacent bullets into separate blocks`() {
        val body = """
            📊 CSPs (3):
              • AAPL 185
              • MSFT 350

              • TSLA 240
        """.trimIndent()

        val parsed = parseAlertBodyFromPlain(body)

        // Blank line in the middle = block boundary regardless of bullet style.
        val blocks = parsed.sections[0].blocks
        assertTrue("expected at least 2 blocks due to blank separator", blocks.size >= 2)
    }
}
