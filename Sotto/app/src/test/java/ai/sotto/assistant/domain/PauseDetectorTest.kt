package ai.sotto.assistant.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Design Doc 1 § Runtime Flow: "Every five to ten seconds, Gemini analyzes the
 * transcript and determines if a natural pause has occurred."
 *
 * These tests are really about restraint — most of them assert that Sotto stays quiet.
 */
class PauseDetectorTest {

    private lateinit var detector: PauseDetector

    @Before
    fun setUp() {
        detector = PauseDetector()
    }

    /** Walks the detector through [durationMs] of speech ending at [endMs]. */
    private fun speakUntil(endMs: Long, startMs: Long = 0L) {
        detector.onVoiceActivity(isSpeaking = true, nowMs = startMs)
        detector.onVoiceActivity(isSpeaking = false, nowMs = endMs)
    }

    // ---- Basic gating -----------------------------------------------------------

    @Test
    fun `no request while someone is talking`() {
        detector.onVoiceActivity(isSpeaking = true, nowMs = 1_000L)
        assertThat(detector.evaluate(1_000L)).isEqualTo(PauseDetector.Verdict.SPEAKING)
        assertThat(detector.shouldRequestSuggestion(1_000L)).isFalse()
    }

    @Test
    fun `no request immediately after speech stops`() {
        speakUntil(1_000L)
        assertThat(detector.evaluate(1_200L))
            .isEqualTo(PauseDetector.Verdict.TOO_SOON_AFTER_SPEECH)
    }

    @Test
    fun `a request is made once the pause is long enough`() {
        speakUntil(1_000L)
        assertThat(detector.shouldRequestSuggestion(1_000L + PauseDetector.DEFAULT_PAUSE_THRESHOLD_MS))
            .isTrue()
    }

    @Test
    fun `the pause threshold is respected exactly`() {
        speakUntil(1_000L)
        val justBefore = 1_000L + PauseDetector.DEFAULT_PAUSE_THRESHOLD_MS - 1
        val exactly = 1_000L + PauseDetector.DEFAULT_PAUSE_THRESHOLD_MS

        assertThat(detector.evaluate(justBefore)).isEqualTo(PauseDetector.Verdict.TOO_SOON_AFTER_SPEECH)
        assertThat(detector.evaluate(exactly)).isEqualTo(PauseDetector.Verdict.ASK)
    }

    // ---- Cadence ----------------------------------------------------------------

    @Test
    fun `a second request is rate limited within the interval`() {
        speakUntil(1_000L)
        assertThat(detector.shouldRequestSuggestion(3_000L)).isTrue()
        detector.onDecisionSettled()

        speakUntil(4_000L, startMs = 3_500L)
        // Only 2 seconds since the last ask, well inside the 7-second default.
        assertThat(detector.evaluate(5_500L)).isEqualTo(PauseDetector.Verdict.RATE_LIMITED)
    }

    @Test
    fun `a request is allowed again after the interval elapses`() {
        speakUntil(1_000L)
        assertThat(detector.shouldRequestSuggestion(3_000L)).isTrue()
        detector.onDecisionSettled()

        speakUntil(9_000L, startMs = 8_000L)
        assertThat(detector.shouldRequestSuggestion(11_000L)).isTrue()
    }

    @Test
    fun `the interval is configurable inside the documented five to ten seconds`() {
        detector.minIntervalMs = 5_000L
        speakUntil(1_000L)
        assertThat(detector.shouldRequestSuggestion(3_000L)).isTrue()
        detector.onDecisionSettled()

        speakUntil(6_500L, startMs = 6_000L)
        assertThat(detector.evaluate(7_800L)).isEqualTo(PauseDetector.Verdict.RATE_LIMITED)
        speakUntil(8_500L, startMs = 8_200L)
        assertThat(detector.evaluate(10_000L)).isEqualTo(PauseDetector.Verdict.ASK)
    }

    // ---- Nothing new ------------------------------------------------------------

    @Test
    fun `no request when nothing has been said since the last one`() {
        speakUntil(1_000L)
        assertThat(detector.shouldRequestSuggestion(3_000L)).isTrue()
        detector.onDecisionSettled()

        // Long silence, nobody spoke again. Not worth asking for a while.
        assertThat(detector.evaluate(3_000L + PauseDetector.DEFAULT_MIN_INTERVAL_MS + 500))
            .isEqualTo(PauseDetector.Verdict.NOTHING_NEW)
    }

    @Test
    fun `after a very long silence a request is allowed anyway`() {
        speakUntil(1_000L)
        assertThat(detector.shouldRequestSuggestion(3_000L)).isTrue()
        detector.onDecisionSettled()

        // The escape hatch: a stalled conversation is exactly when a nudge helps.
        assertThat(detector.evaluate(3_000L + PauseDetector.DEFAULT_MAX_INTERVAL_MS))
            .isEqualTo(PauseDetector.Verdict.ASK)
    }

