package ai.sotto.assistant.vision

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Frame-to-frame tracking of the closest face.
 *
 * Design Doc 1 § Face Detection Pipeline: "Detected faces are tracked by bounding box
 * area to prioritize the closest person in frame." § Runtime Flow: "When a face enters
 * the frame and is tracked for one second or more, the embedding is extracted."
 *
 * Association is intentionally simple — nearest centre plus a similar box size — which
 * is all that's needed when we only ever follow one subject at conversational distance.
 */
class FaceTracker(
    /** Faces this far apart (relative to face width) are considered different people. */
    private val maxCenterDriftRatio: Float = DEFAULT_CENTER_DRIFT_RATIO,
    /** A box that changes size by more than this ratio is treated as a new face. */
    private val maxSizeChangeRatio: Float = DEFAULT_SIZE_CHANGE_RATIO,
    /** How long the subject may vanish (a blink, a turn) before the track is dropped. */
    private val trackTimeoutMs: Long = DEFAULT_TRACK_TIMEOUT_MS,
    /** Ignore boxes smaller than this fraction of the frame — too far away to matter. */
    private val minRelativeArea: Float = DEFAULT_MIN_RELATIVE_AREA,
) {
    private var current: TrackedFace? = null
    private var nextTrackId = 1L

    val tracked: TrackedFace? get() = current

    /**
     * Feeds one frame's detections in and returns the currently tracked face, or null
     * when nothing worth following is in frame.
     */
    fun update(faces: List<DetectedFace>, nowMs: Long, frameArea: Float = 0f): TrackedFace? {
        val candidates = if (frameArea > 0f) {
            faces.filter { it.area / frameArea >= minRelativeArea }
        } else {
            faces
        }

        // Closest person == largest bounding box.
        val closest = candidates.maxByOrNull { it.area }

        if (closest == null) {
            val existing = current
            if (existing != null && nowMs - existing.lastSeenAtMs > trackTimeoutMs) {
                current = null
            }
            return current?.takeIf { nowMs - it.lastSeenAtMs <= trackTimeoutMs }
        }

        val existing = current?.takeIf { previous ->
            nowMs - previous.lastSeenAtMs <= trackTimeoutMs &&
                isSameFace(previous.face, closest)
        }

        current = if (existing != null) {
            existing.copy(
                face = closest,
                lastSeenAtMs = nowMs,
                frameCount = existing.frameCount + 1,
            )
        } else {
            TrackedFace(
                trackId = nextTrackId++,
                face = closest,
                firstSeenAtMs = nowMs,
                lastSeenAtMs = nowMs,
                frameCount = 1,
            )
        }
        return current
    }

    /** True once the current track has been held for at least [dwellMs]. */
    fun hasDwelled(dwellMs: Long, nowMs: Long): Boolean {
        val t = current ?: return false
        if (nowMs - t.lastSeenAtMs > trackTimeoutMs) return false
        return t.dwellMs >= dwellMs
    }

    fun reset() {
        current = null
    }

    private fun isSameFace(previous: DetectedFace, candidate: DetectedFace): Boolean {
        val reference = maxOf(previous.bounds.width(), 1f)
        val drift = hypot(
            (candidate.centerX - previous.centerX).toDouble(),
            (candidate.centerY - previous.centerY).toDouble(),
        ).toFloat()
        if (drift / reference > maxCenterDriftRatio) return false

        val previousWidth = maxOf(previous.bounds.width(), 1f)
        val sizeChange = abs(candidate.bounds.width() - previousWidth) / previousWidth
        return sizeChange <= maxSizeChangeRatio
    }

    companion object {
        const val DEFAULT_CENTER_DRIFT_RATIO = 0.6f
        const val DEFAULT_SIZE_CHANGE_RATIO = 0.5f
        const val DEFAULT_TRACK_TIMEOUT_MS = 600L
        const val DEFAULT_MIN_RELATIVE_AREA = 0.004f
    }
}
