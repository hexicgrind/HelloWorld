package ai.sotto.assistant.data.remote

import ai.sotto.assistant.audio.AudioFormats
import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.core.SLog
import ai.sotto.assistant.data.local.ApiService
import ai.sotto.assistant.data.local.SecureKeyStore
import android.util.Base64
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Gemini Live session — pipeline #3 of the four in Design Doc 1.
 *
 * § Gemini Live API Integration: "Gemini Live receives a live audio stream from the
 * phone microphone. The system injects context into Gemini's system prompt: the name,
 * title, company, and bio of the currently detected person. Gemini listens to the
 * conversation, understands natural speech rhythm, and identifies pauses where
 * interruption would feel natural."
 *
 * Transport note: the design doc says gRPC. The public Gemini Live surface exposes that
 * same `BidiGenerateContent` service over a WebSocket, which is what an API key can
 * authenticate against (gRPC here requires OAuth/service-account credentials — a much
 * worse thing to ask a user to load onto their phone). Same service, same message
 * shapes, key-friendly transport.
 *
 * Response modality is TEXT, not AUDIO, on purpose: Design Doc 1 § Audio Output makes
 * Google Cloud Text-to-Speech the voice of the product, so Gemini decides *what* to
 * whisper and Cloud TTS decides how it sounds.
 */
