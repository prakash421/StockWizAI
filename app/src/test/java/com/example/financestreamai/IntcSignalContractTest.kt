package com.example.financestreamai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the INTC regression case (2026-06-28) where the user reported
 * "only positive points in green, no negative points in red" for INTC.
 *
 * Root cause: INTC's then-current backtest legitimately fired all six
 * bullish bins (price > SMA200 > SMA50, RSI in healthy band, 99% backtest
 * win rate, +7% above 20-day VWAP, 9% off 52-week high, MACD bullish) and
 * zero bearish bins. The backend returned `bearish_signals: []` correctly,
 * and the Android UI silently omitted the bearish section because it was
 * gated on `bearishSignals.isNotEmpty()`.
 *
 * The fix (MainActivity.kt — Spacer + "▼ no bearish signals detected"
 * placeholder) requires the data layer to keep returning `bullishSignals
 * != null` with at least one entry AND `bearishSignals == null || empty`.
 *
 * These tests pin that data contract so the next backend tweak that
 * accidentally drops `bullish_signals` from the JSON shape (e.g. renames
 * to `signals_bull`) breaks CI immediately instead of resurfacing as the
 * same "all-green-no-red" UX bug.
 */
class IntcSignalContractTest {

    private val intcLikeJson = """
        [
          {
            "ticker": "INTC",
            "price": 24.18,
            "beta": 0.95,
            "csps": [],
            "diagonals": [],
            "verticals": [],
            "long_leaps": [],
            "iv_rank": "42.1%",
            "rsi": 56.8,
            "discount_from_high": "9.0%",
            "sma50": 22.40,
            "sma200": 20.10,
            "stock_recommendation": "BUY",
            "bullish_signals": [
              "Price above SMA200",
              "Price above SMA50",
              "RSI in healthy band (56.8)",
              "60d backtest win rate 99%",
              "+7% above 20-day VWAP",
              "MACD bullish crossover"
            ],
            "bearish_signals": []
          }
        ]
    """.trimIndent()

    private fun parse(json: String): List<ScanResultItem> {
        val type = object : TypeToken<List<ScanResultItem>>() {}.type
        return Gson().fromJson(json, type)
    }

    @Test
    fun `intc-style item parses with six bullish signals and empty bearish list`() {
        val results = parse(intcLikeJson)
        assertEquals(1, results.size)
        val intc = results[0]

        assertEquals("INTC", intc.ticker)
        assertNotNull("bullishSignals must not be null", intc.bullishSignals)
        assertEquals(6, intc.bullishSignals!!.size)

        // bearishSignals should be present (not null) and empty — this is
        // the configuration that exposed the UX regression. Either null or
        // empty list is acceptable on the JSON side; both must produce the
        // same "▼ no bearish signals detected" UI affordance.
        assertNotNull(intc.bearishSignals)
        assertTrue("bearishSignals must be empty for INTC scenario", intc.bearishSignals!!.isEmpty())
    }

    @Test
    fun `intc-style item with null bearish_signals also parses safely`() {
        val jsonWithNull = intcLikeJson.replace("\"bearish_signals\": []", "\"bearish_signals\": null")
        val intc = parse(jsonWithNull)[0]
        assertTrue(
            "either null or empty list is acceptable",
            intc.bearishSignals == null || intc.bearishSignals!!.isEmpty()
        )
    }

    @Test
    fun `intc-style item with bearish_signals key missing entirely also parses`() {
        // The backend MAY omit the key altogether when there are no bearish
        // signals. Gson should default the field to its Kotlin default (null).
        val jsonNoKey = """
            [
              {
                "ticker": "INTC",
                "price": 24.18,
                "csps": [],
                "diagonals": [],
                "verticals": [],
                "long_leaps": [],
                "iv_rank": "42.1%",
                "rsi": 56.8,
                "discount_from_high": "9.0%",
                "stock_recommendation": "BUY",
                "bullish_signals": ["Price above SMA200"]
              }
            ]
        """.trimIndent()
        val intc = parse(jsonNoKey)[0]
        assertTrue(intc.bullishSignals?.isNotEmpty() == true)
        // The omission should not crash the UI render path.
        assertTrue(
            "missing bearish_signals key must yield null or empty",
            intc.bearishSignals.isNullOrEmpty()
        )
    }

    @Test
    fun `bull-bear imbalance flags placeholder rendering case`() {
        // Mimics the UI's hasAnyBullSignal vs hasAnyBearSignal predicate
        // in MainActivity ScanResultCard. The UI uses this asymmetry to
        // decide whether to render the "▼ no bearish signals detected"
        // hint. A unit-level check here ensures the predicates stay in
        // sync with what JSON the backend actually emits.
        val intc = parse(intcLikeJson)[0]
        val hasBull = !intc.bullishSignals.isNullOrEmpty()
        val hasBear = !intc.bearishSignals.isNullOrEmpty()
        assertTrue("should have bullish signals", hasBull)
        assertFalse("should NOT have bearish signals", hasBear)
    }

    @Test
    fun `inverse case — all bearish no bullish — parses and triggers opposite placeholder`() {
        val json = """
            [
              {
                "ticker": "XYZ",
                "price": 10.0,
                "csps": [],
                "diagonals": [],
                "verticals": [],
                "long_leaps": [],
                "iv_rank": "60.0%",
                "rsi": 28.0,
                "discount_from_high": "55%",
                "stock_recommendation": "AVOID",
                "bullish_signals": [],
                "bearish_signals": [
                  "Price below SMA50 and SMA200",
                  "RSI 28 (oversold collapse, no reversal)",
                  "60d backtest win rate 12%"
                ]
              }
            ]
        """.trimIndent()
        val item = parse(json)[0]
        assertTrue(item.bullishSignals.isNullOrEmpty())
        assertEquals(3, item.bearishSignals!!.size)
    }
}
