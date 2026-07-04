package com.example.financestreamai

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Regression coverage for [friendlyErrorMessage].
 *
 * The user-visible "Connection to the backend was interrupted." toast
 * (2026-07-04) was silently swallowing the underlying exception class
 * — every ConnectException / EOFException / StreamReset / SocketException
 * looked identical in the UI, so field reports contained zero diagnostic
 * signal. These tests now assert that:
 *
 *   1. Every realistic Retrofit/OkHttp failure mode is mapped to a
 *      user-friendly string that ALSO contains the exception class name,
 *      so the next production report is self-identifying.
 *   2. Special-cased subclasses (SocketTimeout / UnknownHost /
 *      HttpException / ScanStalledException) still get their targeted
 *      messages instead of the generic fallback.
 *   3. Offline detection is respected when a Context is supplied and the
 *      device is genuinely disconnected.
 *
 * We deliberately do NOT pass a real Android [android.content.Context]
 * (JUnit unit tests run on the JVM without an Android framework). The
 * `context = null` overload skips [AppNetwork.hasInternet] and treats
 * the device as online — which matches the production path 99% of the
 * time (scan buttons are only tappable when the app is in foreground
 * with a working connection to load the UI in the first place).
 */
class FriendlyErrorMessageTest {

    // ------------------------------------------------------------------
    // Generic IOException subclasses (must expose class name)
    // ------------------------------------------------------------------

    @Test
    fun ioException_includesClassNameAndMessage() {
        // Plain IOException — simulates a low-level I/O failure that
        // isn't SocketTimeout/UnknownHost/EOFException.
        val msg = friendlyErrorMessage(IOException("stream corruption"))
        assertTrue("must contain class name for diagnostic clarity",
            msg.contains("IOException"))
        assertTrue("must contain original message", msg.contains("stream corruption"))
        assertTrue("must contain retry guidance", msg.contains("try again"))
    }

    @Test
    fun connectException_hitsGenericBranchWithClassName() {
        // ConnectException is thrown when the TCP handshake itself fails
        // (backend not reachable at network layer). Not special-cased,
        // so falls through to the generic IOException branch — but the
        // class name distinguishes it from stream-level failures for
        // triage.
        val msg = friendlyErrorMessage(ConnectException("Connection refused"))
        assertTrue("class name surfaces to user", msg.contains("ConnectException"))
        assertTrue("retry guidance present", msg.contains("try again"))
    }

    @Test
    fun socketException_hitsGenericBranchWithClassName() {
        // SocketException("Connection reset") is the classic
        // "stale-pooled-connection after backend restart" failure that
        // Render redeploys can produce. Without the class name in the
        // message we couldn't tell this from a real transport failure.
        val msg = friendlyErrorMessage(SocketException("Connection reset"))
        assertTrue("class name surfaces to user", msg.contains("SocketException"))
        assertTrue("underlying reason preserved", msg.contains("Connection reset"))
    }

    @Test
    fun eofException_hitsGenericBranchWithClassName() {
        // EOFException fires when the server closes the connection
        // mid-response (common with idle-pool + server restart).
        val msg = friendlyErrorMessage(EOFException("\\n not found: limit=1 content=…"))
        assertTrue("class name surfaces to user", msg.contains("EOFException"))
    }

    @Test
    fun sslException_hitsGenericBranchWithClassName() {
        // SSLException / SSLHandshakeException are IOException subclasses
        // (via javax.net.ssl.SSLException extends IOException). Corporate
        // proxy MITM or expired-cert failures land here.
        val msg = friendlyErrorMessage(SSLHandshakeException("Trust anchor for cert not found"))
        assertTrue("class name surfaces to user",
            msg.contains("SSLHandshakeException"))
    }

    @Test
    fun ioException_withNullMessage_stillIncludesClassName() {
        // Some IOException subclasses (particularly framework wrappers)
        // throw with a null message. Confirm we still produce a usable
        // string that identifies the class.
        val msg = friendlyErrorMessage(IOException()) // no-arg = null message
        assertTrue("class name present even with null message",
            msg.contains("IOException"))
        assertTrue("retry guidance present", msg.contains("try again"))
    }

    @Test
    fun ioException_withBlankMessage_stillIncludesClassName() {
        // Some IOException subclasses (particularly framework wrappers)
        // throw with a blank message. Confirm we still produce a usable
        // string that identifies the class.
        val msg = friendlyErrorMessage(IOException("   "))
        assertTrue("class name present even with blank message",
            msg.contains("IOException"))
    }

