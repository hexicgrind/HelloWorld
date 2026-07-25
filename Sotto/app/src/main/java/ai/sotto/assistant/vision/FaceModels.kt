package ai.sotto.assistant.vision

import android.graphics.PointF
import android.graphics.RectF

/** One face as reported by MediaPipe, in source-image pixel coordinates. */
data class DetectedFace(
    val bounds: RectF,
    val score: Float,
    /** BlazeFace keypoints: left eye, right eye, nose, mouth, left ear, right ear. */
    val keypoints: List<PointF> = emptyList(),
) {
    /**
     * Design Doc 1: "Detected faces are tracked by bounding box area to prioritize
     * the closest person in frame."
     */
    val area: Float get() = bounds.width().coerceAtLeast(0f) * bounds.height().coerceAtLeast(0f)

    val centerX: Float get() = bounds.centerX()
    val centerY: Float get() = bounds.centerY()

    val leftEye: PointF? get() = keypoints.getOrNull(KEYPOINT_LEFT_EYE)
    val rightEye: PointF? get() = keypoints.getOrNull(KEYPOINT_RIGHT_EYE)

    companion object {
        const val KEYPOINT_LEFT_EYE = 0
        const val KEYPOINT_RIGHT_EYE = 1
        const val KEYPOINT_NOSE = 2
        const val KEYPOINT_MOUTH = 3
    }
}

/** A face that has been tracked across frames, with how long we've held it. */
data class TrackedFace(
    val trackId: Long,
    val face: DetectedFace,
    val firstSeenAtMs: Long,
    val lastSeenAtMs: Long,
    val frameCount: Int,
) {
    val dwellMs: Long get() = lastSeenAtMs - firstSeenAtMs
}

/** Result of comparing one embedding against the whole database. */
data class MatchResult(
    val attendeeId: String?,
    val score: Float,
    val runnerUpScore: Float,
    val isMatch: Boolean,
) {
    /**
     * How much better the winner was than the next best candidate. A confident match
     * should be clearly ahead of the field; a narrow margin means two people in the
     * roster look alike and we should be careful about announcing a name.
     */
    val margin: Float get() = score - runnerUpScore

    companion object {
        val NONE = MatchResult(null, 0f, 0f, false)
    }
}
