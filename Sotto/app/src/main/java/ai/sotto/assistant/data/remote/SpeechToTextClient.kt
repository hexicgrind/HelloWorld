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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Cloud Speech-to-Text.
 *
 * Design Doc 1 § Speech and Transcription: "Audio is streamed to Google Cloud
 * Speech-to-Text API in real time, transcribing both the user's voice and the detected
 * person's voice. Transcription latency is typically 500 to 1500 milliseconds."
 *
 * Cloud's true bidirectional streaming endpoint is gRPC-only, and gRPC cannot carry an
 * API key the way the REST surface can. So instead of asking the user for a service
 * account JSON file — which is a genuinely awful thing to ask someone to put on their
 * phone — this uses the REST `speech:recognize` endpoint driven by the voice activity
 * detector: each utterance is posted the moment the speaker pauses. In practice that
 * lands in the same 500-1500 ms window the doc specifies, because the request goes out
 * at end-of-utterance, which is exactly when a transcript becomes useful.
 */
class SpeechToTextClient(
    private val keyStore: SecureKeyStore,
    private val io: CoroutineDispatcher,
    private val client: OkHttpClient = HttpClients.realtime,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Transcript(
        val text: String,
        val confidence: Float,
        val languageCode: String,
    )

    /**
     * Transcribes one utterance of 16 kHz mono LINEAR16 PCM.
     *
     * Returns null when the audio contained no recognisable speech, which is a normal
     * outcome in a noisy room and must not be treated as an error.
     */
    suspend fun transcribe(
        pcm: ByteArray,
        languageCode: String = "en-US",
        phraseHints: List<String> = emptyList(),
    ): Transcript? = withContext(io) {
        if (pcm.size < MIN_UTTERANCE_BYTES) return@withContext null

        val apiKey = keyStore.get(ApiService.SPEECH_TO_TEXT)
            ?: throw AppError.MissingApiKey(ApiService.SPEECH_TO_TEXT.displayName)

        val payload = buildJsonObject {
            put("config", buildJsonObject {
                put("encoding", "LINEAR16")
                put("sampleRateHertz", AudioFormats.SAMPLE_RATE_HZ)
                put("audioChannelCount", AudioFormats.CHANNELS)
                put("languageCode", languageCode)
                put("enableAutomaticPunctuation", true)
                put("profanityFilter", false)
                // "latest_short" is tuned for utterance-at-a-time recognition, which is
                // exactly the shape of traffic the VAD produces.
                put("model", "latest_short")
                put("useEnhanced", true)
                put("maxAlternatives", 1)
                if (phraseHints.isNotEmpty()) {
                    put("speechContexts", buildJsonArray {
                        add(buildJsonObject {
                            put("phrases", buildJsonArray {
                                phraseHints.take(MAX_PHRASE_HINTS).forEach { add(JsonPrimitive(it)) }
                            })
                            put("boost", 12.0)
                        })
                    })
                }
            })
            put("audio", buildJsonObject {
                put("content", Base64.encodeToString(pcm, Base64.NO_WRAP))
            })
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/speech:recognize?key=$apiKey")
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
            parseResponse(body, languageCode)
        }
    }

    internal fun parseResponse(body: String?, languageCode: String): Transcript? {
        if (body.isNullOrBlank()) return null
        return try {
            val results = json.parseToJsonElement(body).jsonObject["results"] as? JsonArray
                ?: return null
            val alternatives = results.firstOrNull()
                ?.jsonObject?.get("alternatives") as? JsonArray
                ?: return null
            val best = alternatives.firstOrNull()?.jsonObject ?: return null
            val text = best["transcript"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) return null
            Transcript(
                text = text,
                confidence = best["confidence"]?.jsonPrimitive?.floatOrNull ?: 0f,
                languageCode = languageCode,
            )
        } catch (t: Throwable) {
            SLog.w(TAG, "Could not parse a Speech-to-Text response", t)
            throw AppError.BadResponse(SERVICE, t)
        }
    }

    companion object {
        private const val TAG = "SpeechToText"
        const val SERVICE = "Speech-to-Text"
        const val DEFAULT_BASE_URL = "https://speech.googleapis.com"

        /** ~250 ms; anything shorter is a cough, not a sentence. */
        const val MIN_UTTERANCE_BYTES = 8_000
        const val MAX_PHRASE_HINTS = 200

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
