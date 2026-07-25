package ai.sotto.assistant.data.remote

import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.core.SLog
import ai.sotto.assistant.data.local.ApiService
import ai.sotto.assistant.data.local.SecureKeyStore
import ai.sotto.assistant.data.model.Attendee
import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * The offline enrichment pass — Design Doc 1 § Offline Processing.
 *
 * "The user taps a button labeled 'Prepare Conference Data.' This data is sent to
 * Gemini API with a prompt asking it to extract and enrich the information. Gemini
 * outputs structured JSON containing: attendee name, title, company, location, session
 * schedule if available, any visible interests or background."
 *
 * Gemini is multimodal, so a badge photo, a PDF and a CSV all take the same path: parts
 * go in, structured JSON comes out. A response schema is attached so the model is
 * constrained to the exact shape in § Database Schema instead of being asked nicely.
 */
class GeminiRestClient(
    private val keyStore: SecureKeyStore,
    private val io: CoroutineDispatcher,
    private val client: OkHttpClient = HttpClients.batch,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One piece of user-supplied source material. */
    sealed interface SourcePart {
        data class Text(val content: String) : SourcePart
        data class Binary(val mimeType: String, val bytes: ByteArray) : SourcePart {
            override fun equals(other: Any?) = this === other
            override fun hashCode() = System.identityHashCode(this)
        }
    }

    data class EnrichmentResult(
        val attendees: List<Attendee>,
        val conferenceName: String,
        val notes: String,
    )

    /**
     * Extracts and enriches attendees from arbitrary source material.
     *
     * @param webGrounding when true, Gemini may use Search to fill in public details.
     *        Off by default because it slows the pass down considerably and the roster
     *        usually already contains what we need.
     */
    suspend fun enrich(
        parts: List<SourcePart>,
        model: String,
        contextHint: String = "",
        webGrounding: Boolean = false,
    ): EnrichmentResult = withContext(io) {
        if (parts.isEmpty()) throw AppError.NoUsableData()

        val apiKey = keyStore.get(ApiService.GEMINI)
            ?: throw AppError.MissingApiKey(ApiService.GEMINI.displayName)

        val payload = buildRequest(parts, contextHint, webGrounding)
        val url = "$baseUrl/v1beta/models/${model.removePrefix("models/")}:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
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
            parseEnrichment(body)
        }
    }

    /**
     * Single-shot text generation. Used for the offline fallback path in Design Doc 1
     * § Error Handling, where Gemini Live is unreachable but the plain REST API is not.
     */
    suspend fun generateText(
        prompt: String,
        model: String,
        systemInstruction: String? = null,
        maxTokens: Int = 200,
    ): String = withContext(io) {
        val apiKey = keyStore.get(ApiService.GEMINI)
            ?: throw AppError.MissingApiKey(ApiService.GEMINI.displayName)

        val payload = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
                })
            }
            systemInstruction?.let {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { add(buildJsonObject { put("text", it) }) }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.8)
                put("maxOutputTokens", maxTokens)
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/v1beta/models/${model.removePrefix("models/")}:generateContent?key=$apiKey")
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
            extractText(body).trim()
        }
    }

    /** Cheap credential check for the Settings screen's "Test key" button. */
    suspend fun validateKey(model: String): Boolean = withContext(io) {
        val apiKey = keyStore.get(ApiService.GEMINI)
            ?: throw AppError.MissingApiKey(ApiService.GEMINI.displayName)
        val request = Request.Builder()
            .url("$baseUrl/v1beta/models/${model.removePrefix("models/")}?key=$apiKey")
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

    internal fun buildRequest(
        parts: List<SourcePart>,
        contextHint: String,
        webGrounding: Boolean,
    ): JsonObject = buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", userPrompt(contextHint)) })
                    parts.forEach { part ->
                        when (part) {
                            is SourcePart.Text -> add(buildJsonObject {
                                put("text", part.content.take(MAX_TEXT_CHARS))
                            })
                            is SourcePart.Binary -> add(buildJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", part.mimeType)
                                    put("data", Base64.encodeToString(part.bytes, Base64.NO_WRAP))
                                }
                            })
                        }
                    }
                }
            })
        }
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", SYSTEM_PROMPT) }) }
        }
        putJsonObject("generationConfig") {
            put("temperature", 0.35)
            put("maxOutputTokens", MAX_ENRICHMENT_TOKENS)
            // Grounding and structured output are mutually exclusive on this endpoint,
            // so schema-constrained JSON only goes on when search is off.
            if (!webGrounding) {
                put("responseMimeType", "application/json")
                put("responseSchema", RESPONSE_SCHEMA)
            }
        }
        if (webGrounding) {
            putJsonArray("tools") { add(buildJsonObject { putJsonObject("googleSearch") {} }) }
        }
        putJsonArray("safetySettings") {
            listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT",
            ).forEach { category ->
                add(buildJsonObject {
                    put("category", category)
                    put("threshold", "BLOCK_ONLY_HIGH")
                })
            }
        }
    }

    internal fun parseEnrichment(body: String?): EnrichmentResult {
        val text = extractText(body)
        if (text.isBlank()) throw AppError.NoUsableData()

        val root = parseLenientJson(text) ?: throw AppError.BadResponse(SERVICE)

        val conferenceName = root["conference_name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val notes = root["notes"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()

        val list = (root["attendees"] as? JsonArray) ?: throw AppError.NoUsableData()
        val attendees = list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            Attendee(
                id = UUID.randomUUID().toString(),
                name = name,
                title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                company = obj["company"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                bio = obj["bio"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                interests = (obj["interests"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
                location = obj["location"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) },
            ).sanitised()
        }

        if (attendees.isEmpty()) throw AppError.NoUsableData()
        SLog.i(TAG, "Enrichment produced ${attendees.size} attendees")
        return EnrichmentResult(attendees, conferenceName, notes)
    }

    internal fun extractText(body: String?): String {
        if (body.isNullOrBlank()) return ""
        return try {
            val root = json.parseToJsonElement(body).jsonObject

            // A prompt-level block has no candidates at all; say so usefully.
            root["promptFeedback"]?.jsonObject?.get("blockReason")
                ?.jsonPrimitive?.contentOrNull
                ?.let { reason ->
                    throw AppError.ServiceFailure(
                        SERVICE,
                        "Gemini declined to process that content ($reason).",
                    )
                }

            val candidate = (root["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
                ?: return ""

            val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
            if (finishReason == "SAFETY" || finishReason == "PROHIBITED_CONTENT") {
                throw AppError.ServiceFailure(SERVICE, "Gemini declined to answer that.")
            }

            ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
                ?.joinToString("")
                .orEmpty()
        } catch (e: AppError) {
            throw e
        } catch (t: Throwable) {
            throw AppError.BadResponse(SERVICE, t)
        }
    }

    /**
     * Models sometimes wrap JSON in a ```json fence or add a sentence either side even
     * when asked not to. Rather than fail the user's five-minute enrichment run over
     * punctuation, dig the object out.
     */
    internal fun parseLenientJson(text: String): JsonObject? {
        runCatching { return json.parseToJsonElement(text).jsonObject }

        val fenced = FENCE_REGEX.find(text)?.groupValues?.getOrNull(1)
        if (fenced != null) {
            runCatching { return json.parseToJsonElement(fenced.trim()).jsonObject }
        }

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { return json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }
        }

        // A bare array of attendees is a common near-miss; accept it.
        val arrayStart = text.indexOf('[')
        val arrayEnd = text.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            runCatching {
                val arr = json.parseToJsonElement(text.substring(arrayStart, arrayEnd + 1)).jsonArray
                return buildJsonObject { put("attendees", arr) }
            }
        }
        return null
    }

    private fun userPrompt(contextHint: String): String = buildString {
        appendLine("Extract every attendee you can find in the material below and enrich each one.")
        appendLine()
        appendLine("Rules:")
        appendLine("- One object per person. Never invent a person who is not in the source.")
        appendLine("- name: their full name as written.")
        appendLine("- title and company: from the source. Leave as \"\" if genuinely absent.")
        appendLine("- bio: at most 200 characters, written to help someone strike up a")
        appendLine("  conversation. Prefer concrete specifics over adjectives. If the source")
        appendLine("  gives you nothing, write a neutral one-liner from their title and company.")
        appendLine("- interests: 2-6 short tags (topics, technologies, industries, hobbies).")
        appendLine("  Infer sensible ones from their role when the source is thin, but keep")
        appendLine("  them plausible rather than specific claims you cannot support.")
        appendLine("- location: seat, table, room or session slot if the source mentions one,")
        appendLine("  otherwise null.")
        appendLine("- Deduplicate people who appear more than once.")
        appendLine("- Never guess private or sensitive details about anyone.")
        if (contextHint.isNotBlank()) {
            appendLine()
            appendLine("Context from the user about this conference: ${contextHint.take(500)}")
        }
        appendLine()
        appendLine("Also return conference_name if you can tell what event this is, and a short")
        appendLine("notes field describing what you found and anything that looked unreliable.")
    }

    companion object {
        private const val TAG = "GeminiRest"
        const val SERVICE = "Gemini"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val MAX_TEXT_CHARS = 400_000
        const val MAX_ENRICHMENT_TOKENS = 32_768

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val FENCE_REGEX = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

        private val SYSTEM_PROMPT = """
            You are a data extraction engine for a conference networking assistant.
            You turn messy attendee material — CSV exports, PDF programmes, photos of
            badge rosters, pasted text — into clean structured records.
            You are accurate and conservative: you never fabricate people, and you never
            state a fact about someone that the source does not support.
            You always reply with JSON only.
        """.trimIndent()

        /**
         * Response schema mirroring Design Doc 1 § Database Schema. `embedding` is
         * deliberately absent — it is computed on-device at face-enrolment time and is
         * not something a language model can produce.
         */
        val RESPONSE_SCHEMA: JsonObject = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                putJsonObject("conference_name") { put("type", "STRING") }
                putJsonObject("notes") { put("type", "STRING") }
                putJsonObject("attendees") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonObject("properties") {
                            putJsonObject("name") { put("type", "STRING") }
                            putJsonObject("title") { put("type", "STRING") }
                            putJsonObject("company") { put("type", "STRING") }
                            putJsonObject("bio") { put("type", "STRING") }
                            putJsonObject("interests") {
                                put("type", "ARRAY")
                                putJsonObject("items") { put("type", "STRING") }
                            }
                            putJsonObject("location") { put("type", "STRING") }
                        }
                        putJsonArray("required") { add(JsonPrimitive("name")) }
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("attendees")) }
        }
    }
}
