package ai.sotto.assistant.vision

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * SSD anchor boxes for BlazeFace, generated exactly as MediaPipe's
 * `SsdAnchorsCalculator` does.
 *
 * These are needed to decode the raw model output ourselves. The numbers are not
 * arbitrary — they are the published configuration for `blaze_face_short_range`, and
 * getting any of them wrong produces boxes that are subtly and uselessly misplaced
 * rather than obviously broken, so [BlazeFaceAnchorsTest] pins the whole set.
 */
object BlazeFaceAnchors {

    /** A prior box, in normalised [0, 1] image coordinates. */
    data class Anchor(val xCenter: Float, val yCenter: Float, val width: Float, val height: Float)

    const val INPUT_SIZE = 128
    const val EXPECTED_ANCHOR_COUNT = 896

    private const val MIN_SCALE = 0.1484375f
    private const val MAX_SCALE = 0.75f
    private const val ANCHOR_OFFSET_X = 0.5f
    private const val ANCHOR_OFFSET_Y = 0.5f
    private const val INTERPOLATED_SCALE_ASPECT_RATIO = 1.0f
    private val STRIDES = intArrayOf(8, 16, 16, 16)
    private val ASPECT_RATIOS = floatArrayOf(1.0f)

    /** Built once; the anchor set is a pure function of the model configuration. */
    val anchors: List<Anchor> by lazy { generate() }

    private fun generate(): List<Anchor> {
        val result = mutableListOf<Anchor>()
        val numLayers = STRIDES.size
        var layerId = 0

        while (layerId < numLayers) {
            val anchorHeights = mutableListOf<Float>()
            val anchorWidths = mutableListOf<Float>()
            val aspectRatios = mutableListOf<Float>()
            val scales = mutableListOf<Float>()

            // Layers that share a stride contribute their anchors to the same feature map.
            var lastSameStrideLayer = layerId
            while (lastSameStrideLayer < numLayers &&
                STRIDES[lastSameStrideLayer] == STRIDES[layerId]
            ) {
                val scale = scaleFor(lastSameStrideLayer, numLayers)
                ASPECT_RATIOS.forEach { ratio ->
                    aspectRatios += ratio
                    scales += scale
                }
                if (INTERPOLATED_SCALE_ASPECT_RATIO > 0f) {
                    val scaleNext = if (lastSameStrideLayer == numLayers - 1) {
                        1.0f
                    } else {
                        scaleFor(lastSameStrideLayer + 1, numLayers)
                    }
                    scales += sqrt(scale * scaleNext)
                    aspectRatios += INTERPOLATED_SCALE_ASPECT_RATIO
                }
                lastSameStrideLayer++
            }

            for (i in aspectRatios.indices) {
                val ratioSqrt = sqrt(aspectRatios[i])
                anchorWidths += scales[i] * ratioSqrt
                anchorHeights += scales[i] / ratioSqrt
            }

            val stride = STRIDES[layerId]
            val featureMapHeight = ceil(INPUT_SIZE.toDouble() / stride).toInt()
            val featureMapWidth = ceil(INPUT_SIZE.toDouble() / stride).toInt()

            for (y in 0 until featureMapHeight) {
                for (x in 0 until featureMapWidth) {
                    repeat(anchorHeights.size) {
                        result += Anchor(
                            xCenter = (x + ANCHOR_OFFSET_X) / featureMapWidth,
                            yCenter = (y + ANCHOR_OFFSET_Y) / featureMapHeight,
                            // blaze_face_short_range uses fixed_anchor_size.
                            width = 1.0f,
                            height = 1.0f,
                        )
                    }
                }
            }
            layerId = lastSameStrideLayer
        }
        return result
    }

    private fun scaleFor(layer: Int, numLayers: Int): Float =
        MIN_SCALE + (MAX_SCALE - MIN_SCALE) * layer / (numLayers - 1f)
}
