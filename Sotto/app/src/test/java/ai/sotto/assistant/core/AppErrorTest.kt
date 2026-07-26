package ai.sotto.assistant.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Design Doc 1 § Error Handling requires the app to degrade rather than fail. Every
 * error therefore has to arrive at the UI as something a non-technical person can read
 * and act on.
 */
class AppErrorTest {

    @Test
    fun `every error has a plain-language message`() {
        val errors = listOf(
            AppError.MissingApiKey("Gemini"),
            AppError.Network(),
            AppError.Unauthorized("Gemini"),
            AppError.RateLimited("Gemini"),
            AppError.ServiceFailure("Gemini", "detail"),
            AppError.BadResponse("Gemini"),
            AppError.UnreadableFile("roster.csv"),
            AppError.NoUsableData(),
            AppError.PermissionDenied("Camera"),
            AppError.ModelUnavailable(),
            AppError.Unknown(),
        )

        errors.forEach { error ->
            assertThat(error.userMessage).isNotEmpty()
            // No stack-trace vocabulary should ever reach the screen.
            assertThat(error.userMessage.lowercase()).doesNotContain("exception")
            assertThat(error.userMessage.lowercase()).doesNotContain("null")
        }
    }

    @Test
    fun `errors the user can act on carry a recovery hint`() {
        val actionable = listOf(
            AppError.MissingApiKey("Gemini"),
            AppError.Network(),
            AppError.Unauthorized("Gemini"),
            AppError.RateLimited("Gemini"),
            AppError.UnreadableFile("roster.csv"),
            AppError.NoUsableData(),
            AppError.PermissionDenied("Camera"),
            AppError.ModelUnavailable(),
        )
        actionable.forEach { assertThat(it.recovery).isNotEmpty() }
    }

    @Test
    fun `the missing key message names the service`() {
        assertThat(AppError.MissingApiKey("Speech-to-Text").userMessage).contains("Speech-to-Text")
    }

    @Test
    fun `the unreadable file message names the file`() {
        assertThat(AppError.UnreadableFile("badges.jpg").userMessage).contains("badges.jpg")
    }

    @Test
    fun `network failures are recognised from their exception types`() {
        listOf(
            UnknownHostException("no dns"),
            ConnectException("refused"),
            SocketTimeoutException("timeout"),
            javax.net.ssl.SSLException("handshake"),
        ).forEach { throwable ->
            assertThat(AppError.from(throwable)).isInstanceOf(AppError.Network::class.java)
        }
    }

    @Test
    fun `a generic IO failure becomes a service failure naming the service`() {
        val error = AppError.from(IOException("socket closed"), "Gemini")
        assertThat(error).isInstanceOf(AppError.ServiceFailure::class.java)
        assertThat((error as AppError.ServiceFailure).service).isEqualTo("Gemini")
    }

    @Test
    fun `an unrecognised throwable becomes Unknown`() {
        assertThat(AppError.from(IllegalStateException("boom")))
            .isInstanceOf(AppError.Unknown::class.java)
    }

    @Test
    fun `an existing AppError passes through unchanged`() {
        val original = AppError.RateLimited("Gemini")
        assertThat(AppError.from(original)).isSameInstanceAs(original)
    }

    @Test
    fun `the underlying cause is preserved for logging`() {
        val cause = IOException("underlying")
        assertThat(AppError.Network(cause).cause).isSameInstanceAs(cause)
    }

    @Test
    fun `a long service detail is truncated so it fits on screen`() {
        val error = AppError.ServiceFailure("Gemini", "x".repeat(1_000))
        assertThat(error.recovery!!.length).isAtMost(180)
    }

    @Test
    fun `a blank detail falls back to something useful`() {
        assertThat(AppError.ServiceFailure("Gemini", "   ").recovery).isNotEmpty()
    }

    // ---- Rejected -----------------------------------------------------------------
    //
    // A 401/403 used to collapse into "check your key and make sure the API is enabled".
    // That advice was wrong for Cloud Speech-to-Text and Text-to-Speech, which reject
    // API keys outright — the user enabled both APIs, tried two keys, and could never
    // have succeeded. The service's own words have to survive.

