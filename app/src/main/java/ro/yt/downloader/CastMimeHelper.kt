package ro.yt.downloader

import androidx.annotation.StringRes
import java.io.File

/**
 * Ghid pentru Google Default Media Receiver (nu validează codec-ul din container).
 * MKV / HEVC / multe MOV pot eșua pe TV chiar cu MIME „corect”.
 */
object CastMimeHelper {

    private val SUPPORTED_VIDEO = setOf("video/mp4", "video/webm")
    private val SUPPORTED_AUDIO = setOf(
        "audio/mpeg", "audio/mp3", "audio/aac", "audio/mp4",
        "audio/flac", "audio/wav", "audio/x-wav", "audio/ogg", "audio/webm"
    )

    fun isDefaultReceiverSupported(mime: String): Boolean {
        val m = mime.lowercase().trim()
        return m in SUPPORTED_VIDEO || m in SUPPORTED_AUDIO
    }

    data class MimeResult(
        val mime: String,
        val supported: Boolean,
        @StringRes val hintResId: Int? = null
    )

    fun forFile(file: File): MimeResult {
        val ext = file.extension.lowercase()
        return when (ext) {
            "mp4", "m4v" -> MimeResult("video/mp4", true)
            "webm" -> MimeResult("video/webm", true)
            "mp3" -> MimeResult("audio/mpeg", true)
            "aac", "m4a" -> MimeResult("audio/aac", true)
            "flac" -> MimeResult("audio/flac", true)
            "wav" -> MimeResult("audio/wav", true)
            "ogg" -> MimeResult("audio/ogg", true)
            "mkv" -> MimeResult("video/x-matroska", false, R.string.cast_hint_mkv)
            "avi" -> MimeResult("video/x-msvideo", false, R.string.cast_hint_avi)
            "mov" -> MimeResult("video/quicktime", false, R.string.cast_hint_mov)
            "ts", "m2ts" -> MimeResult("video/mp2t", false, R.string.cast_hint_ts)
            "wmv" -> MimeResult("video/x-ms-wmv", false, R.string.cast_hint_wmv)
            else -> MimeResult("application/octet-stream", false, R.string.cast_hint_unknown)
        }
    }
}
