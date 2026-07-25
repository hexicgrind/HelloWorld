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
}
