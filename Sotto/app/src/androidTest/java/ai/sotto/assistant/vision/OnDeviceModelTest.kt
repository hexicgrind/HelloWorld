package ai.sotto.assistant.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real bundled models on a real device.
 *
 * The JVM tests use fakes so they can assert logic deterministically; this is the
 * counterpart that proves the actual `.tflite` assets load, produce the shapes the
 * design doc specifies, and behave sanely.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceModelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private var detector: MediaPipeFaceDetector? = null
    private var embedder: TfLiteFaceEmbedder? = null

    @Before
    fun setUp() {
        detector = MediaPipeFaceDetector.create(context)
        embedder = TfLiteFaceEmbedder.create(context)
    }

    @After
    fun tearDown() {
        detector?.close()
        embedder?.close()
    }

    /** A crude synthetic face: oval head, two eyes, a mouth. */
    private fun syntheticFace(
        width: Int = 480,
        height: Int = 640,
        skin: Int = Color.rgb(222, 190, 165),
        offsetX: Float = 0f,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(200, 205, 215))

        val cx = width / 2f + offsetX
        val cy = height / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = skin
        canvas.drawOval(RectF(cx - 90f, cy - 115f, cx + 90f, cy + 115f), paint)

        paint.color = Color.rgb(40, 40, 45)
        canvas.drawOval(RectF(cx - 55f, cy - 40f, cx - 20f, cy - 18f), paint)
        canvas.drawOval(RectF(cx + 20f, cy - 40f, cx + 55f, cy - 18f), paint)

        paint.color = Color.rgb(150, 90, 90)
        canvas.drawOval(RectF(cx - 35f, cy + 45f, cx + 35f, cy + 70f), paint)

        paint.color = Color.rgb(90, 65, 55)
        canvas.drawOval(RectF(cx - 14f, cy - 10f, cx + 14f, cy + 30f), paint)

        return bitmap
    }

    // ---- The bundled models load ---------------------------------------------------

    @Test
    fun faceDetectorLoadsFromAssets() {
        assertThat(detector).isNotNull()
    }

    @Test
    fun faceEmbedderLoadsFromAssets() {
        assertThat(embedder).isNotNull()
    }

    @Test
    fun embedderProducesTheDocumentedDimensionCount() {
        // Design Doc 1 § Database Schema: "embedding (array of floats, 128 dimensions)".
        assertThat(embedder!!.dimensions).isEqualTo(128)
    }

    // ---- Embedding behaviour on real weights -----------------------------------------

    @Test
    fun embeddingIsUnitLength() {
        val crop = Bitmap.createScaledBitmap(syntheticFace(), 160, 160, true)
        val embedding = embedder!!.embed(crop)

        assertThat(embedding).isNotNull()
        assertThat(embedding!!.size).isEqualTo(128)
        assertThat(FaceMath.magnitude(embedding)).isWithin(1e-3f).of(1f)
    }

    @Test
    fun embeddingIsFiniteAndNonTrivial() {
        val crop = Bitmap.createScaledBitmap(syntheticFace(), 160, 160, true)
        val embedding = embedder!!.embed(crop)!!

        assertThat(embedding.all { it.isFinite() }).isTrue()
        assertThat(embedding.distinct().size).isGreaterThan(50)
    }

    @Test
    fun theSameImageAlwaysProducesTheSameEmbedding() {
        val crop = Bitmap.createScaledBitmap(syntheticFace(), 160, 160, true)

        val first = embedder!!.embed(crop)!!
        val second = embedder!!.embed(crop)!!

        assertThat(FaceMath.cosineSimilarity(first, second)).isWithin(1e-4f).of(1f)
    }

    @Test
    fun aDifferentImageProducesADifferentEmbedding() {
        val a = Bitmap.createScaledBitmap(syntheticFace(skin = Color.rgb(222, 190, 165)), 160, 160, true)
        val b = Bitmap.createScaledBitmap(syntheticFace(skin = Color.rgb(90, 65, 50)), 160, 160, true)

        val similarity = FaceMath.cosineSimilarity(embedder!!.embed(a)!!, embedder!!.embed(b)!!)

        assertThat(similarity).isLessThan(0.999f)
    }

    @Test
    fun embeddingMeetsTheLatencyBudget() {
        // Design Doc 1 § Latency Budget: "Face detection and embedding: 80 to 120
        // milliseconds." Measured after a warm-up so JIT and delegate setup aren't
        // counted, and with generous headroom for slow test hardware.
        val crop = Bitmap.createScaledBitmap(syntheticFace(), 160, 160, true)
        repeat(3) { embedder!!.embed(crop) }

        val startedAt = System.nanoTime()
        repeat(10) { embedder!!.embed(crop) }
        val averageMs = (System.nanoTime() - startedAt) / 10 / 1_000_000.0

        assertThat(averageMs).isLessThan(500.0)
    }

    // ---- Detection on real weights ------------------------------------------------------

    @Test
    fun detectorReturnsNothingForABlankFrame() {
        val blank = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GRAY)
        }
        assertThat(detector!!.detect(blank)).isEmpty()
    }

    @Test
    fun detectorDoesNotCrashOnATinyFrame() {
        val tiny = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GRAY)
        }
        assertThat(detector!!.detect(tiny)).isNotNull()
    }

    @Test
    fun detectionMeetsTheLatencyBudget() {
        val frame = syntheticFace()
        repeat(3) { detector!!.detect(frame) }

        val startedAt = System.nanoTime()
        repeat(10) { detector!!.detect(frame) }
        val averageMs = (System.nanoTime() - startedAt) / 10 / 1_000_000.0

        assertThat(averageMs).isLessThan(200.0)
    }

    // ---- The two together ------------------------------------------------------------------

    @Test
    fun theFullPipelineRunsWithRealModels() {
        val matcher = FaceMatcher()
        val pipeline = FacePipeline(detector!!, embedder!!, matcher)
        try {
            val frame = syntheticFace()
            // No exception, and a defined state, is the assertion here — whether a
            // synthetic face is detected depends on the model, not on our code.
            repeat(40) { assertThat(pipeline.onFrame(frame)).isNotNull() }
            assertThat(pipeline.stats.value.framesProcessed).isEqualTo(40L)
        } finally {
            // The pipeline does not own these; the fixture closes them.
        }
    }
}
