package ro.yt.downloader

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Fișier listat: fie în memoria aplicației ([file]), fie în Descărcări publice ([contentUri]).
 */
data class DownloadedFileEntry(
    val title: String,
    val mime: String,
    val file: File?,
    val contentUri: Uri?,
    val sortKey: Long,
    /** Pentru deduplicare cu MediaStore când [file] e null. */
    val sizeBytes: Long = -1L
) {
    fun effectiveSizeForDedupe(): Long = when {
        file != null -> file.length()
        sizeBytes >= 0L -> sizeBytes
        else -> 0L
    }

    /** Cheie stabilă pentru selecție (cale pe disc sau URI). */
    fun stableKey(): String =
        file?.absolutePath ?: (contentUri?.toString() ?: title)

    fun playUri(context: Context): Uri {
        contentUri?.let { return it }
        val f = file ?: throw IllegalStateException("no source")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            f
        )
    }
}
