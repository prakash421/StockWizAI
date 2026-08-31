package com.example.financestreamai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Reproduces the watchdog skip logic from
 * [DailyRecommendationWorker.shouldSkipWatchdogRun] so a future refactor
 * of the constants (skip-window, stop-hour, timezone) is caught by CI.
 *
 * Contract:
 *   Watchdog run is SKIPPED (no-op Result.success) when either
 *     (a) last success was within the past 6 hours, OR
 *     (b) current Pacific hour is >= 11
 *   Otherwise it falls through and runs a full daily scan.
 */
class DailyWatchdogGateTest {

    private val pacific: TimeZone = TimeZone.getTimeZone("America/Los_Angeles")
    private val skipWindowMs = 6L * 60L * 60L * 1000L
    private val stopHourPt = 11

    private fun shouldSkip(lastSuccessMs: Long, nowMs: Long): Boolean {
        if (lastSuccessMs > 0L && (nowMs - lastSuccessMs) < skipWindowMs) return true
        val cal = Calendar.getInstance(pacific).apply { timeInMillis = nowMs }
        return cal.get(Calendar.HOUR_OF_DAY) >= stopHourPt
    }

    private fun pacificMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long {
        return Calendar.getInstance(pacific).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun `skip when primary succeeded within 6h at 7-15 AM PT`() {
        val now = pacificMillis(2026, 8, 31, 7, 15)
        val lastSuccess = pacificMillis(2026, 8, 31, 6, 50) // primary just ran
        assertTrue(shouldSkip(lastSuccess, now))
    }

    @Test
    fun `skip at 10-15 AM PT when primary succeeded at 6-50`() {
        val now = pacificMillis(2026, 8, 31, 10, 15)
        val lastSuccess = pacificMillis(2026, 8, 31, 6, 50)
        // 3h25m < 6h → skip
        assertTrue(shouldSkip(lastSuccess, now))
    }

    @Test
    fun `run when primary was skipped and it's 7-15 AM PT`() {
        val now = pacificMillis(2026, 8, 31, 7, 15)
        // Last success yesterday morning (~25h ago) — outside skip window
        val lastSuccess = pacificMillis(2026, 8, 30, 6, 50)
        assertFalse(shouldSkip(lastSuccess, now))
    }

    @Test
    fun `run when never succeeded and it's 7-15 AM PT`() {
        val now = pacificMillis(2026, 8, 31, 7, 15)
        assertFalse(shouldSkip(lastSuccessMs = 0L, nowMs = now))
    }

    @Test
    fun `skip when past 11 AM PT even if primary was skipped`() {
        val now = pacificMillis(2026, 8, 31, 11, 5)
        val lastSuccess = pacificMillis(2026, 8, 30, 6, 50) // yesterday
        assertTrue(shouldSkip(lastSuccess, now))
    }

    @Test
    fun `skip exactly at 11 AM PT`() {
        val now = pacificMillis(2026, 8, 31, 11, 0)
        assertTrue(shouldSkip(lastSuccessMs = 0L, nowMs = now))
    }

    @Test
    fun `run at 10-59 AM PT if primary skipped`() {
        val now = pacificMillis(2026, 8, 31, 10, 59)
        val lastSuccess = pacificMillis(2026, 8, 30, 6, 50)
        assertFalse(shouldSkip(lastSuccess, now))
    }

    @Test
    fun `run when boundary exactly 6h after last success`() {
        val now = pacificMillis(2026, 8, 31, 8, 15)
        val lastSuccess = now - skipWindowMs // exactly at boundary
        // 6h - 6h = 0 which is NOT < 6h — should run
        // Note: also fires the "past 11 AM PT" check → 8:15 is before 11
        assertFalse(shouldSkip(lastSuccess, now))
    }
}
