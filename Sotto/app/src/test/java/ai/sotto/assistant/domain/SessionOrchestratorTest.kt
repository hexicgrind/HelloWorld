package ai.sotto.assistant.domain

import ai.sotto.assistant.audio.AudioCapture
import ai.sotto.assistant.audio.BluetoothAudioManager
import ai.sotto.assistant.audio.SoundCues
import ai.sotto.assistant.audio.WhisperPlayer
import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.core.DispatcherProvider
import ai.sotto.assistant.data.local.AttendeeRepository
import ai.sotto.assistant.data.local.SettingsRepository
import ai.sotto.assistant.data.local.SottoSettings
import ai.sotto.assistant.data.model.Attendee
import ai.sotto.assistant.data.model.ConferenceDatabase
import ai.sotto.assistant.data.model.SuggestionKind
import ai.sotto.assistant.data.remote.GeminiLiveClient
import ai.sotto.assistant.data.remote.GeminiRestClient
import ai.sotto.assistant.data.remote.SpeechToTextClient
import ai.sotto.assistant.data.remote.TextToSpeechClient
import ai.sotto.assistant.vision.DetectedFace
import ai.sotto.assistant.vision.FaceEmbedder
import ai.sotto.assistant.vision.FaceDetectorSource
import ai.sotto.assistant.vision.FaceMatcher
import ai.sotto.assistant.vision.FaceMath
import ai.sotto.assistant.vision.FacePipeline
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * The conductor, with fake models and mocked I/O.
 *
 * These tests exist mainly to hold Design Doc 1 § Error Handling to its word — all four
 * of its fallbacks are behaviours a user experiences, not implementation details.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionOrchestratorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var now = 10_000L

    private class FakeDetector(var faces: List<DetectedFace> = emptyList()) : FaceDetectorSource {
        override fun detect(bitmap: Bitmap) = faces
        override fun close() = Unit
    }

    private class FakeEmbedder(var embedding: FloatArray?) : FaceEmbedder {
        override val dimensions = 128
        override fun embed(alignedFace: Bitmap) = embedding
        override fun close() = Unit
    }

    private fun embedding(seed: Int): FloatArray {
        val random = Random(seed)
        return FaceMath.l2Normalize(FloatArray(128) { random.nextFloat() * 2 - 1 })
    }

    private val adaEmbedding = embedding(5)
    private val ada = Attendee(
        id = "ada",
        name = "Ada Lovelace",
        title = "Chief Scientist",
        company = "Analytical Engines",
        embedding = adaEmbedding.toList(),
    )

    private lateinit var detector: FakeDetector
    private lateinit var embedder: FakeEmbedder
    private lateinit var pipeline: FacePipeline
    private lateinit var repository: AttendeeRepository
    private lateinit var settings: SettingsRepository
    private lateinit var audioCapture: AudioCapture
    private lateinit var stt: SpeechToTextClient
    private lateinit var live: GeminiLiveClient
    private lateinit var rest: GeminiRestClient
    private lateinit var tts: TextToSpeechClient
    private lateinit var player: WhisperPlayer
    private lateinit var bluetooth: BluetoothAudioManager
    private lateinit var cues: SoundCues

    /** Lets a test drive the live socket's lifecycle events. */
    private val liveEvents = MutableSharedFlow<GeminiLiveClient.Event>(
        replay = 1,
        extraBufferCapacity = 8,
    )

    /**
     * Runs every orchestrator coroutine eagerly and inline, on a scheduler of its own.
     *
     * The isolated scheduler matters: the decision loop is an unbounded
     * `while (isActive) { delay(...) }`, so any attempt to advance a *shared* virtual
     * clock to idle would never terminate. Here the loop simply parks on its first
     * delay and everything else has already run.
     */
    private val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())

    private fun dispatchers(d: CoroutineDispatcher) = object : DispatcherProvider {
        override val main = d
        override val io = d
        override val default = d
    }

    @Before
    fun setUp() {
        now = 10_000L
        // A face is in frame by default; tests that need an empty frame clear this.
        detector = FakeDetector(listOf(visibleFace()))
        embedder = FakeEmbedder(adaEmbedding)
        pipeline = FacePipeline(detector, embedder, FaceMatcher(clock = { now }), clock = { now })

        repository = AttendeeRepository(temporaryFolder.root, Dispatchers.Unconfined) { now }

        settings = mockk(relaxed = true)
        every { settings.settings } returns flowOf(SottoSettings())
        coEvery { settings.current() } returns SottoSettings()

        audioCapture = mockk(relaxed = true)
        every { audioCapture.frames } returns emptyFlow()

        stt = mockk(relaxed = true)
        rest = mockk(relaxed = true)
        tts = mockk(relaxed = true)
        player = mockk(relaxed = true)
        bluetooth = mockk(relaxed = true)
        cues = mockk(relaxed = true)

        liveEvents.resetReplayCache()
        live = mockk(relaxed = true)
        every { live.events } returns liveEvents
        every { live.isReady } returns false
    }

    private fun orchestrator(dispatcher: CoroutineDispatcher) = SessionOrchestrator(
        facePipeline = pipeline,
        audioCapture = audioCapture,
        speechToText = stt,
        liveClient = live,
        restClient = rest,
        textToSpeech = tts,
        player = player,
        bluetooth = bluetooth,
        soundCues = cues,
        repository = repository,
        settingsRepository = settings,
        dispatchers = dispatchers(dispatcher),
        clock = { now },
    )

    private fun frame(): Bitmap {
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(110, 115, 120))
        val paint = Paint()
        for (i in 0 until 20) {
            paint.color = if (i % 2 == 0) Color.rgb(55, 60, 65) else Color.rgb(190, 195, 200)
            canvas.drawRect(0f, i * 32f, 480f, i * 32f + 32f, paint)
        }
        return bitmap
    }

    private fun visibleFace() = DetectedFace(RectF(180f, 240f, 300f, 360f), 0.95f)

    /** Feeds enough frames for the dwell requirement to be met. */
    private fun SessionOrchestrator.holdFaceInFrame(bitmap: Bitmap = frame()) {
        val end = now + 1_400L
        while (now <= end) {
            onCameraFrame(bitmap)
            now += 33L
        }
    }

    // ---- Lifecycle -----------------------------------------------------------------

    @Test
    fun `a session starts and reaches running`() {
        val session = orchestrator(dispatcher)

        session.start()

        assertThat(session.state.value).isEqualTo(SessionOrchestrator.State.RUNNING)
    }

    @Test
    fun `stopping settles back to idle`() {
        val session = orchestrator(dispatcher)

        session.start()
        session.stop()

        // This is what the old teardown got wrong: cancelling its own coroutine left the
        // session stuck in STOPPING forever.
        assertThat(session.state.value).isEqualTo(SessionOrchestrator.State.IDLE)
    }

    @Test
    fun `stopping an idle session is a no-op`() {
        val session = orchestrator(dispatcher)
        session.stop()
        assertThat(session.state.value).isEqualTo(SessionOrchestrator.State.IDLE)
    }

    @Test
    fun `starting twice does not restart the session`() {
        val session = orchestrator(dispatcher)

        session.start()
        session.start()

        assertThat(session.state.value).isEqualTo(SessionOrchestrator.State.RUNNING)
        io.mockk.verify(exactly = 1) { audioCapture.start() }
    }

    @Test
    fun `a microphone failure surfaces as an error rather than a crash`() {
        every { audioCapture.start() } throws AppError.PermissionDenied("Microphone")
        val session = orchestrator(dispatcher)

        session.start()

        assertThat(session.state.value).isEqualTo(SessionOrchestrator.State.ERROR)
        assertThat(session.banner.value).isInstanceOf(AppError.PermissionDenied::class.java)
    }

    // ---- Design Doc 1 § Error Handling -------------------------------------------------

    @Test
    fun `with no database loaded the session still runs`() {
        // "If no attendee database is loaded, face detection runs but no context is
        // passed to Gemini. Gemini can still provide generic networking advice."
        val session = orchestrator(dispatcher)

        session.start()

        assertThat(session.state.value).isEqualTo(SessionOrchestrator.State.RUNNING)
        assertThat(repository.current.isEmpty).isTrue()
    }

    @Test
    fun `when Gemini Live is unreachable a recognised face still gets an identity whisper`() {
        // "If Gemini Live API is unreachable, the app falls back to simple TTS of the
        // matched attendee's name and title."
        runBlocking { repository.save(ConferenceDatabase(attendees = listOf(ada))) }
        every { live.isReady } returns false

        val session = orchestrator(dispatcher)
        session.start()

        session.holdFaceInFrame()

        val suggestion = session.suggestions.value.lastOrNull()
        assertThat(suggestion).isNotNull()
        assertThat(suggestion!!.kind).isEqualTo(SuggestionKind.IDENTITY)
        assertThat(suggestion.text).contains("Ada Lovelace")
        assertThat(suggestion.text).contains("Chief Scientist")
    }

    @Test
    fun `an unrecognised face produces no whisper at all`() {
        // "If a face is detected but no match is found, the app remains silent."
        runBlocking { repository.save(ConferenceDatabase(attendees = listOf(ada))) }
        embedder.embedding = embedding(999)

        val session = orchestrator(dispatcher)
        session.start()

        session.holdFaceInFrame()

        assertThat(session.suggestions.value).isEmpty()
    }

    @Test
    fun `an empty frame produces no whisper`() {
        runBlocking { repository.save(ConferenceDatabase(attendees = listOf(ada))) }
        detector.faces = emptyList()

        val session = orchestrator(dispatcher)
        session.start()

        session.holdFaceInFrame()

        assertThat(session.suggestions.value).isEmpty()
    }

    // ---- Context injection ------------------------------------------------------------

    @Test
    fun `recognising someone injects their context into the live session`() {
        // Design Doc 1 § Runtime Flow: "The current target's information is injected
        // into Gemini Live's context."
        runBlocking { repository.save(ConferenceDatabase(attendees = listOf(ada))) }
        every { live.isReady } returns true

        val session = orchestrator(dispatcher)
        session.start()

        // The orchestrator only treats the socket as usable once it has seen Ready.
        liveEvents.tryEmit(GeminiLiveClient.Event.Ready)

        session.holdFaceInFrame()

        io.mockk.verify {
            live.updateContext(match { it.contains("Ada Lovelace") && it.contains("Chief Scientist") })
        }
    }

    @Test
    fun `a recognised face plays the match cue`() {
        runBlocking { repository.save(ConferenceDatabase(attendees = listOf(ada))) }

        val session = orchestrator(dispatcher)
        session.start()

        session.holdFaceInFrame()

        io.mockk.verify { cues.signal(SoundCues.Cue.MATCH) }
    }

    @Test
    fun `frames are ignored while the session is not running`() {
        val session = orchestrator(dispatcher)

        session.holdFaceInFrame()

        assertThat(session.target.value)
            .isEqualTo(ai.sotto.assistant.data.model.TargetState.NoFace)
    }

    // ---- Housekeeping --------------------------------------------------------------------

    @Test
    fun `the banner can be dismissed`() {
        every { audioCapture.start() } throws AppError.PermissionDenied("Microphone")
        val session = orchestrator(dispatcher)

        session.start()
        assertThat(session.banner.value).isNotNull()

        session.dismissBanner()
        assertThat(session.banner.value).isNull()
    }

    @Test
    fun `the transcript can be cleared`() {
        val session = orchestrator(dispatcher)
        session.start()

        session.clearTranscript()
        assertThat(session.transcript.value).isEmpty()
    }

    @Test
    fun `stopping releases the microphone and the live socket`() {
        val session = orchestrator(dispatcher)
        session.start()

        session.stop()

        io.mockk.verify { audioCapture.stop() }
        io.mockk.verify { live.disconnect(notify = false) }
        io.mockk.verify { player.stop() }
    }

    @Test
    fun `stopping clears the health readout`() {
        val session = orchestrator(dispatcher)
        session.start()
        assertThat(session.health.value.faceOk).isTrue()

        session.stop()

        assertThat(session.health.value.faceOk).isFalse()
    }
}
