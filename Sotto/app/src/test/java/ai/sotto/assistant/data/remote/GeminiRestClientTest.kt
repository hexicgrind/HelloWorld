package ai.sotto.assistant.data.remote

import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.data.local.ApiService
import ai.sotto.assistant.data.local.SecureKeyStore
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Design Doc 1 § Offline Processing: "Gemini outputs structured JSON containing:
 * attendee name, title, company, location, session schedule if available, any visible
 * interests or background."
 *
 * Robolectric is needed for `android.util.Base64`, which the request builder uses.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiRestClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GeminiRestClient
    private lateinit var keyStore: SecureKeyStore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        keyStore = mockk(relaxed = true)
        every { keyStore.get(ApiService.GEMINI) } returns "test-key"

        client = GeminiRestClient(
            keyStore = keyStore,
            io = Dispatchers.Unconfined,
            client = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun geminiResponse(text: String) = """
        {
          "candidates": [
            { "content": { "parts": [ { "text": ${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))} } ] } }
          ]
        }
    """.trimIndent()

    // ---- Response parsing --------------------------------------------------------

    @Test
    fun `a well-formed response produces attendees`() {
        val payload = """
            {
              "conference_name": "DevCon 2026",
              "notes": "Read from a CSV.",
              "attendees": [
                {
                  "name": "Ada Lovelace",
                  "title": "Chief Scientist",
                  "company": "Analytical Engines",
                  "bio": "Wrote the first algorithm.",
                  "interests": ["compilers", "mathematics"],
                  "location": "Table 4"
                },
                { "name": "Grace Hopper", "title": "Rear Admiral", "company": "US Navy" }
              ]
            }
        """.trimIndent()

        val result = client.parseEnrichment(geminiResponse(payload))

        assertThat(result.conferenceName).isEqualTo("DevCon 2026")
        assertThat(result.notes).isEqualTo("Read from a CSV.")
        assertThat(result.attendees).hasSize(2)

        val ada = result.attendees.first()
        assertThat(ada.name).isEqualTo("Ada Lovelace")
        assertThat(ada.title).isEqualTo("Chief Scientist")
        assertThat(ada.company).isEqualTo("Analytical Engines")
        assertThat(ada.interests).containsExactly("compilers", "mathematics").inOrder()
        assertThat(ada.location).isEqualTo("Table 4")
    }

    @Test
    fun `each attendee gets a unique id`() {
        val payload = """{"attendees":[{"name":"A"},{"name":"B"},{"name":"C"}]}"""
        val result = client.parseEnrichment(geminiResponse(payload))
        assertThat(result.attendees.map { it.id }.toSet()).hasSize(3)
    }

    @Test
    fun `bios longer than 200 characters are clamped`() {
        val payload = """{"attendees":[{"name":"A","bio":"${"x".repeat(500)}"}]}"""
        val result = client.parseEnrichment(geminiResponse(payload))
        assertThat(result.attendees.single().bio).hasLength(200)
    }

    @Test
    fun `entries without a name are dropped`() {
        val payload = """{"attendees":[{"name":"A"},{"title":"No name"},{"name":"   "}]}"""
        val result = client.parseEnrichment(geminiResponse(payload))
        assertThat(result.attendees).hasSize(1)
    }

    @Test
    fun `a literal null location becomes null`() {
        val payload = """{"attendees":[{"name":"A","location":"null"}]}"""
        val result = client.parseEnrichment(geminiResponse(payload))
        assertThat(result.attendees.single().location).isNull()
    }

    @Test
    fun `missing optional fields default to empty rather than failing`() {
        val payload = """{"attendees":[{"name":"Solo"}]}"""
        val result = client.parseEnrichment(geminiResponse(payload))
        val person = result.attendees.single()
        assertThat(person.title).isEmpty()
        assertThat(person.company).isEmpty()
        assertThat(person.interests).isEmpty()
        assertThat(person.location).isNull()
    }

    // ---- Lenient parsing ------------------------------------------------------------

    @Test
    fun `json wrapped in a markdown fence is still parsed`() {
        val fenced = "```json\n{\"attendees\":[{\"name\":\"Ada\"}]}\n```"
        val result = client.parseEnrichment(geminiResponse(fenced))
        assertThat(result.attendees.single().name).isEqualTo("Ada")
    }

    @Test
    fun `json with chatter around it is still parsed`() {
        val chatty = "Here is what I found:\n{\"attendees\":[{\"name\":\"Ada\"}]}\nHope that helps!"
        val result = client.parseEnrichment(geminiResponse(chatty))
        assertThat(result.attendees.single().name).isEqualTo("Ada")
    }

    @Test
    fun `a bare array of attendees is accepted`() {
        val bare = """[{"name":"Ada"},{"name":"Grace"}]"""
        val result = client.parseEnrichment(geminiResponse(bare))
        assertThat(result.attendees).hasSize(2)
    }

    @Test
    fun `parseLenientJson handles all the shapes`() {
        assertThat(client.parseLenientJson("""{"attendees":[]}""")).isNotNull()
        assertThat(client.parseLenientJson("""```json {"attendees":[]} ```""")).isNotNull()
        assertThat(client.parseLenientJson("""noise {"attendees":[]} noise""")).isNotNull()
        assertThat(client.parseLenientJson("not json at all")).isNull()
    }

    // ---- Failure modes ----------------------------------------------------------------

    @Test(expected = AppError.NoUsableData::class)
    fun `an empty attendee array is a no-usable-data error`() {
        client.parseEnrichment(geminiResponse("""{"attendees":[]}"""))
    }

    @Test(expected = AppError.NoUsableData::class)
    fun `a response with no attendees key is a no-usable-data error`() {
        client.parseEnrichment(geminiResponse("""{"conference_name":"X"}"""))
    }

    @Test(expected = AppError.NoUsableData::class)
    fun `an empty response body is a no-usable-data error`() {
        client.parseEnrichment("""{"candidates":[]}""")
    }

    @Test(expected = AppError.ServiceFailure::class)
    fun `a safety block surfaces as a service failure`() {
        client.extractText("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
    }

    @Test(expected = AppError.ServiceFailure::class)
    fun `a safety finish reason surfaces as a service failure`() {
        client.extractText("""{"candidates":[{"finishReason":"SAFETY","content":{"parts":[]}}]}""")
    }

    @Test
    fun `multi-part responses are concatenated`() {
        val body = """
            {"candidates":[{"content":{"parts":[{"text":"{\"attendees\":["},{"text":"{\"name\":\"Ada\"}]}"}]}}]}
        """.trimIndent()
        val result = client.parseEnrichment(body)
        assertThat(result.attendees.single().name).isEqualTo("Ada")
    }

    // ---- Request construction -----------------------------------------------------------

    @Test
    fun `the request carries a response schema when grounding is off`() {
        val request = client.buildRequest(
            parts = listOf(GeminiRestClient.SourcePart.Text("Ada Lovelace, CTO")),
            contextHint = "",
            webGrounding = false,
        )
        val config = request["generationConfig"]!!.jsonObject
        assertThat(config["responseMimeType"]!!.jsonPrimitive.content).isEqualTo("application/json")
        assertThat(config["responseSchema"]).isNotNull()
        assertThat(request["tools"]).isNull()
    }

    @Test
    fun `the request uses search instead of a schema when grounding is on`() {
        val request = client.buildRequest(
            parts = listOf(GeminiRestClient.SourcePart.Text("Ada")),
            contextHint = "",
            webGrounding = true,
        )
        val config = request["generationConfig"]!!.jsonObject
        assertThat(config["responseSchema"]).isNull()
        assertThat(request["tools"]).isNotNull()
    }

    @Test
    fun `the response schema mirrors the design doc fields`() {
        val properties = GeminiRestClient.RESPONSE_SCHEMA["properties"]!!.jsonObject
        val item = properties["attendees"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject

        assertThat(item.keys).containsExactly(
            "name", "title", "company", "bio", "interests", "location",
        )
        // Embeddings are computed on-device; a model must never be asked for one.
        assertThat(item.keys).doesNotContain("embedding")
    }

    @Test
    fun `binary parts are sent as inline data`() {
        val request = client.buildRequest(
            parts = listOf(
                GeminiRestClient.SourcePart.Binary("application/pdf", byteArrayOf(1, 2, 3))
            ),
            contextHint = "",
            webGrounding = false,
        )
        val parts = request["contents"]!!.jsonArray.first()
            .jsonObject["parts"]!!.jsonArray
        val inline = parts.last().jsonObject["inline_data"]!!.jsonObject
        assertThat(inline["mime_type"]!!.jsonPrimitive.content).isEqualTo("application/pdf")
        assertThat(inline["data"]!!.jsonPrimitive.content).isNotEmpty()
    }

    @Test
    fun `the context hint reaches the prompt`() {
        val request = client.buildRequest(
            parts = listOf(GeminiRestClient.SourcePart.Text("x")),
            contextHint = "DevCon Berlin, infra track",
            webGrounding = false,
        )
        val firstText = request["contents"]!!.jsonArray.first()
            .jsonObject["parts"]!!.jsonArray.first()
            .jsonObject["text"]!!.jsonPrimitive.content
        assertThat(firstText).contains("DevCon Berlin, infra track")
    }

    @Test
    fun `safety settings are attached`() {
        val request = client.buildRequest(
            parts = listOf(GeminiRestClient.SourcePart.Text("x")),
            contextHint = "",
            webGrounding = false,
        )
        assertThat((request["safetySettings"] as JsonArray)).hasSize(4)
    }

    // ---- HTTP behaviour ------------------------------------------------------------------

    @Test
    fun `a successful call returns enriched attendees`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(geminiResponse("""{"attendees":[{"name":"Ada Lovelace"}]}"""))
        )

        val result = client.enrich(
            parts = listOf(GeminiRestClient.SourcePart.Text("Ada Lovelace")),
            model = "gemini-2.0-flash",
        )

        assertThat(result.attendees.single().name).isEqualTo("Ada Lovelace")

        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("generateContent")
        assertThat(recorded.path).contains("key=test-key")
    }

    @Test
    fun `a missing key raises MissingApiKey before any network call`() = runBlocking {
        every { keyStore.get(ApiService.GEMINI) } returns null
        try {
            client.enrich(listOf(GeminiRestClient.SourcePart.Text("x")), "gemini-2.0-flash")
            throw AssertionError("expected MissingApiKey")
        } catch (e: AppError.MissingApiKey) {
            assertThat(e.userMessage).contains("Gemini")
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a 401 becomes Unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        try {
            client.enrich(listOf(GeminiRestClient.SourcePart.Text("x")), "gemini-2.0-flash")
            throw AssertionError("expected Unauthorized")
        } catch (e: AppError.Unauthorized) {
            assertThat(e.recovery).contains("Settings")
        }
    }

    @Test
    fun `a 429 becomes RateLimited`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        try {
            client.enrich(listOf(GeminiRestClient.SourcePart.Text("x")), "gemini-2.0-flash")
            throw AssertionError("expected RateLimited")
        } catch (e: AppError.RateLimited) {
            assertThat(e.userMessage).contains("slow down")
        }
    }

    @Test
    fun `a 500 surfaces the server's own message`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setBody("""{"error":{"message":"Backend overloaded"}}""")
        )
        try {
            client.enrich(listOf(GeminiRestClient.SourcePart.Text("x")), "gemini-2.0-flash")
            throw AssertionError("expected ServiceFailure")
        } catch (e: AppError.ServiceFailure) {
            assertThat(e.recovery).contains("Backend overloaded")
        }
    }

    @Test(expected = AppError.NoUsableData::class)
    fun `enriching nothing fails fast`() = runBlocking {
        client.enrich(emptyList(), "gemini-2.0-flash")
        Unit
    }

    @Test
    fun `generateText returns the model's text`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(geminiResponse("Ask about Berlin.")))
        val text = client.generateText("prompt", "gemini-2.0-flash")
        assertThat(text).isEqualTo("Ask about Berlin.")
    }

    @Test
    fun `validateKey succeeds on 200`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"name":"models/gemini-2.0-flash"}"""))
        assertThat(client.validateKey("gemini-2.0-flash")).isTrue()
    }

    @Test
    fun `validateKey reports an invalid key as Unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            client.validateKey("gemini-2.0-flash")
            throw AssertionError("expected Unauthorized")
        } catch (e: AppError.Unauthorized) {
            assertThat(e.service).isEqualTo(GeminiRestClient.SERVICE)
        }
    }
}
