package ai.sotto.assistant.data.remote

import ai.sotto.assistant.audio.AudioFormats
import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.data.local.ApiService
import ai.sotto.assistant.data.local.SecureKeyStore
import android.util.Base64
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Design Doc 1 § Speech and Transcription and § Audio Output.
 */
@RunWith(RobolectricTestRunner::class)
class SpeechClientsTest {

    private lateinit var server: MockWebServer
    private lateinit var keyStore: SecureKeyStore
    private lateinit var stt: SpeechToTextClient
    private lateinit var tts: TextToSpeechClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        keyStore = mockk(relaxed = true)
        every { keyStore.get(ApiService.SPEECH_TO_TEXT) } returns "stt-key"
        every { keyStore.get(ApiService.TEXT_TO_SPEECH) } returns "tts-key"

        val base = server.url("/").toString().trimEnd('/')
        stt = SpeechToTextClient(keyStore, Dispatchers.Unconfined, OkHttpClient(), base)
        tts = TextToSpeechClient(keyStore, Dispatchers.Unconfined, OkHttpClient(), base)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun audio(millis: Int): ByteArray = ByteArray(AudioFormats.millisToBytes(millis.toLong()))

    // ---- Speech-to-Text -------------------------------------------------------------

    @Test
    fun `a transcript is parsed with its confidence`() {
        val body = """
            {"results":[{"alternatives":[{"transcript":"How was Berlin?","confidence":0.94}]}]}
        """.trimIndent()

        val result = stt.parseResponse(body, "en-US")

        assertThat(result).isNotNull()
        assertThat(result!!.text).isEqualTo("How was Berlin?")
        assertThat(result.confidence).isWithin(1e-4f).of(0.94f)
        assertThat(result.languageCode).isEqualTo("en-US")
    }

    @Test
    fun `an empty results array means no speech, not an error`() {
        assertThat(stt.parseResponse("""{"results":[]}""", "en-US")).isNull()
        assertThat(stt.parseResponse("""{}""", "en-US")).isNull()
        assertThat(stt.parseResponse("", "en-US")).isNull()
    }

    @Test
    fun `a blank transcript is treated as no speech`() {
        val body = """{"results":[{"alternatives":[{"transcript":"   "}]}]}"""
        assertThat(stt.parseResponse(body, "en-US")).isNull()
    }

    @Test
    fun `a missing confidence defaults to zero`() {
        val body = """{"results":[{"alternatives":[{"transcript":"Hello"}]}]}"""
        assertThat(stt.parseResponse(body, "en-US")!!.confidence).isEqualTo(0f)
    }

    @Test
    fun `the transcript is trimmed`() {
        val body = """{"results":[{"alternatives":[{"transcript":"  Hello  "}]}]}"""
        assertThat(stt.parseResponse(body, "en-US")!!.text).isEqualTo("Hello")
    }

