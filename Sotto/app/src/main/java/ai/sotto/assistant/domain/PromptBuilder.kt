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
        appendLine("Your entire reply is either exactly:")
        appendLine("  $PASS_TOKEN")
        appendLine("or exactly one line in this shape:")
        appendLine("  <KIND>: <the sentence to whisper>")
        appendLine("where <KIND> is FOLLOW_UP, CONNECTION or TOPIC_SHIFT.")
        appendLine()
        appendLine("Worked examples of a valid reply:")
        appendLine("  FOLLOW_UP: Ask what changed after the Berlin launch.")
        appendLine("  CONNECTION: You both worked on payments at Monzo.")
        appendLine("  TOPIC_SHIFT: Bring up her paper on retrieval benchmarks.")
        appendLine("  $PASS_TOKEN")
        appendLine()
        // The parser rejects fragments outright, so a half-sentence costs the user the
        // whole turn. Say so, rather than relying on the model to infer it.
        appendLine("Hard rules:")
        appendLine("- Finish the sentence. A cut-off half-sentence is discarded entirely.")
        appendLine("- No preamble, no heading, no reasoning, no quotes, no markdown.")
        appendLine("- Never output more than that one line.")

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
        val text = stripWrapping(raw)
        if (text.isEmpty()) return Decision.PASS
        if (text.equals(PASS_TOKEN, ignoreCase = true)) return Decision.PASS

        val lines = text.lineSequence()
            .map { it.trim().trim('`').trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return Decision.PASS
        if (lines.first().equals(PASS_TOKEN, ignoreCase = true)) return Decision.PASS

        // Prefer a line that actually carries the KIND marker. Searching per line, not
        // once against the whole blob anchored at position zero, is what makes a
        // preamble survivable: a model that opens with "Here's a thought:" used to have
        // that preamble whispered into the user's ear as if it were the suggestion.
        var kind: SuggestionKind? = null
        var body: String? = null

        for ((index, line) in lines.withIndex()) {
            val match = KIND_REGEX.find(line) ?: continue
            kind = kindOf(match.groupValues.getOrNull(1))
            val remainder = line.removeRange(match.range).trim()
            body = remainder.ifBlank { lines.getOrNull(index + 1)?.trim().orEmpty() }
            break
        }

        // No marker at all: take the first line that reads like something a person could
        // say out loud, rather than blindly taking line one.
        if (body.isNullOrBlank()) body = lines.firstOrNull(::looksLikeWhisper)

        var cleaned = clean(body.orEmpty())
        if (cleaned.isEmpty()) return Decision.PASS
        if (cleaned.equals(PASS_TOKEN, ignoreCase = true)) return Decision.PASS
        if (REFUSAL_REGEX.containsMatchIn(cleaned)) return Decision.PASS

        // A fragment is worse than silence. Whispering "and then the" into someone's ear
        // mid-conversation is actively harmful, so anything that isn't a whole thought
        // becomes a PASS.
        if (!isCompleteThought(cleaned)) return Decision.PASS

        if (cleaned.length > MAX_SUGGESTION_CHARS) cleaned = truncateAtWord(cleaned)

        return Decision(kind ?: SuggestionKind.FOLLOW_UP, cleaned)
    }

    private fun stripWrapping(raw: String): String {
        var text = raw.trim()
        // ```lang\n ... \n```
        if (text.startsWith("```")) {
            text = text.removePrefix("```").substringBeforeLast("```")
            text = text.substringAfter('\n', text)
        }
        text = text.trim('`', ' ', '\n', '\r')
        if (text.startsWith("json", ignoreCase = true)) text = text.removePrefix("json").trim()
        return text
    }

    private fun kindOf(label: String?): SuggestionKind? = when (label?.uppercase()?.replace(" ", "_")) {
        "FOLLOW_UP", "FOLLOWUP", "FOLLOW-UP" -> SuggestionKind.FOLLOW_UP
        "CONNECTION" -> SuggestionKind.CONNECTION
        "TOPIC_SHIFT", "TOPICSHIFT", "TOPIC-SHIFT" -> SuggestionKind.TOPIC_SHIFT
        else -> null
    }

    /** Filters out headings, preambles, bullets-of-nothing and stray markdown. */
    private fun looksLikeWhisper(line: String): Boolean {
        val candidate = clean(line)
        if (candidate.length < MIN_SUGGESTION_CHARS) return false
        if (candidate.equals(PASS_TOKEN, ignoreCase = true)) return false
        // "Here's a suggestion:" and friends — a label for what follows, not the thing.
        if (candidate.endsWith(":")) return false
        if (PREAMBLE_REGEX.containsMatchIn(candidate)) return false
        return candidate.any { it.isLetter() }
    }

    private fun clean(line: String): String = line
        .trim()
        .removePrefix("- ").removePrefix("* ").removePrefix("• ")
        .trim()
        .trim('"', '“', '”', '\'', '‘', '’', '*', '_', ' ')
        .trim()

    /**
     * Whether this reads as a finished sentence rather than a truncated fragment.
     *
     * Model turns get cut off — by an output-token ceiling, by a dropped socket frame,
     * by the turn being interrupted. What arrives then is the *start* of a sentence, and
     * it used to be spoken as though it were the whole suggestion.
     */
    internal fun isCompleteThought(text: String): Boolean {
        val words = text.split(WHITESPACE).filter { it.isNotBlank() }
        if (words.size < MIN_SUGGESTION_WORDS) return false
        // Ends mid-clause: a conjunction or article as the final word is a giveaway.
        if (DANGLING_REGEX.containsMatchIn(text)) return false
        // An unbalanced opening bracket means the closing half never arrived.
        if (text.count { it == '(' } != text.count { it == ')' }) return false
        return true
    }

    private fun truncateAtWord(text: String): String {
        val hard = text.take(MAX_SUGGESTION_CHARS)
        val lastSpace = hard.lastIndexOf(' ')
        val cut = if (lastSpace > MAX_SUGGESTION_CHARS / 2) hard.take(lastSpace) else hard
        return cut.trimEnd().trimEnd(',', ';', ':', '-', '–') + "…"
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
    const val MIN_SUGGESTION_CHARS = 8

    /** "Ask about Berlin." is three words; anything shorter isn't a usable line. */
    const val MIN_SUGGESTION_WORDS = 3

    private val WHITESPACE = Regex("\\s+")

    private val KIND_REGEX =
        Regex("^\\s*[-*•]?\\s*\\**\\[?(FOLLOW[_ -]?UP|CONNECTION|TOPIC[_ -]?SHIFT)]?\\**\\s*[:\\-–]\\s*",
            RegexOption.IGNORE_CASE)

    private val REFUSAL_REGEX = Regex(
        "^(i\\s|as an ai|i'm sorry|sorry,|i cannot|i can't|unable to)",
        RegexOption.IGNORE_CASE,
    )

    /** Meta-commentary the model wraps around its actual answer. */
    private val PREAMBLE_REGEX = Regex(
        "^(here('s| is)|okay|ok\\b|sure\\b|certainly|based on|given (that|the)|" +
            "(a |one )?(good |possible |suggested )?(suggestion|option|idea|whisper|response)\\b|" +
            "output|answer|reply|kind|format)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A trailing word that can only be the middle of a sentence. If the turn ends here,
     * the rest of it never arrived.
     */
    private val DANGLING_REGEX = Regex(
        "\\b(a|an|the|and|or|but|so|to|of|in|on|at|for|with|that|which|who|because|" +
            "about|from|as|is|are|was|were|their|your|his|her|its|our|my|this|these|" +
            "those|if|when|while|how|what|it's|they're)\\s*[,;:\\-–—]?\\s*$",
        RegexOption.IGNORE_CASE,
    )
}
