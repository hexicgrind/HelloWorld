package ai.sotto.assistant.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The VAD gates both transcription and the pause detector, so its behaviour is what
 * decides whether Sotto interrupts at the right moment or the wrong one.
 */
class VoiceActivityDetectorTest {

    private lateinit var vad: VoiceActivityDetector

    @Before
    fun setUp() {
        vad = VoiceActivityDetector()
    }

    // ---- Test signal helpers ---------------------------------------------------

    /** A frame of 16-bit PCM at the given amplitude (0..1). */
    private fun tone(amplitude: Float, frequency: Float = 220f): ByteArray {
        val samples = AudioFormats.FRAME_SAMPLES
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val t = i.toFloat() / AudioFormats.SAMPLE_RATE_HZ
            val value = (sin(2 * PI * frequency * t) * amplitude * Short.MAX_VALUE).toInt()
            val clamped = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[i * 2] = (clamped and 0xFF).toByte()
            bytes[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun silence(): ByteArray = ByteArray(AudioFormats.FRAME_BYTES)

    private fun roomNoise(amplitude: Float = 0.0015f, seed: Int = 5): ByteArray {
        val random = Random(seed)
        val samples = AudioFormats.FRAME_SAMPLES
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = ((random.nextFloat() * 2 - 1) * amplitude * Short.MAX_VALUE).toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun feed(frame: ByteArray, count: Int): VoiceActivityDetector.Frame {
        var last = vad.process(frame)
        repeat(count - 1) { last = vad.process(frame) }
        return last
    }

    // ---- Level measurement ------------------------------------------------------

    @Test
    fun `digital silence reports the floor level`() {
        assertThat(VoiceActivityDetector.rmsDb(silence()))
            .isEqualTo(VoiceActivityDetector.SILENCE_DB)
    }

    @Test
    fun `a loud tone measures near zero dBFS`() {
        val level = VoiceActivityDetector.rmsDb(tone(1.0f))
        // A full-scale sine has an RMS of -3 dBFS.
        assertThat(level).isGreaterThan(-6f)
        assertThat(level).isLessThan(0f)
    }

    @Test
    fun `halving amplitude drops the level by about six dB`() {
        val loud = VoiceActivityDetector.rmsDb(tone(0.8f))
        val quiet = VoiceActivityDetector.rmsDb(tone(0.4f))
        assertThat(loud - quiet).isWithin(0.5f).of(6.02f)
    }

    @Test
    fun `an empty buffer reports the floor`() {
        assertThat(VoiceActivityDetector.rmsDb(ByteArray(0)))
            .isEqualTo(VoiceActivityDetector.SILENCE_DB)
    }

    @Test
    fun `an odd-length buffer is handled without crashing`() {
        val level = VoiceActivityDetector.rmsDb(ByteArray(7) { 10 })
        assertThat(level.isNaN()).isFalse()
    }

    @Test
    fun `peak measures the loudest sample`() {
        assertThat(VoiceActivityDetector.peak(tone(0.5f))).isWithin(0.02f).of(0.5f)
        assertThat(VoiceActivityDetector.peak(silence())).isEqualTo(0f)
    }

    // ---- Speech detection -------------------------------------------------------

    @Test
    fun `silence is never speech`() {
        val result = feed(silence(), 60)
        assertThat(result.isSpeech).isFalse()
        assertThat(vad.isSpeaking).isFalse()
    }

    @Test
    fun `quiet room noise is never speech`() {
        val result = feed(roomNoise(), 100)
        assertThat(result.isSpeech).isFalse()
    }

    @Test
    fun `loud audio is detected as speech`() {
        feed(roomNoise(), 30)          // let the noise floor settle
        val result = feed(tone(0.4f), 10)
        assertThat(result.isSpeech).isTrue()
    }

    @Test
    fun `speech onset requires several frames - a single click is ignored`() {
        feed(roomNoise(), 30)
        val single = vad.process(tone(0.4f))
        assertThat(single.isSpeech).isFalse()
        assertThat(single.speechStarted).isFalse()
    }

    @Test
    fun `speechStarted fires exactly once at the beginning`() {
        feed(roomNoise(), 30)
        var starts = 0
        repeat(20) { if (vad.process(tone(0.4f)).speechStarted) starts++ }
        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun `speechEnded fires exactly once after the hangover`() {
        feed(roomNoise(), 30)
        feed(tone(0.4f), 15)

        var ends = 0
        repeat(60) { if (vad.process(silence()).speechEnded) ends++ }
        assertThat(ends).isEqualTo(1)
        assertThat(vad.isSpeaking).isFalse()
    }

    @Test
    fun `a short gap mid-sentence does not end speech`() {
        feed(roomNoise(), 30)
        feed(tone(0.4f), 15)

        // ~200ms of quiet: a breath between words, not the end of a turn.
        feed(silence(), 10)
        assertThat(vad.isSpeaking).isTrue()

        val resumed = feed(tone(0.4f), 5)
        assertThat(resumed.isSpeech).isTrue()
    }

    @Test
    fun `a long pause does end speech`() {
        feed(roomNoise(), 30)
        feed(tone(0.4f), 15)
        feed(silence(), VoiceActivityDetector.DEFAULT_HANGOVER_FRAMES + 5)
        assertThat(vad.isSpeaking).isFalse()
    }

    @Test
    fun `silenceMs grows while quiet`() {
        feed(roomNoise(), 30)
        feed(tone(0.4f), 15)
        feed(silence(), 50)
        assertThat(vad.silenceMs()).isAtLeast(50L * AudioFormats.FRAME_MS - AudioFormats.FRAME_MS)
    }

    @Test
    fun `silenceMs resets when speech resumes`() {
        feed(roomNoise(), 30)
        feed(silence(), 30)
        assertThat(vad.silenceMs()).isGreaterThan(0L)
        feed(tone(0.4f), 5)
        assertThat(vad.silenceMs()).isEqualTo(0L)
    }

    // ---- Adaptive noise floor ---------------------------------------------------

    @Test
    fun `the noise floor adapts to a quiet room`() {
        val result = feed(roomNoise(0.0008f), 60)
        assertThat(result.noiseFloorDb).isLessThan(VoiceActivityDetector.INITIAL_NOISE_FLOOR_DB + 5f)
    }

    @Test
    fun `speech is still detected above a loud room`() {
        // A noisy hall: the floor rises, but a person talking is still louder.
        feed(roomNoise(0.02f, seed = 9), 80)
        val result = feed(tone(0.5f), 10)
        assertThat(result.isSpeech).isTrue()
    }

    @Test
    fun `sustained speech does not drag the noise floor upward`() {
        feed(roomNoise(), 40)
        val floorBefore = vad.process(roomNoise()).noiseFloorDb

        feed(tone(0.6f), 100)   // a long monologue
        val floorAfter = vad.process(tone(0.6f)).noiseFloorDb

        assertThat(floorAfter).isWithin(1f).of(floorBefore)
    }

    // ---- Display level ----------------------------------------------------------

    @Test
    fun `display level stays inside zero to one`() {
        listOf(silence(), roomNoise(), tone(0.1f), tone(0.5f), tone(1.0f)).forEach { frame ->
            vad.process(frame)
            assertThat(vad.displayLevel).isAtLeast(0f)
            assertThat(vad.displayLevel).isAtMost(1f)
        }
    }

    @Test
    fun `display level rises with amplitude`() {
        vad.process(silence())
        val quiet = vad.displayLevel
        vad.process(tone(0.5f))
        val loud = vad.displayLevel
        assertThat(loud).isGreaterThan(quiet)
    }

    // ---- Reset -------------------------------------------------------------------

    @Test
    fun `reset returns the detector to its initial state`() {
        feed(roomNoise(), 30)
        feed(tone(0.5f), 20)
        assertThat(vad.isSpeaking).isTrue()

        vad.reset()

        assertThat(vad.isSpeaking).isFalse()
        assertThat(vad.lastLevelDb).isEqualTo(VoiceActivityDetector.SILENCE_DB)
        assertThat(vad.silenceMs()).isEqualTo(0L)
    }

    // ---- A realistic conversation -------------------------------------------------

    @Test
    fun `a two-turn conversation produces two utterances`() {
        var starts = 0
        var ends = 0

        fun run(frame: ByteArray, count: Int) {
            repeat(count) {
                val result = vad.process(frame)
                if (result.speechStarted) starts++
                if (result.speechEnded) ends++
            }
        }

        run(roomNoise(), 40)          // room tone
        run(tone(0.45f), 60)          // they speak
        run(roomNoise(), 40)          // pause
        run(tone(0.5f, 180f), 50)     // you reply
        run(roomNoise(), 40)          // pause

        assertThat(starts).isEqualTo(2)
        assertThat(ends).isEqualTo(2)
    }
}
