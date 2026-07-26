package ai.sotto.assistant.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/**
 * The BlazeFace anchor generation and output decoding.
 *
 * This replaces MediaPipe's native decoding with our own, so it has to be right. A
 * mistake here doesn't crash — it produces confident boxes in slightly the wrong place,
 * which is far harder to notice and would quietly wreck recognition accuracy.
 */
class BlazeFaceTest {

    // ---- Anchors -----------------------------------------------------------------

    @Test
    fun `anchor count matches the model's box count`() {
        // The model emits 896 boxes; anything else means the configuration is wrong.
        assertThat(BlazeFaceAnchors.anchors).hasSize(BlazeFaceAnchors.EXPECTED_ANCHOR_COUNT)
        assertThat(BlazeFaceAnchors.anchors).hasSize(896)
    }

    @Test
    fun `the first layer contributes 512 anchors on a 16x16 grid`() {
        // stride 8 over a 128 px input = 16x16 cells, 2 anchors each.
        val firstLayer = BlazeFaceAnchors.anchors.take(512)
        val distinctCentres = firstLayer.map { it.xCenter to it.yCenter }.distinct()
        assertThat(distinctCentres).hasSize(256)
    }

    @Test
    fun `the second layer contributes 384 anchors on an 8x8 grid`() {
        // stride 16 = 8x8 cells, 6 anchors each.
        val secondLayer = BlazeFaceAnchors.anchors.drop(512)
        assertThat(secondLayer).hasSize(384)
        assertThat(secondLayer.map { it.xCenter to it.yCenter }.distinct()).hasSize(64)
    }

    @Test
    fun `every anchor centre sits inside the image`() {
        BlazeFaceAnchors.anchors.forEach { anchor ->
            assertThat(anchor.xCenter).isIn(com.google.common.collect.Range.closed(0f, 1f))
            assertThat(anchor.yCenter).isIn(com.google.common.collect.Range.closed(0f, 1f))
        }
    }

    @Test
    fun `anchors use the fixed unit size the model was trained with`() {
        BlazeFaceAnchors.anchors.forEach { anchor ->
            assertThat(anchor.width).isEqualTo(1.0f)
            assertThat(anchor.height).isEqualTo(1.0f)
        }
    }

    @Test
    fun `the first anchor is at the centre of the first cell`() {
        val first = BlazeFaceAnchors.anchors.first()
        // (0 + 0.5) / 16
        assertThat(first.xCenter).isWithin(1e-6f).of(0.03125f)
        assertThat(first.yCenter).isWithin(1e-6f).of(0.03125f)
    }

    @Test
    fun `anchor generation is stable across calls`() {
        assertThat(BlazeFaceAnchors.anchors).isEqualTo(BlazeFaceAnchors.anchors)
    }

    // ---- Decoding ----------------------------------------------------------------

    /** Builds raw tensors with one planted detection at [index]. */
    private fun tensors(
        index: Int,
        centreOffsetX: Float = 0f,
        centreOffsetY: Float = 0f,
        width: Float = 32f,
        height: Float = 32f,
        logit: Float = 5f,
        boxes: Int = BlazeFaceAnchors.EXPECTED_ANCHOR_COUNT,
    ): Pair<FloatArray, FloatArray> {
        val regressors = FloatArray(boxes * BlazeFaceDecoder.VALUES_PER_BOX)
        val scores = FloatArray(boxes) { -20f }   // sigmoid(-20) ≈ 0
        val base = index * BlazeFaceDecoder.VALUES_PER_BOX
        regressors[base] = centreOffsetX
        regressors[base + 1] = centreOffsetY
        regressors[base + 2] = width
        regressors[base + 3] = height
        scores[index] = logit
        return regressors to scores
    }

    @Test
    fun `a high-scoring box is decoded`() {
        val (regressors, scores) = tensors(index = 100)
        val detections = BlazeFaceDecoder.decode(regressors, scores)
        assertThat(detections).hasSize(1)
    }

    @Test
    fun `low-scoring boxes are discarded`() {
        val (regressors, scores) = tensors(index = 100, logit = -5f)
        assertThat(BlazeFaceDecoder.decode(regressors, scores)).isEmpty()
    }

