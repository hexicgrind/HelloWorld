package ai.sotto.assistant.data.local

import ai.sotto.assistant.core.SLog
import ai.sotto.assistant.data.model.Attendee
import ai.sotto.assistant.data.model.ConferenceDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The local attendee database, persisted as a single JSON file exactly as the design
 * doc specifies. Held in memory for the whole app lifetime because Design Doc 1
 * § Runtime Flow requires "the app loads the precomputed attendee database into
 * memory" and budgets only 5-10 ms for a lookup.
 *
 * Writes are serialised through a mutex and go through a temp file + atomic rename,
 * so a crash mid-save can never leave a truncated database behind.
 */
class AttendeeRepository(
    private val storageDir: File,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        isLenient = true
    }

    private val writeLock = Mutex()

    private val _database = MutableStateFlow(ConferenceDatabase.empty())
    val database: StateFlow<ConferenceDatabase> = _database.asStateFlow()

    val current: ConferenceDatabase get() = _database.value

    private val file: File get() = File(storageDir, DATABASE_FILE_NAME)
    private val tempFile: File get() = File(storageDir, "$DATABASE_FILE_NAME.tmp")

    /** Directory holding enrolment photos; created lazily. */
    val facesDir: File get() = File(storageDir, FACES_DIR).apply { if (!exists()) mkdirs() }

    /**
     * Reads the database off disk. A corrupt file is quarantined rather than deleted
     * so the user can still recover it, and the app starts with an empty database
     * (which Design Doc 1 § Error Handling explicitly supports).
     */
    suspend fun load(): ConferenceDatabase = withContext(io) {
        if (!storageDir.exists()) storageDir.mkdirs()
        val f = file
        if (!f.exists() || f.length() == 0L) {
            _database.value = ConferenceDatabase.empty()
            return@withContext _database.value
        }
        val parsed = runCatching { json.decodeFromString<ConferenceDatabase>(f.readText()) }
            .getOrElse { t ->
                SLog.e(TAG, "Attendee database is unreadable; quarantining it", t)
                runCatching { f.copyTo(File(storageDir, "$DATABASE_FILE_NAME.corrupt"), overwrite = true) }
                ConferenceDatabase.empty()
            }
        _database.value = parsed
        SLog.i(TAG, "Loaded ${parsed.size} attendees (${parsed.enrolledCount} with faces)")
        parsed
    }

    suspend fun save(db: ConferenceDatabase): ConferenceDatabase = withContext(io) {
        writeLock.withLock {
            if (!storageDir.exists()) storageDir.mkdirs()
            val stamped = db.copy(
                schemaVersion = ConferenceDatabase.CURRENT_SCHEMA_VERSION,
                createdAt = if (db.createdAt == 0L) clock() else db.createdAt,
                updatedAt = clock(),
            )
            val tmp = tempFile
            tmp.writeText(json.encodeToString(ConferenceDatabase.serializer(), stamped))
            if (!tmp.renameTo(file)) {
                // renameTo can fail on some filesystems; fall back to a copy.
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            _database.value = stamped
            stamped
        }
    }

    suspend fun replaceAll(
        attendees: List<Attendee>,
        conferenceName: String,
        sourceSummary: String,
        replaceExisting: Boolean,
    ): ConferenceDatabase {
        val merged = current
            .copy(
                conferenceName = conferenceName.ifBlank { current.conferenceName },
                sourceSummary = sourceSummary,
            )
            .mergeWith(attendees, replaceExisting)
        return save(merged)
    }

    suspend fun upsert(attendee: Attendee): ConferenceDatabase = save(current.upsert(attendee))

    suspend fun delete(id: String): ConferenceDatabase {
        current.findById(id)?.photoPath?.let { path ->
            withContext(io) { runCatching { File(facesDir, path).delete() } }
        }
        return save(current.remove(id))
    }

    /** Attaches a freshly-computed face embedding to a person. */
    suspend fun setEmbedding(
        id: String,
        embedding: FloatArray,
        photoFileName: String?,
    ): ConferenceDatabase {
        val attendee = current.findById(id) ?: return current
        return upsert(
            attendee.copy(
                embedding = embedding.toList(),
                photoPath = photoFileName ?: attendee.photoPath,
                enrolledAt = clock(),
            )
        )
    }

    suspend fun clearEmbedding(id: String): ConferenceDatabase {
        val attendee = current.findById(id) ?: return current
        attendee.photoPath?.let { withContext(io) { runCatching { File(facesDir, it).delete() } } }
        return upsert(attendee.copy(embedding = null, photoPath = null, enrolledAt = null))
    }

    suspend fun clearAll(): ConferenceDatabase = withContext(io) {
        runCatching { facesDir.deleteRecursively() }
        save(ConferenceDatabase.empty())
    }

    /** Serialised JSON, for the "export / share database" affordance. */
    suspend fun exportJson(): String = withContext(io) {
        json.encodeToString(ConferenceDatabase.serializer(), current)
    }

    companion object {
        private const val TAG = "AttendeeRepo"
        const val DATABASE_FILE_NAME = "conference_database.json"
        const val FACES_DIR = "faces"
    }
}
