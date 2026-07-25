package ai.sotto.assistant.vision

import ai.sotto.assistant.data.model.Attendee
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * The matching half of Design Doc 1 § Testing Strategy's first line, and the enforcement
 * point for "Matches above a confidence threshold" plus the documented 0.70 default.
 */
class FaceMatcherTest {

    private var now = 1_000L
    private lateinit var matcher: FaceMatcher

    private val random = Random(101)

    @Before
    fun setUp() {
        now = 1_000L
        matcher = FaceMatcher(clock = { now })
    }

    private fun embedding(seed: Int): FloatArray {
        val r = Random(seed)
        return FaceMath.l2Normalize(FloatArray(128) { r.nextFloat() * 2 - 1 })
    }

    private fun attendee(id: String, embedding: FloatArray?) = Attendee(
        id = id,
        name = "Person $id",
        embedding = embedding?.toList(),
    )

    /** Nudges an embedding until it sits at approximately [target] similarity. */
    private fun near(source: FloatArray, target: Float): FloatArray {
        val orthogonal = FaceMath.l2Normalize(FloatArray(128) { random.nextFloat() * 2 - 1 })
        var best = source
        var bestDelta = Float.MAX_VALUE
        var alpha = 0.0f
        while (alpha <= 1.0f) {
            val blended = FaceMath.l2Normalize(
                FloatArray(128) { source[it] * (1 - alpha) + orthogonal[it] * alpha }
            )
            val delta = kotlin.math.abs(FaceMath.cosineSimilarity(source, blended) - target)
            if (delta < bestDelta) {
                bestDelta = delta
                best = blended
            }
            alpha += 0.005f
        }
        return best
    }

    // ---- Threshold behaviour ----------------------------------------------------

    @Test
    fun `default threshold is the design doc value`() {
        assertThat(FaceMatcher.DEFAULT_THRESHOLD).isEqualTo(0.70f)
        assertThat(FaceMatcher().threshold).isEqualTo(0.70f)
    }

