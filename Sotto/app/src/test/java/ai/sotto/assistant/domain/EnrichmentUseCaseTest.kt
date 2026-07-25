package ai.sotto.assistant.domain

import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.data.local.AttendeeRepository
import ai.sotto.assistant.data.local.SettingsRepository
import ai.sotto.assistant.data.local.SottoSettings
import ai.sotto.assistant.data.model.Attendee
import ai.sotto.assistant.data.remote.GeminiRestClient
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/** Design Doc 1 § Offline Processing, end to end with a faked Gemini. */
@OptIn(ExperimentalCoroutinesApi::class)
class EnrichmentUseCaseTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var gemini: GeminiRestClient
    private lateinit var repository: AttendeeRepository
    private lateinit var settings: SettingsRepository
    private lateinit var useCase: EnrichmentUseCase

    private var now = 1_000L

    @Before
    fun setUp() {
        gemini = mockk()
        settings = mockk()
        coEvery { settings.current() } returns SottoSettings()
        repository = AttendeeRepository(temporaryFolder.root, Dispatchers.Unconfined) { now }
        useCase = EnrichmentUseCase(gemini, repository, settings)
    }

    private fun source(name: String = "roster.csv", text: String = "Ada Lovelace, CTO") =
        DocumentExtractor.Source(
            part = GeminiRestClient.SourcePart.Text(text),
            displayName = name,
            mimeType = "text/plain",
            sizeBytes = text.length,
        )

    private fun attendee(name: String) = Attendee(id = UUID.randomUUID().toString(), name = name)

    @Test
    fun `a successful run reports progress then done`() = runTest {
        coEvery { gemini.enrich(any(), any(), any(), any()) } returns
            GeminiRestClient.EnrichmentResult(
                attendees = listOf(attendee("Ada Lovelace"), attendee("Grace Hopper")),
                conferenceName = "DevCon",
                notes = "Two people found.",
            )

        val emissions = useCase.run(listOf(source()), clock = { now.also { now += 1_000 } }).toList()

        val stages = emissions.filterIsInstance<EnrichmentUseCase.Progress.Stage>()
        val done = emissions.filterIsInstance<EnrichmentUseCase.Progress.Done>().single()

        assertThat(stages).isNotEmpty()
        // Progress must move forward, never backward — a bar that jumps around reads as
        // broken.
        stages.zipWithNext().forEach { (a, b) -> assertThat(b.fraction).isAtLeast(a.fraction) }

        assertThat(done.added).isEqualTo(2)
        assertThat(done.database.size).isEqualTo(2)
        assertThat(done.database.conferenceName).isEqualTo("DevCon")
        assertThat(done.notes).isEqualTo("Two people found.")
    }

    @Test
    fun `results are persisted to the repository`() = runTest {
        coEvery { gemini.enrich(any(), any(), any(), any()) } returns
            GeminiRestClient.EnrichmentResult(listOf(attendee("Ada")), "DevCon", "")

        useCase.run(listOf(source())).toList()

        assertThat(repository.current.size).isEqualTo(1)
        assertThat(repository.current.attendees.single().name).isEqualTo("Ada")
    }

    @Test
    fun `no sources fails immediately without calling Gemini`() = runTest {
        val emissions = useCase.run(emptyList()).toList()
        val failure = emissions.filterIsInstance<EnrichmentUseCase.Progress.Failed>().single()

        assertThat(failure.error).isInstanceOf(AppError.NoUsableData::class.java)
        coVerify(exactly = 0) { gemini.enrich(any(), any(), any(), any()) }
    }

    @Test
    fun `an API failure is reported rather than thrown`() = runTest {
        coEvery { gemini.enrich(any(), any(), any(), any()) } throws AppError.Unauthorized("Gemini")

        val emissions = useCase.run(listOf(source())).toList()
        val failure = emissions.filterIsInstance<EnrichmentUseCase.Progress.Failed>().single()

        assertThat(failure.error).isInstanceOf(AppError.Unauthorized::class.java)
        assertThat(emissions.filterIsInstance<EnrichmentUseCase.Progress.Done>()).isEmpty()
    }

    @Test
    fun `an unexpected throwable is wrapped rather than escaping`() = runTest {
        coEvery { gemini.enrich(any(), any(), any(), any()) } throws IllegalStateException("boom")

        val emissions = useCase.run(listOf(source())).toList()
        val failure = emissions.filterIsInstance<EnrichmentUseCase.Progress.Failed>().single()

        assertThat(failure.error).isInstanceOf(AppError.Unknown::class.java)
    }

    @Test
    fun `merging keeps previously enrolled faces`() = runTest {
        repository.save(
            ai.sotto.assistant.data.model.ConferenceDatabase(
                attendees = listOf(
                    Attendee(id = "1", name = "Ada", company = "Acme", embedding = List(128) { 0.1f })
                )
            )
        )

        coEvery { gemini.enrich(any(), any(), any(), any()) } returns
            GeminiRestClient.EnrichmentResult(
                attendees = listOf(Attendee(id = "new", name = "Ada", company = "Acme", title = "CTO")),
                conferenceName = "",
                notes = "",
            )

        useCase.run(listOf(source()), replaceExisting = true).toList()

        val ada = repository.current.attendees.single()
        assertThat(ada.hasFace).isTrue()
        assertThat(ada.title).isEqualTo("CTO")
    }

    @Test
    fun `the stage detail names the files being read`() = runTest {
        coEvery { gemini.enrich(any(), any(), any(), any()) } returns
            GeminiRestClient.EnrichmentResult(listOf(attendee("Ada")), "", "")

        val emissions = useCase.run(
            listOf(source("roster.csv"), source("badges.jpg"))
        ).toList()

        val first = emissions.filterIsInstance<EnrichmentUseCase.Progress.Stage>().first()
        assertThat(first.detail).contains("roster.csv")
        assertThat(first.detail).contains("badges.jpg")
    }

    @Test
    fun `the enrichment model from settings is used`() = runTest {
        coEvery { settings.current() } returns SottoSettings(enrichmentModel = "gemini-custom")
        coEvery { gemini.enrich(any(), any(), any(), any()) } returns
            GeminiRestClient.EnrichmentResult(listOf(attendee("Ada")), "", "")

        useCase.run(listOf(source()), contextHint = "DevCon", webGrounding = true).toList()

        coVerify {
            gemini.enrich(
                parts = any(),
                model = "gemini-custom",
                contextHint = "DevCon",
                webGrounding = true,
            )
        }
    }

    @Test
    fun `elapsed time is reported`() = runTest {
        coEvery { gemini.enrich(any(), any(), any(), any()) } returns
            GeminiRestClient.EnrichmentResult(listOf(attendee("Ada")), "", "")

        var tick = 0L
        val emissions = useCase.run(listOf(source()), clock = { tick.also { tick += 5_000 } }).toList()
        val done = emissions.filterIsInstance<EnrichmentUseCase.Progress.Done>().single()

        assertThat(done.elapsedMs).isGreaterThan(0L)
    }
}