class GeminiLiveClient(
    private val keyStore: SecureKeyStore,
    private val client: OkHttpClient = HttpClients.streaming,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

    private var socket: WebSocket? = null
    private val open = AtomicBoolean(false)
    private val setupComplete = AtomicBoolean(false)
    private val turns = TurnAssembler()

    /**
     * Reassembles a streamed model turn, and remembers whether we asked for it.
     *
     * The live session streams microphone audio, so its automatic voice-activity
     * detection generates replies on its own every time someone stops speaking. Those
     * are answers aimed at the conversation, not whispers for the user's ear. They used
     * to be spoken verbatim, and worse, a decision request arriving while one was still
     * streaming would clear the buffer underneath it — so what finally got spoken was
     * the tail end of one turn glued to nothing. Between them, those two faults are why
     * suggestions read as half-formed nonsense.
     */
    internal class TurnAssembler {
        private val buffer = StringBuilder()
        private var requested = false

        /** Marks the next completed turn as one the app asked for. */
        @Synchronized
        fun expectTurn() {
            buffer.setLength(0)
            requested = true
        }

        @Synchronized
        fun reset() {
            buffer.setLength(0)
            requested = false
        }

        @Synchronized
        fun append(text: String) {
            buffer.append(text)
        }

        /** The finished turn, or null if it was unrequested or empty. */
        @Synchronized
        fun finish(): String? {
            val text = buffer.toString().trim()
            buffer.setLength(0)
            val wanted = requested
            requested = false
            return if (wanted && text.isNotEmpty()) text else null
        }
    }

    enum class Status { IDLE, CONNECTING, READY, RECONNECTING, FAILED, CLOSED }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: Flow<Event> = _events.asSharedFlow()

    sealed interface Event {
        data object Ready : Event
        /** A complete model turn: whatever Gemini decided to say (or not say). */
        data class Turn(val text: String, val usedWebSearch: Boolean) : Event
        /** Gemini's own transcription of the input audio, when enabled. */
        data class InputTranscript(val text: String) : Event
        data class Failed(val error: AppError) : Event
        data object Closed : Event
    }

    /** The context currently injected into the system prompt. */
    @Volatile
    var systemPrompt: String = ""
        private set

    private var pendingModel: String = ""
    private var pendingAllowSearch: Boolean = true
    private var reconnectAttempt = 0

    val isReady: Boolean get() = open.get() && setupComplete.get()

    /**
     * Opens the socket and sends the setup frame.
     *
     * Design Doc 1 § Error Handling: "If Gemini Live API is unreachable, the app falls
     * back to simple TTS of the matched attendee's name and title." This method reports
     * failure through [status] and [Event.Failed] rather than throwing, so the caller
     * can degrade instead of tearing the session down.
     */
    fun connect(
        model: String,
        systemInstruction: String,
        allowWebSearch: Boolean,
    ) {
        // Recorded before the credential check so the configured context is inspectable
        // even when the connection never gets off the ground.
        systemPrompt = systemInstruction

        val apiKey = keyStore.get(ApiService.GEMINI)
        if (apiKey.isNullOrBlank()) {
            _status.value = Status.FAILED
            _events.tryEmit(Event.Failed(AppError.MissingApiKey(ApiService.GEMINI.displayName)))
            return
        }

        disconnect(notify = false)

        pendingModel = model
        pendingAllowSearch = allowWebSearch
        _status.value = Status.CONNECTING
        setupComplete.set(false)
        turns.reset()

        val request = Request.Builder()
            .url("$baseUrl?key=$apiKey")
            .build()

        socket = client.newWebSocket(request, Listener())
        open.set(true)
    }

    /**
     * Replaces the injected context without dropping the socket. Design Doc 1
     * § Runtime Flow: "The current target's information is injected into Gemini Live's
     * context." That happens every time the person in front of the camera changes, so
     * it has to be cheap — a turn, not a reconnect.
     */
    fun updateContext(contextText: String) {
        if (!isReady) return
        val frame = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", contextText) })
                        }
                    })
                }
                // No turnComplete: this is context, not a request for a response.
                put("turnComplete", false)
            }
        }
        send(frame)
    }

    /** Streams one PCM frame of microphone audio into the live session. */
    fun sendAudio(pcm: ByteArray) {
        if (!isReady || pcm.isEmpty()) return
        val frame = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonArray("mediaChunks") {
                    add(buildJsonObject {
                        put("mimeType", AudioFormats.MIME_LINEAR16)
                        put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                    })
                }
            }
        }
        send(frame)
    }

    /**
     * Asks Gemini to decide whether this is the moment to whisper. Called on the
     * cadence from § Runtime Flow ("Every five to ten seconds, Gemini analyzes the
     * transcript and determines if a natural pause has occurred").
     */
    fun requestDecision(prompt: String) {
        if (!isReady) return
        turns.expectTurn()
        val frame = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", prompt) })
                        }
                    })
                }
                put("turnComplete", true)
            }
        }
        send(frame)
    }

    fun disconnect(notify: Boolean = true) {
        val s = socket
        socket = null
        open.set(false)
        setupComplete.set(false)
        runCatching { s?.close(NORMAL_CLOSURE, "session ended") }
        if (notify) {
            _status.value = Status.CLOSED
            _events.tryEmit(Event.Closed)
        }
    }

    override fun close() = disconnect()

    private fun send(frame: JsonObject) {
        val s = socket ?: return
        runCatching { s.send(json.encodeToString(JsonObject.serializer(), frame)) }
            .onFailure { SLog.w(TAG, "Failed to send a frame", it) }
    }

    private fun sendSetup() {
        val frame = buildSetupFrame(pendingModel, systemPrompt, pendingAllowSearch)
        send(frame)
    }

    internal fun buildSetupFrame(
        model: String,
        systemInstruction: String,
        allowWebSearch: Boolean,
    ): JsonObject = buildJsonObject {
        putJsonObject("setup") {
            put("model", if (model.startsWith("models/")) model else "models/$model")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("TEXT")) }
                put("temperature", 0.75)
                put("topP", 0.95)
                // Generous, despite the answer being one sentence. Current Gemini models
                // spend output tokens on internal reasoning before they emit anything,
                // and that comes out of the same budget — a tight cap doesn't produce a
                // short answer, it produces an answer that stops mid-sentence. Short
                // replies still only bill for what they use.
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", systemInstruction) })
                }
            }
            // Design Doc 1 § Runtime Flow: Gemini "can optionally request a live web
            // search to fetch additional information about the target".
            if (allowWebSearch) {
                putJsonArray("tools") {
                    add(buildJsonObject { putJsonObject("googleSearch") {} })
                }
            }
            // Gives us a transcript of what the mic heard, straight from the live model.
            putJsonObject("inputAudioTranscription") {}
        }
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            SLog.i(TAG, "Live socket open")
            reconnectAttempt = 0
            sendSetup()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            open.set(false)
            setupComplete.set(false)
            val error = when {
                response != null && (response.code == 401 || response.code == 403) ->
                    AppError.Unauthorized(SERVICE)
                response != null && response.code == 429 -> AppError.RateLimited(SERVICE)
                else -> AppError.from(t, SERVICE)
            }
            SLog.w(TAG, "Live socket failed: ${error.userMessage}", t)
            _status.value = Status.FAILED
            _events.tryEmit(Event.Failed(error))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            SLog.i(TAG, "Live socket closing ($code)")
            open.set(false)
            setupComplete.set(false)
            _status.value = Status.CLOSED
            _events.tryEmit(Event.Closed)
        }
    }

    private fun handle(raw: String) {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        if (root == null) {
            SLog.w(TAG, "Ignoring an unparseable live frame")
            return
        }

        if (root.containsKey("setupComplete")) {
            setupComplete.set(true)
            _status.value = Status.READY
            _events.tryEmit(Event.Ready)
            SLog.i(TAG, "Live session ready")
            return
        }

        root["serverContent"]?.jsonObject?.let { handleServerContent(it) }

        // A tool call means Gemini reached for web search. We acknowledge it so the
        // turn can finish; the search itself is executed server-side.
        root["toolCall"]?.let { sawWebSearch = true }
    }

    @Volatile
    private var sawWebSearch = false

    private fun handleServerContent(content: JsonObject) {
        (content["inputTranscription"] as? JsonObject)
            ?.get("text")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { _events.tryEmit(Event.InputTranscript(it)) }

        if (content["interrupted"]?.jsonPrimitive?.contentOrNull == "true") {
            turns.reset()
            return
        }

        (content["modelTurn"] as? JsonObject)
            ?.get("parts")?.let { it as? JsonArray }
            ?.forEach { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                    ?.let { turns.append(it) }
            }

        if (content["turnComplete"]?.jsonPrimitive?.contentOrNull != "true") return

        val completed = turns.finish()
        if (completed == null) {
            SLog.d(TAG) { "Ignoring a model turn nobody asked for" }
            return
        }

        val used = sawWebSearch
        sawWebSearch = false
        _events.tryEmit(Event.Turn(completed, used))
    }

    companion object {
        private const val TAG = "GeminiLive"
        const val SERVICE = "Gemini Live"
        const val NORMAL_CLOSURE = 1000
        const val MAX_OUTPUT_TOKENS = 1_024

        const val DEFAULT_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}
