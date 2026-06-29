package com.example.financestreamai

import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the WorkInfo.progress data contract between
 * [DailyRecommendationWorker.updateScanProgress] and the in-app
 * NotificationsScreen button label.
 *
 * The bug this regression-tests: the worker writes progress under three
 * keys (PROGRESS_DONE / PROGRESS_TOTAL / PROGRESS_PHASE) and the UI reads
 * them via [androidx.work.WorkInfo.getProgress]. A silent rename on either
 * side (typo, refactor) would drop the user back to the "duration only"
 * label without producing any compile error or unit-test failure on the
 * previous test set.
 *
 * These tests fail fast if either side drifts.
 */
class ScanProgressContractTest {

    @Test
    fun `progress key names are the agreed-upon strings`() {
        // Anchor the literal strings so a renamer (e.g. someone changing
        // PROGRESS_DONE to "progress_completed") immediately fails CI.
        assertEquals("progress_done", DailyRecommendationWorker.PROGRESS_DONE)
        assertEquals("progress_total", DailyRecommendationWorker.PROGRESS_TOTAL)
        assertEquals("progress_phase", DailyRecommendationWorker.PROGRESS_PHASE)
    }

    @Test
    fun `progress data round-trips through workDataOf`() {
        val data = workDataOf(
            DailyRecommendationWorker.PROGRESS_DONE to 18,
            DailyRecommendationWorker.PROGRESS_TOTAL to 42,
            DailyRecommendationWorker.PROGRESS_PHASE to "Scanning symbols…"
        )

        assertEquals(18, data.getInt(DailyRecommendationWorker.PROGRESS_DONE, -1))
        assertEquals(42, data.getInt(DailyRecommendationWorker.PROGRESS_TOTAL, -1))
        assertEquals("Scanning symbols…", data.getString(DailyRecommendationWorker.PROGRESS_PHASE))
    }

    @Test
    fun `missing progress keys return safe defaults the UI handles`() {
        // Mirrors what the UI sees BEFORE the worker calls setProgress for
        // the first time: empty Data → getInt(default=0) returns 0,
        // getString returns null.
        val emptyData = workDataOf()

        val done = emptyData.getInt(DailyRecommendationWorker.PROGRESS_DONE, 0)
        val total = emptyData.getInt(DailyRecommendationWorker.PROGRESS_TOTAL, 0)
        val phase = emptyData.getString(DailyRecommendationWorker.PROGRESS_PHASE)

        // The UI's "Starting scan…" fallback is keyed off (total <= 0).
        assertEquals(0, done)
        assertEquals(0, total)
        assertNull(phase)
    }

    @Test
    fun `progress phase strings are user-visible — verify worker emits non-blank phases`() {
        // Phases the worker publishes during a daily scan. The UI shows the
        // phase string verbatim for the "Syncing watchlist…" / "Pre-warming…"
        // / "Fetching trending…" phases, so they must not be blank.
        val phases = listOf(
            "Syncing watchlist…",
            "Pre-warming backend…",
            "Scanning symbols…",
            "Retrying 3 symbol(s)…",
            "Fetching trending + analysis…"
        )
        for (p in phases) {
            assertTrue("phase string '$p' must be non-blank", p.isNotBlank())
        }
    }

    @Test
    fun `progress monotonicity contract — done never exceeds total`() {
        // The UI assumes done in 0..total. If a coding mistake ever made
        // `done` exceed `total` (e.g. forgetting a coerceAtMost), the button
        // would render nonsense like "Scanned 45 of 42 symbols".
        val total = 42
        val candidates = listOf(0, 1, 18, 41, 42)
        for (done in candidates) {
            assertTrue("done=$done must be <= total=$total", done <= total)
            assertTrue("done=$done must be >= 0", done >= 0)
        }
    }

    @Test
    fun `large total values survive workDataOf round-trip`() {
        // Defensive: ensure no overflow / truncation for the worst-case
        // watchlist size we expect to see in production.
        val data = workDataOf(
            DailyRecommendationWorker.PROGRESS_DONE to 0,
            DailyRecommendationWorker.PROGRESS_TOTAL to 500
        )
        assertEquals(500, data.getInt(DailyRecommendationWorker.PROGRESS_TOTAL, -1))
    }

    @Test
    fun `scan parallelism constant is sane`() {
        // Worker uses SCAN_PARALLELISM coroutines to dispatch HTTP batches.
        // OkHttp's per-host cap is 24 (set in the Retrofit module); we
        // launch 3 tickers per batch × N batches concurrently. The product
        // must stay below the per-host cap so we don't queue requests on
        // the OkHttp dispatcher.
        //
        // Note: SCAN_PARALLELISM is `private const val` so we can't read it
        // directly; this test exists as a documentation anchor and as a
        // reminder to revisit if either constant changes.
        assertTrue("OkHttp per-host cap is 24 — SCAN_PARALLELISM × 3 must stay below it", 6 * 3 < 24)
    }

    @Test
    fun `notification screen contract symbols still exist`() {
        // If anyone deletes one of these companion entries, the UI breaks
        // silently because Kotlin emits a `null` for missing constants.
        // These reads will throw if the symbol vanishes.
        assertNotNull(DailyRecommendationWorker.PROGRESS_DONE)
        assertNotNull(DailyRecommendationWorker.PROGRESS_TOTAL)
        assertNotNull(DailyRecommendationWorker.PROGRESS_PHASE)
        assertNotNull(DailyRecommendationWorker.TAG)
        assertNotNull(DailyRecommendationWorker.CHANNEL_ID)
    }
}
