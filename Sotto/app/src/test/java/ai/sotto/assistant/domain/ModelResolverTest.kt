package ai.sotto.assistant.domain

import ai.sotto.assistant.data.local.SottoSettings
import ai.sotto.assistant.data.remote.GeminiRestClient.ModelInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Model selection.
 *
 * This class exists because a hardcoded `gemini-2.0-flash` was retired by Google and
 * every enrichment run started failing with an error the user could do nothing about.
 * The rules below are what stop that recurring.
 */
class ModelResolverTest {

    private fun model(
        id: String,
        vararg methods: String = arrayOf("generateContent"),
    ) = ModelInfo(id = id, displayName = id, description = "", methods = methods.toList())

    private val typicalCatalogue = listOf(
        model("gemini-3.6-flash"),
        model("gemini-3.5-flash"),
        model("gemini-3.5-flash-lite"),
        model("gemini-2.5-flash"),
        model("gemini-2.5-pro"),
        model("gemini-3.1-flash-live-preview", "bidiGenerateContent"),
        model("embedding-001", "embedContent"),
    )

    // ---- The configured model is fine ---------------------------------------------

    @Test
    fun `an available model is kept`() {
        val choice = ModelResolver.resolve(
            "gemini-2.5-flash", typicalCatalogue, ModelResolver.Purpose.ENRICHMENT,
        )
        assertThat(choice.model).isEqualTo("gemini-2.5-flash")
        assertThat(choice.substituted).isFalse()
    }

    @Test
    fun `a models-prefixed name still matches`() {
        val choice = ModelResolver.resolve(
            "models/gemini-2.5-flash", typicalCatalogue, ModelResolver.Purpose.ENRICHMENT,
        )
        assertThat(choice.substituted).isFalse()
    }

    // ---- The configured model is gone ------------------------------------------------

    @Test
    fun `a retired model is replaced`() {
        // The exact failure that shipped.
        val choice = ModelResolver.resolve(
            "gemini-2.0-flash", typicalCatalogue, ModelResolver.Purpose.ENRICHMENT,
        )
        assertThat(choice.substituted).isTrue()
        assertThat(choice.model).isEqualTo("gemini-3.6-flash")
        assertThat(choice.reason).contains("no longer available")
    }

    @Test
    fun `the replacement explains itself in the user's terms`() {
        val choice = ModelResolver.resolve(
            "gemini-2.0-flash", typicalCatalogue, ModelResolver.Purpose.ENRICHMENT,
        )
        assertThat(choice.reason).contains("gemini-2.0-flash")
        assertThat(choice.reason).contains("gemini-3.6-flash")
    }

    @Test
    fun `preference order is respected`() {
        val limited = listOf(model("gemini-2.5-flash"), model("gemini-3.5-flash"))
        val choice = ModelResolver.resolve(
            "gemini-2.0-flash", limited, ModelResolver.Purpose.ENRICHMENT,
        )
        // 3.5 ranks above 2.5 in the fallback list.
        assertThat(choice.model).isEqualTo("gemini-3.5-flash")
    }

    @Test
    fun `an unknown catalogue still yields a sensible pick`() {
        val future = listOf(
            model("gemini-9.9-pro"),
            model("gemini-9.9-flash"),
            model("gemini-8.0-flash"),
        )
        val choice = ModelResolver.resolve(
            "gemini-2.0-flash", future, ModelResolver.Purpose.ENRICHMENT,
        )
        assertThat(choice.substituted).isTrue()
        // Newest version, and flash over pro.
        assertThat(choice.model).isEqualTo("gemini-9.9-flash")
    }

    @Test
    fun `stable models are preferred over preview ones`() {
        val mixed = listOf(
            model("gemini-9.9-flash-preview"),
            model("gemini-8.0-flash"),
        )
        val choice = ModelResolver.resolve(
            "gemini-2.0-flash", mixed, ModelResolver.Purpose.ENRICHMENT,
        )
        assertThat(choice.model).isEqualTo("gemini-8.0-flash")
    }

    // ---- Purpose filtering ------------------------------------------------------------

