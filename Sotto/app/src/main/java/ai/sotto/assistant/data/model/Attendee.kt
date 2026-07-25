package ai.sotto.assistant.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One attendee record, exactly the schema from Design Doc 1 § Database Schema:
 *
 *   name, title, company, bio (<= 200 chars), interests[], location?, embedding[128]
 *
 * [id], [photoPath] and [enrolledAt] are implementation bookkeeping and are not part
 * of the documented schema.
 */
@Serializable
data class Attendee(
    val id: String,
    val name: String,
    val title: String = "",
    val company: String = "",
    val bio: String = "",
    val interests: List<String> = emptyList(),
    val location: String? = null,
    /**
     * 128-dimensional face embedding, L2-normalised. Null until a face has been
     * enrolled for this person.
     */
    val embedding: List<Float>? = null,
    /** Relative path (within the app's private face store) of the enrolment photo. */
    @SerialName("photo_path") val photoPath: String? = null,
    @SerialName("enrolled_at") val enrolledAt: Long? = null,
) {
    val hasFace: Boolean get() = embedding != null && embedding.size == EMBEDDING_DIMENSIONS

    /** "Head of Product at Acme" / "Head of Product" / "Acme" / "". */
    val headline: String
        get() = when {
            title.isNotBlank() && company.isNotBlank() -> "$title at $company"
            title.isNotBlank() -> title
            else -> company
        }

    val initials: String
        get() = name.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .let { parts ->
                when {
                    parts.isEmpty() -> "?"
                    parts.size == 1 -> parts[0].take(2).uppercase()
                    else -> "${parts.first().first()}${parts.last().first()}".uppercase()
                }
            }

    /**
     * Clamps free-text fields to the documented limits and drops empty interests, so
     * a sloppy model response can never poison the local database.
     */
    fun sanitised(): Attendee = copy(
        name = name.trim(),
        title = title.trim().take(MAX_TITLE),
        company = company.trim().take(MAX_COMPANY),
        bio = bio.trim().take(MAX_BIO),
        interests = interests.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_INTERESTS),
        location = location?.trim()?.takeIf { it.isNotBlank() },
        embedding = embedding?.takeIf { it.size == EMBEDDING_DIMENSIONS },
    )

    companion object {
        /** Design Doc 1: "embedding (array of floats, 128 dimensions, precomputed)". */
        const val EMBEDDING_DIMENSIONS = 128

        /** Design Doc 1: "bio (string, up to two hundred characters)". */
        const val MAX_BIO = 200
        const val MAX_TITLE = 120
        const val MAX_COMPANY = 120
        const val MAX_INTERESTS = 12
    }
}
