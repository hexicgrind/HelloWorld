package ai.sotto.assistant.domain

import ai.sotto.assistant.data.local.SottoSettings
import ai.sotto.assistant.data.model.Attendee
import ai.sotto.assistant.data.model.Speaker
import ai.sotto.assistant.data.model.SuggestionKind
import ai.sotto.assistant.data.model.TranscriptLine

/**
 * Builds every prompt Sotto sends, and parses what comes back.
 *
 * This is where Design Doc 1 § Gemini Live API Integration becomes concrete: "The system
 * injects context into Gemini's system prompt: the name, title, company, and bio of the
 * currently detected person... When a pause is detected, Gemini either whispers a
 * follow-up question the user could ask, surfaces a connection between the user and the
 * target, or suggests a topic shift based on shared interests."
 *
 * Kept free of Android dependencies so the prompt contract is unit-testable.
 */
object PromptBuilder {

    /** Sentinel the model returns when the right move is to stay quiet. */
    const val PASS_TOKEN = "PASS"

    /**
     * The standing system instruction for the live session. Deliberately heavy on
     * restraint: a networking assistant that talks too much is worse than no assistant,
     * because the user is standing in front of a real human being.
     */
    fun systemInstruction(settings: SottoSettings, hasDatabase: Boolean): String = buildString {
        appendLine("You are Sotto, a networking assistant whispering into one earpiece.")
        appendLine("The user is at a conference, mid-conversation, with a real person in front")
        appendLine("of them. Only the user can hear you.")
        appendLine()

        appendLine("## What you are for")
        appendLine("At a natural pause, offer exactly one of:")
        appendLine("1. A follow-up question the user could ask next.")
        appendLine("2. A connection between the user and the person they're talking to.")
        appendLine("3. A topic shift grounded in a shared interest.")
        appendLine()

        appendLine("## How to speak")
        appendLine("- One sentence. Under 18 words. It gets read aloud into an earpiece.")
        appendLine("- Give the user words they can actually say, not instructions about talking.")
        appendLine("  Good: \"Ask how the Berlin launch went.\"  Bad: \"Consider asking a question.\"")
        appendLine("- Never greet, never narrate, never mention that you are an AI.")
        appendLine("- Never say anything the other person would be uncomfortable overhearing.")
        appendLine()

        appendLine("## When to stay silent")
        appendLine("Silence is the default and it is not a failure. Reply with exactly $PASS_TOKEN when:")
        appendLine("- The conversation is flowing and needs nothing.")
        appendLine("- You'd only be restating what was just said.")
        appendLine("- You have no specific, useful thing to add.")
        appendLine("- The moment is emotionally delicate.")
        appendLine("- You already made a similar suggestion recently.")
        appendLine("Most turns should be $PASS_TOKEN. Aim to be right, not present.")
        appendLine()

        appendLine("## Output format")
        appendLine("Reply with either exactly:")
        appendLine("  $PASS_TOKEN")
        appendLine("or a single line:")
        appendLine("  <KIND>: <the sentence to whisper>")
        appendLine("where <KIND> is FOLLOW_UP, CONNECTION or TOPIC_SHIFT.")
        appendLine("No quotes, no markdown, no explanation.")

        if (!hasDatabase) {
            appendLine()
            // Design Doc 1 § Error Handling: "If no attendee database is loaded, face
            // detection runs but no context is passed to Gemini. Gemini can still
            // provide generic networking advice."
            appendLine("## No attendee database is loaded")
            appendLine("You do not know who this person is. Work only from what you hear.")
            appendLine("Generic networking help is fine, but be even more willing to $PASS_TOKEN.")
            appendLine("Never guess a name, employer or role.")
        }

        val profile = userProfileBlock(settings)
        if (profile.isNotBlank()) {
            appendLine()
            appendLine("## Who the user is")
            append(profile)
        }

        if (settings.customInstructions.isNotBlank()) {
            appendLine()
            appendLine("## The user's own instructions (these take priority)")
            appendLine(settings.customInstructions.take(2_000))
        }
    }.trim()

    private fun userProfileBlock(settings: SottoSettings): String = buildString {
        if (settings.userName.isNotBlank()) appendLine("- Name: ${settings.userName}")
        if (settings.userRole.isNotBlank()) appendLine("- Role: ${settings.userRole}")
        if (settings.userGoal.isNotBlank()) {
            appendLine("- What they want out of this event: ${settings.userGoal}")
        }
    }

    /**
     * The context injection from § Runtime Flow — "The current target's information is
     * injected into Gemini Live's context."
     */
    fun targetContext(attendee: Attendee): String = buildString {
        appendLine("[CONTEXT UPDATE] The person now in front of the user:")
        appendLine("- Name: ${attendee.name}")
        if (attendee.title.isNotBlank()) appendLine("- Title: ${attendee.title}")
        if (attendee.company.isNotBlank()) appendLine("- Company: ${attendee.company}")
        if (attendee.bio.isNotBlank()) appendLine("- Bio: ${attendee.bio}")
        if (attendee.interests.isNotEmpty()) {
            appendLine("- Interests: ${attendee.interests.joinToString(", ")}")
        }
        attendee.location?.takeIf { it.isNotBlank() }?.let { appendLine("- Location: $it") }
        appendLine()
        appendLine("Use this quietly. Do not read it out. Do not announce that you recognised them.")
    }.trim()

    /** Sent when the recognised person leaves the frame. */
    fun clearedContext(): String =
        "[CONTEXT UPDATE] The person you had context on is no longer in view. " +
            "Do not refer to them by name until told otherwise."

