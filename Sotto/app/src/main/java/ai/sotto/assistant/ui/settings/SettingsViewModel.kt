package ai.sotto.assistant.ui.settings

import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.data.local.ApiService
import ai.sotto.assistant.data.local.SecureKeyStore
import ai.sotto.assistant.data.local.SottoSettings
import ai.sotto.assistant.data.local.VoiceEngine
import ai.sotto.assistant.data.remote.GeminiRestClient
import ai.sotto.assistant.di.AppContainer
import ai.sotto.assistant.domain.ModelResolver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    enum class KeyTestState { IDLE, TESTING, VALID, INVALID }

    /** Per-service outcome, because one key does not mean three working APIs. */
    data class ServiceResult(val service: ApiService, val ok: Boolean, val detail: String)

    data class UiState(
        val settings: SottoSettings = SottoSettings(),
        val keyState: SecureKeyStore.KeyState = SecureKeyStore.KeyState(true, emptyMap()),
        val keyDrafts: Map<ApiService, String> = emptyMap(),
        val revealed: Set<ApiService> = emptySet(),
        val keyTest: KeyTestState = KeyTestState.IDLE,
        val keyTestMessage: String? = null,
        val serviceResults: List<ServiceResult> = emptyList(),
        val models: List<GeminiRestClient.ModelInfo> = emptyList(),
        val loadingModels: Boolean = false,
        val modelNotice: String? = null,
        val deviceVoices: List<ai.sotto.assistant.audio.DeviceTtsEngine.VoiceOption> = emptyList(),
        val error: AppError? = null,
        val savedNotice: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        container.settingsRepository.settings
            .onEach { settings -> _state.value = _state.value.copy(settings = settings) }
            .launchIn(viewModelScope)
        container.keyStore.state
            .onEach { keys -> _state.value = _state.value.copy(keyState = keys) }
            .launchIn(viewModelScope)

        // Fetch the model list on open when a key is present, so a retired model is
        // repaired before the user next tries to prepare data.
        if (container.keyStore.has(ApiService.GEMINI)) refreshModels()

        // The installed voices can only be listed once the engine has started.
        viewModelScope.launch {
            if (container.deviceTts.ensureReady()) {
                _state.value = _state.value.copy(deviceVoices = container.deviceTts.voicesFor())
            }
        }

        // Pre-fill the fields with whatever is already stored.
        _state.value = _state.value.copy(
            keyDrafts = ApiService.entries.associateWith {
                container.keyStore.peek(it).orEmpty()
            },
        )
    }

    // ---- API keys ---------------------------------------------------------------

    fun onKeyDraftChange(service: ApiService, value: String) {
        _state.value = _state.value.copy(
            keyDrafts = _state.value.keyDrafts + (service to value),
            keyTest = KeyTestState.IDLE,
            keyTestMessage = null,
        )
    }

    fun toggleReveal(service: ApiService) {
        val revealed = _state.value.revealed
        _state.value = _state.value.copy(
            revealed = if (service in revealed) revealed - service else revealed + service,
        )
    }

    fun saveKey(service: ApiService) {
        val draft = _state.value.keyDrafts[service].orEmpty()
        container.keyStore.put(service, draft)
        _state.value = _state.value.copy(
            savedNotice = "${service.displayName} key saved.",
            keyTest = KeyTestState.IDLE,
        )
    }

    fun saveAllKeys() {
        _state.value.keyDrafts.forEach { (service, value) ->
            container.keyStore.put(service, value)
        }
        _state.value = _state.value.copy(savedNotice = "Keys saved.")
    }

    fun setUseSharedKey(shared: Boolean) {
        container.keyStore.setUseSharedKey(shared)
    }

    fun clearKeys() {
        container.keyStore.clearAll()
        _state.value = _state.value.copy(
            keyDrafts = ApiService.entries.associateWith { "" },
            savedNotice = "All keys removed from this phone.",
        )
    }

    /**
     * Tests all three APIs, not just Gemini.
     *
     * Enabling the Generative Language API does not enable Cloud Speech-to-Text or
     * Cloud Text-to-Speech — they are separate services on the project. Reporting "your
     * key works" after checking only Gemini sent the user away confident and then
     * failed them at the first whisper.
     */
    fun testGeminiKey() {
        _state.value.keyDrafts.forEach { (service, value) ->
            if (value.isNotBlank()) container.keyStore.put(service, value)
        }

        _state.value = _state.value.copy(
            keyTest = KeyTestState.TESTING,
            keyTestMessage = null,
            serviceResults = emptyList(),
        )

        viewModelScope.launch {
            val results = mutableListOf<ServiceResult>()

            results += probe(ApiService.GEMINI) {
                container.geminiRest.validateKey(_state.value.settings.enrichmentModel)
            }
            results += probe(ApiService.SPEECH_TO_TEXT) { container.speechToText.validateKey() }
            results += probe(ApiService.TEXT_TO_SPEECH) { container.textToSpeech.validateKey() }

            val allOk = results.all { it.ok }
            _state.value = _state.value.copy(
                keyTest = if (allOk) KeyTestState.VALID else KeyTestState.INVALID,
                serviceResults = results,
                keyTestMessage = when {
                    allOk -> "All three services work."
                    results.first { it.service == ApiService.GEMINI }.ok ->
                        "Gemini works. The services below still need enabling — the app " +
                            "will run without them, it just won't speak or transcribe."
                    else -> "Gemini isn't working, so nothing else will either."
                },
            )

            if (results.first { it.service == ApiService.GEMINI }.ok) refreshModels()
        }
    }

    private suspend fun probe(service: ApiService, block: suspend () -> Boolean): ServiceResult =
        try {
            block()
            ServiceResult(service, true, "Working")
        } catch (e: AppError) {
            ServiceResult(service, false, "${e.userMessage} ${e.recovery.orEmpty()}".trim())
        } catch (t: Throwable) {
            ServiceResult(service, false, AppError.from(t, service.displayName).userMessage)
        }

    /**
     * Asks the API which models this key can use, and silently repairs the configured
     * model if it has been retired.
     */
    fun refreshModels() {
        _state.value = _state.value.copy(loadingModels = true, modelNotice = null)
        viewModelScope.launch {
            try {
                val models = container.geminiRest.listModels()
                _state.value = _state.value.copy(models = models, loadingModels = false)
                healRetiredModels(models)
            } catch (e: AppError) {
                _state.value = _state.value.copy(
                    loadingModels = false,
                    modelNotice = "Couldn't fetch the model list: ${e.userMessage}",
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loadingModels = false,
                    modelNotice = AppError.from(t).userMessage,
                )
            }
        }
    }

    private suspend fun healRetiredModels(models: List<GeminiRestClient.ModelInfo>) {
        val settings = _state.value.settings
        val enrichment = ModelResolver.resolve(
            settings.enrichmentModel, models, ModelResolver.Purpose.ENRICHMENT,
        )
        val live = ModelResolver.resolve(
            settings.geminiModel, models, ModelResolver.Purpose.LIVE,
        )

        if (enrichment.substituted || live.substituted) {
            container.settingsRepository.update {
                it.copy(enrichmentModel = enrichment.model, geminiModel = live.model)
            }
            _state.value = _state.value.copy(
                modelNotice = listOfNotNull(
                    enrichment.reason.takeIf { enrichment.substituted },
                    live.reason.takeIf { live.substituted },
                ).joinToString(" "),
            )
        }
    }

    // ---- Preferences ------------------------------------------------------------

    fun update(transform: (SottoSettings) -> SottoSettings) {
        viewModelScope.launch {
            container.settingsRepository.update(transform)
            val next = transform(_state.value.settings)
            container.updateCuePreferences(next.soundCuesEnabled, next.hapticsEnabled)
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            container.settingsRepository.resetToDefaults()
            _state.value = _state.value.copy(savedNotice = "Settings reset to their defaults.")
        }
    }

    /** Plays the chosen voice so the user can hear it before committing. */
    fun previewVoice() {
        viewModelScope.launch {
            val settings = _state.value.settings
            val line = "Ask them how the Berlin launch went."

            if (settings.voiceEngine == VoiceEngine.CLOUD) {
                val played = runCatching {
                    container.textToSpeech.synthesize(
                        text = line,
                        voiceName = settings.ttsVoice,
                        languageCode = settings.sttLanguage,
                        speakingRate = settings.speakingRate,
                    )?.also { audio ->
                        container.whisperPlayer.volume = settings.whisperVolume
                        container.whisperPlayer.play(audio.pcm, audio.sampleRateHz)
                    } != null
                }.getOrElse { false }
                if (played) return@launch

                _state.value = _state.value.copy(
                    savedNotice = "Cloud voices were rejected — that API needs service-account " +
                        "credentials, not a key. Played the phone's voice instead.",
                )
            }

            val spoken = container.deviceTts.speak(
                text = line,
                speakingRate = settings.speakingRate,
                volume = settings.whisperVolume,
                languageTag = settings.sttLanguage,
                voiceName = settings.deviceVoice.takeIf { it.isNotBlank() },
            )
            if (!spoken) {
                _state.value = _state.value.copy(
                    error = AppError.ServiceFailure(
                        "Speech",
                        "Your phone's speech engine didn't respond. Check Android Settings › " +
                            "Accessibility › Text-to-speech output.",
                    ),
                )
            }
        }
    }

    fun playCuePreview() {
        container.soundCues.signal(ai.sotto.assistant.audio.SoundCues.Cue.MATCH)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(
            savedNotice = null,
            keyTestMessage = null,
            modelNotice = null,
        )
    }
}
