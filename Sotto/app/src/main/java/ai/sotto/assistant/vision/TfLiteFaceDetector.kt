package ai.sotto.assistant.vision

import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.core.SLog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * BlazeFace run directly on LiteRT, bypassing MediaPipe's Tasks layer entirely.
 *
 * This exists because MediaPipe's Java layer turned out to be the single most fragile
 * thing in the app: on a real device its `Graph` class failed to initialise (Flogger's
 * stack-walking logger versus R8's optimiser), taking face detection down with it, while
 * the very same TFLite runtime happily ran FaceNet in the same process.
 *
 * Same model file, same weights, same outputs — just decoded here instead of behind a
 * 14 MB native graph runtime. It is used automatically whenever MediaPipe won't start.
 */
class TfLiteFaceDetector private constructor(
    private val interpreter: Interpreter,
    private val regressorIndex: Int,
    private val scoreIndex: Int,
    private val boxCount: Int,
) : FaceDetectorSource {

    private val lock = Any()
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
    private val regressors = Array(1) { Array(boxCount) { FloatArray(BlazeFaceDecoder.VALUES_PER_BOX) } }
    private val scores = Array(1) { Array(boxCount) { FloatArray(1) } }
    private val flatRegressors = FloatArray(boxCount * BlazeFaceDecoder.VALUES_PER_BOX)
    private val flatScores = FloatArray(boxCount)

    @Volatile
    var scoreThreshold: Float = BlazeFaceDecoder.DEFAULT_SCORE_THRESHOLD

    override fun detect(bitmap: Bitmap): List<DetectedFace> = synchronized(lock) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()

        try {
            // BlazeFace expects a square 128x128 input. The frame is letterboxed rather
            // than stretched, so faces keep their aspect ratio and the boxes map back
            // cleanly.
            val letterbox = Letterbox.of(bitmap.width, bitmap.height, INPUT_SIZE)
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                letterbox.scaledWidth,
                letterbox.scaledHeight,
                true,
            )

            try {
                writeInput(scaled, letterbox)
            } finally {
                if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            }

            interpreter.runForMultipleInputsOutputs(
                arrayOf<Any>(inputBuffer),
                mapOf(regressorIndex to regressors, scoreIndex to scores),
            )

            flatten()

            val decoded = BlazeFaceDecoder.decode(
                regressors = flatRegressors,
                scores = flatScores,
                scoreThreshold = scoreThreshold,
            )
            return BlazeFaceDecoder.nonMaxSuppression(decoded)
                .map { it.toDetectedFace(letterbox, bitmap.width, bitmap.height) }
        } catch (t: Throwable) {
            SLog.w(TAG, "Face detection failed on a frame", t)
            return emptyList()
        }
    }

    private fun writeInput(scaled: Bitmap, letterbox: Letterbox) {
        inputBuffer.rewind()
        // Fill with the neutral value so the letterbox padding is not interpreted as
        // image content.
        repeat(INPUT_SIZE * INPUT_SIZE * 3) { inputBuffer.putFloat(0f) }

        val buffer = IntArray(scaled.width * scaled.height)
        scaled.getPixels(buffer, 0, scaled.width, 0, 0, scaled.width, scaled.height)

        for (y in 0 until scaled.height) {
            val destinationRow = y + letterbox.offsetY
            if (destinationRow !in 0 until INPUT_SIZE) continue
            for (x in 0 until scaled.width) {
                val destinationCol = x + letterbox.offsetX
                if (destinationCol !in 0 until INPUT_SIZE) continue
                val pixel = buffer[y * scaled.width + x]
                val base = ((destinationRow * INPUT_SIZE) + destinationCol) * 3 * FLOAT_BYTES
                inputBuffer.putFloat(base, normalise((pixel shr 16) and 0xFF))
                inputBuffer.putFloat(base + FLOAT_BYTES, normalise((pixel shr 8) and 0xFF))
                inputBuffer.putFloat(base + 2 * FLOAT_BYTES, normalise(pixel and 0xFF))
            }
        }
        inputBuffer.rewind()
    }

    /** BlazeFace was trained on inputs scaled to [-1, 1]. */
    private fun normalise(channel: Int): Float = (channel / 127.5f) - 1.0f

    private fun flatten() {
        for (i in 0 until boxCount) {
            System.arraycopy(
                regressors[0][i], 0,
                flatRegressors, i * BlazeFaceDecoder.VALUES_PER_BOX,
                BlazeFaceDecoder.VALUES_PER_BOX,
            )
            flatScores[i] = scores[0][i][0]
        }
    }

    private fun BlazeFaceDecoder.Detection.toDetectedFace(
        letterbox: Letterbox,
        sourceWidth: Int,
        sourceHeight: Int,
    ): DetectedFace {
        fun mapX(normalised: Float) = letterbox.toSourceX(normalised, sourceWidth)
        fun mapY(normalised: Float) = letterbox.toSourceY(normalised, sourceHeight)

        return DetectedFace(
            bounds = RectF(mapX(left), mapY(top), mapX(right), mapY(bottom)),
            score = score,
            keypoints = keypoints.map { (x, y) -> PointF(mapX(x), mapY(y)) },
        )
    }

    override fun close() {
        synchronized(lock) { runCatching { interpreter.close() } }
    }

    /**
     * Geometry of fitting a non-square frame into the square model input without
     * distorting it, and getting back out again.
     */
    data class Letterbox(
        val scaledWidth: Int,
        val scaledHeight: Int,
        val offsetX: Int,
        val offsetY: Int,
        val inputSize: Int,
    ) {
        fun toSourceX(normalised: Float, sourceWidth: Int): Float {
            val pixelInInput = normalised * inputSize - offsetX
            return pixelInInput / scaledWidth * sourceWidth
        }

        fun toSourceY(normalised: Float, sourceHeight: Int): Float {
            val pixelInInput = normalised * inputSize - offsetY
            return pixelInInput / scaledHeight * sourceHeight
        }

        companion object {
            fun of(width: Int, height: Int, inputSize: Int): Letterbox {
                val scale = minOf(
                    inputSize.toFloat() / width,
                    inputSize.toFloat() / height,
                )
                val scaledWidth = (width * scale).toInt().coerceIn(1, inputSize)
                val scaledHeight = (height * scale).toInt().coerceIn(1, inputSize)
                return Letterbox(
                    scaledWidth = scaledWidth,
                    scaledHeight = scaledHeight,
                    offsetX = (inputSize - scaledWidth) / 2,
                    offsetY = (inputSize - scaledHeight) / 2,
                    inputSize = inputSize,
                )
            }
        }
    }

    companion object {
        private const val TAG = "TfLiteFaceDetector"
        private const val FLOAT_BYTES = 4
        const val INPUT_SIZE = BlazeFaceAnchors.INPUT_SIZE
        const val MODEL_ASSET = "blaze_face_short_range.tflite"

        fun create(context: Context): TfLiteFaceDetector {
            try {
                val fd = context.assets.openFd(MODEL_ASSET)
                val buffer = FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
                fd.close()

                val interpreter = Interpreter(
                    buffer,
                    Interpreter.Options().apply {
                        numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                        setUseXNNPACK(true)
                    },
                )

                // Identify the outputs by shape rather than trusting their order: the
                // regressor tensor is the one with 16 values per box.
                var regressorIndex = 0
                var scoreIndex = 1
                var boxCount = BlazeFaceAnchors.EXPECTED_ANCHOR_COUNT
                for (i in 0 until interpreter.outputTensorCount) {
                    val shape = interpreter.getOutputTensor(i).shape()
                    if (shape.size == 3 && shape[2] == BlazeFaceDecoder.VALUES_PER_BOX) {
                        regressorIndex = i
                        boxCount = shape[1]
                    } else if (shape.size == 3 && shape[2] == 1) {
                        scoreIndex = i
                    }
                }

                if (boxCount != BlazeFaceAnchors.anchors.size) {
                    SLog.w(
                        TAG,
                        "Model reports $boxCount boxes, anchors generated " +
                            "${BlazeFaceAnchors.anchors.size} — decoding may be wrong",
                    )
                }

                SLog.i(TAG, "BlazeFace ready on LiteRT: $boxCount boxes")
                return TfLiteFaceDetector(interpreter, regressorIndex, scoreIndex, boxCount)
            } catch (t: Throwable) {
                throw AppError.ModelUnavailable("face detection", t)
            }
        }
    }
}