    @Test
    fun `the score is a sigmoid of the logit`() {
        val (regressors, scores) = tensors(index = 0, logit = 0f)
        // sigmoid(0) = 0.5, which is below the default threshold.
        assertThat(BlazeFaceDecoder.decode(regressors, scores, scoreThreshold = 0.4f).single().score)
            .isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun `a known logit maps to a known probability`() {
        // sigmoid(ln(9)) = 0.9
        val (regressors, scores) = tensors(index = 0, logit = ln(9.0).toFloat())
        assertThat(BlazeFaceDecoder.decode(regressors, scores).single().score)
            .isWithin(1e-4f).of(0.9f)
    }

    @Test
    fun `a zero-offset box lands on its anchor centre`() {
        val index = 100
        val anchor = BlazeFaceAnchors.anchors[index]
        val (regressors, scores) = tensors(index = index, width = 32f, height = 32f)

        val detection = BlazeFaceDecoder.decode(regressors, scores).single()

        val centreX = (detection.left + detection.right) / 2f
        val centreY = (detection.top + detection.bottom) / 2f
        assertThat(centreX).isWithin(1e-5f).of(anchor.xCenter)
        assertThat(centreY).isWithin(1e-5f).of(anchor.yCenter)
    }

    @Test
    fun `box size is the regressed value divided by the 128px training scale`() {
        val (regressors, scores) = tensors(index = 100, width = 64f, height = 32f)
        val detection = BlazeFaceDecoder.decode(regressors, scores).single()

        assertThat(detection.width).isWithin(1e-5f).of(0.5f)   // 64/128
        assertThat(detection.height).isWithin(1e-5f).of(0.25f) // 32/128
    }

    @Test
    fun `a centre offset shifts the box by that fraction of the training scale`() {
        val index = 100
        val anchor = BlazeFaceAnchors.anchors[index]
        val (regressors, scores) = tensors(index = index, centreOffsetX = 12.8f)

        val detection = BlazeFaceDecoder.decode(regressors, scores).single()

        val centreX = (detection.left + detection.right) / 2f
        assertThat(centreX).isWithin(1e-5f).of(anchor.xCenter + 0.1f)
    }

    @Test
    fun `keypoints are decoded relative to the same anchor`() {
        val index = 100
        val anchor = BlazeFaceAnchors.anchors[index]
        val regressors = FloatArray(
            BlazeFaceAnchors.EXPECTED_ANCHOR_COUNT * BlazeFaceDecoder.VALUES_PER_BOX
        )
        val scores = FloatArray(BlazeFaceAnchors.EXPECTED_ANCHOR_COUNT) { -20f }
        val base = index * BlazeFaceDecoder.VALUES_PER_BOX
        regressors[base + 2] = 32f
        regressors[base + 3] = 32f
        regressors[base + 4] = -12.8f   // left eye x
        regressors[base + 5] = -6.4f    // left eye y
        scores[index] = 5f

        val detection = BlazeFaceDecoder.decode(regressors, scores).single()

        assertThat(detection.keypoints).hasSize(BlazeFaceDecoder.KEYPOINT_COUNT)
        val (eyeX, eyeY) = detection.keypoints[0]
        assertThat(eyeX).isWithin(1e-5f).of(anchor.xCenter - 0.1f)
        assertThat(eyeY).isWithin(1e-5f).of(anchor.yCenter - 0.05f)
    }

    @Test
    fun `an extreme logit does not overflow`() {
        val (regressors, scores) = tensors(index = 0, logit = 10_000f)
        val detection = BlazeFaceDecoder.decode(regressors, scores).single()
        assertThat(detection.score).isFinite()
        assertThat(detection.score).isWithin(1e-6f).of(1f)
    }

    @Test
    fun `mismatched tensor sizes yield nothing rather than crashing`() {
        assertThat(BlazeFaceDecoder.decode(FloatArray(10), FloatArray(896))).isEmpty()
        assertThat(BlazeFaceDecoder.decode(FloatArray(0), FloatArray(0))).isEmpty()
    }

    @Test
    fun `multiple detections are all returned`() {
        val boxes = BlazeFaceAnchors.EXPECTED_ANCHOR_COUNT
        val regressors = FloatArray(boxes * BlazeFaceDecoder.VALUES_PER_BOX)
        val scores = FloatArray(boxes) { -20f }
        listOf(10, 300, 700).forEach { i ->
            val base = i * BlazeFaceDecoder.VALUES_PER_BOX
            regressors[base + 2] = 20f
            regressors[base + 3] = 20f
            scores[i] = 5f
        }
        assertThat(BlazeFaceDecoder.decode(regressors, scores)).hasSize(3)
    }

    // ---- Non-maximum suppression ---------------------------------------------------

    private fun detection(
        left: Float, top: Float, right: Float, bottom: Float, score: Float,
    ) = BlazeFaceDecoder.Detection(left, top, right, bottom, score, emptyList())

    @Test
    fun `overlapping boxes collapse to the highest scoring one`() {
        val kept = BlazeFaceDecoder.nonMaxSuppression(
            listOf(
                detection(0.1f, 0.1f, 0.5f, 0.5f, 0.9f),
                detection(0.11f, 0.11f, 0.51f, 0.51f, 0.8f),
                detection(0.12f, 0.12f, 0.52f, 0.52f, 0.7f),
            )
        )
        assertThat(kept).hasSize(1)
        assertThat(kept.single().score).isEqualTo(0.9f)
    }

    @Test
    fun `separate faces are both kept`() {
        val kept = BlazeFaceDecoder.nonMaxSuppression(
            listOf(
                detection(0.0f, 0.0f, 0.3f, 0.3f, 0.9f),
                detection(0.6f, 0.6f, 0.9f, 0.9f, 0.85f),
            )
        )
        assertThat(kept).hasSize(2)
    }

    @Test
    fun `an empty or single-element list passes through`() {
        assertThat(BlazeFaceDecoder.nonMaxSuppression(emptyList())).isEmpty()
        val one = listOf(detection(0f, 0f, 1f, 1f, 0.9f))
        assertThat(BlazeFaceDecoder.nonMaxSuppression(one)).hasSize(1)
    }

    @Test
    fun `results are capped`() {
        val many = (0 until 50).map {
            val x = it * 0.02f
            detection(x, 0f, x + 0.01f, 0.01f, 0.9f)
        }
        assertThat(BlazeFaceDecoder.nonMaxSuppression(many).size)
            .isAtMost(BlazeFaceDecoder.DEFAULT_MAX_RESULTS)
    }

    @Test
    fun `IoU of identical boxes is one`() {
        val box = detection(0.1f, 0.1f, 0.5f, 0.5f, 0.9f)
        assertThat(BlazeFaceDecoder.intersectionOverUnion(box, box)).isWithin(1e-5f).of(1f)
    }

    @Test
    fun `IoU of disjoint boxes is zero`() {
        val a = detection(0f, 0f, 0.2f, 0.2f, 0.9f)
        val b = detection(0.5f, 0.5f, 0.7f, 0.7f, 0.9f)
        assertThat(BlazeFaceDecoder.intersectionOverUnion(a, b)).isEqualTo(0f)
    }

    @Test
    fun `IoU of half-overlapping boxes is one third`() {
        // Two unit-area boxes sharing half their area: 0.5 / 1.5.
        val a = detection(0f, 0f, 1f, 1f, 0.9f)
        val b = detection(0.5f, 0f, 1.5f, 1f, 0.9f)
        assertThat(BlazeFaceDecoder.intersectionOverUnion(a, b)).isWithin(1e-5f).of(1f / 3f)
    }

    // ---- Letterboxing ----------------------------------------------------------------

    @Test
    fun `a portrait frame is letterboxed without distortion`() {
        val box = TfLiteFaceDetector.Letterbox.of(480, 640, 128)

        assertThat(box.scaledHeight).isEqualTo(128)
        assertThat(box.scaledWidth).isEqualTo(96)   // 480 * (128/640)
        assertThat(box.offsetY).isEqualTo(0)
        assertThat(box.offsetX).isEqualTo(16)

        // Aspect ratio survives.
        val sourceRatio = 480f / 640f
        val scaledRatio = box.scaledWidth.toFloat() / box.scaledHeight
        assertThat(abs(sourceRatio - scaledRatio)).isLessThan(0.01f)
    }

    @Test
    fun `a square frame needs no padding`() {
        val box = TfLiteFaceDetector.Letterbox.of(512, 512, 128)
        assertThat(box.offsetX).isEqualTo(0)
        assertThat(box.offsetY).isEqualTo(0)
        assertThat(box.scaledWidth).isEqualTo(128)
    }

    @Test
    fun `coordinates map back to the source frame`() {
        val box = TfLiteFaceDetector.Letterbox.of(480, 640, 128)

        // The centre of the model input maps to the centre of the source frame.
        assertThat(box.toSourceX(0.5f, 480)).isWithin(1f).of(240f)
        assertThat(box.toSourceY(0.5f, 640)).isWithin(1f).of(320f)
    }

    @Test
    fun `mapping back is the inverse of mapping in`() {
        val box = TfLiteFaceDetector.Letterbox.of(480, 640, 128)
        listOf(0.2f, 0.35f, 0.5f, 0.8f).forEach { normalised ->
            val sourceX = box.toSourceX(normalised, 480)
            // Re-derive the normalised coordinate and check it round-trips.
            val back = (sourceX / 480f * box.scaledWidth + box.offsetX) / box.inputSize
            assertThat(back).isWithin(1e-3f).of(normalised)
        }
    }

    @Test
    fun `an extreme aspect ratio still produces a valid letterbox`() {
        val box = TfLiteFaceDetector.Letterbox.of(1920, 120, 128)
        assertThat(box.scaledWidth).isAtMost(128)
        assertThat(box.scaledHeight).isAtLeast(1)
        assertThat(box.offsetY).isAtLeast(0)
    }
}