    @Test
    fun `an API that refuses keys outright says so`() {
        val error = AppError.Rejected(
            "Text-to-Speech",
            "API keys are not supported by this API. Expected OAuth2 access token or other " +
                "authentication credentials that assert a principal.",
        )
        assertThat(error.recovery!!.lowercase()).contains("doesn't accept api keys")
        assertThat(error.recovery!!.lowercase()).contains("service-account")
        // Must not send the user back to enable an API they already enabled.
        assertThat(error.recovery!!.lowercase()).doesNotContain("enable this api")
    }

    @Test
    fun `a genuinely disabled API does advise enabling it`() {
        val error = AppError.Rejected(
            "Speech-to-Text",
            "Cloud Speech-to-Text API has not been used in project 123 before or it is disabled.",
        )
        assertThat(error.recovery!!.lowercase()).contains("enable this api")
    }

    @Test
    fun `a malformed key advises checking the key`() {
        val error = AppError.Rejected("Gemini", "API key not valid. Please pass a valid API key.")
        assertThat(error.recovery!!.lowercase()).contains("check the key")
    }

    @Test
    fun `an unrecognised reason is passed through verbatim`() {
        val error = AppError.Rejected("Gemini", "Requests from this Android client are blocked.")
        assertThat(error.recovery).contains("blocked")
    }

    @Test
    fun `the rejection names the service`() {
        assertThat(AppError.Rejected("Text-to-Speech", "x").userMessage).contains("Text-to-Speech")
    }

    @Test
    fun `a very long reason is truncated`() {
        assertThat(AppError.Rejected("Gemini", "x".repeat(2_000)).recovery!!.length)
            .isAtMost(240)
    }

    // ---- ModelUnavailable -------------------------------------------------------
    //
    // These exist because the first version of this error told users to reinstall no
    // matter what went wrong. When the real cause was a native library that could not
    // load on their device at all, that advice was worse than useless — it sent them
    // round a loop that could never fix anything.

    @Test
    fun `a native library failure does not tell the user to reinstall`() {
        val error = AppError.ModelUnavailable(
            "face detection",
            UnsatisfiedLinkError("dlopen failed: library is not 16 KB aligned"),
        )
        assertThat(error.recovery!!.lowercase()).doesNotContain("reinstalling this one will help")
        assertThat(error.recovery!!.lowercase()).contains("newer build")
        assertThat(error.recovery!!.lowercase()).contains("won't help")
    }

    @Test
    fun `a native library failure surfaces the underlying message`() {
        val error = AppError.ModelUnavailable(
            "face detection",
            UnsatisfiedLinkError("dlopen failed: not 16 KB aligned"),
        )
        assertThat(error.recovery).contains("not 16 KB aligned")
    }

    @Test
    fun `a wrapped native library failure is still recognised`() {
        val wrapped = RuntimeException("init failed", UnsatisfiedLinkError("dlopen failed"))
        assertThat(AppError.ModelUnavailable("face detection", wrapped).recovery!!.lowercase())
            .contains("newer build")
    }

    @Test
    fun `a genuinely missing file does advise reinstalling`() {
        val error = AppError.ModelUnavailable(
            "face recognition",
            java.io.FileNotFoundException("facenet.tflite"),
        )
        assertThat(error.recovery!!.lowercase()).contains("reinstalling")
    }

    @Test
    fun `the message names which model failed`() {
        assertThat(AppError.ModelUnavailable("face detection").userMessage)
            .contains("face detection")
        assertThat(AppError.ModelUnavailable("face recognition").userMessage)
            .contains("face recognition")
    }

    @Test
    fun `an unknown cause says so instead of inventing advice`() {
        val recovery = AppError.ModelUnavailable().recovery!!
        assertThat(recovery).isNotEmpty()
        assertThat(recovery.lowercase()).contains("no further detail")
    }

    @Test
    fun `a long underlying message is truncated to fit on screen`() {
        val error = AppError.ModelUnavailable(
            "face detection",
            UnsatisfiedLinkError("x".repeat(2_000)),
        )
        assertThat(error.recovery!!.length).isLessThan(400)
    }

    @Test
    fun `a multi-line underlying message is reduced to its first line`() {
        val error = AppError.ModelUnavailable(
            "face detection",
            java.io.IOException("first line\nsecond line\nthird line"),
        )
        assertThat(error.recovery).contains("first line")
        assertThat(error.recovery).doesNotContain("second line")
    }
}
