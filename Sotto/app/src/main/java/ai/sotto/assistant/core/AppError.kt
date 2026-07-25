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

    class ModelUnavailable(cause: Throwable? = null) : AppError(
        userMessage = "The on-device face models couldn't be loaded.",
        recovery = "Reinstall Sotto — a model file may be damaged.",
        cause = cause,
    )

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
