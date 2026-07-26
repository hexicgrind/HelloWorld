package ai.sotto.assistant.domain

/**
 * Decides when it would feel natural to interrupt.
 *
 * Design Doc 1 § Gemini Live API Integration: Gemini "understands natural speech rhythm,
 * and identifies pauses where interruption would feel natural. When a pause is detected,
 * Gemini either whispers a follow-up question..." and § Runtime Flow: "Every five to ten
 * seconds, Gemini analyzes the transcript and determines if a natural pause has occurred."
 *
 * Gemini makes the editorial call about *what* to say. This class makes the cheap local
 * call about *whether to even ask* — it is the gate that stops the app pestering the API
 * (and the user) several times a second. Every rule here exists to protect the same
 * thing: the user is in a real conversation and a badly-timed whisper is worse than
 * silence.
 *
 * Pure and clock-injected, so all of its timing behaviour is unit-testable.
 */
class PauseDetector(
    /** Quiet needed before a gap counts as a pause at all. */
    var pauseThresholdMs: Long = DEFAULT_PAUSE_THRESHOLD_MS,
    /** Design Doc 1: the five-to-ten-second analysis cadence. */
    var minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    /** Never let a gap this long go by without at least considering a suggestion. */
    var maxIntervalMs: Long = DEFAULT_MAX_INTERVAL_MS,
    /** Enforced quiet time after we speak, so we never stack whispers. */
    var cooldownAfterSpeakingMs: Long = DEFAULT_COOLDOWN_MS,
    /** How long to wait for an answer before assuming one is never coming. */
    var responseTimeoutMs: Long = DEFAULT_RESPONSE_TIMEOUT_MS,
) {
    private var lastSpeechEndedAtMs = 0L
    private var lastRequestAtMs = 0L
    private var lastSpokeAtMs = 0L
    private var awaitingSinceMs = 0L
    private var speechSinceLastRequest = false
    private var currentlySpeaking = false

    /** Why a decision request was or wasn't made — surfaced in the debug panel. */
    enum class Verdict {
        /** Ask Gemini whether to whisper. */
        ASK,
        /** Someone is mid-sentence. */
        SPEAKING,
        /** Not enough quiet yet. */
        TOO_SOON_AFTER_SPEECH,
        /** Asked recently; respect the cadence. */
        RATE_LIMITED,
        /** We just whispered; give them room. */
        COOLING_DOWN,
        /** Nothing new has been said since the last time we asked. */
        NOTHING_NEW,

        /**
         * A question is already out and the answer hasn't come back.
         *
         * This one is load-bearing. Sending a second request over the live socket
         * interrupts the generation already in progress, and the half-written sentence
         * gets delivered as though it were finished — which is how a user ended up
         * hearing whispers like "ask what kind". Asking again is not free; it destroys
         * the answer to the previous question.
         */
        AWAITING_REPLY,
    }

    /** Call on every VAD frame. */
    fun onVoiceActivity(isSpeaking: Boolean, nowMs: Long) {
        if (isSpeaking) {
            currentlySpeaking = true
            speechSinceLastRequest = true
        } else if (currentlySpeaking) {
            currentlySpeaking = false
            lastSpeechEndedAtMs = nowMs
        }
    }

    /** Call whenever a transcript line lands, so a quiet-but-real utterance still counts. */
    fun onTranscript(nowMs: Long) {
        speechSinceLastRequest = true
        if (!currentlySpeaking) lastSpeechEndedAtMs = nowMs
    }

    /** Call when a whisper actually starts playing. */
    fun onSuggestionSpoken(nowMs: Long) {
        lastSpokeAtMs = nowMs
    }

    /**
     * Call when a decision comes back — whether it was a whisper or a PASS, and whether
     * it succeeded or failed. Until this happens the gate stays shut.
     */
    fun onDecisionSettled() {
        awaitingSinceMs = 0L
    }

    /** Evaluates the current moment without changing any state. */
    fun evaluate(nowMs: Long): Verdict {
        // Before anything else: never talk over our own outstanding question.
        if (awaitingSinceMs > 0L && nowMs - awaitingSinceMs < responseTimeoutMs) {
            return Verdict.AWAITING_REPLY
        }

        if (currentlySpeaking) return Verdict.SPEAKING

        if (lastSpokeAtMs > 0 && nowMs - lastSpokeAtMs < cooldownAfterSpeakingMs) {
            return Verdict.COOLING_DOWN
        }

        val quietFor = if (lastSpeechEndedAtMs == 0L) Long.MAX_VALUE else nowMs - lastSpeechEndedAtMs
        if (quietFor < pauseThresholdMs) return Verdict.TOO_SOON_AFTER_SPEECH

        val sinceLastRequest =
            if (lastRequestAtMs == 0L) Long.MAX_VALUE else nowMs - lastRequestAtMs

        // The long-gap escape hatch: after maxInterval of nothing, it's worth offering
        // something even if the conversation has stalled rather than progressed.
        if (sinceLastRequest >= maxIntervalMs) return Verdict.ASK

        if (sinceLastRequest < minIntervalMs) return Verdict.RATE_LIMITED
        if (!speechSinceLastRequest) return Verdict.NOTHING_NEW

        return Verdict.ASK
    }

    /**
     * Evaluates and, when the answer is [Verdict.ASK], records that a request is going
     * out. Returns true when the caller should ask Gemini.
     */
    fun shouldRequestSuggestion(nowMs: Long): Boolean {
        val verdict = evaluate(nowMs)
        if (verdict != Verdict.ASK) return false
        lastRequestAtMs = nowMs
        awaitingSinceMs = nowMs
        speechSinceLastRequest = false
        return true
    }

    /** How long the room has been quiet, for the UI. */
    fun quietMs(nowMs: Long): Long =
        if (currentlySpeaking || lastSpeechEndedAtMs == 0L) 0L else nowMs - lastSpeechEndedAtMs

    fun reset() {
        lastSpeechEndedAtMs = 0L
        lastRequestAtMs = 0L
        lastSpokeAtMs = 0L
        awaitingSinceMs = 0L
        speechSinceLastRequest = false
        currentlySpeaking = false
    }

    companion object {
        const val DEFAULT_PAUSE_THRESHOLD_MS = 1_200L
        const val DEFAULT_MIN_INTERVAL_MS = 7_000L
        const val DEFAULT_MAX_INTERVAL_MS = 25_000L
        const val DEFAULT_COOLDOWN_MS = 6_000L

        /**
         * Generous, because a grounded answer can genuinely take this long. Cutting it
         * short is exactly the failure this timeout exists to avoid.
         */
        const val DEFAULT_RESPONSE_TIMEOUT_MS = 30_000L
    }
}