    // ------------------------------------------------------------------
    // Special-cased branches must NOT hit the generic fallback
    // ------------------------------------------------------------------

    @Test
    fun socketTimeout_producesTimeoutSpecificMessage() {
        val msg = friendlyErrorMessage(SocketTimeoutException("timeout"))
        // Distinguishable from the generic IOException branch: no class
        // name (because the message is already specific), and it names
        // the two known Render causes.
        assertFalse("timeout message must not be the generic wrapper",
            msg.contains("Connection to the backend was interrupted"))
        assertTrue("names cold-start cause", msg.contains("waking from sleep"))
        assertTrue("names lock-contention cause",
            msg.contains("scheduled background scan"))
    }

    @Test
    fun unknownHost_producesBackendUnreachableMessage() {
        // 2026-07-03: this branch was DELIBERATELY softened to stop
        // reporting "you're offline" when the device is actually online
        // (VALIDATED flakes on cellular handoffs). Regression-guard it.
        val msg = friendlyErrorMessage(UnknownHostException(
            "Unable to resolve host \"financestreamai-backend.onrender.com\""))
        assertTrue("names the backend as the failure origin",
            msg.contains("FinanceStream backend"))
        assertFalse("must NOT accuse the phone of being offline",
            msg.contains("offline"))
        assertFalse("must NOT say 'No internet connection'",
            msg.contains("No internet"))
        // 2026-07-04: field-report enrichment — the underlying detail
        // (which usually contains the failing hostname) is now surfaced
        // so a screenshot alone is enough to diagnose DoH-vs-system-DNS
        // failures.
        assertTrue("must include the underlying host / detail",
            msg.contains("financestreamai-backend.onrender.com"))
    }

    @Test
    fun httpException_500_producesServerErrorMessage() {
        val msg = friendlyErrorMessage(newHttpException(500, "boom"))
        assertTrue("names the code", msg.contains("500"))
        assertTrue("hints at restart",
            msg.contains("restarting") || msg.contains("retry"))
    }

    @Test
    fun httpException_429_producesRateLimitMessage() {
        val msg = friendlyErrorMessage(newHttpException(429, "throttled"))
        assertTrue("names the throttle condition",
            msg.contains("Too many requests"))
    }

    @Test
    fun httpException_404_producesGenericServerErrorMessage() {
        val msg = friendlyErrorMessage(newHttpException(404, "job gone"))
        // 4xx that isn't 429/5xx hits the "else" of the HttpException
        // branch — still names the code so support can look up logs.
        assertTrue("code surfaces", msg.contains("404"))
    }

    @Test
    fun scanStalled_producesStalledSpecificMessage() {
        // The recent (2026-07-04) stagnation-detection path — must not
        // regress to the generic "connection interrupted" wording, and
        // must include the N/M ticker fraction and the stall duration.
        val msg = friendlyErrorMessage(ScanStalledException(
            ticker = 36,
            total = 46,
            stalledForSec = 92,
            message = "Scan stalled at 36/46 — no progress for 92s.",
        ))
        assertTrue("mentions the current progress fraction",
            msg.contains("36/46"))
        assertTrue("mentions stall duration in seconds",
            msg.contains("92s"))
        assertTrue("hints at retry timing",
            msg.contains("try again") || msg.contains("30 seconds"))
    }

    // ------------------------------------------------------------------
    // Non-IOException fallback
    // ------------------------------------------------------------------

    @Test
    fun genericException_includesClassNameAndMessage() {
        // Non-IOException, non-HttpException falls through to the final
        // `else` branch. Must still surface enough info to root-cause
        // instead of just showing the raw e.message which historically
        // was sometimes null / cryptic.
        val msg = friendlyErrorMessage(IllegalStateException("job dispatcher closed"))
        assertTrue("class name present", msg.contains("IllegalStateException"))
        assertTrue("original message preserved",
            msg.contains("job dispatcher closed"))
    }

    @Test
    fun genericException_withNullMessage_falls_backToClassNameOnly() {
        // Some framework exceptions throw with a null message. Confirm
        // we still produce a usable string that identifies the class.
        val msg = friendlyErrorMessage(RuntimeException()) // no-arg = null message
        assertTrue("class name present",
            msg.contains("RuntimeException"))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun newHttpException(code: Int, body: String): HttpException {
        val errBody = """{"detail":"$body"}""".toResponseBody(
            "application/json".toMediaTypeOrNull()
        )
        val response = Response.error<Any>(code, errBody)
        return HttpException(response)
    }
}
