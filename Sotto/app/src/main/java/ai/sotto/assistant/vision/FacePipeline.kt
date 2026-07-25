package ai.sotto.assistant.vision

import android.graphics.Bitmap
import ai.sotto.assistant.core.SLog
import ai.sotto.assistant.data.model.Attendee
import ai.sotto.assistant.data.model.TargetState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * Pipeline #1 of the four in Design Doc 1 § Core Architecture: detect -> track ->
 * embed -> match.
 *
 * Per § Runtime Flow: "When a face enters the frame and is tracked for one second or
 * more, the embedding is extracted and matched against the database. If a match is
 * found above the confidence threshold (default zero point seven), the attendee record
 * is retrieved and marked as 'current target.'"
 *
 * Embedding is the expensive step (the doc budgets 80-120 ms for detect + embed), so it
 * only runs once the dwell requirement is met and then at a throttled cadence, not on
 * every frame.
 */
class FacePipeline(
    private val detector: FaceDetectorSource,
    private val embedder: FaceEmbedder,
    private val matcher: FaceMatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {

    private val tracker = FaceTracker()

    private val _target = MutableStateFlow<TargetState>(TargetState.NoFace)
    val target: StateFlow<TargetState> = _target.asStateFlow()

    private val _lastFaceBox = MutableStateFlow<DetectedFace?>(null)
    val lastFaceBox: StateFlow<DetectedFace?> = _lastFaceBox.asStateFlow()

    private val framesProcessed = AtomicLong(0)
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    data class Stats(
        val framesProcessed: Long = 0,
        val embeddingsComputed: Long = 0,
        val lastDetectMs: Long = 0,
        val lastEmbedMs: Long = 0,
        val lastMatchMs: Long = 0,
        val facesInFrame: Int = 0,
    )

    /** Dwell required before we spend an embedding. Design doc default: one second. */
    @Volatile
    var dwellMs: Long = 1_000L

    @Volatile
    private var attendees: List<Attendee> = emptyList()

    /** Sentinel meaning "we have not embedded on this track yet", so the first
     *  attempt is never throttled regardless of what the clock happens to read. */
    private var lastEmbedAtMs = NEVER_EMBEDDED
    private var lastTrackId = -1L
    private var embeddingsComputed = 0L

    fun setAttendees(list: List<Attendee>) {
        attendees = list
        matcher.clearCache()
    }

    fun setThreshold(threshold: Float) {
        matcher.threshold = threshold
    }

    /**
     * Processes one camera frame. Returns the current target state.
     *
     * Frames arrive upright and already rotated; [frame] is not retained or recycled
     * here — the caller owns it.
     */
    fun onFrame(frame: Bitmap): TargetState {
        val now = clock()
        framesProcessed.incrementAndGet()

        val detectStart = clock()
        val faces = detector.detect(frame)
        val detectMs = clock() - detectStart

        val frameArea = (frame.width * frame.height).toFloat()
        val tracked = tracker.update(faces, now, frameArea)
        _lastFaceBox.value = tracked?.face

        _stats.value = _stats.value.copy(
            framesProcessed = framesProcessed.get(),
            lastDetectMs = detectMs,
            facesInFrame = faces.size,
        )

        if (tracked == null) {
            // Design Doc 1 § Error Handling: with no face, the app simply stays silent.
            if (_target.value !is TargetState.NoFace) _target.value = TargetState.NoFace
            return _target.value
        }

        // A new person in frame invalidates whatever we previously concluded.
        if (tracked.trackId != lastTrackId) {
            lastTrackId = tracked.trackId
            lastEmbedAtMs = NEVER_EMBEDDED
            matcher.clearCache()
            _target.value = TargetState.Tracking(tracked.dwellMs)
        }

        if (tracked.dwellMs < dwellMs) {
            _target.value = TargetState.Tracking(tracked.dwellMs)
            return _target.value
        }

        val alreadyMatched = _target.value is TargetState.Matched
        val cadence = if (alreadyMatched) REMATCH_INTERVAL_MS else RETRY_INTERVAL_MS
        if (lastEmbedAtMs != NEVER_EMBEDDED && now - lastEmbedAtMs < cadence) {
            return _target.value
        }

        lastEmbedAtMs = now
        val state = embedAndMatch(frame, tracked.face, now)
        _target.value = state
        return state
    }

    private fun embedAndMatch(frame: Bitmap, face: DetectedFace, now: Long): TargetState {
        val crop = FaceImaging.alignedCrop(frame, face) ?: return TargetState.Tracking(dwellMs)

        try {
            if (FaceImaging.cropQuality(crop) < MIN_CROP_QUALITY) {
                SLog.d(TAG) { "Skipping a low-quality crop" }
                return _target.value.takeIf { it is TargetState.Matched }
                    ?: TargetState.Tracking(dwellMs)
            }

            val embedStart = clock()
            val embedding = embedder.embed(crop) ?: return TargetState.Tracking(dwellMs)
            val embedMs = clock() - embedStart
            embeddingsComputed++

            val snapshot = attendees
            if (snapshot.none { it.hasFace }) {
                // Design Doc 1 § Error Handling: "If no attendee database is loaded,
                // face detection runs but no context is passed to Gemini."
                _stats.value = _stats.value.copy(
                    embeddingsComputed = embeddingsComputed,
                    lastEmbedMs = embedMs,
                )
                return TargetState.Unrecognised(bestScore = 0f)
            }

            val matchStart = clock()
            val result = matcher.matchCached(embedding, snapshot)
            val matchMs = clock() - matchStart

            _stats.value = _stats.value.copy(
                embeddingsComputed = embeddingsComputed,
                lastEmbedMs = embedMs,
                lastMatchMs = matchMs,
            )

            val attendee = result.attendeeId?.let { id -> snapshot.firstOrNull { it.id == id } }
            return if (result.isMatch && attendee != null) {
                val previous = _target.value
                if (previous is TargetState.Matched && previous.attendee.id == attendee.id) {
                    previous.copy(score = result.score)
                } else {
                    SLog.i(TAG, "Matched ${attendee.name} at ${"%.2f".format(result.score)}")
                    TargetState.Matched(attendee, result.score, now)
                }
            } else {
                // Design Doc 1 § Error Handling: "If a face is detected but no match is
                // found, the app remains silent."
                TargetState.Unrecognised(result.score)
            }
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    /**
     * One-shot embedding for the enrolment flow: takes a still, finds the biggest face
     * and returns its embedding plus the aligned crop for the UI to show back.
     */
    fun embedStill(bitmap: Bitmap): EnrolmentSample? {
        val face = detector.detect(bitmap).maxByOrNull { it.area } ?: return null
        val crop = FaceImaging.alignedCrop(bitmap, face) ?: return null
        val quality = FaceImaging.cropQuality(crop)
        val embedding = embedder.embed(crop)
        if (embedding == null) {
            if (!crop.isRecycled) crop.recycle()
            return null
        }
        return EnrolmentSample(embedding, crop, face, quality)
    }

    data class EnrolmentSample(
        val embedding: FloatArray,
        val crop: Bitmap,
        val face: DetectedFace,
        val quality: Float,
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    fun reset() {
        tracker.reset()
        matcher.clearCache()
        lastTrackId = -1L
        lastEmbedAtMs = NEVER_EMBEDDED
        _target.value = TargetState.NoFace
        _lastFaceBox.value = null
    }

    override fun close() {
        runCatching { detector.close() }
        runCatching { embedder.close() }
    }

    companion object {
        private const val TAG = "FacePipeline"

        /** Once matched, re-verify occasionally to notice the person walking away. */
        const val REMATCH_INTERVAL_MS = 2_000L

        /** While unmatched, retry a bit more eagerly — lighting and pose change fast. */
        const val RETRY_INTERVAL_MS = 600L

        const val MIN_CROP_QUALITY = 0.18f

        private const val NEVER_EMBEDDED = Long.MIN_VALUE
    }
}
