package ai.sotto.assistant.data.model

/** Who said a given line of transcript. */
enum class Speaker { USER, THEM, UNKNOWN }

/**
 * One line of the running transcript. Design Doc 1 § UI Components: "Real-time
 * transcript display (optional, for debugging)."
 */
data class TranscriptLine(
    val id: Long,
    val text: String,
    val speaker: Speaker,
    val timestampMs: Long,
    val isFinal: Boolean,
    val confidence: Float = 0f,
)

/** Why Sotto decided to whisper. Mirrors the three moves in Design Doc 1. */
enum class SuggestionKind {
    /** "whispers a follow-up question the user could ask" */
    FOLLOW_UP,
    /** "surfaces a connection between the user and the target" */
    CONNECTION,
    /** "suggests a topic shift based on shared interests" */
    TOPIC_SHIFT,
    /** Fallback path: just who this person is. */
    IDENTITY,
    /** No database loaded — generic networking advice. */
    GENERIC,
}

/** A single whispered suggestion, on its way to (or already spoken through) the earpiece. */
data class Suggestion(
    val id: Long,
    val text: String,
    val kind: SuggestionKind,
    val attendeeId: String?,
    val createdAtMs: Long,
    val spokenAtMs: Long? = null,
    val usedWebSearch: Boolean = false,
)

/** What the face pipeline currently believes is in front of the camera. */
sealed interface TargetState {
    data object NoFace : TargetState

    /** A face is being tracked but hasn't cleared the dwell time yet. */
    data class Tracking(val dwellMs: Long) : TargetState

    /** Tracked long enough, embedded, but no attendee cleared the threshold. */
    data class Unrecognised(val bestScore: Float) : TargetState

    data class Matched(
        val attendee: Attendee,
        val score: Float,
        val matchedAtMs: Long,
    ) : TargetState
}

/** Where whispered audio is currently going. */
enum class AudioRoute { BLUETOOTH, WIRED_HEADSET, PHONE_SPEAKER }

/** Health of the four parallel pipelines, surfaced in the UI as small status dots. */
data class PipelineHealth(
    val faceOk: Boolean = false,
    val transcriptionOk: Boolean = false,
    val assistantOk: Boolean = false,
    val audioOutOk: Boolean = false,
    val degradedReason: String? = null,
)
