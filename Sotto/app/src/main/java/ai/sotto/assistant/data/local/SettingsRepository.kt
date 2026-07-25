package ai.sotto.assistant.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("sotto_settings")

/**
 * Everything the user can tune. Defaults come straight from Design Doc 1, so an
 * untouched install behaves exactly as specified.
 */
data class SottoSettings(
    /** Design Doc 1: "confidence threshold (default zero point seven)". */
    val matchThreshold: Float = DEFAULT_MATCH_THRESHOLD,
    /** Design Doc 1: face "tracked for one second or more" before embedding. */
    val trackDwellMs: Int = DEFAULT_TRACK_DWELL_MS,
    /** Design Doc 1: "Every five to ten seconds, Gemini analyzes the transcript". */
    val suggestionIntervalSec: Int = DEFAULT_SUGGESTION_INTERVAL_SEC,
    /** Minimum quiet time that counts as a natural pause. */
    val pauseThresholdMs: Int = DEFAULT_PAUSE_THRESHOLD_MS,
    /** Design Doc 1: live web search is "an exception, not the rule". */
    val allowWebSearch: Boolean = true,
    val showTranscript: Boolean = false,
    val useCloudTranscription: Boolean = true,
    val useCloudTts: Boolean = true,
    val preferBluetoothOutput: Boolean = true,
    val whisperVolume: Float = DEFAULT_WHISPER_VOLUME,
    val soundCuesEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val ttsVoice: String = DEFAULT_TTS_VOICE,
    val speakingRate: Float = DEFAULT_SPEAKING_RATE,
    val geminiModel: String = DEFAULT_LIVE_MODEL,
    val enrichmentModel: String = DEFAULT_ENRICHMENT_MODEL,
    val sttLanguage: String = DEFAULT_LANGUAGE,
    /** Design Doc 1 § UI Components: "Settings panel for Gemini Live context configuration". */
    val customInstructions: String = "",
    val userName: String = "",
    val userRole: String = "",
    val userGoal: String = "",
    val onboardingComplete: Boolean = false,
) {
    companion object {
        const val DEFAULT_MATCH_THRESHOLD = 0.70f
        const val DEFAULT_TRACK_DWELL_MS = 1_000
        const val DEFAULT_SUGGESTION_INTERVAL_SEC = 7
        const val DEFAULT_PAUSE_THRESHOLD_MS = 1_200
        const val DEFAULT_WHISPER_VOLUME = 0.85f
        const val DEFAULT_SPEAKING_RATE = 1.08f
        const val DEFAULT_TTS_VOICE = "en-US-Neural2-C"
        const val DEFAULT_LANGUAGE = "en-US"
        const val DEFAULT_LIVE_MODEL = "models/gemini-2.0-flash-live-001"
        const val DEFAULT_ENRICHMENT_MODEL = "gemini-2.0-flash"

        val MATCH_THRESHOLD_RANGE = 0.40f..0.95f
        val SUGGESTION_INTERVAL_RANGE = 5..10
        val TRACK_DWELL_RANGE = 300..3_000
    }
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<SottoSettings> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    suspend fun current(): SottoSettings = settings.first()

    suspend fun update(transform: (SottoSettings) -> SottoSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs.writeSettings(transform(prefs.toSettings()))
        }
    }

    suspend fun resetToDefaults() {
        context.settingsDataStore.edit { prefs ->
            val onboarded = prefs[Keys.onboardingComplete] ?: false
            prefs.clear()
            prefs[Keys.onboardingComplete] = onboarded
        }
    }

    private fun Preferences.toSettings() = SottoSettings(
        matchThreshold = this[Keys.matchThreshold] ?: SottoSettings.DEFAULT_MATCH_THRESHOLD,
        trackDwellMs = this[Keys.trackDwellMs] ?: SottoSettings.DEFAULT_TRACK_DWELL_MS,
        suggestionIntervalSec = this[Keys.suggestionInterval]
            ?: SottoSettings.DEFAULT_SUGGESTION_INTERVAL_SEC,
        pauseThresholdMs = this[Keys.pauseThreshold] ?: SottoSettings.DEFAULT_PAUSE_THRESHOLD_MS,
        allowWebSearch = this[Keys.allowWebSearch] ?: true,
        showTranscript = this[Keys.showTranscript] ?: false,
        useCloudTranscription = this[Keys.useCloudStt] ?: true,
        useCloudTts = this[Keys.useCloudTts] ?: true,
        preferBluetoothOutput = this[Keys.preferBluetooth] ?: true,
        whisperVolume = this[Keys.whisperVolume] ?: SottoSettings.DEFAULT_WHISPER_VOLUME,
        soundCuesEnabled = this[Keys.soundCues] ?: true,
        hapticsEnabled = this[Keys.haptics] ?: true,
        ttsVoice = this[Keys.ttsVoice] ?: SottoSettings.DEFAULT_TTS_VOICE,
        speakingRate = this[Keys.speakingRate] ?: SottoSettings.DEFAULT_SPEAKING_RATE,
        geminiModel = this[Keys.liveModel] ?: SottoSettings.DEFAULT_LIVE_MODEL,
        enrichmentModel = this[Keys.enrichModel] ?: SottoSettings.DEFAULT_ENRICHMENT_MODEL,
        sttLanguage = this[Keys.language] ?: SottoSettings.DEFAULT_LANGUAGE,
        customInstructions = this[Keys.customInstructions] ?: "",
        userName = this[Keys.userName] ?: "",
        userRole = this[Keys.userRole] ?: "",
        userGoal = this[Keys.userGoal] ?: "",
        onboardingComplete = this[Keys.onboardingComplete] ?: false,
    )

    private fun androidx.datastore.preferences.core.MutablePreferences.writeSettings(s: SottoSettings) {
        this[Keys.matchThreshold] = s.matchThreshold.coerceIn(SottoSettings.MATCH_THRESHOLD_RANGE)
        this[Keys.trackDwellMs] = s.trackDwellMs.coerceIn(SottoSettings.TRACK_DWELL_RANGE)
        this[Keys.suggestionInterval] =
            s.suggestionIntervalSec.coerceIn(SottoSettings.SUGGESTION_INTERVAL_RANGE)
        this[Keys.pauseThreshold] = s.pauseThresholdMs.coerceIn(300, 5_000)
        this[Keys.allowWebSearch] = s.allowWebSearch
        this[Keys.showTranscript] = s.showTranscript
        this[Keys.useCloudStt] = s.useCloudTranscription
        this[Keys.useCloudTts] = s.useCloudTts
        this[Keys.preferBluetooth] = s.preferBluetoothOutput
        this[Keys.whisperVolume] = s.whisperVolume.coerceIn(0f, 1f)
        this[Keys.soundCues] = s.soundCuesEnabled
        this[Keys.haptics] = s.hapticsEnabled
        this[Keys.ttsVoice] = s.ttsVoice
        this[Keys.speakingRate] = s.speakingRate.coerceIn(0.5f, 2.0f)
        this[Keys.liveModel] = s.geminiModel
        this[Keys.enrichModel] = s.enrichmentModel
        this[Keys.language] = s.sttLanguage
        this[Keys.customInstructions] = s.customInstructions.take(2_000)
        this[Keys.userName] = s.userName.take(120)
        this[Keys.userRole] = s.userRole.take(200)
        this[Keys.userGoal] = s.userGoal.take(400)
        this[Keys.onboardingComplete] = s.onboardingComplete
    }

    private object Keys {
        val matchThreshold = floatPreferencesKey("match_threshold")
        val trackDwellMs = intPreferencesKey("track_dwell_ms")
        val suggestionInterval = intPreferencesKey("suggestion_interval_sec")
        val pauseThreshold = intPreferencesKey("pause_threshold_ms")
        val allowWebSearch = booleanPreferencesKey("allow_web_search")
        val showTranscript = booleanPreferencesKey("show_transcript")
        val useCloudStt = booleanPreferencesKey("use_cloud_stt")
        val useCloudTts = booleanPreferencesKey("use_cloud_tts")
        val preferBluetooth = booleanPreferencesKey("prefer_bluetooth")
        val whisperVolume = floatPreferencesKey("whisper_volume")
        val soundCues = booleanPreferencesKey("sound_cues")
        val haptics = booleanPreferencesKey("haptics")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val speakingRate = floatPreferencesKey("speaking_rate")
        val liveModel = stringPreferencesKey("live_model")
        val enrichModel = stringPreferencesKey("enrich_model")
        val language = stringPreferencesKey("stt_language")
        val customInstructions = stringPreferencesKey("custom_instructions")
        val userName = stringPreferencesKey("user_name")
        val userRole = stringPreferencesKey("user_role")
        val userGoal = stringPreferencesKey("user_goal")
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
    }
}