    @Test
    fun `audio shorter than the minimum is skipped without a network call`() = runBlocking {
        assertThat(stt.transcribe(audio(50))).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a real utterance is posted to the recognize endpoint`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"results":[{"alternatives":[{"transcript":"Hello","confidence":0.9}]}]}"""
            )
        )

        val result = stt.transcribe(audio(2_000), "en-US", listOf("Ada Lovelace"))

        assertThat(result!!.text).isEqualTo("Hello")

        val request = server.takeRequest()
        assertThat(request.path).contains("/v1/speech:recognize")
        assertThat(request.path).contains("key=stt-key")

        val body = request.body.readUtf8()
        assertThat(body).contains("LINEAR16")
        assertThat(body).contains("\"sampleRateHertz\":16000")
        assertThat(body).contains("Ada Lovelace")   // phrase hints improve name accuracy
    }

    @Test
    fun `a missing STT key raises MissingApiKey`() = runBlocking {
        every { keyStore.get(ApiService.SPEECH_TO_TEXT) } returns null
        try {
            stt.transcribe(audio(2_000))
            throw AssertionError("expected MissingApiKey")
        } catch (e: AppError.MissingApiKey) {
            assertThat(e.userMessage).contains("Speech-to-Text")
        }
    }

    @Test
    fun `a rejected STT key becomes Unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            stt.transcribe(audio(2_000))
            throw AssertionError("expected Unauthorized")
        } catch (e: AppError.Unauthorized) {
            assertThat(e.service).isEqualTo(SpeechToTextClient.SERVICE)
        }
    }

    @Test(expected = AppError.BadResponse::class)
    fun `unparseable JSON from STT becomes a BadResponse`() {
        stt.parseResponse("""{"results": [ {"alternatives": """, "en-US")
    }

    @Test
    fun `a wrongly typed results field is treated as no speech rather than an error`() {
        // Being lenient here is deliberate: a transcription hiccup must never take the
        // session down, and the live model hears the raw audio regardless.
        assertThat(stt.parseResponse("""{"results": "not an array"}""", "en-US")).isNull()
    }

    // ---- Text-to-Speech ---------------------------------------------------------------

    @Test
    fun `synthesised audio is base64 decoded`() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val body = """{"audioContent":"${Base64.encodeToString(pcm, Base64.NO_WRAP)}"}"""

        val decoded = tts.parseResponse(body)

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.toList()).containsExactlyElementsIn(pcm.toList()).inOrder()
    }

    @Test
    fun `a WAV header is stripped so the bytes go straight to AudioTrack`() {
        val pcm = ByteArray(64) { it.toByte() }
        val withHeader = AudioFormats.wavHeader(pcm.size) + pcm
        val body = """{"audioContent":"${Base64.encodeToString(withHeader, Base64.NO_WRAP)}"}"""

        val decoded = tts.parseResponse(body)

        assertThat(decoded!!.size).isEqualTo(pcm.size)
        assertThat(decoded.toList()).containsExactlyElementsIn(pcm.toList()).inOrder()
    }

    @Test
    fun `an empty response yields null`() {
        assertThat(tts.parseResponse("""{}""")).isNull()
        assertThat(tts.parseResponse("")).isNull()
    }

    @Test
    fun `an empty string is never synthesised`() = runBlocking {
        assertThat(tts.synthesize("   ")).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `synthesis posts to the synthesize endpoint with the chosen voice`() = runBlocking {
        val pcm = ByteArray(32) { 7 }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"audioContent":"${Base64.encodeToString(pcm, Base64.NO_WRAP)}"}""")
        )

        val audio = tts.synthesize("Ask about Berlin.", voiceName = "en-US-Neural2-J")

        assertThat(audio).isNotNull()
        assertThat(audio!!.sampleRateHz).isEqualTo(AudioFormats.OUTPUT_SAMPLE_RATE_HZ)

        val request = server.takeRequest()
        assertThat(request.path).contains("/v1/text:synthesize")
        assertThat(request.path).contains("key=tts-key")

        val body = request.body.readUtf8()
        assertThat(body).contains("en-US-Neural2-J")
        assertThat(body).contains("LINEAR16")
    }

    @Test
    fun `repeated text is served from the cache without a second call`() = runBlocking {
        val pcm = ByteArray(16) { 3 }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"audioContent":"${Base64.encodeToString(pcm, Base64.NO_WRAP)}"}""")
        )

        tts.synthesize("Ask about Berlin.")
        tts.synthesize("Ask about Berlin.")

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `different text bypasses the cache`() = runBlocking {
        val pcm = ByteArray(16) { 3 }
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"audioContent":"${Base64.encodeToString(pcm, Base64.NO_WRAP)}"}""")
            )
        }

        tts.synthesize("First line.")
        tts.synthesize("Second line.")

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a missing TTS key raises MissingApiKey`() = runBlocking {
        every { keyStore.get(ApiService.TEXT_TO_SPEECH) } returns null
        try {
            tts.synthesize("Hello")
            throw AssertionError("expected MissingApiKey")
        } catch (e: AppError.MissingApiKey) {
            assertThat(e.userMessage).contains("Text-to-Speech")
        }
    }

    // ---- SSML ------------------------------------------------------------------------

    @Test
    fun `whisper SSML wraps the text softly`() {
        val ssml = TextToSpeechClient.toWhisperSsml("Ask about Berlin.")
        assertThat(ssml).startsWith("<speak>")
        assertThat(ssml).endsWith("</speak>")
        assertThat(ssml).contains("volume=\"soft\"")
        assertThat(ssml).contains("Ask about Berlin.")
    }

    @Test
    fun `SSML escapes characters that would break the markup`() {
        val ssml = TextToSpeechClient.toWhisperSsml("""Tom & Jerry's <tag> "quote"""")
        assertThat(ssml).contains("&amp;")
        assertThat(ssml).contains("&lt;tag&gt;")
        assertThat(ssml).contains("&quot;")
        assertThat(ssml).contains("&apos;")
        assertThat(ssml).doesNotContain("<tag>")
    }

    @Test
    fun `the voice catalogue is populated and well formed`() {
        assertThat(TextToSpeechClient.VOICES).isNotEmpty()
        assertThat(TextToSpeechClient.VOICES.map { it.first })
            .contains(TextToSpeechClient.DEFAULT_VOICE)
        TextToSpeechClient.VOICES.forEach { (id, label) ->
            assertThat(id).isNotEmpty()
            assertThat(label).isNotEmpty()
        }
    }
}
