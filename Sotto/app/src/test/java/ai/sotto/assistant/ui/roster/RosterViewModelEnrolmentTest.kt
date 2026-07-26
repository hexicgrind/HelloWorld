package ai.sotto.assistant.ui.roster

import ai.sotto.assistant.core.DispatcherProvider
import ai.sotto.assistant.data.local.AttendeeRepository
import ai.sotto.assistant.data.model.Attendee
import ai.sotto.assistant.data.model.ConferenceDatabase
import ai.sotto.assistant.di.AppContainer
import ai.sotto.assistant.vision.DetectedFace
import ai.sotto.assistant.vision.FaceDetectorSource
import ai.sotto.assistant.vision.FaceEmbedder
import ai.sotto.assistant.vision.FaceMatcher
import ai.sotto.assistant.vision.FacePipeline
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Enrolment, end to end through the view model.
 *
 * A user reported being "only able to add one photo for each person, so I can't do any
 * of the face matching". Two separate faults produced that: the photo picker took one
 * image at a time, and re-entering the enrolment screen — which happens on its own if
 * the system recreates the activity while the picker is open — wiped whatever had
 * already been captured. Both are pinned down here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RosterViewModelEnrolmentTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private class FakeDetector : FaceDetectorSource {
        var faces: List<DetectedFace> = listOf(DetectedFace(RectF(120f, 160f, 360f, 460f), 0.95f))

        /** Per-call overrides, so one batch can mix photos that do and don't have a face. */
        val script = ArrayDeque<List<DetectedFace>>()

        override fun detect(bitmap: Bitmap) = script.removeFirstOrNull() ?: faces
        override fun close() = Unit
    }

    private class FakeEmbedder : FaceEmbedder {
        override val dimensions = 128
        var next: FloatArray? = FloatArray(128) { 0.1f }
        override fun embed(alignedFace: Bitmap) = next
        override fun close() = Unit
    }

    private lateinit var container: AppContainer
    private lateinit var repository: AttendeeRepository
    private lateinit var detector: FakeDetector
    private lateinit var embedder: FakeEmbedder
    private lateinit var viewModel: RosterViewModel

    private val ada = Attendee(id = "ada", name = "Ada Lovelace")
    private val grace = Attendee(id = "grace", name = "Grace Hopper")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        detector = FakeDetector()
        embedder = FakeEmbedder()
        repository = AttendeeRepository(temporaryFolder.root, Dispatchers.Unconfined) { 1_000L }
        runBlocking { repository.save(ConferenceDatabase(attendees = listOf(ada, grace))) }

        container = mockk(relaxed = true)
        every { container.appContext } returns RuntimeEnvironment.getApplication()
        every { container.attendeeRepository } returns repository
        every { container.faceMatcher } returns FaceMatcher()
        every { container.facePipeline } returns
            FacePipeline(detector, embedder, FaceMatcher(), clock = { 1_000L })
        every { container.dispatchers } returns object : DispatcherProvider {
            override val main = dispatcher
            override val io = dispatcher
            override val default = dispatcher
        }

        viewModel = RosterViewModel(container)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- Photos ---------------------------------------------------------------------

    /** A photo with enough texture that the crop clears the blur/brightness floor. */
    private fun photoBytes(seed: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(120, 125, 130))
        val random = Random(seed)
        val paint = Paint()
        for (i in 0 until 40) {
            paint.color = if (i % 2 == 0) Color.rgb(40, 45, 50) else Color.rgb(205, 210, 215)
            canvas.drawRect(0f, i * 16f, 480f, i * 16f + 16f, paint)
        }
        repeat(30) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            canvas.drawCircle(random.nextInt(480).toFloat(), random.nextInt(640).toFloat(), 12f, paint)
        }
        return ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
    }

    private fun registerPhoto(index: Int): Uri {
        val uri = Uri.parse("content://media/external/images/$index")
        Shadows.shadowOf(RuntimeEnvironment.getApplication().contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(photoBytes(index)))
        return uri
    }

    @Test
    fun `several photos picked at once all become samples`() {
        // The whole point of the multi-select picker: three photos, one trip.
        viewModel.beginEnrolment("ada")

        viewModel.captureFromUris(listOf(registerPhoto(1), registerPhoto(2), registerPhoto(3)))

        assertThat(viewModel.enrol.value.samples).hasSize(3)
        assertThat(viewModel.enrol.value.isReliable).isTrue()
        assertThat(viewModel.enrol.value.capturing).isFalse()
    }

    @Test
    fun `photos added one at a time accumulate instead of replacing`() {
        viewModel.beginEnrolment("ada")

        viewModel.captureFromUris(listOf(registerPhoto(1)))
        viewModel.captureFromUris(listOf(registerPhoto(2)))

        assertThat(viewModel.enrol.value.samples).hasSize(2)
    }

    @Test
    fun `re-entering the screen for the same person keeps what was captured`() {
        // The reported bug. The screen calls beginEnrolment from a LaunchedEffect, which
        // runs again after an activity recreation — and picking a photo can cause one.
        viewModel.beginEnrolment("ada")
        viewModel.captureFromUris(listOf(registerPhoto(1)))
        assertThat(viewModel.enrol.value.samples).hasSize(1)

        viewModel.beginEnrolment("ada")

        assertThat(viewModel.enrol.value.samples).hasSize(1)
        assertThat(viewModel.enrol.value.attendee?.id).isEqualTo("ada")
    }

    @Test
    fun `switching to a different person starts clean`() {
        viewModel.beginEnrolment("ada")
        viewModel.captureFromUris(listOf(registerPhoto(1)))

        viewModel.beginEnrolment("grace")

        assertThat(viewModel.enrol.value.samples).isEmpty()
        assertThat(viewModel.enrol.value.attendee?.id).isEqualTo("grace")
    }

    @Test
    fun `no more than the maximum number of samples is kept`() {
        viewModel.beginEnrolment("ada")

        viewModel.captureFromUris((1..RosterViewModel.MAX_SAMPLES + 4).map { registerPhoto(it) })

        assertThat(viewModel.enrol.value.samples).hasSize(RosterViewModel.MAX_SAMPLES)
    }

    @Test
    fun `a photo with no face in it is skipped and said so`() {
        detector.faces = emptyList()
        viewModel.beginEnrolment("ada")

        viewModel.captureFromUris(listOf(registerPhoto(1)))

        assertThat(viewModel.enrol.value.samples).isEmpty()
        assertThat(viewModel.enrol.value.message).contains("No face found")
        assertThat(viewModel.enrol.value.error).isNull()
    }

    @Test
    fun `the usable photos in a mixed batch still get through`() {
        // Picking three shots from a gallery, one of which is the back of their head:
        // the batch must not fail as a whole.
        viewModel.beginEnrolment("ada")
        detector.script.addLast(detector.faces)
        detector.script.addLast(emptyList())
        detector.script.addLast(detector.faces)

        viewModel.captureFromUris(listOf(registerPhoto(1), registerPhoto(2), registerPhoto(3)))

        assertThat(viewModel.enrol.value.samples).hasSize(2)
        assertThat(viewModel.enrol.value.message).contains("Added 2 photos")
        assertThat(viewModel.enrol.value.message).contains("Skipped 1")
        assertThat(viewModel.enrol.value.error).isNull()
    }

    @Test
    fun `an empty pick does nothing at all`() {
        viewModel.beginEnrolment("ada")

        viewModel.captureFromUris(emptyList())

        assertThat(viewModel.enrol.value.samples).isEmpty()
        assertThat(viewModel.enrol.value.message).isNull()
        assertThat(viewModel.enrol.value.capturing).isFalse()
    }

    // ---- Saving ---------------------------------------------------------------------

    @Test
    fun `one photo is enough to make someone recognisable`() {
        // This is what the user could not get past: with the old three-sample gate, the
        // Save button never lit up and face matching was unreachable.
        viewModel.beginEnrolment("ada")
        viewModel.captureFromUris(listOf(registerPhoto(1)))

        assertThat(viewModel.enrol.value.canSave).isTrue()
        viewModel.saveEnrolment()

        assertThat(repository.current.findById("ada")?.hasFace).isTrue()
        assertThat(viewModel.enrol.value.saved).isTrue()
    }

    @Test
    fun `saving from one photo says plainly that it is less reliable`() {
        viewModel.beginEnrolment("ada")
        viewModel.captureFromUris(listOf(registerPhoto(1)))

        viewModel.saveEnrolment()

        assertThat(viewModel.enrol.value.message).contains("less reliable")
    }

    @Test
    fun `saving from three photos does not hedge`() {
        viewModel.beginEnrolment("ada")
        viewModel.captureFromUris(listOf(registerPhoto(1), registerPhoto(2), registerPhoto(3)))

        viewModel.saveEnrolment()

        assertThat(viewModel.enrol.value.message).doesNotContain("less reliable")
        assertThat(repository.current.findById("ada")?.hasFace).isTrue()
    }

    @Test
    fun `saving nothing is refused`() {
        viewModel.beginEnrolment("ada")

        viewModel.saveEnrolment()

        assertThat(repository.current.findById("ada")?.hasFace).isFalse()
        assertThat(viewModel.enrol.value.saved).isFalse()
    }

    @Test
    fun `the stored embedding is the average of the samples`() {
        viewModel.beginEnrolment("ada")
        embedder.next = FloatArray(128) { if (it == 0) 1f else 0f }
        viewModel.captureFromUris(listOf(registerPhoto(1)))
        embedder.next = FloatArray(128) { if (it == 1) 1f else 0f }
        viewModel.captureFromUris(listOf(registerPhoto(2)))

        viewModel.saveEnrolment()

        val stored = repository.current.findById("ada")?.embedding.orEmpty()
        assertThat(stored).hasSize(128)
        // Averaged then L2-normalised: the two contributing axes end up equal.
        assertThat(stored[0]).isWithin(1e-4f).of(stored[1])
        assertThat(stored[0]).isGreaterThan(0f)
        assertThat(stored[2]).isWithin(1e-4f).of(0f)
    }

    @Test
    fun `enrolment survives being reopened and can then be completed`() {
        // The full shape of the reported failure: add, get interrupted, add again, save.
        viewModel.beginEnrolment("ada")
        viewModel.captureFromUris(listOf(registerPhoto(1)))
        viewModel.beginEnrolment("ada")
        viewModel.captureFromUris(listOf(registerPhoto(2)))
        viewModel.beginEnrolment("ada")
        viewModel.captureFromUris(listOf(registerPhoto(3)))

        assertThat(viewModel.enrol.value.samples).hasSize(3)
        viewModel.saveEnrolment()

        assertThat(repository.current.findById("ada")?.hasFace).isTrue()
    }

    @Test
    fun `leaving the screen clears the enrolment`() {
        viewModel.beginEnrolment("ada")
        viewModel.captureFromUris(listOf(registerPhoto(1)))

        viewModel.endEnrolment()

        assertThat(viewModel.enrol.value.samples).isEmpty()
        assertThat(viewModel.enrol.value.attendee).isNull()
    }

    @Test
    fun `a removed sample is gone from the next save`() {
        viewModel.beginEnrolment("ada")
        viewModel.captureFromUris(listOf(registerPhoto(1), registerPhoto(2)))

        viewModel.removeSample(0)

        assertThat(viewModel.enrol.value.samples).hasSize(1)
    }

    @Test
    fun `removing an index that isn't there is ignored`() {
        viewModel.beginEnrolment("ada")
        viewModel.captureFromUris(listOf(registerPhoto(1)))

        viewModel.removeSample(7)

        assertThat(viewModel.enrol.value.samples).hasSize(1)
    }
}