    /**
     * The decision prompt, sent on the pause cadence.
     */
    fun decisionPrompt(
        target: Attendee?,
        recentTranscript: List<TranscriptLine>,
        recentSuggestions: List<String>,
        quietForMs: Long,
    ): String = buildString {
        appendLine("A pause of about ${quietForMs / 1000} seconds just happened.")
        appendLine()

        if (target != null) {
            appendLine("You are talking to ${target.name}${target.headline.let { if (it.isBlank()) "" else ", $it" }}.")
        } else {
            appendLine("You have not identified this person.")
        }
        appendLine()

        appendLine("Recent conversation:")
        if (recentTranscript.isEmpty()) {
            appendLine("(nothing transcribed yet)")
        } else {
            recentTranscript.takeLast(MAX_TRANSCRIPT_LINES).forEach { line ->
                val who = when (line.speaker) {
                    Speaker.USER -> "User"
                    Speaker.THEM -> target?.name?.substringBefore(' ') ?: "Them"
                    Speaker.UNKNOWN -> "Someone"
                }
                appendLine("$who: ${line.text}")
            }
        }

        if (recentSuggestions.isNotEmpty()) {
            appendLine()
            appendLine("You already whispered these — do not repeat or paraphrase them:")
            recentSuggestions.takeLast(MAX_RECENT_SUGGESTIONS).forEach { appendLine("- $it") }
        }

        appendLine()
        appendLine("Whisper something now, or reply $PASS_TOKEN.")
    }.trim()

    /** The parsed outcome of one decision turn. */
    data class Decision(val kind: SuggestionKind?, val text: String?) {
        val shouldSpeak: Boolean get() = !text.isNullOrBlank()

        companion object {
            val PASS = Decision(null, null)
        }
    }

    /**
     * Parses a model turn. Written defensively: a model that ignores the format and
     * just returns a good sentence should still produce a usable whisper, and anything
     * that smells like a refusal or a meta-comment becomes a PASS.
     */
    fun parseDecision(raw: String): Decision {
        var text = raw.trim()
        if (text.isEmpty()) return Decision.PASS

        text = text.trim('`', ' ', '\n', '\r')
        if (text.startsWith("json", ignoreCase = true)) text = text.removePrefix("json").trim()

        // A bare PASS, or a leading PASS on its own line.
        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.equals(PASS_TOKEN, ignoreCase = true)) return Decision.PASS
        if (text.equals(PASS_TOKEN, ignoreCase = true)) return Decision.PASS

        val kindMatch = KIND_REGEX.find(text)
        val kind = kindMatch?.groupValues?.getOrNull(1)?.uppercase()?.let { label ->
            when (label) {
                "FOLLOW_UP", "FOLLOWUP", "FOLLOW-UP" -> SuggestionKind.FOLLOW_UP
                "CONNECTION" -> SuggestionKind.CONNECTION
                "TOPIC_SHIFT", "TOPICSHIFT", "TOPIC-SHIFT" -> SuggestionKind.TOPIC_SHIFT
                else -> null
            }
        }

        var body = if (kindMatch != null) text.removeRange(kindMatch.range).trim() else text
        body = body.trim().trim('"', '“', '”', ' ').trim()
        body = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        if (body.isEmpty()) return Decision.PASS
        if (body.equals(PASS_TOKEN, ignoreCase = true)) return Decision.PASS
        if (REFUSAL_REGEX.containsMatchIn(body)) return Decision.PASS
        if (body.length > MAX_SUGGESTION_CHARS) body = body.take(MAX_SUGGESTION_CHARS).trimEnd() + "…"

        return Decision(kind ?: SuggestionKind.FOLLOW_UP, body)
    }

    /**
     * Design Doc 1 § Error Handling: "If Gemini Live API is unreachable, the app falls
     * back to simple TTS of the matched attendee's name and title."
     */
    fun identityFallback(attendee: Attendee): String {
        val headline = attendee.headline
        return if (headline.isBlank()) attendee.name else "${attendee.name}, $headline"
    }

    /** Prompt for the REST fallback when the live socket is down but HTTP works. */
    fun offlineFallbackPrompt(
        target: Attendee?,
        recentTranscript: List<TranscriptLine>,
    ): String = buildString {
        appendLine("Give one short networking whisper (under 18 words) or reply $PASS_TOKEN.")
        if (target != null) {
            appendLine("Person: ${target.name}, ${target.headline}. ${target.bio}")
            if (target.interests.isNotEmpty()) {
                appendLine("Interests: ${target.interests.joinToString(", ")}")
            }
        }
        if (recentTranscript.isNotEmpty()) {
            appendLine("Recent conversation:")
            recentTranscript.takeLast(8).forEach { appendLine("- ${it.text}") }
        }
    }.trim()

    /**
     * Phrase hints for Speech-to-Text. Feeding the roster's names and companies in
     * measurably improves recognition of exactly the words that matter most here.
     */
    fun speechHints(attendees: List<Attendee>): List<String> =
        attendees.asSequence()
            .flatMap { sequenceOf(it.name, it.company) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(200)
            .toList()

    const val MAX_TRANSCRIPT_LINES = 14
    const val MAX_RECENT_SUGGESTIONS = 5
    const val MAX_SUGGESTION_CHARS = 180

    private val KIND_REGEX =
        Regex("^\\s*\\[?(FOLLOW[_ -]?UP|CONNECTION|TOPIC[_ -]?SHIFT)]?\\s*[:\\-–]\\s*",
            RegexOption.IGNORE_CASE)

    private val REFUSAL_REGEX = Regex(
        "^(i\\s|as an ai|i'm sorry|sorry,|i cannot|i can't|unable to)",
        RegexOption.IGNORE_CASE,
    )
}
