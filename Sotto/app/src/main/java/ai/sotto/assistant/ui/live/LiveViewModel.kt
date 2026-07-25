package ai.sotto.assistant.ui.live

import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.data.local.SottoSettings
import ai.sotto.assistant.data.model.ConferenceDatabase
import ai.sotto.assistant.data.model.PipelineHealth
import ai.sotto.assistant.data.model.Suggestion
import ai.sotto.assistant.data.model.TargetState
import ai.sotto.assistant.data.model.TranscriptLine
import ai.sotto.assistant.di.AppContainer
import ai.sotto.assistant.domain.SessionOrchestrator
import ai.sotto.assistant.vision.DetectedFace
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the live session screen. Owns nothing itself — the orchestrator owns the
 * pipelines — and simply projects their state into something the UI can render.
 */
class LiveViewModel(private val container: AppContainer) : ViewModel() {

    private val orchestrator: SessionOrchestrator? = container.sessionOrchestrator

    data class UiState(
        val sessionState: SessionOrchestrator.State = SessionOrchestrator.State.IDLE,
        val target: TargetState = TargetState.NoFace,
        val whisper: Suggestion? = null,
        val transcript: List<TranscriptLine> = emptyList(),
        val suggestions: List<Suggestion> = emptyList(),
        val health: PipelineHealth = PipelineHealth(),
        val micLevel: Float = 0f,
        val banner: AppError? = null,
        val settings: SottoSettings = SottoSettings(),
        val faceBox: DetectedFace? = null,
        val frameWidth: Int = 0,
        val frameHeight: Int = 0,
        val attendeeCount: Int = 0,
        val enrolledCount: Int = 0,
        val modelsAvailable: Boolean = true,
        val cameraError: String? = null,
    ) {
        val isRunning: Boolean get() = sessionState == SessionOrchestrator.State.RUNNING
        val isStarting: Boolean get() = sessionState == SessionOrchestrator.State.STARTING
    }

    /** What the session pipelines are reporting. */
    private data class SessionSlice(
        val sessionState: SessionOrchestrator.State,
        val target: TargetState,
        val whisper: Suggestion?,
        val transcript: List<TranscriptLine>,
        val suggestions: List<Suggestion>,
    )

    /** Everything outside the session proper: health, prefs, roster. */
    private data class ContextSlice(
        val health: PipelineHealth,
        val micLevel: Float,
        val banner: AppError?,
        val settings: SottoSettings,
        val database: ConferenceDatabase,
    )

    /** View-local concerns the orchestrator has no business knowing about. */
    private data class ViewSlice(
        val faceBox: DetectedFace?,
        val frameWidth: Int,
        val frameHeight: Int,
        val cameraError: String?,
    )

    private val frameSize = MutableStateFlow(0 to 0)
    private val cameraError = MutableStateFlow<String?>(null)

    private val _showTranscript = MutableStateFlow(false)
    val showTranscript: StateFlow<Boolean> = _showTranscript.asStateFlow()

    val state: StateFlow<UiState> = if (orchestrator == null) {
        MutableStateFlow(UiState(modelsAvailable = false)).asStateFlow()
    } else {
        val sessionFlow = combine(
            orchestrator.state,
            orchestrator.target,
            orchestrator.currentWhisper,
            orchestrator.transcript,
            orchestrator.suggestions,
            ::SessionSlice,
        )
        val contextFlow = combine(
            orchestrator.health,
            orchestrator.micLevel,
            orchestrator.banner,
            container.settingsRepository.settings,
            container.attendeeRepository.database,
            ::ContextSlice,
        )
        val viewFlow = combine(
            container.facePipeline?.lastFaceBox ?: MutableStateFlow(null),
            frameSize,
            cameraError,
        ) { box, size, error -> ViewSlice(box, size.first, size.second, error) }

        combine(sessionFlow, contextFlow, viewFlow) { session, context, view ->
            UiState(
                sessionState = session.sessionState,
                target = session.target,
                whisper = session.whisper,
                transcript = session.transcript,
                suggestions = session.suggestions,
                health = context.health,
                micLevel = context.micLevel,
                banner = context.banner,
                settings = context.settings,
                attendeeCount = context.database.size,
                enrolledCount = context.database.enrolledCount,
                faceBox = view.faceBox,
                frameWidth = view.frameWidth,
                frameHeight = view.frameHeight,
                cameraError = view.cameraError,
                modelsAvailable = true,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())
    }

    fun startSession() = orchestrator?.start()

    fun stopSession() = orchestrator?.stop()

    fun onFrame(bitmap: Bitmap, width: Int, height: Int) {
        if (frameSize.value.first != width || frameSize.value.second != height) {
            frameSize.value = width to height
        }
        orchestrator?.onCameraFrame(bitmap)
    }

    fun onCameraError(t: Throwable) {
        cameraError.value = t.message ?: "The camera could not be started."
    }

    fun toggleTranscript() {
        _showTranscript.value = !_showTranscript.value
    }

    fun dismissBanner() = orchestrator?.dismissBanner()

    fun clearTranscript() = orchestrator?.clearTranscript()

    fun replay(suggestion: Suggestion) = orchestrator?.replay(suggestion)

    override fun onCleared() {
        orchestrator?.stop()
        super.onCleared()
    }
}
