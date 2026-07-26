package ai.sotto.assistant.vision

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Turns BlazeFace's raw tensors into face boxes.
 *
 * The model emits 896 candidate boxes as offsets from prior anchors, plus a logit per
 * candidate. Decoding is: apply the offsets to the anchors, sigmoid the logits, drop
 * everything below threshold, and suppress overlapping duplicates.
 *
 * Pure maths, no Android — so all of it is unit-testable on the JVM, which matters
 * because a decoding error here produces plausible-looking boxes in the wrong place
 * rather than an obvious failure.
 */
object BlazeFaceDecoder {

    /** A decoded detection in normalised [0, 1] coordinates. */
    data class Detection(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val score: Float,
        /** Six (x, y) pairs: left eye, right eye, nose, mouth, left ear, right ear. */
        val keypoints: List<Pair<Float, Float>>,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val area: Float get() = max(0f, width) * max(0f, height)
    }

    const val VALUES_PER_BOX = 16
    const val KEYPOINT_COUNT = 6

    /** The model was trained at 128 px; offsets are expressed in that pixel scale. */
    private const val COORD_SCALE = 128f

    /** Matches MediaPipe's score clipping, which stops sigmoid from saturating. */
    private const val SCORE_CLIP = 100f

    /**
     * @param regressors flat [896 * 16] box offsets and keypoints
     * @param scores flat [896] classification logits
     */
    fun decode(
        regressors: FloatArray,
        scores: FloatArray,
        anchors: List<BlazeFaceAnchors.Anchor> = BlazeFaceAnchors.anchors,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
    ): List<Detection> {
        val count = min(anchors.size, scores.size)
        if (count == 0 || regressors.size < count * VALUES_PER_BOX) return emptyList()

        val detections = mutableListOf<Detection>()
        for (i in 0 until count) {
            val score = sigmoid(scores[i].coerceIn(-SCORE_CLIP, SCORE_CLIP))
            if (score < scoreThreshold) continue

            val anchor = anchors[i]
            val base = i * VALUES_PER_BOX

            val xCenter = regressors[base] / COORD_SCALE * anchor.width + anchor.xCenter
            val yCenter = regressors[base + 1] / COORD_SCALE * anchor.height + anchor.yCenter
            val width = regressors[base + 2] / COORD_SCALE * anchor.width
            val height = regressors[base + 3] / COORD_SCALE * anchor.height

            val keypoints = ArrayList<Pair<Float, Float>>(KEYPOINT_COUNT)
            for (k in 0 until KEYPOINT_COUNT) {
                val offset = base + 4 + k * 2
                if (offset + 1 >= regressors.size) break
                keypoints += Pair(
                    regressors[offset] / COORD_SCALE * anchor.width + anchor.xCenter,
                    regressors[offset + 1] / COORD_SCALE * anchor.height + anchor.yCenter,
                )
            }

            detections += Detection(
                left = xCenter - width / 2f,
                top = yCenter - height / 2f,
                right = xCenter + width / 2f,
                bottom = yCenter + height / 2f,
                score = score,
                keypoints = keypoints,
            )
        }
        return detections
    }

    /**
     * Greedy non-maximum suppression: keep the highest-scoring box, discard anything
     * overlapping it by more than [iouThreshold], repeat.
     */
    fun nonMaxSuppression(
        detections: List<Detection>,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): List<Detection> {
        if (detections.size <= 1) return detections

        val remaining = detections.sortedByDescending { it.score }.toMutableList()
        val kept = mutableListOf<Detection>()

        while (remaining.isNotEmpty() && kept.size < maxResults) {
            val best = remaining.removeAt(0)
            kept += best
            remaining.removeAll { intersectionOverUnion(best, it) > iouThreshold }
        }
        return kept
    }

    fun intersectionOverUnion(a: Detection, b: Detection): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f

        val intersection = (right - left) * (bottom - top)
        val union = a.area + b.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    const val DEFAULT_SCORE_THRESHOLD = 0.55f
    const val DEFAULT_IOU_THRESHOLD = 0.3f
    const val DEFAULT_MAX_RESULTS = 5
}
