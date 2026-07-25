package ai.sotto.assistant.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioFormatsTest {

    @Test
    fun `capture format matches what Cloud Speech and Gemini Live expect`() {
        assertThat(AudioFormats.SAMPLE_RATE_HZ).isEqualTo(16_000)
        assertThat(AudioFormats.CHANNELS).isEqualTo(1)
        assertThat(AudioFormats.BITS_PER_SAMPLE).isEqualTo(16)
    }

    @Test
    fun `Gemini Live returns audio at 24 kHz`() {
        assertThat(AudioFormats.OUTPUT_SAMPLE_RATE_HZ).isEqualTo(24_000)
    }

    @Test
    fun `a frame is twenty milliseconds`() {
        assertThat(AudioFormats.FRAME_MS).isEqualTo(20)
        assertThat(AudioFormats.FRAME_SAMPLES).isEqualTo(320)
        assertThat(AudioFormats.FRAME_BYTES).isEqualTo(640)
    }

    @Test
    fun `the mime type advertises the capture rate`() {
        assertThat(AudioFormats.MIME_LINEAR16).isEqualTo("audio/pcm;rate=16000")
    }

    @Test
    fun `byte and millisecond conversions round-trip`() {
        listOf(20L, 100L, 1_000L, 30_000L).forEach { ms ->
            val bytes = AudioFormats.millisToBytes(ms)
            assertThat(AudioFormats.bytesToMillis(bytes)).isEqualTo(ms)
        }
    }

    @Test
    fun `one second of audio is thirty two kilobytes`() {
        assertThat(AudioFormats.millisToBytes(1_000L)).isEqualTo(32_000)
    }

    @Test
    fun `the wav header is a valid 44-byte RIFF header`() {
        val pcmSize = 3_200
        val header = AudioFormats.wavHeader(pcmSize)

        assertThat(header).hasLength(44)
        assertThat(String(header, 0, 4)).isEqualTo("RIFF")
        assertThat(String(header, 8, 4)).isEqualTo("WAVE")
        assertThat(String(header, 12, 4)).isEqualTo("fmt ")
        assertThat(String(header, 36, 4)).isEqualTo("data")

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(buffer.getInt(4)).isEqualTo(36 + pcmSize)     // chunk size
        assertThat(buffer.getInt(16)).isEqualTo(16)              // PCM subchunk size
        assertThat(buffer.getShort(20)).isEqualTo(1.toShort())   // PCM format
        assertThat(buffer.getShort(22)).isEqualTo(1.toShort())   // mono
        assertThat(buffer.getInt(24)).isEqualTo(16_000)          // sample rate
        assertThat(buffer.getInt(28)).isEqualTo(32_000)          // byte rate
        assertThat(buffer.getShort(32)).isEqualTo(2.toShort())   // block align
        assertThat(buffer.getShort(34)).isEqualTo(16.toShort())  // bits per sample
        assertThat(buffer.getInt(40)).isEqualTo(pcmSize)         // data size
    }

    @Test
    fun `the wav header honours a custom sample rate`() {
        val header = AudioFormats.wavHeader(100, sampleRate = 24_000)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(buffer.getInt(24)).isEqualTo(24_000)
        assertThat(buffer.getInt(28)).isEqualTo(48_000)
    }
}
