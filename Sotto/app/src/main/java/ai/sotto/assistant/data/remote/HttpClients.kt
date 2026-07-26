package ai.sotto.assistant.data.remote

import ai.sotto.assistant.BuildConfig
import ai.sotto.assistant.core.AppError
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp plumbing. Design Doc 1 § Technical Stack: "Networking: OkHttp for API
 * calls".
 */
object HttpClients {

    /** Short timeouts: this is a real-time product, a slow call is a failed call. */
    val realtime: OkHttpClient by lazy {
        base()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The enrichment pass is explicitly offline work — Design Doc 1 budgets "two to
     * five minutes" for a hundred-person list — so it gets a much longer leash.
     */
    val batch: OkHttpClient by lazy {
        base()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** Live streaming socket: no read timeout, plus pings to keep the link alive. */
    val streaming: OkHttpClient by lazy {
        base()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private fun base(): OkHttpClient.Builder = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .apply {
            if (BuildConfig.DEBUG) {
                // HEADERS only — bodies would contain transcripts and API keys.
                addInterceptor(
                    HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
                )
            }
        }

    /**
     * Turns a non-2xx response into the right [AppError]. Google's error envelope is
     * `{"error": {"message": "..."}}`, which is far more useful to show than a status
     * code, so we surface it when it's there.
     */
    fun errorFor(service: String, response: Response, body: String?): AppError = when (response.code) {
        400 -> AppError.ServiceFailure(service, extractMessage(body) ?: "The request was rejected.")
        // Google's own message is the useful part here — it distinguishes "wrong key"
        // from "this API does not accept API keys at all", which are very different
        // problems. Swallowing it sent a user chasing a config error that did not exist.
        401, 403 -> extractMessage(body)
            ?.let { AppError.Rejected(service, it) }
            ?: AppError.Unauthorized(service)
        429 -> AppError.RateLimited(service)
        in 500..599 -> AppError.ServiceFailure(
            service,
            extractMessage(body) ?: "$service is temporarily unavailable.",
        )
        else -> AppError.ServiceFailure(
            service,
            extractMessage(body) ?: "Unexpected response (${response.code}).",
        )
    }

    fun extractMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(body)
            root.jsonObjectOrNull()
                ?.get("error")
                ?.jsonObjectOrNull()
                ?.get("message")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() =
        this as? kotlinx.serialization.json.JsonObject
}
