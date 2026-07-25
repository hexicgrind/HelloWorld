package ai.sotto.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import ai.sotto.assistant.core.SLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays whispered suggestions to the earpiece.
 *
 * Design Doc 1 § Technical Stack: "Audio Output: Android AudioTrack API routed to
 * Bluetooth audio device." Using AudioTrack rather than MediaPlayer lets us stream PCM
 * as it arrives from either Cloud TTS or the Gemini Live socket, which is where the
 * doc's 200-500 ms output budget is won or lost.
 *
 * USAGE_ASSISTANT + CONTENT_TYPE_SPEECH is what tells Android this is a spoken
 * assistant cue, so the platform ducks music instead of talking over it and routes to
 * the connected headset by default.
 */
class WhisperPlayer(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : Closeable {

    private var track: AudioTrack? = null
    private var trackSampleRate = AudioFormats.OUTPUT_SAMPLE_RATE_HZ
    private val playing = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val lock = Any()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /** 0..1; applied as a linear gain on the track. */
    @Volatile
    var volume: Float = 0.85f
        set(value) {
            field = value.coerceIn(0f, 1f)
            synchronized(lock) { runCatching { track?.setVolume(field) } }
        }

    /**
     * Plays a complete PCM buffer and suspends until it has finished. A WAV header, if
     * present, is skipped.
     */
    suspend fun play(pcm: ByteArray, sampleRate: Int = AudioFormats.OUTPUT_SAMPLE_RATE_HZ) {
        if (pcm.isEmpty()) return
        val body = stripWavHeader(pcm)
        if (body.isEmpty()) return

        withContext(io) {
            cancelled.set(false)
            val t = obtainTrack(sampleRate) ?: return@withContext
            playing.set(true)
            _isSpeaking.value = true
            try {
                runCatching { t.play() }
                var offset = 0
                while (offset < body.size && !cancelled.get()) {
                    val chunk = minOf(CHUNK_BYTES, body.size - offset)
                    val written = t.write(body, offset, chunk, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) {
                        SLog.w(TAG, "AudioTrack.write returned $written; stopping playback")
                        break
                    }
                    offset += written
                }
                if (!cancelled.get()) {
                    // Let the hardware drain rather than clipping the last syllable.
                    val tailMs = (BUFFER_DRAIN_FRAMES * 1000L) / sampleRate
                    runCatching { Thread.sleep(tailMs.coerceIn(40L, 400L)) }
                }
            } catch (t2: Throwable) {
                SLog.e(TAG, "Playback failed", t2)
            } finally {
                playing.set(false)
                _isSpeaking.value = false
                synchronized(lock) { runCatching { track?.pause(); track?.flush() } }
            }
        }
    }

    /** Opens a streaming session for audio that arrives in pieces (Gemini Live). */
    fun beginStream(sampleRate: Int = AudioFormats.OUTPUT_SAMPLE_RATE_HZ) {
        synchronized(lock) {
            cancelled.set(false)
            val t = obtainTrack(sampleRate) ?: return
            playing.set(true)
            _isSpeaking.value = true
            runCatching { t.play() }
        }
    }

    /** Writes one chunk into an open stream. Safe to call from any thread. */
    fun writeStream(pcm: ByteArray) {
        if (cancelled.get()) return
        val t = synchronized(lock) { track } ?: return
        runCatching { t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) }
    }

    fun endStream() {
        synchronized(lock) {
            playing.set(false)
            _isSpeaking.value = false
            runCatching { track?.stop() }
        }
    }

    /** Cuts playback off immediately — used when the user starts talking again. */
    fun stop() {
        cancelled.set(true)
        synchronized(lock) {
            playing.set(false)
            _isSpeaking.value = false
            runCatching { track?.pause() }
            runCatching { track?.flush() }
        }
    }

    private fun obtainTrack(sampleRate: Int): AudioTrack? = synchronized(lock) {
        val existing = track
        if (existing != null && trackSampleRate == sampleRate &&
            existing.state == AudioTrack.STATE_INITIALIZED
        ) {
            return existing
        }
        runCatching { existing?.release() }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            SLog.e(TAG, "Device rejected ${sampleRate}Hz mono output")
            track = null
            return null
        }

        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, CHUNK_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }.getOrElse { t ->
            SLog.e(TAG, "Could not create AudioTrack", t)
            null
        }

        if (created == null || created.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { created?.release() }
            track = null
            return null
        }
        created.setVolume(volume)
        track = created
        trackSampleRate = sampleRate
        return created
    }

    /**
     * Design Doc 1 § Error Handling: "If the Bluetooth earpiece is disconnected, TTS
     * output defaults to phone speaker." AudioTrack does this for us — the platform
     * re-routes USAGE_ASSISTANT output to the speaker the moment the headset drops —
     * so all we do is report it.
     */
    fun describeCurrentRoute(): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val out = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.isSink }
                ?.productName
                ?.toString()
        }.getOrNull()
        return out?.takeIf { it.isNotBlank() } ?: "Phone speaker"
    }

    override fun close() {
        stop()
        synchronized(lock) {
            runCatching { track?.release() }
            track = null
        }
    }

    companion object {
        private const val TAG = "WhisperPlayer"
        private const val CHUNK_BYTES = 4_096
        private const val BUFFER_DRAIN_FRAMES = 2_048

        /** Strips a 44-byte RIFF header if the buffer has one. */
        fun stripWavHeader(data: ByteArray): ByteArray {
            if (data.size <= 44) return data
            val isRiff = data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
                data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte()
            val isWave = data[8] == 'W'.code.toByte() && data[9] == 'A'.code.toByte() &&
                data[10] == 'V'.code.toByte() && data[11] == 'E'.code.toByte()
            return if (isRiff && isWave) data.copyOfRange(44, data.size) else data
        }
    }
}
