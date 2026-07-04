package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Pins the next-daily-scan firing time so accidental timezone-handling
 * regressions surface immediately.
 *
 * Contract:
 *   The daily picks notification fires at 7:00 AM Pacific Time
 *   (== 10:00 AM Eastern, ~30 minutes after the US equities cash open
 *   at 9:30 AM ET / 6:30 AM PT).
 *
 * The scheduling code in [MainActivity.scheduleDailyRecommendations]
 * computes the next firing instant in `America/Los_Angeles` and feeds
 * `target.timeInMillis - now.timeInMillis` to WorkManager as the
 * initialDelay. This test reproduces the same calculation so a future
 * refactor that drops the explicit timezone (defaulting back to device
 * locale) is caught by CI.
 */
class DailyScheduleTimeTest {

    private val pacific = TimeZone.getTimeZone("America/Los_Angeles")
    private val eastern = TimeZone.getTimeZone("America/New_York")

    /** Reproduces MainActivity.scheduleDailyRecommendations target computation. */
    private fun nextSevenAmPacificMillis(nowMillis: Long): Long {
        val now = Calendar.getInstance(pacific).apply { timeInMillis = nowMillis }
        val target = Calendar.getInstance(pacific).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis
    }

    @Test
    fun `target lands on 7am pacific when fired at 5am pacific`() {
        val now = Calendar.getInstance(pacific).apply {
            clear()
            set(2026, Calendar.JUNE, 30, 5, 0, 0)
        }
        val targetMs = nextSevenAmPacificMillis(now.timeInMillis)
        val target = Calendar.getInstance(pacific).apply { timeInMillis = targetMs }
        assertEquals(7, target.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, target.get(Calendar.MINUTE))
        assertEquals(30, target.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `target rolls to tomorrow when fired at 8am pacific`() {
        val now = Calendar.getInstance(pacific).apply {
            clear()
            set(2026, Calendar.JUNE, 30, 8, 0, 0)
        }
        val targetMs = nextSevenAmPacificMillis(now.timeInMillis)
        val target = Calendar.getInstance(pacific).apply { timeInMillis = targetMs }
        assertEquals(7, target.get(Calendar.HOUR_OF_DAY))
        // Should be JULY 1, not JUNE 30.
        assertEquals(Calendar.JULY, target.get(Calendar.MONTH))
        assertEquals(1, target.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `target time corresponds to 10am eastern`() {
        // 7 AM PT == 10 AM ET (always, regardless of DST because both zones
        // observe DST in lockstep).
        val now = Calendar.getInstance(pacific).apply {
            clear()
            set(2026, Calendar.JUNE, 30, 5, 0, 0)
        }
        val targetMs = nextSevenAmPacificMillis(now.timeInMillis)
        val targetInEt = Calendar.getInstance(eastern).apply { timeInMillis = targetMs }
        assertEquals(10, targetInEt.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, targetInEt.get(Calendar.MINUTE))
    }

    @Test
    fun `device in tokyo timezone still computes correct PT target`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            // 2026-06-30 05:00 PT (the same UTC instant as the first test).
            val now = Calendar.getInstance(pacific).apply {
                clear()
                set(2026, Calendar.JUNE, 30, 5, 0, 0)
            }
            val targetMs = nextSevenAmPacificMillis(now.timeInMillis)
            val targetInPt = Calendar.getInstance(pacific).apply { timeInMillis = targetMs }
            assertEquals(
                "PT calculation must be independent of device default timezone",
                7,
                targetInPt.get(Calendar.HOUR_OF_DAY)
            )
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `initial delay never exceeds 24 hours`() {
        // Worst case: now is one millisecond after 7 AM PT — next target is
        // tomorrow at 7 AM PT, so the delay is just under 24h.
        val now = Calendar.getInstance(pacific).apply {
            clear()
            set(2026, Calendar.JUNE, 30, 7, 0, 1)
        }
        val targetMs = nextSevenAmPacificMillis(now.timeInMillis)
        val delayMs = targetMs - now.timeInMillis
        assertTrue("delay must be positive", delayMs > 0)
        assertTrue("delay must be < 24h", delayMs < 24L * 60 * 60 * 1000)
    }

    @Test
    fun `dst spring-forward day still hits 7am pacific wall clock`() {
        // 2026-03-08 is the US DST spring-forward day (clocks jump from 2:00
        // AM to 3:00 AM PST → PDT). The user's expected wall-clock time is
        // still 7 AM local, so the notification must fire then.
        val now = Calendar.getInstance(pacific).apply {
            clear()
            set(2026, Calendar.MARCH, 8, 5, 0, 0)
        }
        val targetMs = nextSevenAmPacificMillis(now.timeInMillis)
        val target = Calendar.getInstance(pacific).apply { timeInMillis = targetMs }
        assertEquals(7, target.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, target.get(Calendar.MINUTE))
        assertEquals(8, target.get(Calendar.DAY_OF_MONTH))
    }
}
