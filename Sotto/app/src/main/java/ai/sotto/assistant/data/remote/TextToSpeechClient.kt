package ai.sotto.assistant.data.remote

import ai.sotto.assistant.audio.AudioFormats
import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.core.SLog
import ai.sotto.assistant.data.local.ApiService
import ai.sotto.assistant.data.local.SecureKeyStore
import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Cloud Text-to-Speech.
 *
 * Design Doc 1 § Audio Output: "Text-to-speech via Google Cloud Text-to-Speech
 * synthesizes Gemini's suggestions and outputs to a Bluetooth earpiece connected to
 * the phone. TTS latency is typically 200 to 500 milliseconds."
 *
 * We ask for LINEAR16 rather than MP3 so the bytes can go straight into AudioTrack
 * with no decode step — that decode is 50-100 ms we don't have to spend.
 *
 * Repeated whispers ("Ask them about X") recur often enough that a small LRU cache
 * pays for itself, and a cache hit removes the synthesis latency entirely.
 */
class TextToSpeechClient(
    private val keyStore: SecureKeyStore,
    private val io: CoroutineDispatcher,
    private val client: OkHttpClient = HttpClients.realtime,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val cache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) =
            size > CACHE_ENTRIES
    }

    data class Audio(val pcm: ByteArray, val sampleRateHz: Int) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /**
     * Synthesises [text] to raw PCM.
     *
     * Speech is rendered as SSML with a slightly reduced volume and a small leading
     * pause, because this audio lands in someone's ear mid-conversation — it should
     * sound like a whisper from a colleague, not a notification.
     */
    suspend fun synthesize(
        text: String,
        voiceName: String = DEFAULT_VOICE,
        languageCode: String = "en-US",
        speakingRate: Float = 1.08f,
        whisper: Boolean = true,
    ): Audio? = withContext(io) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return@withContext null

        val cacheKey = "$voiceName|$languageCode|$speakingRate|$whisper|$cleaned"
        synchronized(cache) { cache[cacheKey] }?.let {
            SLog.d(TAG) { "TTS cache hit" }
            return@withContext Audio(it, SAMPLE_RATE)
        }

        val apiKey = keyStore.get(ApiService.TEXT_TO_SPEECH)
            ?: throw AppError.MissingApiKey(ApiService.TEXT_TO_SPEECH.displayName)

        val payload = buildJsonObject {
            put("input", buildJsonObject {
                if (whisper) put("ssml", toWhisperSsml(cleaned)) else put("text", cleaned)
            })
            put("voice", buildJsonObject {
                put("languageCode", languageCode)
                put("name", voiceName)
            })
            put("audioConfig", buildJsonObject {
                put("audioEncoding", "LINEAR16")
                put("sampleRateHertz", SAMPLE_RATE)
                put("speakingRate", speakingRate.coerceIn(0.25f, 4.0f))
                put("pitch", WHISPER_PITCH)
                put("volumeGainDb", WHISPER_GAIN_DB)
                put("effectsProfileId", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("headphone-class-device"))
                })
            })
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/text:synthesize?key=$apiKey")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (t: Throwable) {
            throw AppError.from(t, SERVICE)
        }

        response.use { res ->
            val body = res.body?.string()
            if (!res.isSuccessful) throw HttpClients.errorFor(SERVICE, res, body)
            val pcm = parseResponse(body) ?: return@use null
            synchronized(cache) { cache[cacheKey] = pcm }
            Audio(pcm, SAMPLE_RATE)
        }
    }

    internal fun parseResponse(body: String?): ByteArray? {
        if (body.isNullOrBlank()) return null
        return try {
            val encoded = json.parseToJsonElement(body)
                .jsonObject["audioContent"]
                ?.jsonPrimitive?.contentOrNull
                ?: return null
            val decoded = Base64.decode(encoded, Base64.DEFAULT)
            // Cloud TTS prepends a WAV header for LINEAR16; AudioTrack wants bare PCM.
            ai.sotto.assistant.audio.WhisperPlayer.stripWavHeader(decoded)
        } catch (t: Throwable) {
            throw AppError.BadResponse(SERVICE, t)
        }
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    /**
     * Verifies the key really works against *this* API.
     *
     * Enabling the Generative Language API does not enable Cloud Text-to-Speech — they
     * are separate services on the project. Testing only Gemini and reporting "your key
     * works" was actively misleading, because the first thing the user heard afterwards
     * was this service rejecting them.
     */
    suspend fun validateKey(): Boolean = withContext(io) {
        val apiKey = keyStore.get(ApiService.TEXT_TO_SPEECH)
            ?: throw AppError.MissingApiKey(ApiService.TEXT_TO_SPEECH.displayName)
        val request = Request.Builder()
            .url("$baseUrl/v1/voices?languageCode=en-US&key=$apiKey")
            .get()
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (t: Throwable) {
            throw AppError.from(t, SERVICE)
        }
        response.use { res ->
            val body = res.body?.string()
            if (!res.isSuccessful) throw HttpClients.errorFor(SERVICE, res, body)
            true
        }
    }

    companion object {
        private const val TAG = "TextToSpeech"
        const val SERVICE = "Text-to-Speech"
        const val DEFAULT_BASE_URL = "https://texttospeech.googleapis.com"
        const val DEFAULT_VOICE = "en-US-Neural2-C"
        const val SAMPLE_RATE = AudioFormats.OUTPUT_SAMPLE_RATE_HZ
        const val CACHE_ENTRIES = 24

        private const val WHISPER_PITCH = -1.0
        private const val WHISPER_GAIN_DB = -3.0
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * Wraps the whisper in SSML: a beat of silence so the first word isn't clipped
         * by a Bluetooth link waking up, then a softer, slightly lower delivery.
         */
        fun toWhisperSsml(text: String): String {
            val escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
            return """<speak><break time="120ms"/><prosody volume="soft" rate="medium">$escaped</prosody></speak>"""
        }

        /** The voices offered in Settings, chosen to be calm and easy to follow. */
        val VOICES = listOf(
            "en-US-Neural2-C" to "Ava — warm, calm (female)",
            "en-US-Neural2-J" to "Miles — low, steady (male)",
            "en-US-Neural2-F" to "Nora — bright, quick (female)",
            "en-US-Neural2-D" to "Reed — deep, measured (male)",
            "en-GB-Neural2-A" to "Imogen — British (female)",
            "en-GB-Neural2-B" to "Oliver — British (male)",
            "en-AU-Neural2-A" to "Zara — Australian (female)",
        )
    }
}
