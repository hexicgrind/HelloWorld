package ai.sotto.assistant.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Turning a detected face into the tightly-cropped, eye-levelled square that FaceNet
 * expects. Alignment is the single biggest accuracy lever in the pipeline: an
 * unaligned crop costs several points of match accuracy against the doc's 90% target.
 */
object FaceImaging {

    /** FaceNet input is 160x160x3. */
    const val INPUT_SIZE = 160

    /**
     * How much context to keep around the detector's box. BlazeFace boxes are tight to
     * the face; FaceNet was trained on crops with a little margin.
     */
    const val CROP_MARGIN = 0.22f

    /**
     * Expands [bounds] by [margin] on every side, squares it off (so the aspect ratio
     * survives the resize), and clamps it inside the image.
     */
    fun expandToSquare(
        bounds: RectF,
        imageWidth: Int,
        imageHeight: Int,
        margin: Float = CROP_MARGIN,
    ): RectF {
        val side = maxOf(bounds.width(), bounds.height()) * (1f + margin * 2f)
        val half = side / 2f
        val cx = bounds.centerX()
        val cy = bounds.centerY()

        var left = cx - half
        var top = cy - half
        var right = cx + half
        var bottom = cy + half

        // Slide the square back inside the frame before clamping, so we lose margin
        // rather than distorting the face when someone is near an edge.
        if (left < 0f) { right -= left; left = 0f }
        if (top < 0f) { bottom -= top; top = 0f }
        if (right > imageWidth) { left -= (right - imageWidth); right = imageWidth.toFloat() }
        if (bottom > imageHeight) { top -= (bottom - imageHeight); bottom = imageHeight.toFloat() }

        return RectF(
            left.coerceAtLeast(0f),
            top.coerceAtLeast(0f),
            right.coerceAtMost(imageWidth.toFloat()),
            bottom.coerceAtMost(imageHeight.toFloat()),
        )
    }

    /** Roll angle in degrees implied by the eye keypoints; 0 when they're unavailable. */
    fun rollDegrees(leftEye: PointF?, rightEye: PointF?): Float {
        if (leftEye == null || rightEye == null) return 0f
        val dx = (rightEye.x - leftEye.x).toDouble()
        val dy = (rightEye.y - leftEye.y).toDouble()
        if (hypot(dx, dy) < 1e-3) return 0f
        return Math.toDegrees(atan2(dy, dx)).toFloat()
    }

    /**
     * Produces the aligned 160x160 crop: de-rotate about the face centre so the eyes
     * are level, then crop and scale. Falls back to a plain crop when the detector
     * gave us no keypoints.
     */
    fun alignedCrop(source: Bitmap, face: DetectedFace): Bitmap? {
        if (source.width <= 0 || source.height <= 0) return null

        val square = expandToSquare(face.bounds, source.width, source.height)
        if (square.width() < MIN_CROP_PX || square.height() < MIN_CROP_PX) return null

        val x = square.left.toInt().coerceIn(0, source.width - 1)
        val y = square.top.toInt().coerceIn(0, source.height - 1)
        val width = square.width().toInt().coerceIn(1, source.width - x)
        val height = square.height().toInt().coerceIn(1, source.height - y)

        val roll = rollDegrees(face.leftEye, face.rightEye)
        val matrix = Matrix().apply {
            if (kotlin.math.abs(roll) > MIN_ROLL_TO_CORRECT) {
                postRotate(-roll, width / 2f, height / 2f)
            }
        }

        // Crop and de-rotate in a single framework call. Doing it this way rather than
        // painting onto a Canvas keeps the pixels on the fast path and avoids the black
        // corners a rotation-into-a-fixed-square would leave behind.
        val cropped = try {
            Bitmap.createBitmap(source, x, y, width, height, matrix, true)
        } catch (t: IllegalArgumentException) {
            return null
        }

        if (cropped.width == INPUT_SIZE && cropped.height == INPUT_SIZE) return cropped

        return Bitmap.createScaledBitmap(cropped, INPUT_SIZE, INPUT_SIZE, true)
            .also { if (it !== cropped && !cropped.isRecycled) cropped.recycle() }
    }

    /**
     * Flattens a 160x160 bitmap into the pre-whitened float tensor FaceNet wants, in
     * NHWC order. Reuses [scratch] when supplied to keep the 30 fps path allocation-free.
     */
    fun toTensor(bitmap: Bitmap, scratch: IntArray? = null): FloatArray {
        val size = bitmap.width * bitmap.height
        val pixels = if (scratch != null && scratch.size == size) scratch else IntArray(size)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val raw = FloatArray(size * 3)
        var o = 0
        for (p in pixels) {
            raw[o++] = ((p shr 16) and 0xFF).toFloat()
            raw[o++] = ((p shr 8) and 0xFF).toFloat()
            raw[o++] = (p and 0xFF).toFloat()
        }
        return FaceMath.prewhiten(raw)
    }

    /**
     * A rough "is this crop worth embedding" score in [0, 1], combining mean luminance
     * against the darkness/blow-out extremes with contrast. Used to skip hopeless
     * frames rather than feeding the model garbage.
     */
    fun cropQuality(bitmap: Bitmap): Float {
        val step = maxOf(1, bitmap.width / 32)
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val p = bitmap.getPixel(x, y)
                val lum = 0.299 * ((p shr 16) and 0xFF) +
                    0.587 * ((p shr 8) and 0xFF) +
                    0.114 * (p and 0xFF)
                sum += lum
                sumSq += lum * lum
                n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        val variance = (sumSq / n) - (mean * mean)
        val contrast = kotlin.math.sqrt(maxOf(0.0, variance))

        // Ideal exposure sits mid-range; punish very dark and very blown-out crops.
        val exposure = 1.0 - (kotlin.math.abs(mean - 128.0) / 128.0)
        val detail = (contrast / 48.0).coerceIn(0.0, 1.0)
        return ((exposure * 0.55 + detail * 0.45).coerceIn(0.0, 1.0)).toFloat()
    }

    /** Scales a bitmap so its longest edge is [maxEdge], preserving aspect ratio. */
    fun downscale(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    const val MIN_CROP_PX = 32f
    private const val MIN_ROLL_TO_CORRECT = 2.5f
}
