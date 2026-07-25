package ai.sotto.assistant.ui.settings

import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.data.local.ApiService
import ai.sotto.assistant.data.local.SecureKeyStore
import ai.sotto.assistant.data.local.SottoSettings
import ai.sotto.assistant.di.AppContainer
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

    data class UiState(
        val settings: SottoSettings = SottoSettings(),
        val keyState: SecureKeyStore.KeyState = SecureKeyStore.KeyState(true, emptyMap()),
        val keyDrafts: Map<ApiService, String> = emptyMap(),
        val revealed: Set<ApiService> = emptySet(),
        val keyTest: KeyTestState = KeyTestState.IDLE,
        val keyTestMessage: String? = null,
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

    /** Round-trips the Gemini key against a cheap endpoint so the user knows it works. */
    fun testGeminiKey() {
        val draft = _state.value.keyDrafts[ApiService.GEMINI].orEmpty()
        if (draft.isNotBlank()) container.keyStore.put(ApiService.GEMINI, draft)

        _state.value = _state.value.copy(keyTest = KeyTestState.TESTING, keyTestMessage = null)
        viewModelScope.launch {
            try {
                container.geminiRest.validateKey(_state.value.settings.enrichmentModel)
                _state.value = _state.value.copy(
                    keyTest = KeyTestState.VALID,
                    keyTestMessage = "Your key works.",
                )
            } catch (e: AppError) {
                _state.value = _state.value.copy(
                    keyTest = KeyTestState.INVALID,
                    keyTestMessage = "${e.userMessage} ${e.recovery.orEmpty()}".trim(),
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    keyTest = KeyTestState.INVALID,
                    keyTestMessage = AppError.from(t).userMessage,
                )
            }
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
            try {
                val settings = _state.value.settings
                val audio = container.textToSpeech.synthesize(
                    text = "Ask them how the Berlin launch went.",
                    voiceName = settings.ttsVoice,
                    languageCode = settings.sttLanguage,
                    speakingRate = settings.speakingRate,
                )
                if (audio != null) {
                    container.whisperPlayer.volume = settings.whisperVolume
                    container.whisperPlayer.play(audio.pcm, audio.sampleRateHz)
                } else {
                    _state.value = _state.value.copy(
                        error = AppError.ServiceFailure("Text-to-Speech", "No audio came back."),
                    )
                }
            } catch (e: AppError) {
                _state.value = _state.value.copy(error = e)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = AppError.from(t))
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
        _state.value = _state.value.copy(savedNotice = null, keyTestMessage = null)
    }
}
