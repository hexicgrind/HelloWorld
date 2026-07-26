package ai.sotto.assistant.core

/**
 * Every failure the user can actually see is modelled here so the UI never has to
 * show a raw exception. [userMessage] is written for a non-technical reader and
 * [recovery] tells them exactly what to do next.
 */
sealed class AppError(
    val userMessage: String,
    val recovery: String? = null,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {

    class MissingApiKey(val service: String) : AppError(
        userMessage = "Sotto needs a $service key before it can do that.",
        recovery = "Open Settings and paste your key.",
    )

    class Network(cause: Throwable? = null) : AppError(
        userMessage = "Couldn't reach the internet.",
        recovery = "Check your Wi-Fi or mobile data and try again.",
        cause = cause,
    )

    class Unauthorized(val service: String) : AppError(
        userMessage = "Your $service key was rejected.",
        recovery = "Double-check the key in Settings, and make sure the API is enabled for it.",
    )

    class RateLimited(val service: String) : AppError(
        userMessage = "$service is asking us to slow down.",
        recovery = "Wait a minute and try again.",
    )

    class ServiceFailure(val service: String, val detail: String) : AppError(
        userMessage = "$service had a problem.",
        recovery = detail.take(180).ifBlank { "Try again in a moment." },
    )

    class BadResponse(val service: String, cause: Throwable? = null) : AppError(
        userMessage = "$service sent back something Sotto couldn't read.",
        recovery = "Try again — this is usually temporary.",
        cause = cause,
    )

    class UnreadableFile(val fileName: String, cause: Throwable? = null) : AppError(
        userMessage = "Couldn't read \"$fileName\".",
        recovery = "Try a CSV, PDF, photo, or plain text file.",
        cause = cause,
    )

    class NoUsableData : AppError(
        userMessage = "No attendees could be found in what you provided.",
        recovery = "Make sure the file lists people's names, then try again.",
    )

    class PermissionDenied(val permission: String) : AppError(
        userMessage = "Sotto needs the $permission permission for this.",
        recovery = "Grant it in Settings › Apps › Sotto › Permissions.",
    )

    /**
     * A face model failed to load.
     *
     * The cause is surfaced verbatim rather than guessed at. The first version of this
     * error told the user to reinstall, which was actively misleading when the real
     * problem was a native library that could not be loaded on their device at all —
     * reinstalling could never have fixed it. If we don't know why, say so.
     */
    class ModelUnavailable(
        val which: String = "face",
        cause: Throwable? = null,
    ) : AppError(
        userMessage = "Sotto can't load its on-device $which model.",
        recovery = describeCause(cause),
        cause = cause,
    ) {
        companion object {
            private fun describeCause(cause: Throwable?): String = when {
                cause is UnsatisfiedLinkError || cause?.cause is UnsatisfiedLinkError ->
                    "This build isn't compatible with your phone's processor or page size. " +
                        "You need a newer build of Sotto — reinstalling this one won't help. " +
                        "(${short(cause)})"
                cause is java.io.FileNotFoundException ->
                    "A model file is missing from the install. Reinstalling Sotto should fix it."
                cause is java.io.IOException ->
                    "A model file couldn't be read: ${short(cause)}"
                cause != null ->
                    "Details: ${short(cause)}"
                else ->
                    "No further detail was available."
            }

            private fun short(t: Throwable?): String {
                if (t == null) return "unknown"
                val message = t.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
                val label = t.javaClass.simpleName
                return if (message.isBlank()) label else "$label: ${message.take(160)}"
            }
        }
    }

    class Unknown(cause: Throwable? = null) : AppError(
        userMessage = "Something went wrong.",
        recovery = "Try again. If it keeps happening, restart Sotto.",
        cause = cause,
    )

    companion object {
        /** Best-effort mapping of an arbitrary throwable onto a presentable error. */
        fun from(t: Throwable, service: String = "The service"): AppError = when (t) {
            is AppError -> t
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.SocketTimeoutException,
            is java.io.InterruptedIOException,
            -> Network(t)
            is javax.net.ssl.SSLException -> Network(t)
            is java.io.IOException -> ServiceFailure(service, t.message.orEmpty())
            else -> Unknown(t)
        }
    }
}
