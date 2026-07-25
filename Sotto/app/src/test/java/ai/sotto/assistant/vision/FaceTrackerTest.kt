package ai.sotto.assistant.vision

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Design Doc 1 § Face Detection Pipeline: "tracked by bounding box area to prioritize
 * the closest person in frame", and § Runtime Flow: "tracked for one second or more".
 *
 * Robolectric only for `RectF` — the tracker itself is pure logic.
 */
@RunWith(RobolectricTestRunner::class)
class FaceTrackerTest {

    private lateinit var tracker: FaceTracker

    @Before
    fun setUp() {
        tracker = FaceTracker()
    }

    private fun face(left: Float, top: Float, size: Float, score: Float = 0.9f) = DetectedFace(
        bounds = RectF(left, top, left + size, top + size),
        score = score,
    )

    @Test
    fun `no detections means nothing tracked`() {
        assertThat(tracker.update(emptyList(), nowMs = 0L)).isNull()
        assertThat(tracker.tracked).isNull()
    }

    @Test
    fun `a single face becomes the track`() {
        val tracked = tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 0L)
        assertThat(tracked).isNotNull()
        assertThat(tracked!!.frameCount).isEqualTo(1)
        assertThat(tracked.dwellMs).isEqualTo(0L)
    }

    @Test
    fun `the largest box wins - the closest person is prioritised`() {
        val small = face(10f, 10f, 40f)
        val large = face(200f, 200f, 120f)
        val medium = face(400f, 100f, 70f)

        val tracked = tracker.update(listOf(small, large, medium), nowMs = 0L)

        assertThat(tracked!!.face.bounds.width()).isEqualTo(120f)
    }

    @Test
    fun `dwell accumulates while the same face stays in frame`() {
        tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 0L)
        tracker.update(listOf(face(102f, 101f, 81f)), nowMs = 200L)
        val tracked = tracker.update(listOf(face(104f, 103f, 82f)), nowMs = 500L)

        assertThat(tracked!!.dwellMs).isEqualTo(500L)
        assertThat(tracked.frameCount).isEqualTo(3)
    }

    @Test
    fun `hasDwelled is false before one second - the design doc requirement`() {
        tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 0L)
        tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 900L)

        assertThat(tracker.hasDwelled(1_000L, nowMs = 900L)).isFalse()
    }

    @Test
    fun `hasDwelled is true at exactly one second`() {
        // Frames arrive at the camera's ~30 fps, not one second apart — a gap that long
        // exceeds the track timeout and legitimately starts a new track.
        var t = 0L
        while (t <= 1_000L) {
            tracker.update(listOf(face(100f, 100f, 80f)), nowMs = t)
            t += 33L
        }
        tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 1_000L)

        assertThat(tracker.hasDwelled(1_000L, nowMs = 1_000L)).isTrue()
    }

    @Test
    fun `a gap longer than the track timeout restarts the dwell clock`() {
        val first = tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 0L)
        // One second with no frames at all is a dropped track, not a held one.
        val second = tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 1_000L)

        assertThat(second!!.trackId).isNotEqualTo(first!!.trackId)
        assertThat(tracker.hasDwelled(1_000L, nowMs = 1_000L)).isFalse()
    }

    @Test
    fun `hasDwelled is false when there is no track at all`() {
        assertThat(tracker.hasDwelled(1_000L, nowMs = 5_000L)).isFalse()
    }

    @Test
    fun `a large jump starts a new track and resets dwell`() {
        val first = tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 0L)
        tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 500L)

        // Someone else steps in, far away from where the previous face was.
        val second = tracker.update(listOf(face(600f, 600f, 80f)), nowMs = 600L)

        assertThat(second!!.trackId).isNotEqualTo(first!!.trackId)
        assertThat(second.dwellMs).isEqualTo(0L)
    }

    @Test
    fun `a sudden size change starts a new track`() {
        val first = tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 0L)
        val second = tracker.update(listOf(face(100f, 100f, 200f)), nowMs = 100L)

        assertThat(second!!.trackId).isNotEqualTo(first!!.trackId)
    }

    @Test
    fun `small natural movement keeps the same track`() {
        val first = tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 0L)
        val second = tracker.update(listOf(face(112f, 108f, 84f)), nowMs = 100L)

        assertThat(second!!.trackId).isEqualTo(first!!.trackId)
    }

    @Test
    fun `a brief dropout does not lose the track`() {
        val first = tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 0L)
        tracker.update(emptyList(), nowMs = 100L)   // a blink
        val resumed = tracker.update(listOf(face(101f, 101f, 80f)), nowMs = 200L)

        assertThat(resumed!!.trackId).isEqualTo(first!!.trackId)
        assertThat(resumed.dwellMs).isEqualTo(200L)
    }

    @Test
    fun `a long absence drops the track`() {
        tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 0L)
        val afterGap = tracker.update(emptyList(), nowMs = 5_000L)

        assertThat(afterGap).isNull()
        assertThat(tracker.tracked).isNull()
    }

    @Test
    fun `a face returning after a long absence is a new track`() {
        val first = tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 0L)
        tracker.update(emptyList(), nowMs = 5_000L)
        val returned = tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 5_100L)

        assertThat(returned!!.trackId).isNotEqualTo(first!!.trackId)
        assertThat(returned.dwellMs).isEqualTo(0L)
    }

    @Test
    fun `tiny faces are ignored when the frame area is known`() {
        val frameArea = 480f * 640f
        // 8x8 box is far below the minimum relative area.
        val tracked = tracker.update(listOf(face(10f, 10f, 8f)), nowMs = 0L, frameArea = frameArea)
        assertThat(tracked).isNull()
    }

    @Test
    fun `a reasonably sized face passes the area filter`() {
        val frameArea = 480f * 640f
        val tracked = tracker.update(listOf(face(100f, 100f, 90f)), nowMs = 0L, frameArea = frameArea)
        assertThat(tracked).isNotNull()
    }

    @Test
    fun `reset clears the current track`() {
        tracker.update(listOf(face(100f, 100f, 80f)), nowMs = 0L)
        tracker.reset()
        assertThat(tracker.tracked).isNull()
    }

    @Test
    fun `track ids are unique and increasing`() {
        val ids = mutableSetOf<Long>()
        repeat(5) { index ->
            tracker.reset()
            tracker.update(listOf(face(100f, 100f, 80f)), nowMs = index * 100L)?.let { ids += it.trackId }
        }
        assertThat(ids).hasSize(5)
    }

    @Test
    fun `a realistic approach and departure produces one track`() {
        // Someone walks toward the camera, their box growing gradually.
        var size = 60f
        var trackId: Long? = null
        for (frame in 0 until 30) {
            size *= 1.02f
            val tracked = tracker.update(
                listOf(face(200f - size / 2, 200f - size / 2, size)),
                nowMs = frame * 33L,
            )
            if (trackId == null) trackId = tracked?.trackId
            assertThat(tracked!!.trackId).isEqualTo(trackId)
        }
        assertThat(tracker.hasDwelled(1_000L, nowMs = 29 * 33L)).isFalse()
        tracker.update(listOf(face(150f, 150f, size)), nowMs = 1_200L)
        assertThat(tracker.hasDwelled(1_000L, nowMs = 1_200L)).isTrue()
    }
}
