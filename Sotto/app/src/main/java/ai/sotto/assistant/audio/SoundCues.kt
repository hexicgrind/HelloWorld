package ai.sotto.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import ai.sotto.assistant.R
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

    private val appContext = context.applicationContext

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
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

    private val soundIds: Map<Cue, Int> = buildMap {
        runCatching { put(Cue.MATCH, pool.load(appContext, R.raw.sfx_match, 1)) }
        runCatching { put(Cue.WHISPER, pool.load(appContext, R.raw.sfx_whisper, 1)) }
        runCatching { put(Cue.READY, pool.load(appContext, R.raw.sfx_ready, 1)) }
        runCatching { put(Cue.STOP, pool.load(appContext, R.raw.sfx_stop, 1)) }
        runCatching { put(Cue.ALERT, pool.load(appContext, R.raw.sfx_alert, 1)) }
    }

    private val loaded = mutableSetOf<Int>()

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(loaded) { loaded.add(sampleId) }
        }
    }

    /** Cue volume relative to the whisper volume; cues sit well under speech. */
    @Volatile
    var volume: Float = 0.55f
        set(value) { field = value.coerceIn(0f, 1f) }

    fun play(cue: Cue) {
        if (!enabledProvider()) return
        val id = soundIds[cue] ?: return
        val isLoaded = synchronized(loaded) { id in loaded }
        if (!isLoaded) return
        val gain = volume * cueGain(cue)
        runCatching { pool.play(id, gain, gain, 1, 0, 1.0f) }
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
