package ai.sotto.assistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.core.SLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous microphone capture.
 *
 * Design Doc 1 § Speech and Transcription: "The phone microphone captures ambient audio
 * continuously." One capture instance fans its frames out to every consumer — Cloud
 * Speech-to-Text, the Gemini Live socket and the VAD all read the same stream, because
 * only one process can hold the microphone.
 *
 * VOICE_COMMUNICATION is the right source here: it engages the platform's echo
 * canceller, which matters a lot when Sotto's own whisper is playing into an earpiece
 * a few centimetres from the mic.
 */
class AudioCapture(
    private val context: Context,
    private val frameBytes: Int = AudioFormats.FRAME_BYTES,
) : Closeable {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    private val _frames = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** A hot stream of 20 ms PCM frames. Dropped rather than queued if a consumer stalls. */
    val frames: Flow<ByteArray> = _frames.asSharedFlow()

    val isRecording: Boolean get() = running.get()

    /** Set while Sotto is speaking, so we don't transcribe our own whisper. */
    @Volatile
    var muted: Boolean = false

    @SuppressLint("MissingPermission")
    @Throws(AppError::class)
    fun start() {
        if (running.get()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw AppError.PermissionDenied("Microphone")
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            AudioFormats.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw AppError.ServiceFailure("Microphone", "This device rejected 16 kHz mono capture.")
        }
        val bufferSize = maxOf(minBuffer * 2, frameBytes * 8)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AudioFormats.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    AudioFormats.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
        } catch (t: Throwable) {
            throw AppError.ServiceFailure("Microphone", t.message.orEmpty())
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            throw AppError.ServiceFailure("Microphone", "The microphone is in use by another app.")
        }

        attachEffects(recorder.audioSessionId)

        record = recorder
        running.set(true)
        recorder.startRecording()

        thread = Thread({ readLoop(recorder) }, "sotto-audio-capture").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
        SLog.i(TAG, "Microphone capture started (buffer=$bufferSize bytes)")
    }

    private fun readLoop(recorder: AudioRecord) {
        val buffer = ByteArray(frameBytes)
        val silence = ByteArray(frameBytes)
        while (running.get()) {
            val read = try {
                recorder.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                SLog.e(TAG, "Microphone read failed", t)
                break
            }
            when {
                read > 0 -> {
                    val frame = if (muted) silence.copyOf() else buffer.copyOf(read)
                    _frames.tryEmit(frame)
                }
                read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE ||
                    read == AudioRecord.ERROR_DEAD_OBJECT -> {
                    SLog.e(TAG, "Microphone returned error $read; stopping capture")
                    break
                }
            }
        }
    }

    private fun attachEffects(sessionId: Int) {
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
        }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        }
        runCatching {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { thread?.join(500) }
        thread = null
        record?.let { r ->
            runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
            runCatching { r.release() }
        }
        record = null
        listOfNotNull(echoCanceler, noiseSuppressor, gainControl).forEach {
            runCatching { it.release() }
        }
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
        SLog.i(TAG, "Microphone capture stopped")
    }

    override fun close() = stop()

    private companion object {
        const val TAG = "AudioCapture"
    }
}
