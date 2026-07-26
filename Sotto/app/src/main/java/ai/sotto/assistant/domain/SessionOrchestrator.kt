package ai.sotto.assistant.domain

import ai.sotto.assistant.audio.AudioCapture
import ai.sotto.assistant.audio.AudioFormats
import ai.sotto.assistant.audio.BluetoothAudioManager
import ai.sotto.assistant.audio.DeviceTtsEngine
import ai.sotto.assistant.audio.SoundCues
import ai.sotto.assistant.audio.VoiceActivityDetector
import ai.sotto.assistant.audio.WhisperPlayer
import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.core.DispatcherProvider
import ai.sotto.assistant.core.SLog
import ai.sotto.assistant.data.local.AttendeeRepository
import ai.sotto.assistant.data.local.SettingsRepository
import ai.sotto.assistant.data.local.SottoSettings
import ai.sotto.assistant.data.local.VoiceEngine
import ai.sotto.assistant.data.model.Attendee
import ai.sotto.assistant.data.model.PipelineHealth
import ai.sotto.assistant.data.model.Speaker
import ai.sotto.assistant.data.model.Suggestion
import ai.sotto.assistant.data.model.SuggestionKind
import ai.sotto.assistant.data.model.TargetState
import ai.sotto.assistant.data.model.TranscriptLine
import ai.sotto.assistant.data.remote.GeminiLiveClient
import ai.sotto.assistant.data.remote.GeminiRestClient
import ai.sotto.assistant.data.remote.SpeechToTextClient
import ai.sotto.assistant.data.remote.TextToSpeechClient
import ai.sotto.assistant.vision.FacePipeline
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * The conductor for all four pipelines in Design Doc 1 § Core Architecture:
 * "face detection and matching, speech transcription, conversational AI
 * decision-making, and audio output."
 *
 * Everything the doc specifies about runtime behaviour is enforced here — the context
 * injection on target change, the pause-gated suggestion cadence, and every one of the
 * four fallbacks in § Error Handling.
 */
