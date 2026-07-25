package ai.sotto.assistant.vision

import ai.sotto.assistant.data.model.Attendee

/**
 * Matches a probe embedding against the loaded attendee database.
 *
 * Design Doc 1 § Face Detection Pipeline: "This embedding is compared via cosine
 * similarity against precomputed embeddings of all attendees in the local database.
 * Matches above a confidence threshold are cached to avoid repeated API calls."
 *
 * The class is pure and synchronous; the database lives in memory, so a full scan of
 * a few hundred 128-float vectors sits comfortably inside the doc's 5-10 ms budget.
 */
class FaceMatcher(
    threshold: Float = DEFAULT_THRESHOLD,
    /**
     * Minimum lead the winner must have over the runner-up. Guards against confidently
     * announcing the wrong name when two people in the roster genuinely look alike.
     */
    private val minMargin: Float = DEFAULT_MIN_MARGIN,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Design Doc 1: "the confidence threshold (default zero point seven)". */
    @Volatile
    var threshold: Float = threshold.coerceIn(0f, 1f)
        set(value) {
            field = value.coerceIn(0f, 1f)
            clearCache()
        }

    private val cache = LinkedHashMap<String, CachedMatch>(16, 0.75f, true)

    private data class CachedMatch(
        val embedding: FloatArray,
        val result: MatchResult,
        val cachedAtMs: Long,
    )

    /**
     * Compares [probe] against every enrolled attendee.
     *
     * Attendees without an enrolled face are skipped — the doc's precomputed-embedding
     * model means an un-enrolled person simply cannot be recognised, and Design Doc 1
     * § Error Handling says that case must stay silent rather than guess.
     */
    fun match(probe: FloatArray, attendees: List<Attendee>): MatchResult {
        if (probe.isEmpty()) return MatchResult.NONE

        var bestId: String? = null
        var best = Float.NEGATIVE_INFINITY
        var runnerUp = Float.NEGATIVE_INFINITY

        for (attendee in attendees) {
            val embedding = attendee.embedding ?: continue
            if (embedding.size != probe.size) continue
            val score = FaceMath.cosineSimilarity(probe, embedding.toFloatArray())
            if (score > best) {
                runnerUp = best
                best = score
                bestId = attendee.id
            } else if (score > runnerUp) {
                runnerUp = score
            }
        }

        if (bestId == null) return MatchResult.NONE

        val bestScore = best.coerceAtLeast(0f)
        val runnerUpScore = if (runnerUp.isFinite()) runnerUp.coerceAtLeast(0f) else 0f
        val isMatch = bestScore >= threshold && (bestScore - runnerUpScore) >= minMargin
        return MatchResult(
            attendeeId = bestId,
            score = bestScore,
            runnerUpScore = runnerUpScore,
            isMatch = isMatch,
        )
    }

    /**
     * Cached variant used on the live camera path. A probe that is essentially the
     * same face we resolved moments ago reuses the previous answer instead of
     * re-scanning the database and re-triggering downstream API work.
     */
    fun matchCached(
        probe: FloatArray,
        attendees: List<Attendee>,
        cacheKey: String = GLOBAL_CACHE_KEY,
    ): MatchResult {
        if (probe.isEmpty()) return MatchResult.NONE
        val now = clock()

        cache[cacheKey]?.let { cached ->
            val fresh = now - cached.cachedAtMs <= CACHE_TTL_MS
            val sameFace = FaceMath.cosineSimilarity(probe, cached.embedding) >= CACHE_HIT_SIMILARITY
            if (fresh && sameFace) return cached.result
        }

        val result = match(probe, attendees)
        if (result.isMatch) {
            cache[cacheKey] = CachedMatch(probe.copyOf(), result, now)
            trimCache()
        } else {
            cache.remove(cacheKey)
        }
        return result
    }

    fun clearCache() = cache.clear()

    /** Drops any cached decision that points at an attendee who no longer exists. */
    fun invalidateFor(attendeeId: String) {
        cache.entries.removeAll { it.value.result.attendeeId == attendeeId }
    }

    val cacheSize: Int get() = cache.size

    private fun trimCache() {
        while (cache.size > MAX_CACHE_ENTRIES) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.70f
        const val DEFAULT_MIN_MARGIN = 0.03f

        /** Two probes this similar are treated as the same face for caching purposes. */
        const val CACHE_HIT_SIMILARITY = 0.92f
        const val CACHE_TTL_MS = 30_000L
        const val MAX_CACHE_ENTRIES = 16
        const val GLOBAL_CACHE_KEY = "current"
    }
}
