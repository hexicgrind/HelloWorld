package ai.sotto.assistant.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConferenceDatabaseTest {

    private fun person(
        id: String,
        name: String,
        company: String = "",
        embedding: List<Float>? = null,
    ) = Attendee(id = id, name = name, company = company, embedding = embedding)

    private val face = List(128) { 0.1f }

    @Test
    fun `an empty database reports itself as empty`() {
        val db = ConferenceDatabase.empty()
        assertThat(db.isEmpty).isTrue()
        assertThat(db.size).isEqualTo(0)
        assertThat(db.enrolledCount).isEqualTo(0)
    }

    @Test
    fun `enrolledCount counts only people with a usable embedding`() {
        val db = ConferenceDatabase(
            attendees = listOf(
                person("1", "A", embedding = face),
                person("2", "B"),
                person("3", "C", embedding = List(64) { 0.1f }),
            )
        )
        assertThat(db.size).isEqualTo(3)
        assertThat(db.enrolledCount).isEqualTo(1)
    }

    @Test
    fun `upsert adds a new person`() {
        val db = ConferenceDatabase.empty().upsert(person("1", "Ada"))
        assertThat(db.size).isEqualTo(1)
        assertThat(db.findById("1")!!.name).isEqualTo("Ada")
    }

    @Test
    fun `upsert replaces an existing person in place`() {
        val db = ConferenceDatabase.empty()
            .upsert(person("1", "Ada"))
            .upsert(person("2", "Grace"))
            .upsert(person("1", "Ada Lovelace"))

        assertThat(db.size).isEqualTo(2)
        assertThat(db.findById("1")!!.name).isEqualTo("Ada Lovelace")
        // Order is preserved, so the list doesn't jump around under the user.
        assertThat(db.attendees.map { it.id }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `upsert sanitises what it stores`() {
        val db = ConferenceDatabase.empty().upsert(person("1", "  Ada  "))
        assertThat(db.findById("1")!!.name).isEqualTo("Ada")
    }

    @Test
    fun `remove deletes by id`() {
        val db = ConferenceDatabase.empty()
            .upsert(person("1", "Ada"))
            .upsert(person("2", "Grace"))
            .remove("1")

        assertThat(db.size).isEqualTo(1)
        assertThat(db.findById("1")).isNull()
    }

    @Test
    fun `removing an unknown id is a no-op`() {
        val db = ConferenceDatabase.empty().upsert(person("1", "Ada"))
        assertThat(db.remove("nope").size).isEqualTo(1)
    }

    // ---- Merge behaviour ----------------------------------------------------------

    @Test
    fun `merge with replace discards the old roster`() {
        val existing = ConferenceDatabase.empty().upsert(person("1", "Ada"))
        val merged = existing.mergeWith(listOf(person("2", "Grace")), replaceExisting = true)

        assertThat(merged.size).isEqualTo(1)
        assertThat(merged.attendees.single().name).isEqualTo("Grace")
    }

    @Test
    fun `merge without replace keeps both rosters`() {
        val existing = ConferenceDatabase.empty().upsert(person("1", "Ada"))
        val merged = existing.mergeWith(listOf(person("2", "Grace")), replaceExisting = false)

        assertThat(merged.size).isEqualTo(2)
    }

    @Test
    fun `re-importing a roster preserves an enrolled face`() {
        // This is the important one: a user who re-uploads the attendee list must not
        // lose the faces they spent time enrolling.
        val existing = ConferenceDatabase.empty()
            .upsert(person("1", "Ada Lovelace", company = "Analytical Engines", embedding = face))

        val merged = existing.mergeWith(
            listOf(person("fresh-id", "Ada Lovelace", company = "Analytical Engines")),
            replaceExisting = true,
        )

        assertThat(merged.size).isEqualTo(1)
        val ada = merged.attendees.single()
        assertThat(ada.hasFace).isTrue()
        assertThat(ada.id).isEqualTo("1")   // stable id, so photo paths stay valid
    }

    @Test
    fun `merge matches people case-insensitively`() {
        val existing = ConferenceDatabase.empty()
            .upsert(person("1", "ADA LOVELACE", company = "ACME", embedding = face))

        val merged = existing.mergeWith(
            listOf(person("x", "ada lovelace", company = "acme")),
            replaceExisting = true,
        )

        assertThat(merged.size).isEqualTo(1)
        assertThat(merged.attendees.single().hasFace).isTrue()
    }

    @Test
    fun `same name at different companies stays two people`() {
        val existing = ConferenceDatabase.empty().upsert(person("1", "John Smith", company = "Acme"))
        val merged = existing.mergeWith(
            listOf(person("2", "John Smith", company = "Globex")),
            replaceExisting = false,
        )
        assertThat(merged.size).isEqualTo(2)
    }

    @Test
    fun `merge takes fresh details over stale ones`() {
        val existing = ConferenceDatabase.empty()
            .upsert(Attendee(id = "1", name = "Ada", company = "Acme", title = "Engineer"))

        val merged = existing.mergeWith(
            listOf(Attendee(id = "x", name = "Ada", company = "Acme", title = "Chief Scientist")),
            replaceExisting = true,
        )

        assertThat(merged.attendees.single().title).isEqualTo("Chief Scientist")
    }

    @Test
    fun `duplicates within one import are collapsed`() {
        val merged = ConferenceDatabase.empty().mergeWith(
            listOf(
                person("1", "Ada", company = "Acme"),
                person("2", "Ada", company = "Acme"),
            ),
            replaceExisting = true,
        )
        assertThat(merged.size).isEqualTo(1)
    }

    @Test
    fun `merging an empty list into an empty database stays empty`() {
        assertThat(ConferenceDatabase.empty().mergeWith(emptyList(), true).isEmpty).isTrue()
    }

    @Test
    fun `identityKey is stable across whitespace and case`() {
        val a = person("1", "  Ada Lovelace ", company = " Acme ")
        val b = person("2", "ada lovelace", company = "ACME")
        // Keys are computed on the raw fields, so trim first the way merge does.
        assertThat(ConferenceDatabase.identityKey(a.sanitised()))
            .isEqualTo(ConferenceDatabase.identityKey(b.sanitised()))
    }
}