    @Test
    fun `enrichment never picks a model that cannot generate content`() {
        val choice = ModelResolver.resolve(
            "gemini-2.0-flash", typicalCatalogue, ModelResolver.Purpose.ENRICHMENT,
        )
        assertThat(choice.model).isNotEqualTo("embedding-001")
        assertThat(choice.model).isNotEqualTo("gemini-3.1-flash-live-preview")
    }

    @Test
    fun `live only picks a model that supports bidiGenerateContent`() {
        val choice = ModelResolver.resolve(
            "models/gemini-2.0-flash-live-001", typicalCatalogue, ModelResolver.Purpose.LIVE,
        )
        assertThat(choice.substituted).isTrue()
        assertThat(choice.model).isEqualTo("models/gemini-3.1-flash-live-preview")
    }

    @Test
    fun `live results are models-prefixed as the Live API requires`() {
        val choice = ModelResolver.resolve(
            "models/dead-model", typicalCatalogue, ModelResolver.Purpose.LIVE,
        )
        assertThat(choice.model).startsWith("models/")
    }

    @Test
    fun `enrichment results are not models-prefixed`() {
        val choice = ModelResolver.resolve(
            "dead-model", typicalCatalogue, ModelResolver.Purpose.ENRICHMENT,
        )
        assertThat(choice.model).doesNotContain("models/")
    }

    // ---- Degenerate cases ----------------------------------------------------------------

    @Test
    fun `an empty catalogue leaves the setting alone`() {
        // We couldn't ask, so we don't second-guess.
        val choice = ModelResolver.resolve(
            "gemini-2.0-flash", emptyList(), ModelResolver.Purpose.ENRICHMENT,
        )
        assertThat(choice.model).isEqualTo("gemini-2.0-flash")
        assertThat(choice.substituted).isFalse()
    }

    @Test
    fun `a catalogue with nothing suitable leaves the setting alone`() {
        val choice = ModelResolver.resolve(
            "gemini-2.0-flash", listOf(model("embedding-001", "embedContent")),
            ModelResolver.Purpose.ENRICHMENT,
        )
        assertThat(choice.substituted).isFalse()
    }

    @Test
    fun `no live models available leaves the live setting alone`() {
        val textOnly = listOf(model("gemini-3.6-flash"))
        val choice = ModelResolver.resolve(
            "models/x", textOnly, ModelResolver.Purpose.LIVE,
        )
        assertThat(choice.substituted).isFalse()
    }

    @Test
    fun `whitespace around the configured name is tolerated`() {
        val choice = ModelResolver.resolve(
            "  gemini-2.5-flash  ", typicalCatalogue, ModelResolver.Purpose.ENRICHMENT,
        )
        assertThat(choice.substituted).isFalse()
    }

    // ---- Version parsing -------------------------------------------------------------------

    @Test
    fun `version numbers are extracted for ranking`() {
        assertThat(ModelResolver.versionOf("gemini-3.6-flash")).isEqualTo(3.6)
        assertThat(ModelResolver.versionOf("gemini-2.5-pro")).isEqualTo(2.5)
        assertThat(ModelResolver.versionOf("gemini-3-flash-preview")).isEqualTo(3.0)
        assertThat(ModelResolver.versionOf("embedding-001")).isEqualTo(0.0)
    }

    @Test
    fun `the shipped defaults are not the retired model`() {
        // A guard against pasting a dead name back in.
        assertThat(SottoSettings.DEFAULT_ENRICHMENT_MODEL).isNotEqualTo("gemini-2.0-flash")
        assertThat(SottoSettings.DEFAULT_LIVE_MODEL).doesNotContain("gemini-2.0")
        assertThat(SottoSettings.ENRICHMENT_FALLBACKS).doesNotContain("gemini-2.0-flash")
    }

    @Test
    fun `every fallback is distinct and non-empty`() {
        listOf(SottoSettings.ENRICHMENT_FALLBACKS, SottoSettings.LIVE_FALLBACKS).forEach { list ->
            assertThat(list).isNotEmpty()
            assertThat(list.toSet()).hasSize(list.size)
            list.forEach { assertThat(it).isNotEmpty() }
        }
    }
}
