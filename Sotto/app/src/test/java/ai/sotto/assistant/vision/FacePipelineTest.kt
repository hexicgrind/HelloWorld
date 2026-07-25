package ai.sotto.assistant.vision

import ai.sotto.assistant.data.model.Attendee
import ai.sotto.assistant.data.model.TargetState
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * The end-to-end face pipeline with fake models, so the documented runtime flow can be
 * asserted deterministically:
 *
 *   "When a face enters the frame and is tracked for one second or more, the embedding
 *    is extracted and matched against the database. If a match is found above the
 *    confidence threshold... the attendee record is retrieved and marked as 'current
 *    target.'"
 */
@RunWith(RobolectricTestRunner::class)
class FacePipelineTest {

    private var now = 0L

    /** Returns whatever faces the test tells it to. */
    private class FakeDetector : FaceDetectorSource {
        var faces: List<DetectedFace> = emptyList()
        var detectCount = 0
        override fun detect(bitmap: Bitmap): List<DetectedFace> {
            detectCount++
            return faces
        }
        override fun close() = Unit
    }

    /** Returns a fixed embedding, and counts how often it was asked. */
    private class FakeEmbedder(var embedding: FloatArray?) : FaceEmbedder {
        var embedCount = 0
        override val dimensions = 128
        override fun embed(alignedFace: Bitmap): FloatArray? {
            embedCount++
            return embedding
        }
        override fun close() = Unit
    }

    private lateinit var detector: FakeDetector
    private lateinit var embedder: FakeEmbedder
    private lateinit var matcher: FaceMatcher
    private lateinit var pipeline: FacePipeline

    /**
     * Builds a distinct pseudo-random unit vector. The Random instance is created once
     * per vector — creating it inside the lambda would seed it identically for every
     * element and produce a constant vector, which is cosine-identical to every other
     * constant vector and would make this whole test file assert nothing.
     */
    private fun randomEmbedding(seed: Int): FloatArray {
        val random = Random(seed)
        return FaceMath.l2Normalize(FloatArray(128) { random.nextFloat() * 2 - 1 })
    }

    private val adaEmbedding = randomEmbedding(5)

    private val ada = Attendee(
        id = "ada",
        name = "Ada Lovelace",
        title = "Chief Scientist",
        embedding = adaEmbedding.toList(),
    )

    @Before
    fun setUp() {
        now = 0L
        detector = FakeDetector()
        embedder = FakeEmbedder(adaEmbedding)
        matcher = FaceMatcher(clock = { now })
        pipeline = FacePipeline(detector, embedder, matcher, clock = { now })
        pipeline.setAttendees(listOf(ada))
    }

    /** A frame with enough contrast to clear the quality gate. */
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

    /** Advances the pipeline through [durationMs] of 30 fps frames. */
    private fun run(durationMs: Long, bitmap: Bitmap = frame()): TargetState {
        var state: TargetState = TargetState.NoFace
        val end = now + durationMs
        while (now <= end) {
            state = pipeline.onFrame(bitmap)
            now += 33L
        }
        return state
    }

    @Test
    fun `an empty frame produces no target`() {
        detector.faces = emptyList()
        assertThat(pipeline.onFrame(frame())).isEqualTo(TargetState.NoFace)
    }

    @Test
    fun `no embedding is computed before the dwell time elapses`() {
        detector.faces = listOf(visibleFace())
        val state = run(500L)

        assertThat(state).isInstanceOf(TargetState.Tracking::class.java)
        assertThat(embedder.embedCount).isEqualTo(0)
    }

    @Test
    fun `a match is made once the face has been held for a second`() {
        detector.faces = listOf(visibleFace())
        val state = run(1_200L)

        assertThat(state).isInstanceOf(TargetState.Matched::class.java)
        val matched = state as TargetState.Matched
        assertThat(matched.attendee.id).isEqualTo("ada")
        assertThat(matched.score).isGreaterThan(0.7f)
        assertThat(embedder.embedCount).isAtLeast(1)
    }

    @Test
    fun `an unknown face is reported as unrecognised, not guessed`() {
        // Design Doc 1 § Error Handling: "If a face is detected but no match is found,
        // the app remains silent."
        embedder.embedding = randomEmbedding(99)
        detector.faces = listOf(visibleFace())

        val state = run(1_200L)

        assertThat(state).isInstanceOf(TargetState.Unrecognised::class.java)
    }

    @Test
    fun `with no enrolled faces the pipeline reports unrecognised`() {
        // "If no attendee database is loaded, face detection runs but no context is
        // passed to Gemini."
        pipeline.setAttendees(listOf(ada.copy(embedding = null)))
        detector.faces = listOf(visibleFace())

        val state = run(1_200L)

        assertThat(state).isInstanceOf(TargetState.Unrecognised::class.java)
    }

