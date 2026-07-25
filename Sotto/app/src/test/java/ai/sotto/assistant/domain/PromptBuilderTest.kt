package ai.sotto.assistant.domain

import ai.sotto.assistant.data.local.SottoSettings
import ai.sotto.assistant.data.model.Attendee
import ai.sotto.assistant.data.model.Speaker
import ai.sotto.assistant.data.model.SuggestionKind
import ai.sotto.assistant.data.model.TranscriptLine
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Design Doc 1 § Testing Strategy: "Integration tests for Gemini Live API context
 * injection and response parsing." This covers both halves of that contract at the
 * prompt layer, where they can be tested deterministically.
 */
class PromptBuilderTest {

    private val ada = Attendee(
        id = "1",
        name = "Ada Lovelace",
        title = "Chief Scientist",
        company = "Analytical Engines",
        bio = "Wrote the first algorithm intended for a machine.",
        interests = listOf("compilers", "mathematics", "poetry"),
        location = "Table 4",
    )

    private fun line(text: String, speaker: Speaker = Speaker.THEM, id: Long = 1) =
        TranscriptLine(id, text, speaker, 0L, isFinal = true)

    // ---- System instruction ------------------------------------------------------

    @Test
    fun `system instruction names the three documented moves`() {
        val prompt = PromptBuilder.systemInstruction(SottoSettings(), hasDatabase = true)
        assertThat(prompt).contains("follow-up question")
        assertThat(prompt).contains("connection between the user")
        assertThat(prompt).contains("topic shift")
    }

    @Test
    fun `system instruction teaches the PASS token`() {
        val prompt = PromptBuilder.systemInstruction(SottoSettings(), hasDatabase = true)
        assertThat(prompt).contains(PromptBuilder.PASS_TOKEN)
        assertThat(prompt).contains("Silence is the default")
    }

    @Test
    fun `without a database the prompt says so and forbids guessing names`() {
        val prompt = PromptBuilder.systemInstruction(SottoSettings(), hasDatabase = false)
        assertThat(prompt).contains("No attendee database is loaded")
        assertThat(prompt).contains("Never guess a name")
    }

    @Test
    fun `with a database the no-database block is absent`() {
        val prompt = PromptBuilder.systemInstruction(SottoSettings(), hasDatabase = true)
        assertThat(prompt).doesNotContain("No attendee database is loaded")
    }

    @Test
    fun `the user profile is injected when provided`() {
        val settings = SottoSettings(
            userName = "Grace Hopper",
            userRole = "VP Engineering",
            userGoal = "Find compiler collaborators",
        )
        val prompt = PromptBuilder.systemInstruction(settings, hasDatabase = true)
        assertThat(prompt).contains("Grace Hopper")
        assertThat(prompt).contains("VP Engineering")
        assertThat(prompt).contains("Find compiler collaborators")
    }

    @Test
    fun `an empty profile adds no empty section`() {
        val prompt = PromptBuilder.systemInstruction(SottoSettings(), hasDatabase = true)
        assertThat(prompt).doesNotContain("## Who the user is")
    }

    @Test
    fun `custom instructions are included and marked as taking priority`() {
        val settings = SottoSettings(customInstructions = "Never mention the weather.")
        val prompt = PromptBuilder.systemInstruction(settings, hasDatabase = true)
        assertThat(prompt).contains("Never mention the weather.")
        assertThat(prompt).contains("take priority")
    }

    @Test
    fun `custom instructions are length capped`() {
        val settings = SottoSettings(customInstructions = "x".repeat(10_000))
        val prompt = PromptBuilder.systemInstruction(settings, hasDatabase = true)
        assertThat(prompt.length).isLessThan(10_000)
    }

    // ---- Context injection --------------------------------------------------------

    @Test
    fun `target context carries name title company and bio as the doc requires`() {
        val context = PromptBuilder.targetContext(ada)
        assertThat(context).contains("Ada Lovelace")
        assertThat(context).contains("Chief Scientist")
        assertThat(context).contains("Analytical Engines")
        assertThat(context).contains("first algorithm")
    }

    @Test
    fun `target context includes interests and location`() {
        val context = PromptBuilder.targetContext(ada)
        assertThat(context).contains("compilers, mathematics, poetry")
        assertThat(context).contains("Table 4")
    }

    @Test
    fun `target context tells the model not to read it aloud`() {
        val context = PromptBuilder.targetContext(ada)
        assertThat(context).contains("Do not read it out")
    }

    @Test
    fun `target context omits blank fields cleanly`() {
        val sparse = Attendee(id = "2", name = "Someone")
        val context = PromptBuilder.targetContext(sparse)
        assertThat(context).contains("Someone")
        assertThat(context).doesNotContain("Title:")
        assertThat(context).doesNotContain("Company:")
        assertThat(context).doesNotContain("Interests:")
    }

