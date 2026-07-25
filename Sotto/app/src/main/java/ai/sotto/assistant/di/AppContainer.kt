package ai.sotto.assistant.di

import ai.sotto.assistant.audio.AudioCapture
import ai.sotto.assistant.audio.BluetoothAudioManager
import ai.sotto.assistant.audio.SoundCues
import ai.sotto.assistant.audio.WhisperPlayer
import ai.sotto.assistant.core.DispatcherProvider
import ai.sotto.assistant.core.SLog
import ai.sotto.assistant.data.local.AttendeeRepository
import ai.sotto.assistant.data.local.SecureKeyStore
import ai.sotto.assistant.data.local.SettingsRepository
import ai.sotto.assistant.data.remote.GeminiLiveClient
import ai.sotto.assistant.data.remote.GeminiRestClient
import ai.sotto.assistant.data.remote.SpeechToTextClient
import ai.sotto.assistant.data.remote.TextToSpeechClient
import ai.sotto.assistant.domain.DocumentExtractor
import ai.sotto.assistant.domain.EnrichmentUseCase
import ai.sotto.assistant.domain.SessionOrchestrator
import ai.sotto.assistant.vision.FaceMatcher
import ai.sotto.assistant.vision.FacePipeline
import ai.sotto.assistant.vision.MediaPipeFaceDetector
import ai.sotto.assistant.vision.TfLiteFaceEmbedder
import android.content.Context
import kotlinx.coroutines.runBlocking

/**
 * Manual dependency container.
 *
 * Hand-rolled rather than Hilt: the graph is small and entirely known at compile time,
 * and skipping annotation processing keeps builds fast and every collaborator trivially
 * swappable in tests.
 *
 * The two ML models are expensive to construct, so they are created lazily on first use
 * and shared for the process lifetime.
 */
class AppContainer(
    private val context: Context,
    val dispatchers: DispatcherProvider = DispatcherProvider.Default,
) {
    val appContext: Context = context.applicationContext

    // ---- Storage ---------------------------------------------------------------

    val keyStore: SecureKeyStore by lazy { SecureKeyStore.create(appContext) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val attendeeRepository: AttendeeRepository by lazy {
        AttendeeRepository(appContext.filesDir, dispatchers.io)
    }

    // ---- Remote ----------------------------------------------------------------

    val geminiRest: GeminiRestClient by lazy { GeminiRestClient(keyStore, dispatchers.io) }

    val geminiLive: GeminiLiveClient by lazy { GeminiLiveClient(keyStore) }

    val speechToText: SpeechToTextClient by lazy { SpeechToTextClient(keyStore, dispatchers.io) }

    val textToSpeech: TextToSpeechClient by lazy { TextToSpeechClient(keyStore, dispatchers.io) }

    // ---- Audio -----------------------------------------------------------------

    val audioCapture: AudioCapture by lazy { AudioCapture(appContext) }

    val whisperPlayer: WhisperPlayer by lazy { WhisperPlayer(appContext, dispatchers.io) }

    val bluetoothManager: BluetoothAudioManager by lazy { BluetoothAudioManager(appContext) }

    val soundCues: SoundCues by lazy {
        SoundCues(
            context = appContext,
            enabledProvider = { cachedSoundCuesEnabled },
            hapticsProvider = { cachedHapticsEnabled },
        )
    }

    @Volatile
    private var cachedSoundCuesEnabled = true

    @Volatile
    private var cachedHapticsEnabled = true

    fun updateCuePreferences(sound: Boolean, haptics: Boolean) {
        cachedSoundCuesEnabled = sound
        cachedHapticsEnabled = haptics
    }

    // ---- Vision ----------------------------------------------------------------

    val faceMatcher: FaceMatcher by lazy { FaceMatcher() }

    /**
     * Null when the models can't be loaded. Callers degrade rather than crash — an
     * install with a damaged asset should still let the user fix their settings.
     */
    val facePipeline: FacePipeline? by lazy {
        runCatching {
            FacePipeline(
                detector = MediaPipeFaceDetector.create(appContext),
                embedder = TfLiteFaceEmbedder.create(appContext),
                matcher = faceMatcher,
            )
        }.onFailure { SLog.e(TAG, "Face models unavailable", it) }.getOrNull()
    }

    // ---- Domain ----------------------------------------------------------------

    val documentExtractor: DocumentExtractor by lazy {
        DocumentExtractor(appContext, dispatchers.io)
    }

    val enrichmentUseCase: EnrichmentUseCase by lazy {
        EnrichmentUseCase(geminiRest, attendeeRepository, settingsRepository)
    }

    /** Null when the face models are unavailable; the live screen shows why. */
    val sessionOrchestrator: SessionOrchestrator? by lazy {
        facePipeline?.let { pipeline ->
            SessionOrchestrator(
                facePipeline = pipeline,
                audioCapture = audioCapture,
                speechToText = speechToText,
                liveClient = geminiLive,
                restClient = geminiRest,
                textToSpeech = textToSpeech,
                player = whisperPlayer,
                bluetooth = bluetoothManager,
                soundCues = soundCues,
                repository = attendeeRepository,
                settingsRepository = settingsRepository,
                dispatchers = dispatchers,
            )
        }
    }

    /** Warms the database so the first frame doesn't wait on disk. */
    fun preload() {
        runCatching {
            runBlocking {
                attendeeRepository.load()
                val s = settingsRepository.current()
                updateCuePreferences(s.soundCuesEnabled, s.hapticsEnabled)
            }
        }.onFailure { SLog.w(TAG, "Preload failed", it) }
    }

    fun release() {
        runCatching { sessionOrchestrator?.release() }
    }

    private companion object {
        const val TAG = "AppContainer"
    }
}