    @Test
    fun `with an empty roster the pipeline reports unrecognised`() {
        pipeline.setAttendees(emptyList())
        detector.faces = listOf(visibleFace())
        assertThat(run(1_200L)).isInstanceOf(TargetState.Unrecognised::class.java)
    }

    @Test
    fun `embeddings are throttled rather than computed every frame`() {
        detector.faces = listOf(visibleFace())
        run(1_200L)
        val afterMatch = embedder.embedCount

        run(1_500L)   // another 1.5 seconds of the same person

        // Re-verification happens on a cadence, not at 30 fps.
        assertThat(embedder.embedCount - afterMatch).isAtMost(2)
    }

    @Test
    fun `a face leaving the frame clears the target`() {
        detector.faces = listOf(visibleFace())
        assertThat(run(1_200L)).isInstanceOf(TargetState.Matched::class.java)

        detector.faces = emptyList()
        val state = run(1_000L)

        assertThat(state).isEqualTo(TargetState.NoFace)
    }

    @Test
    fun `a failed embedding does not crash the pipeline`() {
        embedder.embedding = null
        detector.faces = listOf(visibleFace())

        val state = run(1_500L)

        assertThat(state).isInstanceOf(TargetState.Tracking::class.java)
    }

    @Test
    fun `a low-quality frame is skipped rather than embedded`() {
        val black = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        detector.faces = listOf(visibleFace())

        run(1_500L, black)

        assertThat(embedder.embedCount).isEqualTo(0)
    }

    @Test
    fun `raising the threshold turns a match into a non-match`() {
        // A realistic probe: the same person under different lighting, so similarity is
        // high but not perfect. A perfect probe could never be rejected by any threshold.
        val noisy = Random(123)
        embedder.embedding = FaceMath.l2Normalize(
            FloatArray(128) { adaEmbedding[it] + (noisy.nextFloat() * 2 - 1) * 0.15f }
        )
        detector.faces = listOf(visibleFace())

        assertThat(run(1_200L)).isInstanceOf(TargetState.Matched::class.java)

        // Demand near-perfection and the same probe is no longer good enough.
        pipeline.setThreshold(0.995f)
        pipeline.reset()
        now += 100L

        assertThat(run(1_500L)).isInstanceOf(TargetState.Unrecognised::class.java)
    }

    @Test
    fun `the dwell requirement is configurable`() {
        pipeline.dwellMs = 200L
        detector.faces = listOf(visibleFace())

        assertThat(run(400L)).isInstanceOf(TargetState.Matched::class.java)
    }

    @Test
    fun `reset clears the target and the tracker`() {
        detector.faces = listOf(visibleFace())
        run(1_200L)

        pipeline.reset()

        assertThat(pipeline.target.value).isEqualTo(TargetState.NoFace)
        assertThat(pipeline.lastFaceBox.value).isNull()
    }

    @Test
    fun `stats record what the pipeline did`() {
        detector.faces = listOf(visibleFace())
        run(1_200L)

        val stats = pipeline.stats.value
        assertThat(stats.framesProcessed).isGreaterThan(0L)
        assertThat(stats.embeddingsComputed).isAtLeast(1L)
        assertThat(stats.facesInFrame).isEqualTo(1)
    }

    @Test
    fun `the closest of several faces is the one matched`() {
        detector.faces = listOf(
            DetectedFace(RectF(10f, 10f, 50f, 50f), 0.9f),        // far away
            visibleFace(),                                          // closest
            DetectedFace(RectF(400f, 500f, 440f, 540f), 0.9f),    // far away
        )

        val state = run(1_200L)

        assertThat(state).isInstanceOf(TargetState.Matched::class.java)
        assertThat(pipeline.lastFaceBox.value!!.bounds.width()).isEqualTo(120f)
    }

    @Test
    fun `embedStill returns a sample for the enrolment flow`() {
        detector.faces = listOf(visibleFace())

        val sample = pipeline.embedStill(frame())

        assertThat(sample).isNotNull()
        assertThat(sample!!.embedding).hasLength(128)
        assertThat(sample.crop.width).isEqualTo(FaceImaging.INPUT_SIZE)
        assertThat(sample.quality).isGreaterThan(0f)
    }

    @Test
    fun `embedStill returns null when no face is present`() {
        detector.faces = emptyList()
        assertThat(pipeline.embedStill(frame())).isNull()
    }

    @Test
    fun `a new person in frame starts a fresh evaluation`() {
        detector.faces = listOf(visibleFace())
        assertThat(run(1_200L)).isInstanceOf(TargetState.Matched::class.java)

        // Someone else steps in, far from where the first face was.
        detector.faces = listOf(DetectedFace(RectF(20f, 500f, 140f, 620f), 0.95f))
        embedder.embedding = randomEmbedding(77)

        val state = run(1_200L)

        assertThat(state).isInstanceOf(TargetState.Unrecognised::class.java)
    }
}
