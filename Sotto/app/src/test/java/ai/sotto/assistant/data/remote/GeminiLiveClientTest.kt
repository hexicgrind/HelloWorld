package ai.sotto.assistant.data.remote

import ai.sotto.assistant.data.local.ApiService
import ai.sotto.assistant.data.local.SecureKeyStore
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Design Doc 1 § Testing Strategy: "Integration tests for Gemini Live API context
 * injection and response parsing."
 *
 * The socket setup frame is the contract with the Live API, so it is asserted field by
 * field — a silent change there is the kind of thing that only shows up at a conference.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiLiveClientTest {

    private lateinit var client: GeminiLiveClient
    private lateinit var keyStore: SecureKeyStore

    @Before
    fun setUp() {
        keyStore = mockk(relaxed = true)
        every { keyStore.get(ApiService.GEMINI) } returns "test-key"
        client = GeminiLiveClient(keyStore)
    }

    private fun setup(
        model: String = "models/gemini-2.0-flash-live-001",
        instruction: String = "You are Sotto.",
        allowSearch: Boolean = true,
    ): JsonObject = client.buildSetupFrame(model, instruction, allowSearch)["setup"]!!.jsonObject

    // ---- Setup frame -------------------------------------------------------------

    @Test
    fun `the setup frame names the model`() {
        assertThat(setup()["model"]!!.jsonPrimitive.content)
            .isEqualTo("models/gemini-2.0-flash-live-001")
    }

    @Test
    fun `a bare model name is normalised to the models prefix`() {
        assertThat(setup(model = "gemini-2.0-flash-live-001")["model"]!!.jsonPrimitive.content)
            .isEqualTo("models/gemini-2.0-flash-live-001")
    }

    @Test
    fun `the system instruction is injected`() {
        val instruction = setup(instruction = "Be brief and specific.")["systemInstruction"]!!
            .jsonObject["parts"]!!.jsonArray.first()
            .jsonObject["text"]!!.jsonPrimitive.content
        assertThat(instruction).isEqualTo("Be brief and specific.")
    }

    @Test
    fun `the response modality is TEXT so Cloud TTS provides the voice`() {
        // Design Doc 1 § Audio Output makes Cloud Text-to-Speech the voice of the
        // product; asking the Live API for audio would bypass it.
        val modalities = setup()["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray
        assertThat(modalities.map { it.jsonPrimitive.content }).containsExactly("TEXT")
    }

    @Test
    fun `web search is offered when allowed`() {
        val tools = setup(allowSearch = true)["tools"] as JsonArray
        assertThat(tools.first().jsonObject.keys).contains("googleSearch")
    }

    @Test
    fun `web search is absent when disallowed`() {
        assertThat(setup(allowSearch = false)["tools"]).isNull()
    }

    @Test
    fun `input transcription is requested`() {
        assertThat(setup()["inputAudioTranscription"]).isNotNull()
    }

    @Test
    fun `the output budget leaves room for the model to think before answering`() {
        // This used to assert a tight cap on the theory that a short answer needs few
        // tokens. Current Gemini models spend output tokens on internal reasoning first,
        // out of the same budget — so a tight cap produced answers that stopped
        // mid-sentence, which is exactly what a user reported hearing.
        val maxTokens = setup()["generationConfig"]!!.jsonObject["maxOutputTokens"]!!
            .jsonPrimitive.content.toInt()
        assertThat(maxTokens).isEqualTo(GeminiLiveClient.MAX_OUTPUT_TOKENS)
        assertThat(maxTokens).isAtLeast(512)
    }

    // ---- Endpoint ------------------------------------------------------------------

    @Test
    fun `the endpoint is the BidiGenerateContent websocket`() {
        assertThat(GeminiLiveClient.DEFAULT_BASE_URL).startsWith("wss://")
        assertThat(GeminiLiveClient.DEFAULT_BASE_URL).contains("BidiGenerateContent")
        assertThat(GeminiLiveClient.DEFAULT_BASE_URL).contains("generativelanguage.googleapis.com")
    }

    // ---- Lifecycle -------------------------------------------------------------------

    @Test
    fun `connecting without a key fails immediately rather than opening a socket`() {
        every { keyStore.get(ApiService.GEMINI) } returns null

        client.connect("models/x", "instruction", allowWebSearch = false)

        assertThat(client.status.value).isEqualTo(GeminiLiveClient.Status.FAILED)
        assertThat(client.isReady).isFalse()
    }

    @Test
    fun `a fresh client is idle and not ready`() {
        assertThat(client.status.value).isEqualTo(GeminiLiveClient.Status.IDLE)
        assertThat(client.isReady).isFalse()
    }

    @Test
    fun `sending before the session is ready is a safe no-op`() {
        // Every one of these would throw if it assumed an open socket.
        client.sendAudio(ByteArray(320))
        client.updateContext("[CONTEXT UPDATE] someone")
        client.requestDecision("should you speak?")
        assertThat(client.isReady).isFalse()
    }

    @Test
    fun `disconnecting an idle client is safe`() {
        client.disconnect()
        assertThat(client.status.value).isEqualTo(GeminiLiveClient.Status.CLOSED)
    }

    @Test
    fun `connect records the system prompt for inspection`() {
        every { keyStore.get(ApiService.GEMINI) } returns null
        client.connect("models/x", "the instruction", allowWebSearch = false)
        assertThat(client.systemPrompt).isEqualTo("the instruction")
    }

    // ---- Turn assembly -------------------------------------------------------------
    //
    // A user reported suggestions that were "total nonsense... rarely ever even a
    // complete thought". Streaming microphone audio into the live session means its
    // automatic voice-activity detection replies on its own whenever someone stops
    // talking. Those replies were emitted as whispers, and a real decision request
    // arriving mid-stream used to wipe the buffer under an in-flight turn.

    @Test
    fun `a requested turn is delivered`() {
        val turns = GeminiLiveClient.TurnAssembler()
        turns.expectTurn()
        turns.append("FOLLOW_UP: Ask about Berlin.")

        assertThat(turns.finish()).isEqualTo("FOLLOW_UP: Ask about Berlin.")
    }

    @Test
    fun `streamed fragments are reassembled into one turn`() {
        val turns = GeminiLiveClient.TurnAssembler()
        turns.expectTurn()
        turns.append("FOLLOW_UP: Ask how ")
        turns.append("the Berlin launch ")
        turns.append("went.")

        assertThat(turns.finish()).isEqualTo("FOLLOW_UP: Ask how the Berlin launch went.")
    }

    @Test
    fun `a turn nobody asked for is dropped`() {
        // The live model answering the conversation of its own accord.
        val turns = GeminiLiveClient.TurnAssembler()
        turns.append("Oh interesting, tell me more about that.")

        assertThat(turns.finish()).isNull()
    }

    @Test
    fun `an unrequested turn does not poison the next requested one`() {
        val turns = GeminiLiveClient.TurnAssembler()
        turns.append("spontaneous chatter")
        assertThat(turns.finish()).isNull()

        turns.expectTurn()
        turns.append("FOLLOW_UP: Ask about Berlin.")
        assertThat(turns.finish()).isEqualTo("FOLLOW_UP: Ask about Berlin.")
    }

    @Test
    fun `requesting a decision discards whatever was mid-flight`() {
        // Otherwise the tail of an abandoned turn gets glued onto the new one.
        val turns = GeminiLiveClient.TurnAssembler()
        turns.append("half of something unrelated")

        turns.expectTurn()
        turns.append("FOLLOW_UP: Ask about Berlin.")

        assertThat(turns.finish()).isEqualTo("FOLLOW_UP: Ask about Berlin.")
    }

    @Test
    fun `each request only satisfies one turn`() {
        val turns = GeminiLiveClient.TurnAssembler()
        turns.expectTurn()
        turns.append("FOLLOW_UP: Ask about Berlin.")
        assertThat(turns.finish()).isNotNull()

        turns.append("a second, unrequested turn")
        assertThat(turns.finish()).isNull()
    }

    @Test
    fun `an interruption clears the buffer and the request`() {
        val turns = GeminiLiveClient.TurnAssembler()
        turns.expectTurn()
        turns.append("FOLLOW_UP: Ask abo")

        turns.reset()

        turns.append("leftover")
        assertThat(turns.finish()).isNull()
    }

    @Test
    fun `an empty requested turn yields nothing rather than an empty whisper`() {
        val turns = GeminiLiveClient.TurnAssembler()
        turns.expectTurn()
        turns.append("   \n  ")

        assertThat(turns.finish()).isNull()
    }
}