    @Test
    fun `cleared context tells the model to stop using the name`() {
        assertThat(PromptBuilder.clearedContext()).contains("no longer in view")
    }

    // ---- Decision prompt -----------------------------------------------------------

    @Test
    fun `decision prompt includes the target and the transcript`() {
        val prompt = PromptBuilder.decisionPrompt(
            target = ada,
            recentTranscript = listOf(line("We just shipped in Berlin.")),
            recentSuggestions = emptyList(),
            quietForMs = 2_000L,
        )
        assertThat(prompt).contains("Ada Lovelace")
        assertThat(prompt).contains("We just shipped in Berlin.")
        assertThat(prompt).contains("2 seconds")
    }

    @Test
    fun `decision prompt handles an unidentified person`() {
        val prompt = PromptBuilder.decisionPrompt(null, emptyList(), emptyList(), 1_500L)
        assertThat(prompt).contains("have not identified this person")
    }

    @Test
    fun `decision prompt handles an empty transcript`() {
        val prompt = PromptBuilder.decisionPrompt(ada, emptyList(), emptyList(), 1_500L)
        assertThat(prompt).contains("nothing transcribed yet")
    }

    @Test
    fun `decision prompt forbids repeating earlier suggestions`() {
        val prompt = PromptBuilder.decisionPrompt(
            target = ada,
            recentTranscript = emptyList(),
            recentSuggestions = listOf("Ask about the Berlin launch."),
            quietForMs = 2_000L,
        )
        assertThat(prompt).contains("Ask about the Berlin launch.")
        assertThat(prompt).contains("do not repeat")
    }

    @Test
    fun `decision prompt caps how much transcript it sends`() {
        val transcript = (1..100).map { line("Line $it", id = it.toLong()) }
        val prompt = PromptBuilder.decisionPrompt(ada, transcript, emptyList(), 2_000L)

        assertThat(prompt).contains("Line 100")
        assertThat(prompt).doesNotContain("Line 1\n")
        assertThat(prompt.lines().count { it.contains("Line ") })
            .isAtMost(PromptBuilder.MAX_TRANSCRIPT_LINES)
    }

    @Test
    fun `decision prompt labels the speakers`() {
        val prompt = PromptBuilder.decisionPrompt(
            target = ada,
            recentTranscript = listOf(
                line("How's the project?", Speaker.USER, 1),
                line("Going well.", Speaker.THEM, 2),
            ),
            recentSuggestions = emptyList(),
            quietForMs = 1_500L,
        )
        assertThat(prompt).contains("User: How's the project?")
        assertThat(prompt).contains("Ada: Going well.")
    }

    // ---- Response parsing ------------------------------------------------------------

    @Test
    fun `a bare PASS is a pass`() {
        assertThat(PromptBuilder.parseDecision("PASS").shouldSpeak).isFalse()
    }

    @Test
    fun `PASS is case insensitive`() {
        assertThat(PromptBuilder.parseDecision("pass").shouldSpeak).isFalse()
        assertThat(PromptBuilder.parseDecision("Pass").shouldSpeak).isFalse()
    }

    @Test
    fun `PASS with surrounding whitespace is a pass`() {
        assertThat(PromptBuilder.parseDecision("  \n PASS \n ").shouldSpeak).isFalse()
    }

    @Test
    fun `an empty response is a pass`() {
        assertThat(PromptBuilder.parseDecision("").shouldSpeak).isFalse()
        assertThat(PromptBuilder.parseDecision("   ").shouldSpeak).isFalse()
    }

    @Test
    fun `a tagged follow-up is parsed`() {
        val decision = PromptBuilder.parseDecision("FOLLOW_UP: Ask how the Berlin launch went.")
        assertThat(decision.kind).isEqualTo(SuggestionKind.FOLLOW_UP)
        assertThat(decision.text).isEqualTo("Ask how the Berlin launch went.")
    }

    @Test
    fun `a tagged connection is parsed`() {
        val decision = PromptBuilder.parseDecision("CONNECTION: You both worked at Bletchley.")
        assertThat(decision.kind).isEqualTo(SuggestionKind.CONNECTION)
        assertThat(decision.text).isEqualTo("You both worked at Bletchley.")
    }

    @Test
    fun `a tagged topic shift is parsed`() {
        val decision = PromptBuilder.parseDecision("TOPIC_SHIFT: Bring up the poetry angle.")
        assertThat(decision.kind).isEqualTo(SuggestionKind.TOPIC_SHIFT)
        assertThat(decision.text).isEqualTo("Bring up the poetry angle.")
    }