class SessionOrchestrator(
    private val facePipeline: FacePipeline,
    private val audioCapture: AudioCapture,
    private val speechToText: SpeechToTextClient,
    private val liveClient: GeminiLiveClient,
    private val restClient: GeminiRestClient,
    private val textToSpeech: TextToSpeechClient,
    private val player: WhisperPlayer,
    private val deviceTts: DeviceTtsEngine,
    private val bluetooth: BluetoothAudioManager,
    private val soundCues: SoundCues,
    private val repository: AttendeeRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private var sessionJob: Job? = null

    private val vad = VoiceActivityDetector()
    private val pauseDetector = PauseDetector()
    private val utteranceBuffer = ByteArrayOutputStream()
    private val idGenerator = AtomicLong(0)

    // ---- Observable state -----------------------------------------------------

    enum class State { IDLE, STARTING, RUNNING, STOPPING, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _health = MutableStateFlow(PipelineHealth())
    val health: StateFlow<PipelineHealth> = _health.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val transcript: StateFlow<List<TranscriptLine>> = _transcript.asStateFlow()

    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions.asStateFlow()

    private val _currentWhisper = MutableStateFlow<Suggestion?>(null)
    val currentWhisper: StateFlow<Suggestion?> = _currentWhisper.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _banner = MutableStateFlow<AppError?>(null)
    val banner: StateFlow<AppError?> = _banner.asStateFlow()

    val target: StateFlow<TargetState> get() = facePipeline.target

    // ---- Session-scoped mutable state -----------------------------------------

    @Volatile
    private var settings: SottoSettings = SottoSettings()

    @Volatile
    private var currentTargetId: String? = null

    /** When each person was last announced, for the re-recognition cooldown. */
    private val announcedAtMs = mutableMapOf<String, Long>()

    /** Model turns thrown away as unusable this session — visible in Diagnostics. */
    @Volatile
    var discardedTurns: Int = 0
        private set

    @Volatile
    private var liveReady = false

    @Volatile
    private var speaking = false

    private var lastSpeaker: Speaker = Speaker.UNKNOWN

    // ---- Lifecycle ------------------------------------------------------------

    /**
     * Starts everything. Design Doc 1 § Error Handling is explicit that a missing
     * database or an unreachable Gemini must not stop the session, so nothing in here
     * aborts on those conditions — they degrade instead.
     */
    fun start() {
        if (_state.value == State.RUNNING || _state.value == State.STARTING) return
        _state.value = State.STARTING
        _banner.value = null

        sessionJob = scope.launch {
            try {
                settings = settingsRepository.current()
                applySettings(settings)

                repository.load()
                val attendees = repository.current.attendees
                facePipeline.setAttendees(attendees)

                bluetooth.startMonitoring()
                if (settings.preferBluetoothOutput) bluetooth.startScoIfNeeded()

                audioCapture.start()
                _health.value = _health.value.copy(faceOk = true, audioOutOk = true)

                connectLive(attendees.isNotEmpty())

                launch { consumeAudio() }
                launch { consumeLiveEvents() }
                launch { watchTarget() }
                launch { watchSettings() }
                launch { decisionLoop() }

                _state.value = State.RUNNING
                soundCues.signal(SoundCues.Cue.READY)
                SLog.i(TAG, "Session running (${attendees.size} attendees loaded)")
            } catch (e: AppError) {
                SLog.e(TAG, "Session failed to start: ${e.userMessage}")
                _banner.value = e
                soundCues.signal(SoundCues.Cue.ALERT)
                shutdown(finalState = State.ERROR, playCue = false)
            } catch (t: Throwable) {
                val error = AppError.from(t)
                SLog.e(TAG, "Session failed to start", t)
                _banner.value = error
                shutdown(finalState = State.ERROR, playCue = false)
            }
        }
    }

    fun stop() = shutdown(finalState = State.IDLE, playCue = true)

    /**
     * Tears the session down.
     *
     * The pipeline coroutines are cancelled *before* the teardown coroutine is
     * launched, so the teardown is not itself a child being cancelled — otherwise
     * stopping would abandon its own cleanup half-finished and the session would never
     * reach a settled state.
     */
    private fun shutdown(finalState: State, playCue: Boolean) {
        if (_state.value == State.IDLE) return
        if (_state.value != State.ERROR) _state.value = State.STOPPING

        scope.coroutineContext.cancelChildren()
        sessionJob = null

        scope.launch {
            teardown()
            _state.value = finalState
            if (playCue) soundCues.signal(SoundCues.Cue.STOP)
            SLog.i(TAG, "Session stopped ($finalState)")
        }
    }

    private fun teardown() {
        runCatching { player.stop() }
        runCatching { deviceTts.stop() }
        runCatching { audioCapture.stop() }
        runCatching { liveClient.disconnect(notify = false) }
        runCatching { bluetooth.stopSco() }
        runCatching { bluetooth.stopMonitoring() }
        facePipeline.reset()
        pauseDetector.reset()
        vad.reset()
        synchronized(utteranceBuffer) { utteranceBuffer.reset() }
        currentTargetId = null
        announcedAtMs.clear()
        discardedTurns = 0
        liveReady = false
        reconnectAttempts = 0
        speaking = false
        audioCapture.muted = false
        _currentWhisper.value = null
        _micLevel.value = 0f
        _health.value = PipelineHealth()
    }

    /** Frames from CameraX. Runs on the analysis executor, not a coroutine. */
    fun onCameraFrame(bitmap: Bitmap) {
        if (_state.value != State.RUNNING) return
        runCatching { facePipeline.onFrame(bitmap) }
            .onFailure { SLog.w(TAG, "Frame processing failed", it) }
    }

    fun dismissBanner() {
        _banner.value = null
    }

    fun clearTranscript() {
        _transcript.value = emptyList()
    }

    // ---- Pipeline 2: transcription --------------------------------------------

    /**
     * Design Doc 1 § Speech and Transcription. Microphone frames fan out to the VAD, to
     * Gemini Live, and — utterance by utterance — to Cloud Speech-to-Text.
     */
    private fun CoroutineScope.consumeAudio() {
        audioCapture.frames
            .onEach { frame ->
                val decision = vad.process(frame)
                _micLevel.value = vad.displayLevel
                pauseDetector.onVoiceActivity(decision.isSpeech, clock())

                // Everything the mic hears goes to the live model, per § Gemini Live
                // API Integration: "Gemini Live receives a live audio stream".
                if (liveReady && !speaking) liveClient.sendAudio(frame)

                if (decision.isSpeech) {
                    synchronized(utteranceBuffer) {
                        if (utteranceBuffer.size() < MAX_UTTERANCE_BYTES) {
                            utteranceBuffer.write(frame)
                        }
                    }
                }

                if (decision.speechEnded) {
                    val utterance = synchronized(utteranceBuffer) {
                        val bytes = utteranceBuffer.toByteArray()
                        utteranceBuffer.reset()
                        bytes
                    }
                    if (utterance.size >= SpeechToTextClient.MIN_UTTERANCE_BYTES) {
                        launch { transcribeUtterance(utterance) }
                    }
                }
            }
            .launchIn(this)
    }

    private suspend fun transcribeUtterance(pcm: ByteArray) {
        if (!settings.useCloudTranscription) return
        try {
            val hints = PromptBuilder.speechHints(repository.current.attendees)
            val result = speechToText.transcribe(pcm, settings.sttLanguage, hints) ?: return
            appendTranscript(result.text, guessSpeaker(), result.confidence)
            _health.value = _health.value.copy(transcriptionOk = true)
        } catch (e: AppError) {
            // Transcription is a nice-to-have; the live model hears the raw audio
            // regardless. Surface it once and keep the session alive.
            _health.value = _health.value.copy(transcriptionOk = false)
            if (e is AppError.MissingApiKey || e is AppError.Unauthorized) {
                showBannerOnce(e)
            } else {
                SLog.w(TAG, "Transcription failed: ${e.userMessage}")
            }
        } catch (t: Throwable) {
            SLog.w(TAG, "Transcription failed", t)
            _health.value = _health.value.copy(transcriptionOk = false)
        }
    }

    /**
     * Speaker attribution without diarisation: the far speaker is louder in the phone's
     * mic when the phone is held toward them, and Sotto alternates naturally. This is a
     * heuristic and the UI labels it as such rather than claiming certainty.
     */
    private fun guessSpeaker(): Speaker {
        val next = if (lastSpeaker == Speaker.USER) Speaker.THEM else Speaker.USER
        lastSpeaker = next
        return next
    }

    private fun appendTranscript(text: String, speaker: Speaker, confidence: Float) {
        val line = TranscriptLine(
            id = idGenerator.incrementAndGet(),
            text = text,
            speaker = speaker,
            timestampMs = clock(),
            isFinal = true,
            confidence = confidence,
        )
        _transcript.value = (_transcript.value + line).takeLast(MAX_TRANSCRIPT_LINES)
        pauseDetector.onTranscript(clock())
    }

    // ---- Pipeline 1 -> 3: target change, context injection ---------------------

    private fun CoroutineScope.watchTarget() {
        facePipeline.target
            .onEach { state ->
                when (state) {
                    is TargetState.Matched -> {
                        if (state.attendee.id != currentTargetId) {
                            currentTargetId = state.attendee.id
                            onTargetAcquired(state.attendee)
                        }
                    }
                    is TargetState.NoFace, is TargetState.Unrecognised -> {
                        if (currentTargetId != null) {
                            currentTargetId = null
                            if (liveReady) liveClient.updateContext(PromptBuilder.clearedContext())
                        }
                    }
                    is TargetState.Tracking -> Unit
                }
            }
            .launchIn(this)
    }

    /**
     * True the first time someone is recognised, and again only once the cooldown has
     * elapsed.
     *
     * A face leaves the frame the moment its owner turns their head, and comes back a
     * second later. Without this, every glance re-ran the full acquisition — chime,
     * identity whisper, the lot — which is maddening when you are three minutes into a
     * conversation with the same person. The context injection still happens every time,
     * because that costs nothing and keeps the model correct; it is the *announcement*
     * that gets suppressed.
     */
    private fun shouldAnnounce(attendeeId: String, now: Long): Boolean {
        val cooldownMs = settings.recognitionCooldownSec.coerceAtLeast(0) * 1_000L
        val last = announcedAtMs[attendeeId]
        if (last != null && cooldownMs > 0L && now - last < cooldownMs) return false
        announcedAtMs[attendeeId] = now
        // Keep the map from growing without bound over a long session.
        if (announcedAtMs.size > MAX_TRACKED_ANNOUNCEMENTS) {
            announcedAtMs.entries
                .sortedBy { it.value }
                .take(announcedAtMs.size - MAX_TRACKED_ANNOUNCEMENTS)
                .forEach { announcedAtMs.remove(it.key) }
        }
        return true
    }

    private suspend fun onTargetAcquired(attendee: Attendee) {
        val announce = shouldAnnounce(attendee.id, clock())
        SLog.i(TAG, "Target: ${attendee.name}${if (announce) "" else " (within cooldown)"}")

        if (announce) soundCues.signal(SoundCues.Cue.MATCH)

        if (liveReady) {
            // § Runtime Flow: "The current target's information is injected into
            // Gemini Live's context."
            liveClient.updateContext(PromptBuilder.targetContext(attendee))
        } else if (announce) {
            // § Error Handling: "If Gemini Live API is unreachable, the app falls back
            // to simple TTS of the matched attendee's name and title."
            emitSuggestion(
                text = PromptBuilder.identityFallback(attendee),
                kind = SuggestionKind.IDENTITY,
                attendeeId = attendee.id,
                usedWebSearch = false,
            )
        }
    }

    // ---- Pipeline 3: the decision loop ----------------------------------------

    private suspend fun CoroutineScope.decisionLoop() {
        while (isActive) {
            delay(DECISION_TICK_MS)
            if (_state.value != State.RUNNING) continue
            if (speaking) continue

            val now = clock()
            if (!pauseDetector.shouldRequestSuggestion(now)) continue

            val attendee = (facePipeline.target.value as? TargetState.Matched)?.attendee
            val prompt = PromptBuilder.decisionPrompt(
                target = attendee,
                recentTranscript = _transcript.value,
                recentSuggestions = _suggestions.value.takeLast(PromptBuilder.MAX_RECENT_SUGGESTIONS)
                    .map { it.text },
                quietForMs = pauseDetector.quietMs(now),
            )

            if (liveReady) {
                liveClient.requestDecision(prompt)
            } else {
                requestViaRest(attendee, prompt)
            }
        }
    }

    /**
     * REST fallback for § Error Handling's "If Gemini Live API is unreachable". The
     * live socket is the fast path; plain generateContent still gives a useful whisper
     * when the socket won't stay up.
     */
    private suspend fun requestViaRest(attendee: Attendee?, prompt: String) {
        try {
            val text = restClient.generateText(
                prompt = prompt,
                model = settings.enrichmentModel,
                systemInstruction = PromptBuilder.systemInstruction(
                    settings,
                    hasDatabase = repository.current.attendees.isNotEmpty(),
                ),
            )
            val decision = PromptBuilder.parseDecision(text)
            if (decision.shouldSpeak) {
                emitSuggestion(
                    text = decision.text!!,
                    kind = decision.kind ?: SuggestionKind.FOLLOW_UP,
                    attendeeId = attendee?.id,
                    usedWebSearch = false,
                )
            }
            _health.value = _health.value.copy(assistantOk = true, degradedReason = "Using Gemini without the live link")
        } catch (e: AppError) {
            _health.value = _health.value.copy(assistantOk = false, degradedReason = e.userMessage)
            if (e is AppError.MissingApiKey || e is AppError.Unauthorized) showBannerOnce(e)
        } catch (t: Throwable) {
            _health.value = _health.value.copy(assistantOk = false)
        } finally {
            // Reopen the gate however this ended, or the loop stalls until the timeout.
            pauseDetector.onDecisionSettled()
        }
    }

    private fun CoroutineScope.consumeLiveEvents() {
        liveClient.events
            .onEach { event ->
                when (event) {
                    is GeminiLiveClient.Event.Ready -> {
                        liveReady = true
                        _health.value = _health.value.copy(assistantOk = true, degradedReason = null)
                        // Re-inject context for whoever is already in frame.
                        (facePipeline.target.value as? TargetState.Matched)?.let {
                            liveClient.updateContext(PromptBuilder.targetContext(it.attendee))
                        }
                    }

                    is GeminiLiveClient.Event.Turn -> {
                        pauseDetector.onDecisionSettled()
                        val decision = PromptBuilder.parseDecision(event.text)
                        if (decision.shouldSpeak) {
                            emitSuggestion(
                                text = decision.text!!,
                                kind = decision.kind ?: SuggestionKind.FOLLOW_UP,
                                attendeeId = currentTargetId,
                                usedWebSearch = event.usedWebSearch,
                            )
                        } else if (event.text.trim().equals(PromptBuilder.PASS_TOKEN, true)) {
                            SLog.d(TAG) { "Gemini chose to stay quiet" }
                        } else {
                            // Silence from a rejected fragment looks identical to silence
                            // from a deliberate PASS. Log the difference, so "it never
                            // says anything" can be told apart from "it says nothing
                            // useful" without guessing.
                            discardedTurns++
                            SLog.w(TAG, "Discarded an unusable model turn: ${event.text.take(120)}")
                        }
                    }

                    is GeminiLiveClient.Event.InputTranscript -> {
                        if (!settings.useCloudTranscription) {
                            appendTranscript(event.text, guessSpeaker(), 0f)
                            _health.value = _health.value.copy(transcriptionOk = true)
                        }
                    }

                    is GeminiLiveClient.Event.Failed -> {
                        liveReady = false
                        pauseDetector.onDecisionSettled()
                        _health.value = _health.value.copy(
                            assistantOk = false,
                            degradedReason = event.error.userMessage,
                        )
                        if (event.error is AppError.MissingApiKey ||
                            event.error is AppError.Unauthorized
                        ) {
                            showBannerOnce(event.error)
                        }
                        scheduleReconnect()
                    }

                    is GeminiLiveClient.Event.Closed -> {
                        liveReady = false
                        pauseDetector.onDecisionSettled()
                        if (_state.value == State.RUNNING) scheduleReconnect()
                    }
                }
            }
            .launchIn(this)
    }

    private var reconnectAttempts = 0

    private fun scheduleReconnect() {
        if (_state.value != State.RUNNING) return
        scope.launch {
            val attempt = ++reconnectAttempts
            if (attempt > MAX_RECONNECTS) {
                SLog.w(TAG, "Giving up on the live socket; staying on the REST fallback")
                return@launch
            }
            val backoff = (RECONNECT_BASE_MS * (1L shl (attempt - 1)))
                .coerceAtMost(RECONNECT_MAX_MS)
            delay(backoff)
            if (_state.value != State.RUNNING || liveReady) return@launch
            SLog.i(TAG, "Reconnecting the live socket (attempt $attempt)")
            connectLive(repository.current.attendees.isNotEmpty())
        }
    }

    private fun connectLive(hasDatabase: Boolean) {
        liveClient.connect(
            model = settings.geminiModel,
            systemInstruction = PromptBuilder.systemInstruction(settings, hasDatabase),
            allowWebSearch = settings.allowWebSearch,
        )
    }

    // ---- Pipeline 4: audio output ---------------------------------------------

    private fun emitSuggestion(
        text: String,
        kind: SuggestionKind,
        attendeeId: String?,
        usedWebSearch: Boolean,
    ) {
        val suggestion = Suggestion(
            id = idGenerator.incrementAndGet(),
            text = text,
            kind = kind,
            attendeeId = attendeeId,
            createdAtMs = clock(),
            usedWebSearch = usedWebSearch,
        )
        _suggestions.value = (_suggestions.value + suggestion).takeLast(MAX_SUGGESTIONS)
        _currentWhisper.value = suggestion
        scope.launch { speak(suggestion) }
    }

    /**
     * § Audio Output. Cloud TTS is the voice; if it fails we still show the whisper on
     * screen rather than dropping it silently, so the user can read what they'd have
     * heard.
     */
    private suspend fun speak(suggestion: Suggestion) {
        speaking = true
        // Don't transcribe our own whisper back into the conversation.
        audioCapture.muted = true
        soundCues.play(SoundCues.Cue.WHISPER)
        pauseDetector.onSuggestionSpoken(clock())

        try {
            reconnectAttempts = 0
            val spoken = when (settings.voiceEngine) {
                VoiceEngine.CLOUD -> speakViaCloud(suggestion) || speakViaDevice(suggestion)
                VoiceEngine.DEVICE -> speakViaDevice(suggestion)
            }
            _health.value = _health.value.copy(audioOutOk = spoken)

            _suggestions.value = _suggestions.value.map {
                if (it.id == suggestion.id) it.copy(spokenAtMs = clock()) else it
            }
        } catch (e: AppError) {
            _health.value = _health.value.copy(audioOutOk = false, degradedReason = e.userMessage)
            if (e is AppError.MissingApiKey || e is AppError.Unauthorized) showBannerOnce(e)
            SLog.w(TAG, "Whisper playback failed: ${e.userMessage}")
        } catch (t: Throwable) {
            SLog.w(TAG, "Whisper playback failed", t)
            _health.value = _health.value.copy(audioOutOk = false)
        } finally {
            speaking = false
            audioCapture.muted = false
            // Keep the whisper on screen briefly after it finishes playing.
            scope.launch {
                delay(WHISPER_DISPLAY_LINGER_MS)
                if (_currentWhisper.value?.id == suggestion.id) _currentWhisper.value = null
            }
        }
    }

    /**
     * Google Cloud Text-to-Speech. Only usable with real OAuth2 credentials — an API
     * key is always rejected — so a failure here is expected on most installs and must
     * fall through to the device engine rather than leaving the user in silence.
     */
    private suspend fun speakViaCloud(suggestion: Suggestion): Boolean = try {
        val audio = textToSpeech.synthesize(
            text = suggestion.text,
            voiceName = settings.ttsVoice,
            languageCode = settings.sttLanguage,
            speakingRate = settings.speakingRate,
        )
        if (audio != null) {
            player.volume = settings.whisperVolume
            player.play(audio.pcm, audio.sampleRateHz)
            true
        } else {
            false
        }
    } catch (e: AppError) {
        SLog.w(TAG, "Cloud voice unavailable, using the phone's: ${e.userMessage}")
        false
    } catch (t: Throwable) {
        SLog.w(TAG, "Cloud voice unavailable, using the phone's", t)
        false
    }

    /** The phone's own engine: no key, no network, always available. */
    private suspend fun speakViaDevice(suggestion: Suggestion): Boolean = try {
        deviceTts.speak(
            text = suggestion.text,
            speakingRate = settings.speakingRate,
            volume = settings.whisperVolume,
            languageTag = settings.sttLanguage,
            voiceName = settings.deviceVoice.takeIf { it.isNotBlank() },
        )
    } catch (t: Throwable) {
        SLog.w(TAG, "Device speech failed", t)
        false
    }

    /** Reads a whisper again on demand — the "replay" button on the live screen. */
    fun replay(suggestion: Suggestion) {
        scope.launch { speak(suggestion) }
    }

    // ---- Settings --------------------------------------------------------------

    private fun CoroutineScope.watchSettings() {
        settingsRepository.settings
            .onEach { updated ->
                val previous = settings
                settings = updated
                applySettings(updated)
                // The live session's system prompt is fixed at setup time, so a change
                // to the assistant's instructions needs a fresh socket.
                val promptChanged = previous.customInstructions != updated.customInstructions ||
                    previous.geminiModel != updated.geminiModel ||
                    previous.allowWebSearch != updated.allowWebSearch ||
                    previous.userName != updated.userName ||
                    previous.userRole != updated.userRole ||
                    previous.userGoal != updated.userGoal
                if (promptChanged && _state.value == State.RUNNING) {
                    connectLive(repository.current.attendees.isNotEmpty())
                }
            }
            .launchIn(this)
    }

    private fun applySettings(s: SottoSettings) {
        facePipeline.setThreshold(s.matchThreshold)
        facePipeline.dwellMs = s.trackDwellMs.toLong()
        pauseDetector.pauseThresholdMs = s.pauseThresholdMs.toLong()
        pauseDetector.minIntervalMs = s.suggestionIntervalSec * 1_000L
        player.volume = s.whisperVolume
        soundCues.volume = (s.whisperVolume * 0.6f).coerceIn(0f, 1f)
    }

    private fun showBannerOnce(error: AppError) {
        if (_banner.value?.javaClass != error.javaClass) _banner.value = error
    }

    fun release() {
        scope.coroutineContext.cancelChildren()
        teardown()
        runCatching { facePipeline.close() }
        runCatching { player.close() }
        runCatching { audioCapture.close() }
        runCatching { bluetooth.close() }
        runCatching { soundCues.close() }
    }

    companion object {
        private const val TAG = "Session"

        const val DECISION_TICK_MS = 500L
        const val MAX_TRANSCRIPT_LINES = 60
        const val MAX_SUGGESTIONS = 40
        const val MAX_TRACKED_ANNOUNCEMENTS = 500
        const val WHISPER_DISPLAY_LINGER_MS = 6_000L

        /** 30 seconds of audio; a single utterance never legitimately exceeds this. */
        const val MAX_UTTERANCE_BYTES = AudioFormats.SAMPLE_RATE_HZ * 2 * 30

        const val MAX_RECONNECTS = 5
        const val RECONNECT_BASE_MS = 1_500L
        const val RECONNECT_MAX_MS = 30_000L
    }
}
