package ai.sotto.assistant.diagnostics

import ai.sotto.assistant.BuildConfig
import ai.sotto.assistant.vision.BlazeFaceAnchors
import ai.sotto.assistant.vision.MediaPipeFaceDetector
import ai.sotto.assistant.vision.TfLiteFaceDetector
import ai.sotto.assistant.vision.TfLiteFaceEmbedder
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Answers the question "why won't the face models load on *this* phone?" without
 * needing a cable, a laptop, or logcat.
 *
 * This exists because Sotto shipped a build whose models failed to load on a real device
 * while every test passed, and the app could only say "something went wrong". Guessing at
 * causes from a screenshot cost a release. The app should be able to explain itself.
 *
 * Everything here is read-only and safe to run at any time.
 */
object ModelDiagnostics {

    data class Check(
        val name: String,
        val status: Status,
        val detail: String,
    ) {
        enum class Status { OK, WARN, FAIL, INFO }
    }

    data class Report(val checks: List<Check>) {
        val failures: List<Check> get() = checks.filter { it.status == Check.Status.FAIL }
        val allGood: Boolean get() = failures.isEmpty()

        /** Plain text for the clipboard, so the whole report can be pasted into a message. */
        fun asText(): String = buildString {
            appendLine("Sotto diagnostics")
            appendLine("=================")
            checks.forEach { check ->
                appendLine()
                appendLine("[${check.status}] ${check.name}")
                check.detail.lineSequence().forEach { appendLine("    $it") }
            }
        }
    }

    /**
     * @param pipelineSummary what the running app actually ended up with, as opposed to
     *        what a fresh probe can do. This is the line that answers "is face detection
     *        working right now".
     */
    suspend fun run(
        context: Context,
        io: CoroutineDispatcher,
        pipelineSummary: (() -> String)? = null,
    ): Report = withContext(io) {
        val checks = mutableListOf<Check>()

        checks += buildInfo()
        checks += deviceInfo()
        checks += pageSize()
        checks += abiInfo()
        checks += assetCheck(context, MediaPipeFaceDetector.MODEL_ASSET)
        checks += assetCheck(context, TfLiteFaceEmbedder.MODEL_ASSET)
        checks += nativeLibraryCheck(context)
        checks += detectorProbe(context)
        checks += fallbackDetectorProbe(context)
        checks += embedderProbe(context)

        pipelineSummary?.let { summary ->
            val text = runCatching { summary() }.getOrElse { "could not be determined" }
            checks += Check(
                name = "Face pipeline in use",
                status = if (text.contains("unavailable", ignoreCase = true) ||
                    text.contains("not started", ignoreCase = true)
                ) Check.Status.WARN else Check.Status.OK,
                detail = text,
            )
        }

        Report(checks)
    }

    private fun buildInfo() = Check(
        name = "Sotto build",
        status = Check.Status.INFO,
        detail = buildString {
            appendLine("version ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("applicationId ${BuildConfig.APPLICATION_ID}")
            append("build type ${BuildConfig.BUILD_TYPE}")
        },
    )

    private fun deviceInfo() = Check(
        name = "Device",
        status = Check.Status.INFO,
        detail = buildString {
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append("build ${Build.DISPLAY}")
        },
    )

    /**
     * The 16 KB page size question, answered directly rather than inferred.
     *
     * A device reporting 16384 here cannot load 4 KB-aligned native libraries at all.
     */
    private fun pageSize(): Check {
        val bytes = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1L)
        return Check(
            name = "Memory page size",
            status = Check.Status.INFO,
            detail = when {
                bytes <= 0 -> "could not be determined"
                else -> "$bytes bytes (${bytes / 1024} KB)"
            },
        )
    }

    private fun abiInfo() = Check(
        name = "Supported ABIs",
        status = Check.Status.INFO,
        detail = Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "unknown" },
    )

    /**
     * Confirms the model is in the APK *and* stored uncompressed. `openFd` fails on a
     * compressed asset, which is exactly what the memory-mapped loader needs.
     */
    private fun assetCheck(context: Context, asset: String): Check = try {
        context.assets.openFd(asset).use { fd ->
            Check(
                name = "Asset $asset",
                status = Check.Status.OK,
                detail = "present, uncompressed, ${fd.declaredLength} bytes " +
                    "(offset ${fd.startOffset})",
            )
        }
    } catch (t: Throwable) {
        val exists = runCatching {
            context.assets.open(asset).use { it.available() }
        }.getOrNull()
        Check(
            name = "Asset $asset",
            status = Check.Status.FAIL,
            detail = if (exists != null) {
                "present but COMPRESSED — cannot be memory-mapped. ${describe(t)}"
            } else {
                "MISSING from the APK. ${describe(t)}"
            },
        )
    }

    /**
     * Loads each native library by name. This separates "the .so cannot be loaded at
     * all" from "the library loaded but the model was rejected", which are very
     * different problems with very different fixes.
     */
    private fun nativeLibraryCheck(context: Context): Check {
        val results = NATIVE_LIBRARIES.map { lib ->
            try {
                System.loadLibrary(lib)
                "$lib: loaded"
            } catch (t: Throwable) {
                "$lib: FAILED — ${describe(t)}"
            }
        }
        val failed = results.any { it.contains("FAILED") }
        return Check(
            name = "Native libraries",
            status = if (failed) Check.Status.FAIL else Check.Status.OK,
            detail = results.joinToString("\n"),
        )
    }

    private fun detectorProbe(context: Context): Check = probe("Face detection model") {
        MediaPipeFaceDetector.create(context).use { "MediaPipe face detector created" }
    }

    /**
     * The LiteRT path. If MediaPipe is broken but this works, face detection still
     * functions — so this check is what decides whether the feature is actually down.
     */
    private fun fallbackDetectorProbe(context: Context): Check =
        probe("Face detection fallback (LiteRT)") {
            TfLiteFaceDetector.create(context).use {
                "BlazeFace on LiteRT created, ${BlazeFaceAnchors.anchors.size} anchors"
            }
        }

    private fun embedderProbe(context: Context): Check = probe("Face recognition model") {
        TfLiteFaceEmbedder.create(context).use { embedder ->
            "FaceNet created, ${embedder.dimensions}-dimensional output"
        }
    }

    private fun probe(name: String, block: () -> String): Check = try {
        Check(name, Check.Status.OK, block())
    } catch (t: Throwable) {
        Check(name, Check.Status.FAIL, fullDetail(t))
    }

    /** Unwraps the cause chain — the useful message is rarely on the outermost throwable. */
    private fun fullDetail(t: Throwable): String = buildString {
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            appendLine(if (depth == 0) describe(current) else "caused by: ${describe(current)}")
            current = current.cause.takeIf { it !== current }
            depth++
        }
        val frames = t.stackTrace.take(STACK_FRAMES)
        if (frames.isNotEmpty()) {
            appendLine("at:")
            frames.forEach { appendLine("  $it") }
        }
    }.trim()

    private fun describe(t: Throwable): String {
        val message = t.message?.replace('\n', ' ')?.trim().orEmpty()
        return if (message.isBlank()) {
            t.javaClass.name
        } else {
            "${t.javaClass.name}: ${message.take(400)}"
        }
    }

    private const val MAX_CAUSE_DEPTH = 5
    private const val STACK_FRAMES = 8

    private val NATIVE_LIBRARIES = listOf(
        "tensorflowlite_jni",
        "mediapipe_tasks_vision_jni",
    )
}
