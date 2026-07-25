package ai.sotto.assistant.ui.roster

import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.data.model.Attendee
import ai.sotto.assistant.data.model.ConferenceDatabase
import ai.sotto.assistant.di.AppContainer
import ai.sotto.assistant.vision.FaceMath
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * The people list, attendee detail, and face enrolment.
 *
 * Face enrolment is not in Design Doc 1 as a user-facing step — the doc assumes
 * embeddings arrive precomputed. In practice a roster is names and titles, never face
 * vectors, so the proof of concept has to be able to create them. This is the smallest
 * addition that makes the documented recognition flow actually testable: point the
 * camera at someone (or pick a photo), and the same on-device FaceNet model that runs
 * live produces the 128-float embedding the schema calls for.
 */
class RosterViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val database: ConferenceDatabase = ConferenceDatabase.empty(),
        val query: String = "",
        val filter: Filter = Filter.ALL,
        val error: AppError? = null,
        val busy: Boolean = false,
    ) {
        val visible: List<Attendee>
            get() {
                val q = query.trim().lowercase()
                return database.attendees
                    .filter { attendee ->
                        when (filter) {
                            Filter.ALL -> true
                            Filter.ENROLLED -> attendee.hasFace
                            Filter.NOT_ENROLLED -> !attendee.hasFace
                        }
                    }
                    .filter { attendee ->
                        q.isEmpty() ||
                            attendee.name.lowercase().contains(q) ||
                            attendee.company.lowercase().contains(q) ||
                            attendee.title.lowercase().contains(q) ||
                            attendee.interests.any { it.lowercase().contains(q) }
                    }
                    .sortedBy { it.name.lowercase() }
            }
    }

    enum class Filter { ALL, ENROLLED, NOT_ENROLLED }

    /** Enrolment progress for one person. */
    data class EnrolState(
        val attendee: Attendee? = null,
        val samples: List<Sample> = emptyList(),
        val capturing: Boolean = false,
        val message: String? = null,
        val error: AppError? = null,
        val saved: Boolean = false,
    ) {
        data class Sample(val embedding: FloatArray, val quality: Float, val preview: Bitmap?) {
            override fun equals(other: Any?) = this === other
            override fun hashCode() = System.identityHashCode(this)
        }

        val canSave: Boolean get() = samples.size >= MIN_SAMPLES && !capturing
        val averageQuality: Float
            get() = if (samples.isEmpty()) 0f else samples.map { it.quality }.average().toFloat()
    }

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(Filter.ALL)
    private val error = MutableStateFlow<AppError?>(null)
    private val busy = MutableStateFlow(false)

    val state: StateFlow<UiState> = combine(
        container.attendeeRepository.database,
        query,
        filter,
        error,
        busy,
    ) { database, q, f, e, b -> UiState(database, q, f, e, b) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _enrol = MutableStateFlow(EnrolState())
    val enrol: StateFlow<EnrolState> = _enrol.asStateFlow()

    init {
        viewModelScope.launch { container.attendeeRepository.load() }
    }

    fun onQueryChange(text: String) { query.value = text }

    fun onFilterChange(next: Filter) { filter.value = next }

    fun dismissError() { error.value = null }

    fun attendee(id: String): Attendee? = container.attendeeRepository.current.findById(id)

    fun addManually(name: String, title: String, company: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            busy.value = true
            try {
                container.attendeeRepository.upsert(
                    Attendee(
                        id = UUID.randomUUID().toString(),
                        name = name.trim(),
                        title = title.trim(),
                        company = company.trim(),
                    )
                )
                refreshPipeline()
            } catch (t: Throwable) {
                error.value = AppError.from(t)
            } finally {
                busy.value = false
            }
        }
    }

    fun update(attendee: Attendee) {
        viewModelScope.launch {
            runCatching { container.attendeeRepository.upsert(attendee) }
                .onFailure { error.value = AppError.from(it) }
            refreshPipeline()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching {
                container.attendeeRepository.delete(id)
                container.faceMatcher.invalidateFor(id)
            }.onFailure { error.value = AppError.from(it) }
            refreshPipeline()
        }
    }

    fun clearEverything() {
        viewModelScope.launch {
            runCatching { container.attendeeRepository.clearAll() }
                .onFailure { error.value = AppError.from(it) }
            container.faceMatcher.clearCache()
            refreshPipeline()
        }
    }

    // ---- Enrolment -------------------------------------------------------------

    fun beginEnrolment(attendeeId: String) {
        _enrol.value = EnrolState(attendee = attendee(attendeeId))
    }

    fun endEnrolment() {
        _enrol.value.samples.forEach { it.preview?.let { b -> if (!b.isRecycled) b.recycle() } }
        _enrol.value = EnrolState()
    }

    /**
     * Captures one sample from a live camera frame.
     *
     * Called on the camera analysis thread. The frame is recycled the instant this
     * returns, so the copy has to happen here and now — deferring it into the coroutine
     * would read freed pixels.
     */
    fun captureFromFrame(frame: Bitmap) {
        val pipeline = container.facePipeline
        if (pipeline == null) {
            _enrol.value = _enrol.value.copy(error = AppError.ModelUnavailable())
            return
        }
        if (_enrol.value.capturing || _enrol.value.samples.size >= MAX_SAMPLES) return

        val snapshot = try {
            frame.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            _enrol.value = _enrol.value.copy(error = AppError.from(t))
            return
        } ?: return

        _enrol.value = _enrol.value.copy(capturing = true)
        viewModelScope.launch {
            try {
                val sample = withContext(container.dispatchers.default) {
                    try {
                        pipeline.embedStill(snapshot)
                    } finally {
                        if (!snapshot.isRecycled) snapshot.recycle()
                    }
                }
                if (sample == null) {
                    _enrol.value = _enrol.value.copy(
                        capturing = false,
                        message = "No face in that frame — try again with their face fully visible.",
                    )
                    return@launch
                }
                if (sample.quality < MIN_QUALITY) {
                    if (!sample.crop.isRecycled) sample.crop.recycle()
                    _enrol.value = _enrol.value.copy(
                        capturing = false,
                        message = "Too dark or too blurry. More light, or move a little closer.",
                    )
                    return@launch
                }
                val added = _enrol.value.samples +
                    EnrolState.Sample(sample.embedding, sample.quality, sample.crop)
                _enrol.value = _enrol.value.copy(
                    samples = added,
                    capturing = false,
                    message = if (added.size >= MIN_SAMPLES) {
                        "Looking good — you can save, or capture a couple more for accuracy."
                    } else {
                        "Captured ${added.size} of $MIN_SAMPLES. Change angle slightly and capture again."
                    },
                )
            } catch (t: Throwable) {
                _enrol.value = _enrol.value.copy(capturing = false, error = AppError.from(t))
            }
        }
    }

    /** Captures samples from a photo the user picked. */
    fun captureFromUri(uri: Uri) {
        val pipeline = container.facePipeline
        if (pipeline == null) {
            _enrol.value = _enrol.value.copy(error = AppError.ModelUnavailable())
            return
        }
        _enrol.value = _enrol.value.copy(capturing = true)
        viewModelScope.launch {
            try {
                val bitmap = withContext(container.dispatchers.io) {
                    container.appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                    }
                }
                if (bitmap == null) {
                    _enrol.value = _enrol.value.copy(
                        capturing = false,
                        error = AppError.UnreadableFile("that photo"),
                    )
                    return@launch
                }
                val sample = withContext(container.dispatchers.default) {
                    val scaled = ai.sotto.assistant.vision.FaceImaging.downscale(bitmap, 1_280)
                    try {
                        pipeline.embedStill(scaled)
                    } finally {
                        if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
                if (sample == null) {
                    _enrol.value = _enrol.value.copy(
                        capturing = false,
                        message = "Couldn't find a face in that photo. Try one where they're facing the camera.",
                    )
                    return@launch
                }
                val added = _enrol.value.samples +
                    EnrolState.Sample(sample.embedding, sample.quality, sample.crop)
                _enrol.value = _enrol.value.copy(
                    samples = added,
                    capturing = false,
                    message = "Added a photo. ${added.size} of $MIN_SAMPLES captured.",
                )
            } catch (t: Throwable) {
                _enrol.value = _enrol.value.copy(capturing = false, error = AppError.from(t))
            }
        }
    }

    fun removeSample(index: Int) {
        val samples = _enrol.value.samples.toMutableList()
        if (index !in samples.indices) return
        samples.removeAt(index).preview?.let { if (!it.isRecycled) it.recycle() }
        _enrol.value = _enrol.value.copy(samples = samples)
    }

    /**
     * Averages the captured samples into a single 128-float embedding and stores it
     * against the attendee — this is the "precomputed embedding" the design doc's
     * matching step consumes.
     */
    fun saveEnrolment() {
        val current = _enrol.value
        val attendee = current.attendee ?: return
        if (!current.canSave) return

        viewModelScope.launch {
            busy.value = true
            try {
                val averaged = FaceMath.average(current.samples.map { it.embedding })
                if (averaged.isEmpty()) {
                    _enrol.value = current.copy(error = AppError.Unknown())
                    return@launch
                }

                val photoName = current.samples
                    .maxByOrNull { it.quality }
                    ?.preview
                    ?.let { savePreview(attendee.id, it) }

                container.attendeeRepository.setEmbedding(attendee.id, averaged, photoName)
                container.faceMatcher.invalidateFor(attendee.id)
                refreshPipeline()
                _enrol.value = current.copy(saved = true, message = "${attendee.name} can now be recognised.")
            } catch (t: Throwable) {
                _enrol.value = _enrol.value.copy(error = AppError.from(t))
            } finally {
                busy.value = false
            }
        }
    }

    fun clearEnrolment(attendeeId: String) {
        viewModelScope.launch {
            runCatching {
                container.attendeeRepository.clearEmbedding(attendeeId)
                container.faceMatcher.invalidateFor(attendeeId)
            }.onFailure { error.value = AppError.from(it) }
            refreshPipeline()
        }
    }

    fun photoFile(attendee: Attendee): File? = attendee.photoPath
        ?.let { File(container.attendeeRepository.facesDir, it) }
        ?.takeIf { it.exists() }

    private suspend fun savePreview(attendeeId: String, bitmap: Bitmap): String? =
        withContext(container.dispatchers.io) {
            runCatching {
                val name = "$attendeeId.jpg"
                val file = File(container.attendeeRepository.facesDir, name)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                name
            }.getOrNull()
        }

    private fun refreshPipeline() {
        container.facePipeline?.setAttendees(container.attendeeRepository.current.attendees)
    }

    companion object {
        const val MIN_SAMPLES = 3
        const val MAX_SAMPLES = 6
        const val MIN_QUALITY = 0.22f
    }
}
