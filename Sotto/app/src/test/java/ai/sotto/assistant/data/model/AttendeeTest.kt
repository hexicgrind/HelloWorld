package ai.sotto.assistant.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Design Doc 1 § Database Schema, enforced. */
class AttendeeTest {

    @Test
    fun `the embedding dimension matches the design doc`() {
        assertThat(Attendee.EMBEDDING_DIMENSIONS).isEqualTo(128)
    }

    @Test
    fun `the bio limit matches the design doc`() {
        assertThat(Attendee.MAX_BIO).isEqualTo(200)
    }

    @Test
    fun `hasFace requires a correctly sized embedding`() {
        val correct = Attendee(id = "1", name = "A", embedding = List(128) { 0.1f })
        val wrong = Attendee(id = "2", name = "B", embedding = List(64) { 0.1f })
        val none = Attendee(id = "3", name = "C")

        assertThat(correct.hasFace).isTrue()
        assertThat(wrong.hasFace).isFalse()
        assertThat(none.hasFace).isFalse()
    }

    @Test
    fun `headline combines title and company`() {
        assertThat(Attendee(id = "1", name = "A", title = "CTO", company = "Acme").headline)
            .isEqualTo("CTO at Acme")
    }

    @Test
    fun `headline falls back to whichever field exists`() {
        assertThat(Attendee(id = "1", name = "A", title = "CTO").headline).isEqualTo("CTO")
        assertThat(Attendee(id = "1", name = "A", company = "Acme").headline).isEqualTo("Acme")
        assertThat(Attendee(id = "1", name = "A").headline).isEmpty()
    }

    @Test
    fun `initials use first and last name`() {
        assertThat(Attendee(id = "1", name = "Ada Lovelace").initials).isEqualTo("AL")
        assertThat(Attendee(id = "1", name = "Ada Byron King Lovelace").initials).isEqualTo("AL")
    }

    @Test
    fun `initials handle a single name`() {
        assertThat(Attendee(id = "1", name = "Prince").initials).isEqualTo("PR")
    }

    @Test
    fun `initials handle an empty name`() {
        assertThat(Attendee(id = "1", name = "").initials).isEqualTo("?")
        assertThat(Attendee(id = "1", name = "   ").initials).isEqualTo("?")
    }

    @Test
    fun `sanitised clamps the bio to two hundred characters`() {
        val long = Attendee(id = "1", name = "A", bio = "x".repeat(500)).sanitised()
        assertThat(long.bio).hasLength(200)
    }

    @Test
    fun `sanitised trims whitespace`() {
        val messy = Attendee(
            id = "1",
            name = "  Ada  ",
            title = "  CTO  ",
            company = "  Acme  ",
            bio = "  Hello  ",
        ).sanitised()

        assertThat(messy.name).isEqualTo("Ada")
        assertThat(messy.title).isEqualTo("CTO")
        assertThat(messy.company).isEqualTo("Acme")
        assertThat(messy.bio).isEqualTo("Hello")
    }

    @Test
    fun `sanitised drops blank interests and deduplicates case-insensitively`() {
        val messy = Attendee(
            id = "1",
            name = "A",
            interests = listOf("AI", "  ", "ai", "Robotics", "AI"),
        ).sanitised()

        assertThat(messy.interests).hasSize(2)
        assertThat(messy.interests).containsExactly("AI", "Robotics").inOrder()
    }

    @Test
    fun `sanitised caps the interest count`() {
        val many = Attendee(
            id = "1",
            name = "A",
            interests = (1..50).map { "Interest $it" },
        ).sanitised()
        assertThat(many.interests).hasSize(Attendee.MAX_INTERESTS)
    }

    @Test
    fun `sanitised nulls a blank location`() {
        assertThat(Attendee(id = "1", name = "A", location = "   ").sanitised().location).isNull()
        assertThat(Attendee(id = "1", name = "A", location = " Table 4 ").sanitised().location)
            .isEqualTo("Table 4")
    }

    @Test
    fun `sanitised discards a wrongly sized embedding`() {
        val bad = Attendee(id = "1", name = "A", embedding = List(64) { 0.1f }).sanitised()
        assertThat(bad.embedding).isNull()
    }

    @Test
    fun `sanitised keeps a correctly sized embedding`() {
        val good = Attendee(id = "1", name = "A", embedding = List(128) { 0.1f }).sanitised()
        assertThat(good.embedding).hasSize(128)
    }
}
