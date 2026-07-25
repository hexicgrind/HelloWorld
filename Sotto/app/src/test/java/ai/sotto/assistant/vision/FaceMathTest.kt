package ai.sotto.assistant.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Design Doc 1 § Testing Strategy names this first: "Unit tests for face embedding
 * cosine similarity matching."
 */
class FaceMathTest {

    // ---- cosineSimilarity ------------------------------------------------------

    @Test
    fun `identical vectors are perfectly similar`() {
        val v = floatArrayOf(0.1f, -0.4f, 0.9f, 0.2f)
        assertThat(FaceMath.cosineSimilarity(v, v)).isWithin(1e-5f).of(1f)
    }

    @Test
    fun `opposite vectors are perfectly dissimilar`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(-1f, -2f, -3f)
        assertThat(FaceMath.cosineSimilarity(a, b)).isWithin(1e-5f).of(-1f)
    }

    @Test
    fun `orthogonal vectors score zero`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertThat(FaceMath.cosineSimilarity(a, b)).isWithin(1e-6f).of(0f)
    }

    @Test
    fun `similarity ignores magnitude`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val scaled = floatArrayOf(10f, 20f, 30f)
        assertThat(FaceMath.cosineSimilarity(a, scaled)).isWithin(1e-5f).of(1f)
    }

    @Test
    fun `similarity is symmetric`() {
        val random = Random(7)
        repeat(50) {
            val a = FloatArray(128) { random.nextFloat() * 2 - 1 }
            val b = FloatArray(128) { random.nextFloat() * 2 - 1 }
            assertThat(FaceMath.cosineSimilarity(a, b))
                .isWithin(1e-5f)
                .of(FaceMath.cosineSimilarity(b, a))
        }
    }

    @Test
    fun `result always stays inside minus one to one`() {
        val random = Random(11)
        repeat(200) {
            val a = FloatArray(128) { random.nextFloat() * 200 - 100 }
            val b = FloatArray(128) { random.nextFloat() * 200 - 100 }
            val score = FaceMath.cosineSimilarity(a, b)
            assertThat(score).isAtLeast(-1f)
            assertThat(score).isAtMost(1f)
            assertThat(score.isNaN()).isFalse()
        }
    }

    @Test
    fun `mismatched lengths score zero rather than throwing`() {
        assertThat(FaceMath.cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(1f))).isEqualTo(0f)
    }

    @Test
    fun `empty vectors score zero`() {
        assertThat(FaceMath.cosineSimilarity(FloatArray(0), FloatArray(0))).isEqualTo(0f)
    }

    @Test
    fun `a zero vector scores zero instead of producing NaN`() {
        val zero = FloatArray(128)
        val other = FloatArray(128) { 0.5f }
        val score = FaceMath.cosineSimilarity(zero, other)
        assertThat(score).isEqualTo(0f)
        assertThat(score.isNaN()).isFalse()
    }

    @Test
    fun `both vectors zero scores zero`() {
        assertThat(FaceMath.cosineSimilarity(FloatArray(4), FloatArray(4))).isEqualTo(0f)
    }

    @Test
    fun `tiny magnitudes do not overflow into NaN`() {
        val a = FloatArray(128) { 1e-20f }
        val b = FloatArray(128) { 1e-20f }
        val score = FaceMath.cosineSimilarity(a, b)
        assertThat(score.isNaN()).isFalse()
        assertThat(score).isAtMost(1f)
    }

    @Test
    fun `huge magnitudes do not overflow into NaN`() {
        val a = FloatArray(128) { 1e18f }
        val b = FloatArray(128) { 1e18f }
        val score = FaceMath.cosineSimilarity(a, b)
        assertThat(score.isNaN()).isFalse()
        assertThat(score).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `similarity degrades smoothly as noise is added`() {
        val random = Random(3)
        val base = FaceMath.l2Normalize(FloatArray(128) { random.nextFloat() * 2 - 1 })

        var previous = 1f
        listOf(0.1f, 0.3f, 0.6f, 1.0f, 2.0f).forEach { noise ->
            val perturbed = FaceMath.l2Normalize(
                FloatArray(128) { base[it] + (random.nextFloat() * 2 - 1) * noise }
            )
            val score = FaceMath.cosineSimilarity(base, perturbed)
            assertThat(score).isLessThan(previous + 0.05f)
            previous = score
        }
        // With noise twice the signal, similarity should be well below any usable threshold.
        assertThat(previous).isLessThan(FaceMatcher.DEFAULT_THRESHOLD)
    }

    // ---- dot ------------------------------------------------------------------

    @Test
    fun `dot matches cosine for normalised vectors`() {
        val random = Random(19)
        repeat(30) {
            val a = FaceMath.l2Normalize(FloatArray(128) { random.nextFloat() * 2 - 1 })
            val b = FaceMath.l2Normalize(FloatArray(128) { random.nextFloat() * 2 - 1 })
            assertThat(FaceMath.dot(a, b))
                .isWithin(1e-4f)
                .of(FaceMath.cosineSimilarity(a, b))
        }
    }

    @Test
    fun `dot handles mismatched lengths`() {
        assertThat(FaceMath.dot(floatArrayOf(1f), floatArrayOf(1f, 2f))).isEqualTo(0f)
    }

    // ---- l2Normalize -----------------------------------------------------------

    @Test
    fun `normalised vectors have unit magnitude`() {
        val random = Random(23)
        repeat(30) {
            val v = FloatArray(128) { random.nextFloat() * 100 - 50 }
            assertThat(FaceMath.magnitude(FaceMath.l2Normalize(v))).isWithin(1e-4f).of(1f)
        }
    }

    @Test
    fun `normalising preserves direction`() {
        val v = floatArrayOf(3f, 4f)
        val normalised = FaceMath.l2Normalize(v)
        assertThat(normalised[0]).isWithin(1e-5f).of(0.6f)
        assertThat(normalised[1]).isWithin(1e-5f).of(0.8f)
    }

    @Test
    fun `normalising a zero vector returns it unchanged`() {
        val zero = FloatArray(8)
        val result = FaceMath.l2Normalize(zero)
        assertThat(result.toList()).containsExactlyElementsIn(zero.toList())
        assertThat(result.any { it.isNaN() }).isFalse()
    }

    @Test
    fun `normalising does not mutate the input`() {
        val v = floatArrayOf(3f, 4f)
        FaceMath.l2Normalize(v)
        assertThat(v.toList()).containsExactly(3f, 4f).inOrder()
    }

    @Test
    fun `non-finite input is passed through rather than corrupted`() {
        val v = floatArrayOf(1f, Float.NaN, 3f)
        val result = FaceMath.l2Normalize(v)
        assertThat(result.size).isEqualTo(3)
    }

    @Test
    fun `normalising is idempotent`() {
        val random = Random(29)
        val once = FaceMath.l2Normalize(FloatArray(128) { random.nextFloat() })
        val twice = FaceMath.l2Normalize(once)
        once.indices.forEach { assertThat(twice[it]).isWithin(1e-5f).of(once[it]) }
    }

    // ---- prewhiten -------------------------------------------------------------

    @Test
    fun `prewhitening produces zero mean`() {
        val random = Random(31)
        val pixels = FloatArray(160 * 160 * 3) { random.nextFloat() * 255 }
        val whitened = FaceMath.prewhiten(pixels)
        assertThat(abs(whitened.average())).isLessThan(1e-3)
    }

    @Test
    fun `prewhitening produces roughly unit variance`() {
        val random = Random(37)
        val pixels = FloatArray(160 * 160 * 3) { random.nextFloat() * 255 }
        val whitened = FaceMath.prewhiten(pixels)
        val mean = whitened.average()
        val variance = whitened.map { (it - mean) * (it - mean) }.average()
        assertThat(variance).isWithin(0.05).of(1.0)
    }

    @Test
    fun `prewhitening a flat image does not explode`() {
        val flat = FloatArray(100) { 42f }
        val whitened = FaceMath.prewhiten(flat)
        assertThat(whitened.all { it.isFinite() }).isTrue()
        assertThat(whitened.all { abs(it) < 1f }).isTrue()
    }

    @Test
    fun `prewhitening an all-black image stays finite`() {
        val whitened = FaceMath.prewhiten(FloatArray(300))
        assertThat(whitened.all { it.isFinite() }).isTrue()
    }

    @Test
    fun `prewhitening an empty array returns empty`() {
        assertThat(FaceMath.prewhiten(FloatArray(0))).isEmpty()
    }

    @Test
    fun `prewhitening is brightness invariant`() {
        val random = Random(41)
        val base = FloatArray(1_000) { random.nextFloat() * 100 + 50 }
        val brighter = FloatArray(1_000) { base[it] + 40f }
        val a = FaceMath.prewhiten(base)
        val b = FaceMath.prewhiten(brighter)
        a.indices.forEach { assertThat(b[it]).isWithin(1e-3f).of(a[it]) }
    }

    // ---- average ---------------------------------------------------------------

    @Test
    fun `averaging identical embeddings returns the same direction`() {
        val v = FaceMath.l2Normalize(FloatArray(128) { (it % 7).toFloat() })
        val averaged = FaceMath.average(listOf(v, v, v))
        assertThat(FaceMath.cosineSimilarity(v, averaged)).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `averaging returns a unit vector`() {
        val random = Random(43)
        val embeddings = List(5) { FaceMath.l2Normalize(FloatArray(128) { random.nextFloat() }) }
        assertThat(FaceMath.magnitude(FaceMath.average(embeddings))).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `averaging noisy samples lands closer to truth than any single sample`() {
        val random = Random(47)
        val truth = FaceMath.l2Normalize(FloatArray(128) { random.nextFloat() * 2 - 1 })
        val samples = List(5) {
            FaceMath.l2Normalize(FloatArray(128) { truth[it] + (random.nextFloat() * 2 - 1) * 0.35f })
        }

        val averaged = FaceMath.average(samples)
        val averagedScore = FaceMath.cosineSimilarity(truth, averaged)
        val bestSingle = samples.maxOf { FaceMath.cosineSimilarity(truth, it) }

        // This is the whole justification for multi-sample enrolment.
        assertThat(averagedScore).isAtLeast(bestSingle)
    }

    @Test
    fun `averaging an empty list returns empty`() {
        assertThat(FaceMath.average(emptyList())).isEmpty()
    }

    @Test
    fun `averaging mismatched dimensions returns empty rather than garbage`() {
        val result = FaceMath.average(listOf(FloatArray(128), FloatArray(64) { 1f }))
        assertThat(result).isEmpty()
    }

    @Test
    fun `averaging skips empty entries`() {
        val v = FaceMath.l2Normalize(FloatArray(128) { 1f })
        val averaged = FaceMath.average(listOf(FloatArray(0), v, FloatArray(0)))
        assertThat(FaceMath.cosineSimilarity(v, averaged)).isWithin(1e-4f).of(1f)
    }

    // ---- magnitude -------------------------------------------------------------

    @Test
    fun `magnitude computes euclidean length`() {
        assertThat(FaceMath.magnitude(floatArrayOf(3f, 4f))).isWithin(1e-5f).of(5f)
    }

    @Test
    fun `magnitude of an empty vector is zero`() {
        assertThat(FaceMath.magnitude(FloatArray(0))).isEqualTo(0f)
    }
}
