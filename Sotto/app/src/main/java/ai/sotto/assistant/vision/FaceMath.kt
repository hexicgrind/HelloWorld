package ai.sotto.assistant.vision

import kotlin.math.sqrt

/**
 * Pure vector maths for the embedding pipeline. Deliberately free of any Android
 * dependency so it can be unit-tested on the JVM — Design Doc 1 § Testing Strategy
 * calls out "Unit tests for face embedding cosine similarity matching" first.
 */
object FaceMath {

    /**
     * Cosine similarity in [-1, 1]. Returns 0 for mismatched lengths, empty inputs or
     * a zero-magnitude vector rather than producing NaN — the matcher treats 0 as
     * "no information", which is the safe answer.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        val result = dot / (sqrt(normA) * sqrt(normB))
        if (result.isNaN()) return 0f
        return result.coerceIn(-1.0, 1.0).toFloat()
    }

    /**
     * Cosine similarity for vectors that are already unit length — just the dot
     * product. Used on the hot path, where every embedding has been normalised at
     * extraction time.
     */
    fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var sum = 0.0
        for (i in a.indices) sum += a[i].toDouble() * b[i].toDouble()
        if (sum.isNaN()) return 0f
        return sum.coerceIn(-1.0, 1.0).toFloat()
    }

    /** L2 magnitude. */
    fun magnitude(v: FloatArray): Float {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        return sqrt(sum).toFloat()
    }

    /**
     * Returns a unit-length copy. A zero (or non-finite) vector is returned unchanged
     * so callers never see NaNs propagate into the database.
     */
    fun l2Normalize(v: FloatArray): FloatArray {
        if (v.isEmpty()) return v
        var sum = 0.0
        for (x in v) {
            if (!x.isFinite()) return v.copyOf()
            sum += x.toDouble() * x.toDouble()
        }
        val norm = sqrt(sum)
        if (norm <= 1e-12) return v.copyOf()
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    /**
     * Standard FaceNet pre-whitening: zero mean, unit variance across the crop. The
     * `max(std, 1/sqrt(n))` floor is from the reference implementation and stops a
     * flat (e.g. fully dark) crop from being amplified into noise.
     */
    fun prewhiten(pixels: FloatArray): FloatArray {
        if (pixels.isEmpty()) return pixels
        var mean = 0.0
        for (p in pixels) mean += p
        mean /= pixels.size
        var variance = 0.0
        for (p in pixels) {
            val d = p - mean
            variance += d * d
        }
        variance /= pixels.size
        val std = sqrt(variance)
        val stdAdj = maxOf(std, 1.0 / sqrt(pixels.size.toDouble()))
        return FloatArray(pixels.size) { ((pixels[it] - mean) / stdAdj).toFloat() }
    }

    /**
     * Averages several embeddings of the same person into one, then re-normalises.
     * Enrolling from a few frames instead of one is the cheapest accuracy win there is.
     */
    fun average(embeddings: List<FloatArray>): FloatArray {
        val usable = embeddings.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return FloatArray(0)
        val dim = usable.first().size
        if (usable.any { it.size != dim }) return FloatArray(0)
        val sum = FloatArray(dim)
        for (e in usable) for (i in 0 until dim) sum[i] += e[i]
        for (i in 0 until dim) sum[i] /= usable.size
        return l2Normalize(sum)
    }
}
