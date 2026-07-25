package ai.sotto.assistant.ui.home

import ai.sotto.assistant.data.local.ApiService
import ai.sotto.assistant.data.local.SecureKeyStore
import ai.sotto.assistant.data.local.SottoSettings
import ai.sotto.assistant.data.model.AudioRoute
import ai.sotto.assistant.data.model.ConferenceDatabase
import ai.sotto.assistant.di.AppContainer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The dashboard. Its whole job is to answer one question at a glance: **can I start a
 * session right now, and if not, what's missing?**
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    data class ReadinessItem(
        val id: String,
        val title: String,
        val detail: String,
        val done: Boolean,
        val required: Boolean,
        val route: String,
    )

    data class UiState(
        val database: ConferenceDatabase = ConferenceDatabase.empty(),
        val settings: SottoSettings = SottoSettings(),
        val keyState: SecureKeyStore.KeyState = SecureKeyStore.KeyState(true, emptyMap()),
        val audioRoute: AudioRoute = AudioRoute.PHONE_SPEAKER,
        val audioDeviceName: String? = null,
        val cameraGranted: Boolean = false,
        val micGranted: Boolean = false,
        val modelsAvailable: Boolean = true,
        val loading: Boolean = true,
    ) {
        /** The hard requirements: without these there is nothing to start. */
        val canStartSession: Boolean
            get() = cameraGranted && micGranted && modelsAvailable && keyState.has(ApiService.GEMINI)

        val readiness: List<ReadinessItem>
            get() = listOf(
                ReadinessItem(
                    id = "permissions",
                    title = "Camera and microphone",
                    detail = if (cameraGranted && micGranted) {
                        "Granted"
                    } else {
                        "Sotto needs both to see faces and hear the conversation"
                    },
                    done = cameraGranted && micGranted,
                    required = true,
                    route = ai.sotto.assistant.ui.Routes.HOME,
                ),
                ReadinessItem(
                    id = "key",
                    title = "Google API key",
                    detail = when {
                        keyState.hasAll -> "All three services have a key"
                        keyState.has(ApiService.GEMINI) -> "Gemini is set up. Add Speech and Voice keys for the full experience."
                        else -> "Needed for the assistant, transcription and voice"
                    },
                    done = keyState.has(ApiService.GEMINI),
                    required = true,
                    route = ai.sotto.assistant.ui.Routes.SETTINGS,
                ),
                ReadinessItem(
                    id = "roster",
                    title = "Attendee list",
                    detail = when {
                        database.isEmpty -> "Optional — without one, Sotto gives general advice"
                        else -> "${database.size} people loaded"
                    },
                    done = !database.isEmpty,
                    required = false,
                    route = ai.sotto.assistant.ui.Routes.UPLOAD,
                ),
                ReadinessItem(
                    id = "faces",
                    title = "Enrolled faces",
                    detail = when {
                        database.isEmpty -> "Add an attendee list first"
                        database.enrolledCount == 0 -> "Optional — needed to recognise people by face"
                        else -> "${database.enrolledCount} of ${database.size} can be recognised"
                    },
                    done = database.enrolledCount > 0,
                    required = false,
                    route = ai.sotto.assistant.ui.Routes.ROSTER,
                ),
                ReadinessItem(
                    id = "earpiece",
                    title = "Earpiece",
                    detail = when (audioRoute) {
                        AudioRoute.BLUETOOTH -> audioDeviceName ?: "Bluetooth earpiece connected"
                        AudioRoute.WIRED_HEADSET -> "Wired headset connected"
                        AudioRoute.PHONE_SPEAKER -> "Using the phone speaker — everyone will hear it"
                    },
                    done = audioRoute != AudioRoute.PHONE_SPEAKER,
                    required = false,
                    route = ai.sotto.assistant.ui.Routes.EARPIECE,
                ),
            )

        val blockers: List<ReadinessItem> get() = readiness.filter { it.required && !it.done }
    }

    private val permissions = MutableStateFlow(false to false)

    val state: StateFlow<UiState> = combine(
        container.attendeeRepository.database,
        container.settingsRepository.settings,
        container.keyStore.state,
        container.bluetoothManager.state,
        permissions,
    ) { database, settings, keys, earpiece, perms ->
        UiState(
            database = database,
            settings = settings,
            keyState = keys,
            audioRoute = earpiece.route,
            audioDeviceName = earpiece.deviceName,
            cameraGranted = perms.first,
            micGranted = perms.second,
            modelsAvailable = container.facePipeline != null,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        // Keep the cue preferences in sync so SoundCues honours the toggles.
        container.settingsRepository.settings
            .onEach { container.updateCuePreferences(it.soundCuesEnabled, it.hapticsEnabled) }
            .launchIn(viewModelScope)
        viewModelScope.launch { container.attendeeRepository.load() }
    }

    fun onPermissionsChanged(camera: Boolean, microphone: Boolean) {
        permissions.value = camera to microphone
    }

    fun refreshAudioRoute() {
        container.bluetoothManager.refresh()
    }

    fun markOnboardingComplete() {
        viewModelScope.launch {
            container.settingsRepository.update { it.copy(onboardingComplete = true) }
        }
    }
}