    @Test
    fun `a transcript line counts as something new`() {
        speakUntil(1_000L)
        assertThat(detector.shouldRequestSuggestion(3_000L)).isTrue()
        detector.onDecisionSettled()

        detector.onTranscript(nowMs = 4_000L)
        assertThat(detector.evaluate(4_000L + PauseDetector.DEFAULT_MIN_INTERVAL_MS + 100))
            .isEqualTo(PauseDetector.Verdict.ASK)
    }

    // ---- Cooldown ----------------------------------------------------------------

    @Test
    fun `no request right after Sotto speaks`() {
        speakUntil(1_000L)
        detector.shouldRequestSuggestion(3_000L)
        detector.onDecisionSettled()
        detector.onSuggestionSpoken(nowMs = 3_500L)

        speakUntil(5_000L, startMs = 4_500L)
        assertThat(detector.evaluate(6_500L)).isEqualTo(PauseDetector.Verdict.COOLING_DOWN)
    }

    @Test
    fun `requests resume after the cooldown expires`() {
        speakUntil(1_000L)
        detector.shouldRequestSuggestion(3_000L)
        detector.onDecisionSettled()
        detector.onSuggestionSpoken(nowMs = 3_500L)

        val afterCooldown = 3_500L + PauseDetector.DEFAULT_COOLDOWN_MS
        speakUntil(afterCooldown, startMs = afterCooldown - 500)
        assertThat(detector.shouldRequestSuggestion(afterCooldown + 5_000L)).isTrue()
    }

    @Test
    fun `cooldown takes precedence over the long-gap escape hatch`() {
        detector.cooldownAfterSpeakingMs = 30_000L
        speakUntil(1_000L)
        detector.shouldRequestSuggestion(3_000L)
        detector.onDecisionSettled()
        detector.onSuggestionSpoken(nowMs = 3_000L)

        assertThat(detector.evaluate(3_000L + PauseDetector.DEFAULT_MAX_INTERVAL_MS))
            .isEqualTo(PauseDetector.Verdict.COOLING_DOWN)
    }

    // ---- quietMs -----------------------------------------------------------------

    @Test
    fun `quietMs is zero while speaking`() {
        detector.onVoiceActivity(isSpeaking = true, nowMs = 1_000L)
        assertThat(detector.quietMs(2_000L)).isEqualTo(0L)
    }

    @Test
    fun `quietMs measures the gap since speech ended`() {
        speakUntil(1_000L)
        assertThat(detector.quietMs(3_500L)).isEqualTo(2_500L)
    }

    @Test
    fun `quietMs is zero before anyone has spoken`() {
        assertThat(detector.quietMs(5_000L)).isEqualTo(0L)
    }

    // ---- State machine correctness ------------------------------------------------

    @Test
    fun `repeated speaking-true does not reset the end timestamp`() {
        detector.onVoiceActivity(isSpeaking = true, nowMs = 1_000L)
        detector.onVoiceActivity(isSpeaking = true, nowMs = 1_100L)
        detector.onVoiceActivity(isSpeaking = false, nowMs = 2_000L)
        detector.onVoiceActivity(isSpeaking = false, nowMs = 2_100L)

        assertThat(detector.quietMs(3_000L)).isEqualTo(1_000L)
    }

    @Test
    fun `shouldRequestSuggestion consumes the opportunity`() {
        speakUntil(1_000L)
        assertThat(detector.shouldRequestSuggestion(3_000L)).isTrue()
        detector.onDecisionSettled()
        // Calling again immediately must not fire a second request.
        assertThat(detector.shouldRequestSuggestion(3_001L)).isFalse()
    }

    @Test
    fun `evaluate does not consume the opportunity`() {
        speakUntil(1_000L)
        assertThat(detector.evaluate(3_000L)).isEqualTo(PauseDetector.Verdict.ASK)
        assertThat(detector.evaluate(3_000L)).isEqualTo(PauseDetector.Verdict.ASK)
        assertThat(detector.shouldRequestSuggestion(3_000L)).isTrue()
        detector.onDecisionSettled()
    }

    @Test
    fun `reset clears everything`() {
        speakUntil(1_000L)
        detector.shouldRequestSuggestion(3_000L)
        detector.onDecisionSettled()
        detector.onSuggestionSpoken(3_100L)

        detector.reset()

        assertThat(detector.quietMs(5_000L)).isEqualTo(0L)
        assertThat(detector.evaluate(5_000L)).isEqualTo(PauseDetector.Verdict.ASK)
    }

    // ---- A realistic conversation --------------------------------------------------