    @Test
    fun `an exact embedding match is reported`() {
        val target = embedding(1)
        val roster = listOf(attendee("a", target), attendee("b", embedding(2)))

        val result = matcher.match(target, roster)

        assertThat(result.isMatch).isTrue()
        assertThat(result.attendeeId).isEqualTo("a")
        assertThat(result.score).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `a similarity just above the threshold matches`() {
        val target = embedding(3)
        val probe = near(target, 0.80f)
        val roster = listOf(attendee("a", target))

        val result = matcher.match(probe, roster)

        assertThat(result.score).isAtLeast(0.70f)
        assertThat(result.isMatch).isTrue()
    }

    @Test
    fun `a similarity below the threshold does not match`() {
        val target = embedding(5)
        val probe = near(target, 0.45f)
        val roster = listOf(attendee("a", target))

        val result = matcher.match(probe, roster)

        assertThat(result.score).isLessThan(0.70f)
        assertThat(result.isMatch).isFalse()
        // The score is still reported so the UI can show "not recognised, best was N%".
        assertThat(result.attendeeId).isEqualTo("a")
    }

    @Test
    fun `raising the threshold rejects a formerly acceptable match`() {
        val target = embedding(7)
        val probe = near(target, 0.75f)
        val roster = listOf(attendee("a", target))

        assertThat(matcher.match(probe, roster).isMatch).isTrue()

        matcher.threshold = 0.95f
        assertThat(matcher.match(probe, roster).isMatch).isFalse()
    }

    @Test
    fun `lowering the threshold accepts a formerly rejected match`() {
        val target = embedding(11)
        val probe = near(target, 0.55f)
        val roster = listOf(attendee("a", target))

        assertThat(matcher.match(probe, roster).isMatch).isFalse()

        matcher.threshold = 0.50f
        assertThat(matcher.match(probe, roster).isMatch).isTrue()
    }

    @Test
    fun `threshold is clamped into a sane range`() {
        matcher.threshold = 5f
        assertThat(matcher.threshold).isEqualTo(1f)
        matcher.threshold = -3f
        assertThat(matcher.threshold).isEqualTo(0f)
    }

    // ---- Picking the winner -----------------------------------------------------

    @Test
    fun `the closest attendee wins over the rest of the roster`() {
        val target = embedding(13)
        val roster = listOf(
            attendee("far", embedding(14)),
            attendee("closest", target),
            attendee("also-far", embedding(15)),
        )

        assertThat(matcher.match(target, roster).attendeeId).isEqualTo("closest")
    }

    @Test
    fun `runner-up score is reported`() {
        val target = embedding(17)
        val roster = listOf(attendee("a", target), attendee("b", near(target, 0.6f)))

        val result = matcher.match(target, roster)

        assertThat(result.runnerUpScore).isGreaterThan(0f)
        assertThat(result.runnerUpScore).isLessThan(result.score)
        assertThat(result.margin).isGreaterThan(0f)
    }

    @Test
    fun `two nearly identical people are not confidently matched`() {
        // Both above threshold and separated by less than the minimum margin: the safe
        // answer is "I'm not sure", not a coin flip on someone's name.
        val target = embedding(19)
        val roster = listOf(attendee("twin-a", target), attendee("twin-b", target))

        val result = matcher.match(target, roster)

        assertThat(result.score).isAtLeast(0.70f)
        assertThat(result.margin).isLessThan(FaceMatcher.DEFAULT_MIN_MARGIN)
        assertThat(result.isMatch).isFalse()
    }

    // ---- Degenerate input -------------------------------------------------------

    @Test
    fun `an empty roster yields no match`() {
        assertThat(matcher.match(embedding(23), emptyList()).isMatch).isFalse()
        assertThat(matcher.match(embedding(23), emptyList()).attendeeId).isNull()
    }

    @Test
    fun `attendees without an enrolled face are skipped`() {
        val target = embedding(29)
        val roster = listOf(
            attendee("no-face-1", null),
            attendee("enrolled", target),
            attendee("no-face-2", null),
        )

        assertThat(matcher.match(target, roster).attendeeId).isEqualTo("enrolled")
    }

    @Test
    fun `a roster with no enrolled faces yields no match`() {
        val roster = listOf(attendee("a", null), attendee("b", null))
        assertThat(matcher.match(embedding(31), roster)).isEqualTo(MatchResult.NONE)
    }

    @Test
    fun `an empty probe yields no match`() {
        val roster = listOf(attendee("a", embedding(37)))
        assertThat(matcher.match(FloatArray(0), roster)).isEqualTo(MatchResult.NONE)
    }

    @Test
    fun `embeddings of the wrong dimension are ignored`() {
        val target = embedding(41)
        val roster = listOf(
            attendee("wrong-size", FloatArray(64) { 0.5f }),
            attendee("right-size", target),
        )

        assertThat(matcher.match(target, roster).attendeeId).isEqualTo("right-size")
    }

    @Test
    fun `only wrong-dimension embeddings yields no match`() {
        val roster = listOf(attendee("wrong", FloatArray(64) { 0.5f }))
        assertThat(matcher.match(embedding(43), roster)).isEqualTo(MatchResult.NONE)
    }

    // ---- Caching ----------------------------------------------------------------

    @Test
    fun `a repeated probe reuses the cached decision`() {
        val target = embedding(47)
        val roster = listOf(attendee("a", target))

        val first = matcher.matchCached(target, roster)
        assertThat(first.isMatch).isTrue()
        assertThat(matcher.cacheSize).isEqualTo(1)

        // A different roster would give a different answer if the cache were bypassed.
        val second = matcher.matchCached(target, listOf(attendee("z", embedding(48))))
        assertThat(second.attendeeId).isEqualTo("a")
    }

    @Test
    fun `a sufficiently different probe bypasses the cache`() {
        val target = embedding(53)
        val roster = listOf(attendee("a", target))
        matcher.matchCached(target, roster)

        val different = embedding(54)
        val result = matcher.matchCached(different, roster)

        assertThat(result.attendeeId == "a" && result.isMatch).isFalse()
    }

    @Test
    fun `the cache expires after its TTL`() {
        val target = embedding(59)
        val roster = listOf(attendee("a", target))
        matcher.matchCached(target, roster)

        now += FaceMatcher.CACHE_TTL_MS + 1

        val stale = matcher.matchCached(target, listOf(attendee("z", embedding(60))))
        assertThat(stale.attendeeId).isNotEqualTo("a")
    }

    @Test
    fun `a failed match is not cached`() {
        val target = embedding(61)
        val probe = near(target, 0.3f)
        matcher.matchCached(probe, listOf(attendee("a", target)))
        assertThat(matcher.cacheSize).isEqualTo(0)
    }

    @Test
    fun `changing the threshold clears the cache`() {
        val target = embedding(67)
        matcher.matchCached(target, listOf(attendee("a", target)))
        assertThat(matcher.cacheSize).isEqualTo(1)

        matcher.threshold = 0.9f
        assertThat(matcher.cacheSize).isEqualTo(0)
    }

    @Test
    fun `clearCache empties the cache`() {
        val target = embedding(71)
        matcher.matchCached(target, listOf(attendee("a", target)))
        matcher.clearCache()
        assertThat(matcher.cacheSize).isEqualTo(0)
    }

    @Test
    fun `invalidateFor drops entries pointing at a deleted attendee`() {
        val target = embedding(73)
        matcher.matchCached(target, listOf(attendee("a", target)))
        assertThat(matcher.cacheSize).isEqualTo(1)

        matcher.invalidateFor("a")
        assertThat(matcher.cacheSize).isEqualTo(0)
    }

    @Test
    fun `invalidateFor leaves unrelated entries alone`() {
        val target = embedding(79)
        matcher.matchCached(target, listOf(attendee("a", target)))
        matcher.invalidateFor("someone-else")
        assertThat(matcher.cacheSize).isEqualTo(1)
    }

    @Test
    fun `the cache is bounded`() {
        repeat(FaceMatcher.MAX_CACHE_ENTRIES * 3) { index ->
            val e = embedding(1_000 + index)
            matcher.matchCached(e, listOf(attendee("a$index", e)), cacheKey = "key$index")
        }
        assertThat(matcher.cacheSize).isAtMost(FaceMatcher.MAX_CACHE_ENTRIES)
    }

    // ---- Scale ------------------------------------------------------------------

    @Test
    fun `a large roster is matched correctly and quickly`() {
        val roster = (0 until 500).map { attendee("p$it", embedding(2_000 + it)) }
        val target = roster[321].embedding!!.toFloatArray()

        val startedAt = System.nanoTime()
        val result = matcher.match(target, roster)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0

        assertThat(result.attendeeId).isEqualTo("p321")
        assertThat(result.isMatch).isTrue()
        // Design Doc 1 § Latency Budget allows 5-10 ms for the lookup. Generous ceiling
        // here so the assertion is about the algorithm, not about CI machine speed.
        assertThat(elapsedMs).isLessThan(150.0)
    }

    @Test
    fun `every distinct person in a large roster matches themselves`() {
        val roster = (0 until 100).map { attendee("p$it", embedding(3_000 + it)) }
        roster.forEach { person ->
            val result = matcher.match(person.embedding!!.toFloatArray(), roster)
            assertThat(result.attendeeId).isEqualTo(person.id)
            assertThat(result.isMatch).isTrue()
        }
    }
}
