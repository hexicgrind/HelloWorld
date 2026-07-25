package ai.sotto.assistant.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FaceImagingTest {

    // ---- expandToSquare ---------------------------------------------------------

    @Test
    fun `a face box becomes a square with margin`() {
        val bounds = RectF(100f, 100f, 180f, 200f)   // 80 x 100
        val square = FaceImaging.expandToSquare(bounds, 480, 640, margin = 0.2f)

        assertThat(square.width()).isWithin(0.5f).of(square.height())
        // Longest edge (100) grown by 20% on each side.
        assertThat(square.width()).isWithin(0.5f).of(140f)
    }

    @Test
    fun `the square stays centred on the face`() {
        val bounds = RectF(100f, 100f, 180f, 200f)
        val square = FaceImaging.expandToSquare(bounds, 480, 640)

        assertThat(square.centerX()).isWithin(0.5f).of(bounds.centerX())
        assertThat(square.centerY()).isWithin(0.5f).of(bounds.centerY())
    }

    @Test
    fun `a face at the left edge is clamped inside the image`() {
        val bounds = RectF(0f, 100f, 60f, 160f)
        val square = FaceImaging.expandToSquare(bounds, 480, 640)

        assertThat(square.left).isAtLeast(0f)
        assertThat(square.right).isAtMost(480f)
    }

    @Test
    fun `a face at the top edge is clamped inside the image`() {
        val bounds = RectF(100f, 0f, 160f, 60f)
        val square = FaceImaging.expandToSquare(bounds, 480, 640)

        assertThat(square.top).isAtLeast(0f)
        assertThat(square.bottom).isAtMost(640f)
    }

    @Test
    fun `a face at the bottom-right corner is clamped inside the image`() {
        val bounds = RectF(420f, 580f, 480f, 640f)
        val square = FaceImaging.expandToSquare(bounds, 480, 640)

        assertThat(square.left).isAtLeast(0f)
        assertThat(square.top).isAtLeast(0f)
        assertThat(square.right).isAtMost(480f)
        assertThat(square.bottom).isAtMost(640f)
    }

    @Test
    fun `a face larger than the image is clamped rather than inverted`() {
        val bounds = RectF(-50f, -50f, 600f, 800f)
        val square = FaceImaging.expandToSquare(bounds, 480, 640)

        assertThat(square.left).isAtLeast(0f)
        assertThat(square.top).isAtLeast(0f)
        assertThat(square.right).isAtMost(480f)
        assertThat(square.bottom).isAtMost(640f)
        assertThat(square.width()).isAtLeast(0f)
        assertThat(square.height()).isAtLeast(0f)
    }

    // ---- rollDegrees --------------------------------------------------------------

    @Test
    fun `level eyes mean zero roll`() {
        val roll = FaceImaging.rollDegrees(PointF(100f, 200f), PointF(160f, 200f))
        assertThat(roll).isWithin(0.01f).of(0f)
    }

    @Test
    fun `a tilted head produces the expected angle`() {
        // Right eye 60px right and 60px down: a 45 degree roll.
        val roll = FaceImaging.rollDegrees(PointF(100f, 200f), PointF(160f, 260f))
        assertThat(roll).isWithin(0.5f).of(45f)
    }

    @Test
    fun `a tilt the other way is negative`() {
        val roll = FaceImaging.rollDegrees(PointF(100f, 260f), PointF(160f, 200f))
        assertThat(roll).isWithin(0.5f).of(-45f)
    }

    @Test
    fun `missing keypoints mean zero roll rather than a crash`() {
        assertThat(FaceImaging.rollDegrees(null, PointF(1f, 1f))).isEqualTo(0f)
        assertThat(FaceImaging.rollDegrees(PointF(1f, 1f), null)).isEqualTo(0f)
        assertThat(FaceImaging.rollDegrees(null, null)).isEqualTo(0f)
    }

    @Test
    fun `coincident eyes mean zero roll`() {
        val same = PointF(100f, 100f)
        assertThat(FaceImaging.rollDegrees(same, PointF(100f, 100f))).isEqualTo(0f)
    }

    // ---- alignedCrop ----------------------------------------------------------------

    private fun testBitmap(width: Int = 480, height: Int = 640): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.DKGRAY)
        canvas.drawCircle(240f, 300f, 60f, Paint().apply { color = Color.LTGRAY })
        return bitmap
    }

    @Test
    fun `an aligned crop is exactly the model input size`() {
        val face = DetectedFace(RectF(180f, 240f, 300f, 360f), 0.9f)
        val crop = FaceImaging.alignedCrop(testBitmap(), face)

        assertThat(crop).isNotNull()
        assertThat(crop!!.width).isEqualTo(FaceImaging.INPUT_SIZE)
        assertThat(crop.height).isEqualTo(FaceImaging.INPUT_SIZE)
    }

    @Test
    fun `a crop is produced even with a tilted face`() {
        val face = DetectedFace(
            bounds = RectF(180f, 240f, 300f, 360f),
            score = 0.9f,
            keypoints = listOf(PointF(210f, 280f), PointF(270f, 310f)),
        )
        val crop = FaceImaging.alignedCrop(testBitmap(), face)
        assertThat(crop).isNotNull()
        assertThat(crop!!.width).isEqualTo(FaceImaging.INPUT_SIZE)
    }

    @Test
    fun `a face box too small to be useful yields no crop`() {
        val face = DetectedFace(RectF(100f, 100f, 105f, 105f), 0.9f)
        assertThat(FaceImaging.alignedCrop(testBitmap(), face)).isNull()
    }

    @Test
    fun `an empty source bitmap yields no crop`() {
        val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val face = DetectedFace(RectF(0f, 0f, 1f, 1f), 0.9f)
        assertThat(FaceImaging.alignedCrop(empty, face)).isNull()
    }

    // ---- toTensor ---------------------------------------------------------------------

    @Test
    fun `the tensor has one float per channel per pixel`() {
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val tensor = FaceImaging.toTensor(bitmap)
        assertThat(tensor).hasLength(160 * 160 * 3)
    }

    @Test
    fun `the tensor is pre-whitened`() {
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(120, 130, 140))
        }
        val tensor = FaceImaging.toTensor(bitmap)
        assertThat(kotlin.math.abs(tensor.average())).isLessThan(1e-3)
    }

    @Test
    fun `a reused scratch buffer produces identical output`() {
        val bitmap = testBitmap(160, 160)
        val scratch = IntArray(160 * 160)

        val first = FaceImaging.toTensor(bitmap, scratch)
        val second = FaceImaging.toTensor(bitmap, scratch)

        assertThat(second.toList()).containsExactlyElementsIn(first.toList()).inOrder()
    }

    @Test
    fun `a wrongly sized scratch buffer is ignored safely`() {
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val tensor = FaceImaging.toTensor(bitmap, IntArray(10))
        assertThat(tensor).hasLength(160 * 160 * 3)
    }

    // ---- cropQuality -----------------------------------------------------------------

    @Test
    fun `an all-black crop scores poorly`() {
        val black = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        assertThat(FaceImaging.cropQuality(black)).isLessThan(FacePipeline.MIN_CROP_QUALITY)
    }

    @Test
    fun `a blown-out white crop scores poorly`() {
        val white = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        assertThat(FaceImaging.cropQuality(white)).isLessThan(FacePipeline.MIN_CROP_QUALITY)
    }

    @Test
    fun `a well-exposed detailed crop scores well`() {
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(110, 115, 120))
        val paint = Paint()
        for (i in 0 until 12) {
            paint.color = if (i % 2 == 0) Color.rgb(60, 65, 70) else Color.rgb(185, 190, 195)
            canvas.drawRect(0f, i * 13f, 160f, i * 13f + 13f, paint)
        }
        assertThat(FaceImaging.cropQuality(bitmap)).isGreaterThan(FacePipeline.MIN_CROP_QUALITY)
    }

    @Test
    fun `quality always lands between zero and one`() {
        listOf(Color.BLACK, Color.WHITE, Color.RED, Color.rgb(128, 128, 128)).forEach { colour ->
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(colour)
            }
            val quality = FaceImaging.cropQuality(bitmap)
            assertThat(quality).isAtLeast(0f)
            assertThat(quality).isAtMost(1f)
        }
    }

    // ---- downscale ------------------------------------------------------------------------

    @Test
    fun `downscaling caps the longest edge and keeps the aspect ratio`() {
        val large = Bitmap.createBitmap(4_000, 3_000, Bitmap.Config.ARGB_8888)
        val scaled = FaceImaging.downscale(large, 1_600)

        assertThat(scaled.width).isEqualTo(1_600)
        assertThat(scaled.height).isEqualTo(1_200)
    }

    @Test
    fun `an already small bitmap is returned unchanged`() {
        val small = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        assertThat(FaceImaging.downscale(small, 1_600)).isSameInstanceAs(small)
    }

    @Test
    fun `a portrait image downscales on its long edge`() {
        val portrait = Bitmap.createBitmap(1_500, 3_000, Bitmap.Config.ARGB_8888)
        val scaled = FaceImaging.downscale(portrait, 1_000)

        assertThat(scaled.height).isEqualTo(1_000)
        assertThat(scaled.width).isEqualTo(500)
    }
}
