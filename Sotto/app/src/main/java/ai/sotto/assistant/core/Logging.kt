package ai.sotto.assistant.core

import android.util.Log
import ai.sotto.assistant.BuildConfig

/**
 * Thin logging seam. Verbose/debug logs are compiled to no-ops in release, and
 * nothing here ever logs an API key or a transcript body.
 */
object SLog {
    private const val TAG = "Sotto"

    fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[$tag] ${message()}")
    }

    fun i(tag: String, message: String) = Log.i(TAG, "[$tag] $message")

    fun w(tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.w(TAG, "[$tag] $message", t) else Log.w(TAG, "[$tag] $message")
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, "[$tag] $message", t) else Log.e(TAG, "[$tag] $message")
    }

    /** Renders a secret as `AIza…9f2c` so support logs stay useful but safe. */
    fun redact(secret: String?): String = when {
        secret.isNullOrBlank() -> "<none>"
        secret.length <= 8 -> "<set>"
        else -> "${secret.take(4)}…${secret.takeLast(4)}"
    }
}
