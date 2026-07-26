package ai.sotto.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import ai.sotto.assistant.R
import ai.sotto.assistant.core.SLog
import java.io.Closeable

/**
 * The app's sound and haptic vocabulary.
 *
 * These cues are the only thing standing between "the app is working" and "I have no
 * idea what this thing is doing", because the user is looking at a person, not at the
 * screen. They are deliberately quiet and short — nothing here should be audible to
 * the person you're talking to.
 */
class SoundCues(
    context: Context,
    private val enabledProvider: () -> Boolean = { true },
    private val hapticsProvider: () -> Boolean = { true },
) : Closeable {

    enum class Cue { MATCH, WHISPER, READY, STOP, ALERT }

    private companion object { const val TAG = "SoundCues" }

    private val appContext = context.applicationContext

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                // Same route as the spoken whispers (USAGE_ASSISTANT), so cues follow
                // media volume and reach the earpiece. USAGE_ASSISTANCE_SONIFICATION
                // lands on the system stream, which is silent on a phone in vibrate.
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val loaded = mutableSetOf<Int>()

    /**
     * Sample ids, populated after the load-complete listener is registered.
     *
     * Order matters here and used to be wrong: the loads were issued in a property
     * initialiser that ran *before* the `init` block installed the listener, so any
     * sample that finished loading in that window was never recorded as ready — and
     * these files are small enough to win that race almost every time. The cue then
     * silently refused to play forever, which is exactly what "vibrates but no chime"
     * looked like on device.
     */
    private val soundIds: Map<Cue, Int>

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                synchronized(loaded) { loaded.add(sampleId) }
            } else {
                SLog.w(TAG, "Sound cue $sampleId failed to load (status $status)")
            }
        }

        soundIds = buildMap {
            runCatching { put(Cue.MATCH, pool.load(appContext, R.raw.sfx_match, 1)) }
            runCatching { put(Cue.WHISPER, pool.load(appContext, R.raw.sfx_whisper, 1)) }
            runCatching { put(Cue.READY, pool.load(appContext, R.raw.sfx_ready, 1)) }
            runCatching { put(Cue.STOP, pool.load(appContext, R.raw.sfx_stop, 1)) }
            runCatching { put(Cue.ALERT, pool.load(appContext, R.raw.sfx_alert, 1)) }
        }
    }

    /** Cue volume relative to the whisper volume; cues sit well under speech. */
    @Volatile
    var volume: Float = 0.55f
        set(value) { field = value.coerceIn(0f, 1f) }

    fun play(cue: Cue) {
        if (!enabledProvider()) return
        val id = soundIds[cue] ?: return
        val gain = (volume * cueGain(cue)).coerceIn(0f, 1f)
        // play() on a not-yet-loaded sample simply returns 0, so there is no reason to
        // gate on the listener — gating was what silenced the cues in the first place.
        val stream = runCatching { pool.play(id, gain, gain, 1, 0, 1.0f) }.getOrDefault(0)
        if (stream == 0) SLog.d(TAG) { "Cue $cue not ready yet" }
    }

    fun vibrate(cue: Cue) {
        if (!hapticsProvider()) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = when (cue) {
            // A single soft tick is enough to say "someone was recognised" without
            // the user having to glance at the phone.
            Cue.MATCH -> VibrationEffect.createOneShot(28, 90)
            Cue.WHISPER -> VibrationEffect.createOneShot(18, 60)
            Cue.READY -> VibrationEffect.createWaveform(longArrayOf(0, 24, 60, 24), -1)
            Cue.STOP -> VibrationEffect.createOneShot(40, 70)
            Cue.ALERT -> VibrationEffect.createWaveform(longArrayOf(0, 45, 90, 45), -1)
        }
        runCatching { v.vibrate(effect) }
    }

    /** Sound plus haptic together — the normal way to fire a cue. */
    fun signal(cue: Cue) {
        play(cue)
        vibrate(cue)
    }

    private fun cueGain(cue: Cue): Float = when (cue) {
        Cue.WHISPER -> 0.55f   // barely there by design
        Cue.MATCH -> 0.85f
        Cue.READY, Cue.STOP -> 0.75f
        Cue.ALERT -> 0.9f
    }

    override fun close() {
        runCatching { pool.release() }
    }
}
