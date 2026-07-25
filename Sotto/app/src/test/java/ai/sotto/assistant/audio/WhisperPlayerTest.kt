package ai.sotto.assistant.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WhisperPlayerTest {

    @Test
    fun `a WAV header is stripped from the front of the buffer`() {
        val pcm = ByteArray(128) { (it % 127).toByte() }
        val wav = AudioFormats.wavHeader(pcm.size) + pcm

        val stripped = WhisperPlayer.stripWavHeader(wav)

        assertThat(stripped.size).isEqualTo(pcm.size)
        assertThat(stripped.toList()).containsExactlyElementsIn(pcm.toList()).inOrder()
    }

    @Test
    fun `bare PCM is passed through untouched`() {
        val pcm = ByteArray(128) { (it % 127).toByte() }
        assertThat(WhisperPlayer.stripWavHeader(pcm).toList())
            .containsExactlyElementsIn(pcm.toList()).inOrder()
    }

    @Test
    fun `a buffer too short to hold a header is passed through`() {
        val tiny = ByteArray(10) { 1 }
        assertThat(WhisperPlayer.stripWavHeader(tiny).size).isEqualTo(10)
    }

    @Test
    fun `an empty buffer is handled`() {
        assertThat(WhisperPlayer.stripWavHeader(ByteArray(0))).isEmpty()
    }

    @Test
    fun `a buffer that merely starts with RIFF-like bytes is not truncated`() {
        // Real PCM could coincidentally begin with 'R','I','F','F' but will not also
        // carry 'WAVE' at offset 8.
        val fake = ByteArray(100).also {
            it[0] = 'R'.code.toByte()
            it[1] = 'I'.code.toByte()
            it[2] = 'F'.code.toByte()
            it[3] = 'F'.code.toByte()
        }
        assertThat(WhisperPlayer.stripWavHeader(fake).size).isEqualTo(100)
    }

    @Test
    fun `stripping is idempotent`() {
        val pcm = ByteArray(64) { it.toByte() }
        val once = WhisperPlayer.stripWavHeader(AudioFormats.wavHeader(pcm.size) + pcm)
        val twice = WhisperPlayer.stripWavHeader(once)
        assertThat(twice.toList()).containsExactlyElementsIn(once.toList()).inOrder()
    }
}
