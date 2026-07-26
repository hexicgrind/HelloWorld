package ai.sotto.assistant.domain

import ai.sotto.assistant.data.local.SottoSettings
import ai.sotto.assistant.data.remote.GeminiRestClient.ModelInfo

/**
 * Picks a model that actually exists.
 *
 * Google retires Gemini models on its own schedule. This app pinned `gemini-2.0-flash`,
 * Google shut it down, and every enrichment run then failed with an error the user could
 * do nothing about. Hardcoding a model name is therefore a bug with a delayed fuse, and
 * the fix is to treat the configured name as a *preference* rather than a fact.
 *
 * Pure and dependency-free so the selection rules are exhaustively testable.
 */
object ModelResolver {

    enum class Purpose { ENRICHMENT, LIVE }

    data class Choice(
        val model: String,
        /** True when the user's configured model was gone and we substituted. */
        val substituted: Boolean,
        val reason: String,
    )

    /**
     * @param configured what the user has set
     * @param available what the API says the key can use; empty means "we couldn't ask",
     *        in which case the configured value is kept rather than second-guessed.
     */
    fun resolve(
        configured: String,
        available: List<ModelInfo>,
        purpose: Purpose,
    ): Choice {
        val wanted = configured.removePrefix("models/").trim()

        if (available.isEmpty()) {
            return Choice(configured, false, "Model list unavailable; keeping your setting.")
        }

        val usable = available.filter {
            when (purpose) {
                Purpose.ENRICHMENT -> it.supportsGenerateContent
                Purpose.LIVE -> it.supportsLive
            }
        }

        if (usable.isEmpty()) {
            return Choice(configured, false, "No suitable models offered by this key.")
        }

        usable.firstOrNull { it.id == wanted }?.let {
            return Choice(configured, false, "Your chosen model is available.")
        }

        val preferred = when (purpose) {
            Purpose.ENRICHMENT -> SottoSettings.ENRICHMENT_FALLBACKS
            Purpose.LIVE -> SottoSettings.LIVE_FALLBACKS
        }

        // A known-good name we explicitly prefer.
        preferred.forEach { candidate ->
            usable.firstOrNull { it.id == candidate }?.let {
                return Choice(
                    model = qualify(it.id, purpose),
                    substituted = true,
                    reason = "\"$wanted\" is no longer available. Switched to ${it.id}.",
                )
            }
        }

        // Nothing we know by name; take the best of what's on offer.
        val best = pickBest(usable)
        return Choice(
            model = qualify(best.id, purpose),
            substituted = true,
            reason = "\"$wanted\" is no longer available. Switched to ${best.id}.",
        )
    }

    /**
     * Ranking when we recognise nothing: prefer stable over preview, then newer version
     * numbers, then "flash" (fast and cheap, which is what this app wants) over "pro".
     */
    internal fun pickBest(usable: List<ModelInfo>): ModelInfo = usable
        .sortedWith(
            compareBy<ModelInfo> { if (it.isPreview) 1 else 0 }
                .thenByDescending { versionOf(it.id) }
                .thenBy { if (it.id.contains("flash")) 0 else 1 }
                .thenBy { it.id.length }
        )
        .first()

    /** Extracts a comparable version from an id like `gemini-3.5-flash` → 3.5. */
    internal fun versionOf(id: String): Double =
        VERSION_PATTERN.find(id)?.value?.toDoubleOrNull() ?: 0.0

    /** The Live API wants a `models/`-qualified name; generateContent accepts either. */
    private fun qualify(id: String, purpose: Purpose): String = when (purpose) {
        Purpose.LIVE -> if (id.startsWith("models/")) id else "models/$id"
        Purpose.ENRICHMENT -> id
    }

    private val VERSION_PATTERN = Regex("""(?<=gemini-)\d+(\.\d+)?""")
}
