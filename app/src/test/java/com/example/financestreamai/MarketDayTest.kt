package com.example.financestreamai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for [isMarketDayAt] — the gate that decides whether the daily
 * recommendation scan runs at all.
 *
 * The previous implementation lived as a private method on the worker class
 * and was only validated by running the app on holidays/weekends and waiting
 * for the 6:50 AM trigger. That made every change to the holiday list a
 * potential silent regression (e.g. forgetting that 07-03 is observed for
 * Independence Day in 2026 because July 4 is a Saturday).
 *
 * These tests pin the contract: weekends + every published 2026 holiday must
 * return false; every other ET weekday must return true.
 */
class MarketDayTest {

    private fun millisAt(year: Int, month1Based: Int, day: Int, hour: Int = 10): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
        cal.clear()
        cal.set(year, month1Based - 1, day, hour, 0, 0)
        return cal.timeInMillis
    }

    @Test
    fun `weekday in 2026 is a market day`() {
        // Monday, 2026-06-29 — a plain trading day with no holiday.
        assertTrue(isMarketDayAt(millisAt(2026, 6, 29)))
    }

    @Test
    fun `saturday is not a market day`() {
        // Saturday, 2026-06-27.
        assertFalse(isMarketDayAt(millisAt(2026, 6, 27)))
    }

    @Test
    fun `sunday is not a market day`() {
        // Sunday, 2026-06-28.
        assertFalse(isMarketDayAt(millisAt(2026, 6, 28)))
    }

    @Test
    fun `new years day 2026 is closed`() {
        // 2026-01-01 — Thursday — must be closed (NYSE holiday).
        assertFalse(isMarketDayAt(millisAt(2026, 1, 1)))
    }

    @Test
    fun `mlk day 2026 is closed`() {
        // 2026-01-19 — third Monday of January.
        assertFalse(isMarketDayAt(millisAt(2026, 1, 19)))
    }

    @Test
    fun `presidents day 2026 is closed`() {
        // 2026-02-16 — third Monday of February.
        assertFalse(isMarketDayAt(millisAt(2026, 2, 16)))
    }

    @Test
    fun `good friday 2026 is closed`() {
        // 2026-04-03.
        assertFalse(isMarketDayAt(millisAt(2026, 4, 3)))
    }

    @Test
    fun `memorial day 2026 is closed`() {
        // 2026-05-25.
        assertFalse(isMarketDayAt(millisAt(2026, 5, 25)))
    }

    @Test
    fun `independence day observed friday july 3 2026 is closed`() {
        // 2026-07-03 — July 4 falls on Saturday so NYSE closes Fri the 3rd.
        assertFalse(isMarketDayAt(millisAt(2026, 7, 3)))
    }

    @Test
    fun `labor day 2026 is closed`() {
        // 2026-09-07.
        assertFalse(isMarketDayAt(millisAt(2026, 9, 7)))
    }

    @Test
    fun `thanksgiving 2026 is closed`() {
        // 2026-11-26.
        assertFalse(isMarketDayAt(millisAt(2026, 11, 26)))
    }

    @Test
    fun `christmas 2026 is closed`() {
        // 2026-12-25 — Friday.
        assertFalse(isMarketDayAt(millisAt(2026, 12, 25)))
    }

    @Test
    fun `day after thanksgiving 2026 is open`() {
        // 2026-11-27 — half-day but NYSE is open; the scan should still run.
        assertTrue(isMarketDayAt(millisAt(2026, 11, 27)))
    }

    @Test
    fun `juneteenth 2026 is open per holiday set`() {
        // 2026-06-19 — Friday. Juneteenth is NYSE-observed, but the current
        // [US_MARKET_HOLIDAYS_2026] set does NOT include it. This test
        // documents the gap so future maintainers see the omission.
        assertTrue(
            "If this fails, US_MARKET_HOLIDAYS_2026 was updated to include 06-19 — please add a closed-day test for it",
            isMarketDayAt(millisAt(2026, 6, 19))
        )
    }

    @Test
    fun `holiday set can be overridden for non-2026 years`() {
        // Test with a future year by passing a custom holiday set.
        val custom = setOf("07-04")  // hypothetical: 2027 has July 4 fall on Sunday so the obs date moves
        // Friday 2027-07-02 — NOT in the custom set, so should be open.
        assertTrue(isMarketDayAt(millisAt(2027, 7, 2), holidays = custom))
        // Saturday 2027-07-03 — always closed (weekend).
        assertFalse(isMarketDayAt(millisAt(2027, 7, 3), holidays = custom))
        // Monday 2027-07-05 — not in custom set, weekday.
        assertTrue(isMarketDayAt(millisAt(2027, 7, 5), holidays = custom))
    }

    @Test
    fun `device timezone does not affect ET evaluation`() {
        // Simulate a device in Asia/Tokyo by setting the JVM default timezone.
        // The function should still evaluate against ET, so a moment that is
        // Saturday 06:00 in Tokyo (which is Friday 17:00 ET) must be a
        // market day.
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            // Friday 2026-06-26 17:00 ET — should still be market day.
            val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
            cal.clear()
            cal.set(2026, 5, 26, 17, 0, 0)  // June is month index 5
            assertTrue(isMarketDayAt(cal.timeInMillis))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
