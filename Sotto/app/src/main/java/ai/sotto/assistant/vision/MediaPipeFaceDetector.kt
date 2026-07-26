package ai.sotto.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.core.SLog
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import java.io.Closeable

/** Finds faces in a frame. */
interface FaceDetectorSource : Closeable {
    fun detect(bitmap: Bitmap): List<DetectedFace>
}

/**
 * MediaPipe BlazeFace (short-range).
 *
 * Design Doc 1 § Technical Stack: "Face Detection: MediaPipe Face Detection library,
 * version 0.10.3 or later." § Face Detection Pipeline: it "runs continuously on camera
 * frames at thirty frames per second".
 *
 * Runs in [RunningMode.IMAGE] — a blocking call per frame on the CameraX analysis
 * executor. BlazeFace short-range costs single-digit milliseconds, so this keeps the
 * pipeline synchronous and deterministic (and therefore testable) without dropping
 * below the frame budget.
 */
class MediaPipeFaceDetector private constructor(
    private val detector: FaceDetector,
) : FaceDetectorSource {

    private val lock = Any()

    override fun detect(bitmap: Bitmap): List<DetectedFace> = synchronized(lock) {
        try {
            val image = BitmapImageBuilder(bitmap).build()
            val result = detector.detect(image)
            result.detections().map { detection ->
                val box = detection.boundingBox()
                DetectedFace(
                    bounds = RectF(
                        box.left.toFloat(),
                        box.top.toFloat(),
                        box.right.toFloat(),
                        box.bottom.toFloat(),
                    ),
                    score = detection.categories().firstOrNull()?.score() ?: 0f,
                    keypoints = detection.keypoints()
                        .orElse(null)
                        ?.map { kp ->
                            // MediaPipe reports keypoints normalised to [0, 1].
                            PointF(kp.x() * bitmap.width, kp.y() * bitmap.height)
                        }
                        ?: emptyList(),
                )
            }
        } catch (t: Throwable) {
            SLog.e(TAG, "Face detection failed on a frame", t)
            emptyList()
        }
    }

    override fun close() {
        synchronized(lock) { runCatching { detector.close() } }
    }

    companion object {
        private const val TAG = "FaceDetector"
        const val MODEL_ASSET = "blaze_face_short_range.tflite"

        /** Design Doc 1: faces are considered "detected with sufficient confidence". */
        const val DEFAULT_MIN_CONFIDENCE = 0.55f
        const val DEFAULT_MAX_FACES = 5

        fun create(
            context: Context,
            minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
            maxFaces: Int = DEFAULT_MAX_FACES,
        ): MediaPipeFaceDetector = try {
            val options = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(minConfidence)
                .setMinSuppressionThreshold(0.3f)
                .build()
            MediaPipeFaceDetector(FaceDetector.createFromOptions(context, options))
                .also { SLog.i(TAG, "MediaPipe face detector ready (maxFaces=$maxFaces)") }
        } catch (t: Throwable) {
            throw AppError.ModelUnavailable("face detection", t)
        }
    }
}
