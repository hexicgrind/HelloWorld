package ai.sotto.assistant.audio

import ai.sotto.assistant.core.SLog
import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone's own speech engine.
 *
 * This exists because the design doc's choice of Google Cloud Text-to-Speech turned out
 * to be unusable as specified: Cloud TTS accepts only OAuth2 / service-account
 * credentials, never an API key, so a user with a perfectly good Gemini key simply
 * cannot make it speak — and putting a service-account JSON on a phone is a genuinely
 * bad thing to ask for.
 *
 * Android's built-in engine needs no key, no network and no account. It is the one path
 * that always works, so it is the default; Cloud TTS remains available for anyone who
 * has proper credentials.
 */
class DeviceTtsEngine(
    private val context: Context,
) : Closeable {

    private var engine: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private val utteranceCounter = AtomicLong(0)
    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val lock = Any()

    @Volatile
    private var initialised = false

    /** Starts the engine and waits for it to report readiness. Safe to call repeatedly. */
    suspend fun ensureReady(): Boolean {
        synchronized(lock) {
            if (!initialised) {
                initialised = true
                engine = TextToSpeech(context.applicationContext) { status ->
                    val ok = status == TextToSpeech.SUCCESS
                    if (!ok) SLog.w(TAG, "Device speech engine unavailable (status $status)")
                    configure()
                    if (!ready.isCompleted) ready.complete(ok)
                }
            }
        }
        return try {
            withTimeout(INIT_TIMEOUT_MS) { ready.await() }
        } catch (t: TimeoutCancellationException) {
            SLog.w(TAG, "Device speech engine did not initialise in time")
            false
        }
    }

    private fun configure() {
        val tts = engine ?: return
        runCatching {
            // Same routing as the whispers: assistant speech, so it follows media volume
            // and lands in the connected earpiece.
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        runCatching {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    complete(utteranceId, true)
                }

                @Deprecated("Required by the base class")
                override fun onError(utteranceId: String?) {
                    complete(utteranceId, false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    SLog.w(TAG, "Speech failed (code $errorCode)")
                    complete(utteranceId, false)
                }
            })
        }
    }

    private fun complete(utteranceId: String?, success: Boolean) {
        val id = utteranceId ?: return
        synchronized(lock) { pending.remove(id) }?.complete(success)
    }

    /**
     * Speaks [text] and suspends until it finishes. Returns false if the engine could
     * not say it, so the caller can fall back to showing the whisper on screen.
     */
    suspend fun speak(
        text: String,
        speakingRate: Float = 1.0f,
        volume: Float = 1.0f,
        languageTag: String = "en-US",
        voiceName: String? = null,
    ): Boolean {
        if (text.isBlank()) return false
        if (!ensureReady()) return false
        val tts = engine ?: return false

        runCatching {
            val locale = Locale.forLanguageTag(languageTag)
            if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = locale
            }
            voiceName?.let { name ->
                tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it }
            }
            tts.setSpeechRate(speakingRate.coerceIn(0.5f, 2.0f))
            // Slightly lower pitch reads as calmer in an earpiece.
            tts.setPitch(WHISPER_PITCH)
        }

        val id = "sotto-${utteranceCounter.incrementAndGet()}"
        val signal = CompletableDeferred<Boolean>()
        synchronized(lock) { pending[id] = signal }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
        }

        val queued = runCatching {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        }.getOrDefault(TextToSpeech.ERROR)

        if (queued != TextToSpeech.SUCCESS) {
            synchronized(lock) { pending.remove(id) }
            SLog.w(TAG, "Device speech engine refused the utterance")
            return false
        }

        return try {
            withTimeout(SPEAK_TIMEOUT_MS) { signal.await() }
        } catch (t: TimeoutCancellationException) {
            synchronized(lock) { pending.remove(id) }
            SLog.w(TAG, "Speech timed out")
            false
        }
    }

    /** Voices the installed engine offers for [languageTag], for the settings picker. */
    fun voicesFor(languageTag: String = "en"): List<VoiceOption> = runCatching {
        engine?.voices
            .orEmpty()
            .filter { it.locale.language.equals(Locale.forLanguageTag(languageTag).language, true) }
            .filterNot { it.isNetworkConnectionRequired && it.features.contains(Voice.QUALITY_VERY_LOW.toString()) }
            .sortedByDescending { it.quality }
            .map { VoiceOption(it.name, describe(it)) }
            .take(MAX_VOICES)
    }.getOrDefault(emptyList())

    private fun describe(voice: Voice): String {
        val quality = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "very high quality"
            voice.quality >= Voice.QUALITY_HIGH -> "high quality"
            voice.quality >= Voice.QUALITY_NORMAL -> "standard"
            else -> "basic"
        }
        val network = if (voice.isNetworkConnectionRequired) ", needs internet" else ", offline"
        return "${voice.locale.displayName} · $quality$network"
    }

    data class VoiceOption(val id: String, val label: String)

    fun stop() {
        runCatching { engine?.stop() }
        synchronized(lock) {
            pending.values.forEach { it.complete(false) }
            pending.clear()
        }
    }

    override fun close() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
    }

    private companion object {
        const val TAG = "DeviceTts"
        const val INIT_TIMEOUT_MS = 5_000L
        const val SPEAK_TIMEOUT_MS = 20_000L
        const val WHISPER_PITCH = 0.96f
        const val MAX_VOICES = 30
    }
}