    @Test
    fun `tag spelling variations are accepted`() {
        listOf("FOLLOW-UP:", "FOLLOWUP:", "follow_up:", "[FOLLOW_UP]:").forEach { tag ->
            val decision = PromptBuilder.parseDecision("$tag Ask about Berlin.")
            assertThat(decision.kind).isEqualTo(SuggestionKind.FOLLOW_UP)
            assertThat(decision.text).isEqualTo("Ask about Berlin.")
        }
    }

    @Test
    fun `an untagged sentence still becomes a usable whisper`() {
        val decision = PromptBuilder.parseDecision("Ask how the Berlin launch went.")
        assertThat(decision.shouldSpeak).isTrue()
        assertThat(decision.text).isEqualTo("Ask how the Berlin launch went.")
        assertThat(decision.kind).isEqualTo(SuggestionKind.FOLLOW_UP)
    }

    @Test
    fun `markdown fences are stripped`() {
        val decision = PromptBuilder.parseDecision("```\nAsk about Berlin.\n```")
        assertThat(decision.text).isEqualTo("Ask about Berlin.")
    }

    @Test
    fun `surrounding quotes are stripped`() {
        assertThat(PromptBuilder.parseDecision("\"Ask about Berlin.\"").text)
            .isEqualTo("Ask about Berlin.")
        assertThat(PromptBuilder.parseDecision("“Ask about Berlin.”").text)
            .isEqualTo("Ask about Berlin.")
    }

    @Test
    fun `only the first line is used`() {
        val decision = PromptBuilder.parseDecision(
            "Ask about Berlin.\nAlso, here's why I chose that."
        )
        assertThat(decision.text).isEqualTo("Ask about Berlin.")
    }

    @Test
    fun `a refusal is treated as a pass rather than spoken aloud`() {
        listOf(
            "I'm sorry, I can't help with that.",
            "As an AI, I don't have enough context.",
            "I cannot determine an appropriate suggestion.",
            "Sorry, nothing useful here.",
        ).forEach { refusal ->
            assertThat(PromptBuilder.parseDecision(refusal).shouldSpeak).isFalse()
        }
    }

    @Test
    fun `an overlong suggestion is truncated`() {
        val long = "Ask them ".repeat(80)
        val decision = PromptBuilder.parseDecision(long)
        assertThat(decision.text!!.length).isAtMost(PromptBuilder.MAX_SUGGESTION_CHARS + 1)
        assertThat(decision.text).endsWith("…")
    }

    @Test
    fun `a tag followed by PASS is a pass`() {
        assertThat(PromptBuilder.parseDecision("FOLLOW_UP: PASS").shouldSpeak).isFalse()
    }

    @Test
    fun `a leading PASS line wins over trailing chatter`() {
        assertThat(PromptBuilder.parseDecision("PASS\nNothing to add here.").shouldSpeak).isFalse()
    }

    // ---- Fallbacks --------------------------------------------------------------------

    @Test
    fun `identity fallback reads name and title as the doc specifies`() {
        assertThat(PromptBuilder.identityFallback(ada))
            .isEqualTo("Ada Lovelace, Chief Scientist at Analytical Engines")
    }

    @Test
    fun `identity fallback with no title is just the name`() {
        val plain = Attendee(id = "3", name = "Someone")
        assertThat(PromptBuilder.identityFallback(plain)).isEqualTo("Someone")
    }

    @Test
    fun `identity fallback with only a company mentions the company`() {
        val partial = Attendee(id = "4", name = "Someone", company = "Acme")
        assertThat(PromptBuilder.identityFallback(partial)).isEqualTo("Someone, Acme")
    }

    @Test
    fun `offline fallback prompt carries the person and the transcript`() {
        val prompt = PromptBuilder.offlineFallbackPrompt(ada, listOf(line("Berlin was great.")))
        assertThat(prompt).contains("Ada Lovelace")
        assertThat(prompt).contains("Berlin was great.")
        assertThat(prompt).contains(PromptBuilder.PASS_TOKEN)
    }

    // ---- Speech hints -------------------------------------------------------------------

    @Test
    fun `speech hints include names and companies`() {
        val hints = PromptBuilder.speechHints(listOf(ada))
        assertThat(hints).contains("Ada Lovelace")
        assertThat(hints).contains("Analytical Engines")
    }

    @Test
    fun `speech hints drop blanks and duplicates`() {
        val roster = listOf(
            ada,
            ada.copy(id = "dup"),
            Attendee(id = "5", name = "Nobody", company = ""),
        )
        val hints = PromptBuilder.speechHints(roster)
        assertThat(hints).doesNotContain("")
        assertThat(hints.toSet()).hasSize(hints.size)
    }

    @Test
    fun `speech hints are capped for the API limit`() {
        val roster = (1..500).map { Attendee(id = "$it", name = "Person $it", company = "Co $it") }
        assertThat(PromptBuilder.speechHints(roster).size).isAtMost(200)
    }
}
