package ai.sotto.assistant.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The whole local attendee database. Design Doc 1 § Offline Processing: "This
 * enriched database is stored locally on the phone as a JSON file."
 */
@Serializable
data class ConferenceDatabase(
    @SerialName("schema_version") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @SerialName("conference_name") val conferenceName: String = "",
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
    @SerialName("source_summary") val sourceSummary: String = "",
    val attendees: List<Attendee> = emptyList(),
) {
    val size: Int get() = attendees.size
    val enrolledCount: Int get() = attendees.count { it.hasFace }
    val isEmpty: Boolean get() = attendees.isEmpty()

    fun findById(id: String): Attendee? = attendees.firstOrNull { it.id == id }

    fun upsert(attendee: Attendee): ConferenceDatabase {
        val clean = attendee.sanitised()
        val index = attendees.indexOfFirst { it.id == clean.id }
        val next = if (index >= 0) {
            attendees.toMutableList().also { it[index] = clean }
        } else {
            attendees + clean
        }
        return copy(attendees = next)
    }

    fun remove(id: String): ConferenceDatabase =
        copy(attendees = attendees.filterNot { it.id == id })

    /**
     * Merges a freshly-enriched batch in, keeping any face enrolments already made
     * for people who appear in both. Identity is name + company, case-insensitive,
     * which is the only stable key a conference roster reliably gives us.
     */
    fun mergeWith(incoming: List<Attendee>, replaceExisting: Boolean): ConferenceDatabase {
        val base = if (replaceExisting) emptyList() else attendees
        val byKey = LinkedHashMap<String, Attendee>()
        base.forEach { byKey[identityKey(it)] = it }

        // Enrolments are looked up against the *whole* previous roster, not just the
        // base. Otherwise replacing the roster would silently discard every face the
        // user enrolled, which is by far the most expensive data in the database.
        val previousByKey = attendees.associateBy { identityKey(it) }

        incoming.map { it.sanitised() }.forEach { fresh ->
            val key = identityKey(fresh)
            val existing = byKey[key] ?: previousByKey[key]
            byKey[key] = if (existing == null) {
                fresh
            } else {
                // Never lose an enrolled face just because the roster was re-imported.
                fresh.copy(
                    id = existing.id,
                    embedding = fresh.embedding ?: existing.embedding,
                    photoPath = fresh.photoPath ?: existing.photoPath,
                    enrolledAt = fresh.enrolledAt ?: existing.enrolledAt,
                )
            }
        }
        return copy(attendees = byKey.values.toList())
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun identityKey(a: Attendee): String =
            "${a.name.trim().lowercase()}|${a.company.trim().lowercase()}"

        fun empty() = ConferenceDatabase()
    }
}
