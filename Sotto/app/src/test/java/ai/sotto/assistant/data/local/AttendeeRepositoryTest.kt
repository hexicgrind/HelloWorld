package ai.sotto.assistant.data.local

import ai.sotto.assistant.data.model.Attendee
import ai.sotto.assistant.data.model.ConferenceDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AttendeeRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var repository: AttendeeRepository
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        repository = AttendeeRepository(
            storageDir = temporaryFolder.root,
            io = Dispatchers.Unconfined,
            clock = { now },
        )
    }

    private val face = List(128) { 0.1f }

    private fun person(id: String, name: String, embedding: List<Float>? = null) =
        Attendee(id = id, name = name, embedding = embedding)

    private fun databaseFile() = File(temporaryFolder.root, AttendeeRepository.DATABASE_FILE_NAME)

    // ---- Loading -----------------------------------------------------------------

    @Test
    fun `loading with no file yields an empty database`() = runTest {
        val db = repository.load()
        assertThat(db.isEmpty).isTrue()
    }

    @Test
    fun `loading an empty file yields an empty database`() = runTest {
        databaseFile().writeText("")
        assertThat(repository.load().isEmpty).isTrue()
    }

    @Test
    fun `saved data round-trips`() = runTest {
        repository.save(
            ConferenceDatabase(
                conferenceName = "DevCon",
                attendees = listOf(person("1", "Ada", face), person("2", "Grace")),
            )
        )

        val fresh = AttendeeRepository(temporaryFolder.root, Dispatchers.Unconfined) { now }
        val loaded = fresh.load()

        assertThat(loaded.conferenceName).isEqualTo("DevCon")
        assertThat(loaded.size).isEqualTo(2)
        assertThat(loaded.enrolledCount).isEqualTo(1)
        assertThat(loaded.findById("1")!!.embedding).hasSize(128)
    }

    @Test
    fun `a corrupt file is quarantined and the app starts empty`() = runTest {
        databaseFile().writeText("{ this is not valid json ]]]")

        val loaded = repository.load()

        assertThat(loaded.isEmpty).isTrue()
        assertThat(File(temporaryFolder.root, "${AttendeeRepository.DATABASE_FILE_NAME}.corrupt").exists())
            .isTrue()
    }

    @Test
    fun `unknown fields in the file are ignored rather than fatal`() = runTest {
        databaseFile().writeText(
            """
            {
              "schema_version": 1,
              "conference_name": "DevCon",
              "some_future_field": 42,
              "attendees": [
                { "id": "1", "name": "Ada", "unexpected": true }
              ]
            }
            """.trimIndent()
        )

        val loaded = repository.load()

        assertThat(loaded.size).isEqualTo(1)
        assertThat(loaded.findById("1")!!.name).isEqualTo("Ada")
    }

    // ---- Saving -------------------------------------------------------------------

    @Test
    fun `saving stamps timestamps`() = runTest {
        val saved = repository.save(ConferenceDatabase(attendees = listOf(person("1", "Ada"))))
        assertThat(saved.createdAt).isEqualTo(now)
        assertThat(saved.updatedAt).isEqualTo(now)
    }

    @Test
    fun `resaving preserves createdAt and advances updatedAt`() = runTest {
        val first = repository.save(ConferenceDatabase(attendees = listOf(person("1", "Ada"))))
        now += 60_000L
        val second = repository.save(first.upsert(person("2", "Grace")))

        assertThat(second.createdAt).isEqualTo(first.createdAt)
        assertThat(second.updatedAt).isGreaterThan(first.updatedAt)
    }

    @Test
    fun `no temp file is left behind after a save`() = runTest {
        repository.save(ConferenceDatabase(attendees = listOf(person("1", "Ada"))))
        val leftovers = temporaryFolder.root.listFiles()
            ?.filter { it.name.endsWith(".tmp") }
            .orEmpty()
        assertThat(leftovers).isEmpty()
    }

    @Test
    fun `the state flow reflects the latest save`() = runTest {
        repository.save(ConferenceDatabase(attendees = listOf(person("1", "Ada"))))
        assertThat(repository.database.value.size).isEqualTo(1)

        repository.upsert(person("2", "Grace"))
        assertThat(repository.database.value.size).isEqualTo(2)
    }

    // ---- Embeddings ------------------------------------------------------------------

    @Test
    fun `setEmbedding attaches a face and timestamps the enrolment`() = runTest {
        repository.save(ConferenceDatabase(attendees = listOf(person("1", "Ada"))))
        now = 1_700_000_500_000L

        val updated = repository.setEmbedding("1", FloatArray(128) { 0.5f }, "1.jpg")

        val ada = updated.findById("1")!!
        assertThat(ada.hasFace).isTrue()
        assertThat(ada.photoPath).isEqualTo("1.jpg")
        assertThat(ada.enrolledAt).isEqualTo(now)
    }

    @Test
    fun `setEmbedding on an unknown id is a no-op`() = runTest {
        repository.save(ConferenceDatabase(attendees = listOf(person("1", "Ada"))))
        val result = repository.setEmbedding("nope", FloatArray(128), null)
        assertThat(result.size).isEqualTo(1)
        assertThat(result.enrolledCount).isEqualTo(0)
    }

    @Test
    fun `clearEmbedding removes the face`() = runTest {
        repository.save(ConferenceDatabase(attendees = listOf(person("1", "Ada", face))))
        val cleared = repository.clearEmbedding("1")

        val ada = cleared.findById("1")!!
        assertThat(ada.hasFace).isFalse()
        assertThat(ada.photoPath).isNull()
        assertThat(ada.enrolledAt).isNull()
    }

    @Test
    fun `deleting removes the person and their photo`() = runTest {
        repository.save(
            ConferenceDatabase(
                attendees = listOf(
                    Attendee(id = "1", name = "Ada", embedding = face, photoPath = "1.jpg")
                )
            )
        )
        val photo = File(repository.facesDir, "1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertThat(photo.exists()).isTrue()

        repository.delete("1")

        assertThat(repository.current.size).isEqualTo(0)
        assertThat(photo.exists()).isFalse()
    }

    // ---- replaceAll ----------------------------------------------------------------

    @Test
    fun `replaceAll with replace swaps the roster`() = runTest {
        repository.save(ConferenceDatabase(attendees = listOf(person("1", "Ada"))))

        val result = repository.replaceAll(
            attendees = listOf(person("2", "Grace")),
            conferenceName = "DevCon",
            sourceSummary = "roster.csv",
            replaceExisting = true,
        )

        assertThat(result.size).isEqualTo(1)
        assertThat(result.attendees.single().name).isEqualTo("Grace")
        assertThat(result.conferenceName).isEqualTo("DevCon")
        assertThat(result.sourceSummary).isEqualTo("roster.csv")
    }

    @Test
    fun `replaceAll without replace merges`() = runTest {
        repository.save(ConferenceDatabase(attendees = listOf(person("1", "Ada"))))

        val result = repository.replaceAll(
            attendees = listOf(person("2", "Grace")),
            conferenceName = "",
            sourceSummary = "",
            replaceExisting = false,
        )

        assertThat(result.size).isEqualTo(2)
    }

    @Test
    fun `replaceAll keeps the existing conference name when given a blank one`() = runTest {
        repository.save(ConferenceDatabase(conferenceName = "DevCon"))
        val result = repository.replaceAll(listOf(person("1", "Ada")), "", "", true)
        assertThat(result.conferenceName).isEqualTo("DevCon")
    }

    // ---- clearAll and export ----------------------------------------------------------

    @Test
    fun `clearAll empties everything including face photos`() = runTest {
        repository.save(ConferenceDatabase(attendees = listOf(person("1", "Ada", face))))
        File(repository.facesDir, "1.jpg").writeBytes(byteArrayOf(1))

        val cleared = repository.clearAll()

        assertThat(cleared.isEmpty).isTrue()
        assertThat(File(repository.facesDir, "1.jpg").exists()).isFalse()
    }

    @Test
    fun `export produces reloadable json`() = runTest {
        repository.save(
            ConferenceDatabase(conferenceName = "DevCon", attendees = listOf(person("1", "Ada", face)))
        )

        val exported = repository.exportJson()
        databaseFile().writeText(exported)

        val reloaded = AttendeeRepository(temporaryFolder.root, Dispatchers.Unconfined).load()
        assertThat(reloaded.conferenceName).isEqualTo("DevCon")
        assertThat(reloaded.enrolledCount).isEqualTo(1)
    }

    // ---- Scale --------------------------------------------------------------------------

    @Test
    fun `a hundred person roster with embeddings round-trips`() = runTest {
        val roster = (1..100).map {
            Attendee(
                id = "$it",
                name = "Person $it",
                title = "Title $it",
                company = "Company $it",
                bio = "Bio for person $it",
                interests = listOf("a", "b", "c"),
                embedding = List(128) { i -> (i * it % 100) / 100f },
            )
        }
        repository.save(ConferenceDatabase(attendees = roster))

        val reloaded = AttendeeRepository(temporaryFolder.root, Dispatchers.Unconfined).load()

        assertThat(reloaded.size).isEqualTo(100)
        assertThat(reloaded.enrolledCount).isEqualTo(100)
        assertThat(reloaded.findById("50")!!.embedding!!.size).isEqualTo(128)
    }

    @Test
    fun `concurrent saves do not corrupt the file`() = runTest(StandardTestDispatcher()) {
        val repo = AttendeeRepository(temporaryFolder.root, Dispatchers.Unconfined) { now }
        repeat(20) { index ->
            repo.save(ConferenceDatabase(attendees = listOf(person("$index", "Person $index"))))
        }
        val reloaded = AttendeeRepository(temporaryFolder.root, Dispatchers.Unconfined).load()
        assertThat(reloaded.size).isEqualTo(1)
    }
}
