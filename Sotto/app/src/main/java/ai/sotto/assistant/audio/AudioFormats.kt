package ai.sotto.assistant.audio

/**
 * Audio format constants shared by capture, transcription and playback.
 *
 * 16 kHz mono 16-bit little-endian PCM is the common denominator: it is what Google
 * Cloud Speech-to-Text wants for LINEAR16, what the Gemini Live API accepts for input
 * audio, and what every phone microphone can deliver without resampling.
 */
object AudioFormats {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = 2

    /** Gemini Live streams its audio back at 24 kHz. */
    const val OUTPUT_SAMPLE_RATE_HZ = 24_000

    /** 20 ms of audio — small enough for responsive VAD, big enough to be cheap. */
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE_HZ * FRAME_MS / 1000
    const val FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE

    val MIME_LINEAR16 = "audio/pcm;rate=$SAMPLE_RATE_HZ"

    fun bytesToMillis(bytes: Int): Long =
        bytes.toLong() * 1000L / (SAMPLE_RATE_HZ.toLong() * BYTES_PER_SAMPLE)

    fun millisToBytes(ms: Long): Int =
        (ms * SAMPLE_RATE_HZ * BYTES_PER_SAMPLE / 1000L).toInt()

    /**
     * Wraps raw PCM in a 44-byte RIFF/WAVE header. Google's TTS returns bare PCM for
     * LINEAR16 and some decoders insist on a header.
     */
    fun wavHeader(pcmByteCount: Int, sampleRate: Int = SAMPLE_RATE_HZ, channels: Int = CHANNELS): ByteArray {
        val byteRate = sampleRate * channels * BYTES_PER_SAMPLE
        val blockAlign = channels * BYTES_PER_SAMPLE
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcmByteCount)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)                       // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray())
        header.putInt(pcmByteCount)
        return header.array()
    }
}
