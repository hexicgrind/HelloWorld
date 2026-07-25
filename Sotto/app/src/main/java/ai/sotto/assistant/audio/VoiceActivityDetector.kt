package ai.sotto.assistant.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Energy-based voice activity detection with an adaptive noise floor.
 *
 * This is what turns the continuous microphone stream into utterances worth sending to
 * Speech-to-Text, and it is what tells the assistant pipeline when the room went quiet
 * — Design Doc 1 § Gemini Live API Integration: Gemini "identifies pauses where
 * interruption would feel natural".
 *
 * Pure Kotlin over a PCM byte array, so it is fully unit-testable off-device.
 */
class VoiceActivityDetector(
    /** How far above the running noise floor counts as speech, in dB. */
    private val speechMarginDb: Float = DEFAULT_SPEECH_MARGIN_DB,
    /** Frames of speech needed to declare speech started (debounce). */
    private val speechOnsetFrames: Int = DEFAULT_ONSET_FRAMES,
    /** Frames of quiet needed to declare speech ended (hangover). */
    private val speechHangoverFrames: Int = DEFAULT_HANGOVER_FRAMES,
    /** Absolute floor: below this we never call it speech, however quiet the room. */
    private val absoluteFloorDb: Float = DEFAULT_ABSOLUTE_FLOOR_DB,
) {
    private var noiseFloorDb: Float = INITIAL_NOISE_FLOOR_DB
    private var consecutiveSpeech = 0
    private var consecutiveSilence = 0
    private var calibrationFrames = 0

    var isSpeaking: Boolean = false
        private set

    /** Most recent frame level in dBFS, for the UI level meter. */
    var lastLevelDb: Float = SILENCE_DB
        private set

    /** 0..1 level for display, mapped from the useful part of the dB range. */
    val displayLevel: Float
        get() = ((lastLevelDb - DISPLAY_FLOOR_DB) / (0f - DISPLAY_FLOOR_DB)).coerceIn(0f, 1f)

    data class Frame(
        val levelDb: Float,
        val isSpeech: Boolean,
        /** True only on the frame where speech starts. */
        val speechStarted: Boolean,
        /** True only on the frame where speech ends. */
        val speechEnded: Boolean,
        val noiseFloorDb: Float,
    )

    /** Feeds one PCM frame (16-bit little-endian mono) and returns the decision. */
    fun process(pcm: ByteArray, length: Int = pcm.size): Frame {
        val level = rmsDb(pcm, length)
        lastLevelDb = level

        val loudEnough = level > absoluteFloorDb && level > noiseFloorDb + speechMarginDb

        // Adapt the noise floor only while quiet, so a long utterance can't drag the
        // floor up and deafen us mid-conversation.
        if (!loudEnough) {
            val alpha = if (calibrationFrames < CALIBRATION_FRAMES) FAST_ADAPT else SLOW_ADAPT
            noiseFloorDb = noiseFloorDb * (1 - alpha) + level * alpha
            calibrationFrames++
        }

        var started = false
        var ended = false

        if (loudEnough) {
            consecutiveSpeech++
            consecutiveSilence = 0
            if (!isSpeaking && consecutiveSpeech >= speechOnsetFrames) {
                isSpeaking = true
                started = true
            }
        } else {
            consecutiveSilence++
            consecutiveSpeech = 0
            if (isSpeaking && consecutiveSilence >= speechHangoverFrames) {
                isSpeaking = false
                ended = true
            }
        }

        return Frame(
            levelDb = level,
            isSpeech = isSpeaking,
            speechStarted = started,
            speechEnded = ended,
            noiseFloorDb = noiseFloorDb,
        )
    }

    /** Milliseconds of continuous quiet, derived from the hangover counter. */
    fun silenceMs(frameMs: Int = AudioFormats.FRAME_MS): Long =
        consecutiveSilence.toLong() * frameMs

    fun reset() {
        noiseFloorDb = INITIAL_NOISE_FLOOR_DB
        consecutiveSpeech = 0
        consecutiveSilence = 0
        calibrationFrames = 0
        isSpeaking = false
        lastLevelDb = SILENCE_DB
    }

    companion object {
        const val DEFAULT_SPEECH_MARGIN_DB = 9f
        const val DEFAULT_ONSET_FRAMES = 3      // ~60 ms
        const val DEFAULT_HANGOVER_FRAMES = 25  // ~500 ms
        const val DEFAULT_ABSOLUTE_FLOOR_DB = -52f
        const val INITIAL_NOISE_FLOOR_DB = -55f
        const val SILENCE_DB = -96f
        const val DISPLAY_FLOOR_DB = -60f

        private const val CALIBRATION_FRAMES = 25
        private const val FAST_ADAPT = 0.20f
        private const val SLOW_ADAPT = 0.02f

        /** RMS level of a 16-bit PCM buffer in dBFS. */
        fun rmsDb(pcm: ByteArray, length: Int = pcm.size): Float {
            val usable = (length / 2) * 2
            if (usable <= 0) return SILENCE_DB
            var sumSquares = 0.0
            var i = 0
            while (i < usable) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
                val norm = sample.toDouble() / 32768.0
                sumSquares += norm * norm
                i += 2
            }
            val rms = sqrt(sumSquares / (usable / 2))
            if (rms <= 1e-9) return SILENCE_DB
            return (20.0 * log10(rms)).toFloat().coerceAtLeast(SILENCE_DB)
        }

        /** Peak absolute amplitude, normalised to 0..1. */
        fun peak(pcm: ByteArray, length: Int = pcm.size): Float {
            val usable = (length / 2) * 2
            var peak = 0
            var i = 0
            while (i < usable) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
                val a = abs(sample.toInt())
                if (a > peak) peak = a
                i += 2
            }
            return (peak / 32768f).coerceIn(0f, 1f)
        }
    }
}
