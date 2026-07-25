package ai.sotto.assistant.domain

import ai.sotto.assistant.core.AppError
import ai.sotto.assistant.core.SLog
import ai.sotto.assistant.data.remote.GeminiRestClient.SourcePart
import ai.sotto.assistant.vision.FaceImaging
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Turns whatever the user picked into parts Gemini can read.
 *
 * Design Doc 1 § User Input: "Supported formats include CSV attendee lists, PDF
 * documents, images of badge rosters, or plain text. The app accepts any unstructured
 * input."
 *
 * PDFs and images go across as inline binary parts — Gemini reads both natively, which
 * beats doing our own OCR or PDF text extraction and then losing the layout that makes
 * a badge roster legible in the first place.
 */
class DocumentExtractor(
    private val context: Context,
    private val io: CoroutineDispatcher,
) {
    data class Source(
        val part: SourcePart,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Int,
    ) {
        val isBinary: Boolean get() = part is SourcePart.Binary
    }

    suspend fun extract(uri: Uri): Source = withContext(io) {
        val name = displayName(uri)
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()

        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw AppError.UnreadableFile(name)
        } catch (e: AppError) {
            throw e
        } catch (t: Throwable) {
            throw AppError.UnreadableFile(name, t)
        }

        if (bytes.isEmpty()) throw AppError.UnreadableFile(name)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AppError.ServiceFailure(
                "Upload",
                "\"$name\" is ${bytes.size / 1_000_000} MB. Please use a file under ${MAX_FILE_BYTES / 1_000_000} MB.",
            )
        }

        when {
            mime.startsWith("image/") || looksLikeImage(name) -> {
                val (jpeg, outMime) = compressImage(bytes, name)
                Source(SourcePart.Binary(outMime, jpeg), name, outMime, jpeg.size)
            }

            mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> {
                Source(SourcePart.Binary("application/pdf", bytes), name, "application/pdf", bytes.size)
            }

            else -> {
                val text = decodeText(bytes)
                    ?: throw AppError.UnreadableFile(name)
                if (text.isBlank()) throw AppError.UnreadableFile(name)
                Source(SourcePart.Text(text), name, "text/plain", text.length)
            }
        }
    }

    fun fromPastedText(text: String): Source =
        Source(SourcePart.Text(text), "Pasted text", "text/plain", text.length)

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "Selected file"

    /**
     * Re-encodes an image as a reasonably sized JPEG. A modern phone camera photo of a
     * roster is 4-8 MB; at that size it's slow to upload and no more readable than a
     * 1600 px version.
     */
    private fun compressImage(bytes: ByteArray, name: String): Pair<ByteArray, String> {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw AppError.UnreadableFile(name)
        return try {
            val scaled = FaceImaging.downscale(bitmap, MAX_IMAGE_EDGE)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            out.toByteArray() to "image/jpeg"
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Decodes as UTF-8, falling back to Latin-1 for the CSVs Excel still produces. */
    private fun decodeText(bytes: ByteArray): String? {
        val utf8 = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        if (utf8 != null && !utf8.contains('�')) return utf8.stripBom()
        val latin1 = runCatching { String(bytes, Charsets.ISO_8859_1) }.getOrNull()
        return (latin1 ?: utf8)?.stripBom()
    }

    /** Strips a UTF-8 byte-order mark, which Excel's CSV export routinely leaves behind. */
    private fun String.stripBom(): String = removePrefix("\uFEFF")

    private fun looksLikeImage(name: String): Boolean =
        IMAGE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    companion object {
        private const val TAG = "DocumentExtractor"
        /**
         * Gemini's inline-data limit applies to the whole request *after* base64
         * encoding, which inflates by 4/3. Capping the raw file at 12 MB keeps even a
         * single maximum-size upload comfortably inside the 20 MB request ceiling.
         */
        const val MAX_FILE_BYTES = 12 * 1024 * 1024
        const val MAX_IMAGE_EDGE = 1_600
        const val JPEG_QUALITY = 88

        private val IMAGE_EXTENSIONS =
            listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp")

        /** MIME filter for the system file picker. */
        val PICKER_MIME_TYPES = arrayOf(
            "text/*",
            "application/pdf",
            "image/*",
            "application/csv",
            "application/vnd.ms-excel",
            "application/json",
        )
    }
}
