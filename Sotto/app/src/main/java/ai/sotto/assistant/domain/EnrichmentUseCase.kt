package ai.sotto.assistant.domain

import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.core.SLog
import ai.sotto.assistant.data.local.AttendeeRepository
import ai.sotto.assistant.data.local.SettingsRepository
import ai.sotto.assistant.data.model.ConferenceDatabase
import ai.sotto.assistant.data.remote.GeminiRestClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * "Prepare Conference Data" — Design Doc 1 § Offline Processing.
 *
 * "The enrichment happens once during setup, not in real time. Typical processing time
 * for a hundred-person attendee list is two to five minutes."
 *
 * Emits [Progress] the whole way through, because a multi-minute silent spinner is how
 * you convince someone the app has crashed.
 */
class EnrichmentUseCase(
    private val gemini: GeminiRestClient,
    private val repository: AttendeeRepository,
    private val settings: SettingsRepository,
) {
    sealed interface Progress {
        data class Stage(val label: String, val detail: String, val fraction: Float) : Progress
        data class Done(
            val database: ConferenceDatabase,
            val added: Int,
            val notes: String,
            val elapsedMs: Long,
        ) : Progress
        data class Failed(val error: AppError) : Progress
    }

    /**
     * Runs the pass end to end.
     *
     * @param replaceExisting when false, the results merge into the existing roster and
     *        any face enrolments already made are preserved.
     */
    fun run(
        sources: List<DocumentExtractor.Source>,
        contextHint: String = "",
        webGrounding: Boolean = false,
        replaceExisting: Boolean = true,
        clock: () -> Long = System::currentTimeMillis,
    ): Flow<Progress> = flow {
        val startedAt = clock()

        if (sources.isEmpty()) {
            emit(Progress.Failed(AppError.NoUsableData()))
            return@flow
        }

        emit(
            Progress.Stage(
                label = "Reading your files",
                detail = sources.joinToString(", ") { it.displayName }.take(120),
                fraction = 0.10f,
            )
        )

        val settingsSnapshot = try {
            settings.current()
        } catch (t: Throwable) {
            emit(Progress.Failed(AppError.from(t)))
            return@flow
        }

        emit(
            Progress.Stage(
                label = "Asking Gemini to extract and enrich",
                detail = "This usually takes two to five minutes for a large list. " +
                    "You can leave this screen open.",
                fraction = 0.30f,
            )
        )

        val result = try {
            gemini.enrich(
                parts = sources.map { it.part },
                model = settingsSnapshot.enrichmentModel,
                contextHint = contextHint,
                webGrounding = webGrounding,
            )
        } catch (e: AppError) {
            SLog.w(TAG, "Enrichment failed: ${e.userMessage}")
            emit(Progress.Failed(e))
            return@flow
        } catch (t: Throwable) {
            emit(Progress.Failed(AppError.from(t, GeminiRestClient.SERVICE)))
            return@flow
        }

        emit(
            Progress.Stage(
                label = "Saving to your phone",
                detail = "${result.attendees.size} " +
                    if (result.attendees.size == 1) "person found" else "people found",
                fraction = 0.85f,
            )
        )

        val saved = try {
            repository.replaceAll(
                attendees = result.attendees,
                conferenceName = result.conferenceName,
                sourceSummary = sources.joinToString(", ") { it.displayName }.take(300),
                replaceExisting = replaceExisting,
            )
        } catch (t: Throwable) {
            emit(Progress.Failed(AppError.from(t)))
            return@flow
        }

        SLog.i(TAG, "Enrichment complete: ${saved.size} attendees in ${clock() - startedAt} ms")
        emit(
            Progress.Done(
                database = saved,
                added = result.attendees.size,
                notes = result.notes,
                elapsedMs = clock() - startedAt,
            )
        )
    }

    private companion object {
        const val TAG = "Enrichment"
    }
}