    @Test
    fun `a five minute conversation stays within the documented cadence`() {
        var now = 0L
        var asks = 0
        val askTimes = mutableListOf<Long>()

        // Alternating 4-second turns with 2-second gaps, for five minutes.
        while (now < 300_000L) {
            detector.onVoiceActivity(isSpeaking = true, nowMs = now)
            now += 4_000L
            detector.onVoiceActivity(isSpeaking = false, nowMs = now)
            detector.onTranscript(now)

            var tick = now
            while (tick < now + 2_000L) {
                if (detector.shouldRequestSuggestion(tick)) {
                    asks++
                    askTimes += tick
                    detector.onSuggestionSpoken(tick)
                }
                tick += 250L
            }
            now += 2_000L
        }

        assertThat(asks).isGreaterThan(0)
        // Never faster than the interval, once the cooldown is accounted for.
        askTimes.zipWithNext().forEach { (a, b) ->
            assertThat(b - a).isAtLeast(PauseDetector.DEFAULT_MIN_INTERVAL_MS)
        }
        // And not so rare as to be useless over five minutes.
        assertThat(asks).isAtLeast(10)
    }

    @Test
    fun `a monologue never triggers an interruption`() {
        var now = 0L
        var asks = 0
        // Someone talks continuously for 60 seconds with only micro-gaps.
        while (now < 60_000L) {
            detector.onVoiceActivity(isSpeaking = true, nowMs = now)
            now += 500L
            if (detector.shouldRequestSuggestion(now)) asks++
        }
        assertThat(asks).isEqualTo(0)
    }

    // ---- One question at a time ------------------------------------------------------
    //
    // A user heard whispers like "ask what kind" and "what game genre" — the opening
    // words of a sentence, delivered as though finished. Sending a second request over
    // the live socket interrupts the generation already running, and the fragment
    // written so far gets delivered. Nothing here stopped that: the cadence gate only
    // knew when we last *asked*, never whether an answer had come back, so a reply that
    // took longer than the interval was reliably destroyed by the next question.

    @Test
    fun `a second question is not asked while the first is unanswered`() {
        speakUntil(1_000L)
        assertThat(detector.shouldRequestSuggestion(3_000L)).isTrue()

        speakUntil(12_000L, startMs = 11_000L)
        // Past the interval, and something new was said — but the answer is still coming.
        assertThat(detector.evaluate(14_000L)).isEqualTo(PauseDetector.Verdict.AWAITING_REPLY)
        assertThat(detector.shouldRequestSuggestion(14_000L)).isFalse()
    }

    @Test
    fun `the long-gap escape hatch cannot interrupt an unanswered question either`() {
        speakUntil(1_000L)
        assertThat(detector.shouldRequestSuggestion(3_000L)).isTrue()

        assertThat(detector.evaluate(3_000L + PauseDetector.DEFAULT_MAX_INTERVAL_MS))
            .isEqualTo(PauseDetector.Verdict.AWAITING_REPLY)
    }

    @Test
    fun `asking resumes once the answer arrives`() {
        speakUntil(1_000L)
        assertThat(detector.shouldRequestSuggestion(3_000L)).isTrue()

        detector.onDecisionSettled()

        speakUntil(11_000L, startMs = 10_000L)
        assertThat(detector.shouldRequestSuggestion(13_000L)).isTrue()
    }

    @Test
    fun `a PASS reopens the gate just as a whisper does`() {
        // A decision to stay quiet is still an answer; the gate must not stay shut.
        speakUntil(1_000L)
        detector.shouldRequestSuggestion(3_000L)
        detector.onDecisionSettled()

        speakUntil(11_000L, startMs = 10_000L)
        assertThat(detector.evaluate(13_000L)).isEqualTo(PauseDetector.Verdict.ASK)
    }

    @Test
    fun `an answer that never arrives eventually stops blocking`() {
        // A dropped socket must not wedge the session in silence for ever.
        speakUntil(1_000L)
        assertThat(detector.shouldRequestSuggestion(3_000L)).isTrue()

        val afterTimeout = 3_000L + PauseDetector.DEFAULT_RESPONSE_TIMEOUT_MS
        assertThat(detector.evaluate(afterTimeout - 1)).isEqualTo(PauseDetector.Verdict.AWAITING_REPLY)
        assertThat(detector.evaluate(afterTimeout)).isEqualTo(PauseDetector.Verdict.ASK)
    }

    @Test
    fun `the response timeout is long enough for a grounded answer`() {
        // Short enough and the timeout re-creates the very bug it guards against.
        assertThat(PauseDetector.DEFAULT_RESPONSE_TIMEOUT_MS)
            .isGreaterThan(PauseDetector.DEFAULT_MAX_INTERVAL_MS)
    }

    @Test
    fun `resetting clears an outstanding question`() {
        speakUntil(1_000L)
        detector.shouldRequestSuggestion(3_000L)

        detector.reset()

        speakUntil(5_000L, startMs = 4_000L)
        assertThat(detector.evaluate(7_000L)).isEqualTo(PauseDetector.Verdict.ASK)
    }
}
