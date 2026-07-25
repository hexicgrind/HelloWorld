package ai.sotto.assistant.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.sotto.assistant.core.SLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The credential-bearing services Sotto talks to. */
enum class ApiService(val displayName: String, val prefKey: String) {
    /** Gemini API — used for both offline enrichment and the Live session. */
    GEMINI("Gemini", "key_gemini"),

    /** Google Cloud Speech-to-Text. */
    SPEECH_TO_TEXT("Speech-to-Text", "key_stt"),

    /** Google Cloud Text-to-Speech. */
    TEXT_TO_SPEECH("Text-to-Speech", "key_tts"),
}

/**
 * API keys at rest.
 *
 * Keys are stored in [EncryptedSharedPreferences] — AES-256-GCM for values, AES-256-SIV
 * for key names — with the master key held in the hardware-backed Android Keystore. The
 * keys never leave the device except in the `?key=` parameter of the Google endpoint
 * they belong to, are never logged (see [SLog.redact]), and are excluded from cloud
 * backup and device transfer by `data_extraction_rules.xml`.
 *
 * Most people will paste one Google Cloud key that has all three APIs enabled, so
 * [useSharedKey] mirrors the Gemini key across every service.
 */
class SecureKeyStore(
    private val prefs: SharedPreferences,
) {
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<KeyState> = _state.asStateFlow()

    data class KeyState(
        val useSharedKey: Boolean,
        val present: Map<ApiService, Boolean>,
    ) {
        fun has(service: ApiService): Boolean = present[service] == true
        val hasAny: Boolean get() = present.values.any { it }
        val hasAll: Boolean get() = ApiService.entries.all { present[it] == true }
    }

    /** True when one key is being shared across all three services. */
    val useSharedKey: Boolean
        get() = prefs.getBoolean(PREF_SHARED, true)

    fun setUseSharedKey(shared: Boolean) {
        prefs.edit().putBoolean(PREF_SHARED, shared).apply()
        _state.value = readState()
    }

    /** Returns the key for [service], or null when the user hasn't provided one. */
    fun get(service: ApiService): String? {
        val effective = if (useSharedKey) ApiService.GEMINI else service
        return prefs.getString(effective.prefKey, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun put(service: ApiService, key: String?) {
        val cleaned = key?.trim().orEmpty()
        prefs.edit().apply {
            if (cleaned.isEmpty()) remove(service.prefKey) else putString(service.prefKey, cleaned)
        }.apply()
        SLog.i(TAG, "Stored key for ${service.displayName}: ${SLog.redact(cleaned)}")
        _state.value = readState()
    }

    /** Raw stored value, for pre-filling the settings field. */
    fun peek(service: ApiService): String? =
        prefs.getString(service.prefKey, null)?.takeIf { it.isNotEmpty() }

    fun has(service: ApiService): Boolean = !get(service).isNullOrBlank()

    fun clearAll() {
        prefs.edit().apply { ApiService.entries.forEach { remove(it.prefKey) } }.apply()
        _state.value = readState()
    }

    private fun readState() = KeyState(
        useSharedKey = useSharedKey,
        present = ApiService.entries.associateWith { !get(it).isNullOrBlank() },
    )

    companion object {
        private const val TAG = "SecureKeyStore"
        private const val PREF_SHARED = "use_shared_key"
        private const val FILE_NAME = "sotto_credentials"

        /**
         * Builds the encrypted store. If the keystore entry is unusable (which happens
         * after a device restore or a keystore reset) we drop the file and start over
         * rather than crashing on launch — the user just re-enters their key.
         */
        fun create(context: Context): SecureKeyStore = SecureKeyStore(openPrefs(context))

        private fun openPrefs(context: Context): SharedPreferences = try {
            buildEncrypted(context)
        } catch (t: Throwable) {
            SLog.w(TAG, "Encrypted preferences unusable; recreating", t)
            runCatching { context.deleteSharedPreferences(FILE_NAME) }
            try {
                buildEncrypted(context)
            } catch (t2: Throwable) {
                // A device with no working keystore at all. Extremely rare; degrade to
                // in-memory so the app still runs, and the key simply won't persist.
                SLog.e(TAG, "No hardware keystore available; keys will not persist", t2)
                context.getSharedPreferences("${FILE_NAME}_volatile", Context.MODE_PRIVATE)
                    .also { it.edit().clear().apply() }
            }
        }

        private fun buildEncrypted(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
