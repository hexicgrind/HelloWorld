package ai.sotto.assistant.ui.upload

import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.data.local.ApiService
import ai.sotto.assistant.di.AppContainer
import ai.sotto.assistant.domain.DocumentExtractor
import ai.sotto.assistant.domain.EnrichmentUseCase
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Backs the "Prepare Conference Data" screen. */
class UploadViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val sources: List<DocumentExtractor.Source> = emptyList(),
        val pastedText: String = "",
        val contextHint: String = "",
        val webGrounding: Boolean = false,
        val replaceExisting: Boolean = true,
        val running: Boolean = false,
        val stageLabel: String = "",
        val stageDetail: String = "",
        val progress: Float = 0f,
        val error: AppError? = null,
        val result: EnrichmentUseCase.Progress.Done? = null,
        val existingCount: Int = 0,
        val hasGeminiKey: Boolean = false,
    ) {
        val canRun: Boolean
            get() = !running && hasGeminiKey &&
                (sources.isNotEmpty() || pastedText.trim().length >= MIN_PASTE_LENGTH)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        container.attendeeRepository.database
            .onEach { db -> _state.value = _state.value.copy(existingCount = db.size) }
            .launchIn(viewModelScope)
        container.keyStore.state
            .onEach { keys ->
                _state.value = _state.value.copy(hasGeminiKey = keys.has(ApiService.GEMINI))
            }
            .launchIn(viewModelScope)
    }

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = mutableListOf<DocumentExtractor.Source>()
            var failure: AppError? = null
            uris.take(MAX_FILES).forEach { uri ->
                try {
                    added += container.documentExtractor.extract(uri)
                } catch (e: AppError) {
                    failure = e
                } catch (t: Throwable) {
                    failure = AppError.from(t)
                }
            }
            _state.value = _state.value.copy(
                sources = (_state.value.sources + added).take(MAX_FILES),
                error = failure,
                result = null,
            )
        }
    }

    fun removeSource(source: DocumentExtractor.Source) {
        _state.value = _state.value.copy(sources = _state.value.sources - source)
    }

    fun onPastedTextChange(text: String) {
        _state.value = _state.value.copy(pastedText = text, result = null)
    }

    fun onContextHintChange(text: String) {
        _state.value = _state.value.copy(contextHint = text)
    }

    fun setWebGrounding(enabled: Boolean) {
        _state.value = _state.value.copy(webGrounding = enabled)
    }

    fun setReplaceExisting(replace: Boolean) {
        _state.value = _state.value.copy(replaceExisting = replace)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun dismissResult() {
        _state.value = _state.value.copy(result = null)
    }

    /** Design Doc 1 § Offline Processing: the "Prepare Conference Data" action. */
    fun prepare() {
        if (!_state.value.canRun) return
        val snapshot = _state.value

        val allSources = buildList {
            addAll(snapshot.sources)
            val pasted = snapshot.pastedText.trim()
            if (pasted.length >= MIN_PASTE_LENGTH) {
                add(container.documentExtractor.fromPastedText(pasted))
            }
        }

        _state.value = snapshot.copy(running = true, error = null, result = null, progress = 0f)

        job?.cancel()
        job = viewModelScope.launch {
            container.enrichmentUseCase
                .run(
                    sources = allSources,
                    contextHint = snapshot.contextHint,
                    webGrounding = snapshot.webGrounding,
                    replaceExisting = snapshot.replaceExisting,
                )
                .collect { progress ->
                    when (progress) {
                        is EnrichmentUseCase.Progress.Stage -> {
                            _state.value = _state.value.copy(
                                stageLabel = progress.label,
                                stageDetail = progress.detail,
                                progress = progress.fraction,
                            )
                        }
                        is EnrichmentUseCase.Progress.Done -> {
                            container.facePipeline?.setAttendees(progress.database.attendees)
                            _state.value = _state.value.copy(
                                running = false,
                                progress = 1f,
                                result = progress,
                                sources = emptyList(),
                                pastedText = "",
                            )
                        }
                        is EnrichmentUseCase.Progress.Failed -> {
                            _state.value = _state.value.copy(
                                running = false,
                                progress = 0f,
                                error = progress.error,
                            )
                        }
                    }
                }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false, progress = 0f)
    }

    companion object {
        const val MAX_FILES = 8
        const val MIN_PASTE_LENGTH = 12
    }
}
