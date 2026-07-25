package ai.sotto.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.core.SLog
import ai.sotto.assistant.data.model.Attendee
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Extracts a 128-dimensional face embedding from an aligned crop. */
interface FaceEmbedder : Closeable {
    /** Returns an L2-normalised embedding, or null when the crop is unusable. */
    fun embed(alignedFace: Bitmap): FloatArray?

    val dimensions: Int
}

/**
 * FaceNet running on TFLite.
 *
 * Design Doc 1 § Technical Stack: "Face Embedding: TFLite model, FaceNet-based or
 * equivalent". The bundled model takes a 160x160x3 pre-whitened crop and emits 128
 * floats, matching § Database Schema's "embedding (array of floats, 128 dimensions)".
 *
 * One interpreter is shared and calls are serialised, because TFLite interpreters are
 * not thread-safe and the camera path plus enrolment can both reach for it.
 */
class TfLiteFaceEmbedder private constructor(
    private val interpreter: Interpreter,
    private val inputSize: Int,
    override val dimensions: Int,
) : FaceEmbedder {

    private val lock = Any()
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(inputSize * inputSize * 3 * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
    private val outputBuffer = Array(1) { FloatArray(dimensions) }
    private val pixelScratch = IntArray(inputSize * inputSize)

    override fun embed(alignedFace: Bitmap): FloatArray? {
        val prepared = if (alignedFace.width == inputSize && alignedFace.height == inputSize) {
            alignedFace
        } else {
            Bitmap.createScaledBitmap(alignedFace, inputSize, inputSize, true)
        }

        return synchronized(lock) {
            try {
                val tensor = FaceImaging.toTensor(prepared, pixelScratch)
                inputBuffer.rewind()
                for (v in tensor) inputBuffer.putFloat(v)
                inputBuffer.rewind()

                interpreter.run(inputBuffer, outputBuffer)

                val raw = outputBuffer[0]
                if (raw.any { !it.isFinite() }) {
                    SLog.w(TAG, "Model produced a non-finite embedding; discarding")
                    null
                } else {
                    FaceMath.l2Normalize(raw.copyOf())
                }
            } catch (t: Throwable) {
                SLog.e(TAG, "Embedding failed", t)
                null
            } finally {
                if (prepared !== alignedFace && !prepared.isRecycled) prepared.recycle()
            }
        }
    }

    override fun close() {
        synchronized(lock) { runCatching { interpreter.close() } }
    }

    companion object {
        private const val TAG = "FaceEmbedder"
        private const val FLOAT_BYTES = 4
        const val MODEL_ASSET = "facenet.tflite"

        /**
         * Loads the model from assets. The APK keeps `.tflite` uncompressed
         * (see `noCompress` in build.gradle.kts) so it can be memory-mapped straight
         * off disk instead of being copied into the heap.
         */
        fun create(context: Context, threads: Int = defaultThreads()): TfLiteFaceEmbedder {
            try {
                val fd = context.assets.openFd(MODEL_ASSET)
                val buffer = FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        fd.startOffset,
                        fd.declaredLength,
                    )
                }
                fd.close()

                val options = Interpreter.Options().apply {
                    numThreads = threads
                    setUseXNNPACK(true)
                }
                val interpreter = Interpreter(buffer, options)

                val inputShape = interpreter.getInputTensor(0).shape()
                val outputShape = interpreter.getOutputTensor(0).shape()
                val size = inputShape.getOrElse(1) { FaceImaging.INPUT_SIZE }
                val dims = outputShape.lastOrNull() ?: Attendee.EMBEDDING_DIMENSIONS

                SLog.i(TAG, "FaceNet ready: input ${inputShape.joinToString("x")} -> $dims-d")
                if (dims != Attendee.EMBEDDING_DIMENSIONS) {
                    SLog.w(TAG, "Model emits $dims dims, design doc specifies ${Attendee.EMBEDDING_DIMENSIONS}")
                }
                return TfLiteFaceEmbedder(interpreter, size, dims)
            } catch (t: Throwable) {
                throw AppError.ModelUnavailable(t)
            }
        }

        private fun defaultThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}
