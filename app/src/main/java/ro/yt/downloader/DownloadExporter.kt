package ro.yt.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Copiază un fișier în **Descărcări/DLPulse** ca să fie vizibil în managerul de fișiere / „Descărcări”.
 */
object DownloadExporter {

    private const val SUBFOLDER = "DLPulse"

    fun copyToPublicDownloads(context: Context, source: File): Uri? {
        if (!source.isFile || !source.canRead() || source.length() == 0L) {
            return null
        }
        val mime = guessMime(source.name)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertMediaStore(context, source, mime)
        } else {
            copyLegacyPublic(source)
        }
    }

    private fun guessMime(name: String): String {
        return when {
            name.endsWith(".mp4", true) -> "video/mp4"
            name.endsWith(".webm", true) -> "video/webm"
            name.endsWith(".mkv", true) -> "video/x-matroska"
            name.endsWith(".mp3", true) -> "audio/mpeg"
            name.endsWith(".m4a", true) -> "audio/mp4"
            name.endsWith(".opus", true) -> "audio/opus"
            else -> "application/octet-stream"
        }
    }

    private fun insertMediaStore(context: Context, source: File, mime: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBFOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { it.copyTo(out) }
            } ?: throw IOException("openOutputStream null")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun copyLegacyPublic(source: File): Uri? {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(base, SUBFOLDER)
        if (!dir.exists() && !dir.mkdirs()) return null
        val original = source.name
        var dest = File(dir, original)
        var n = 1
        while (dest.exists() && n < 50) {
            val dot = original.lastIndexOf('.')
            val ext = if (dot > 0) original.substring(dot) else ""
            val baseName = if (dot > 0) original.substring(0, dot) else original
            dest = File(dir, "${baseName}_$n$ext")
            n++
        }
        FileInputStream(source).use { inp ->
            dest.outputStream().use { inp.copyTo(it) }
        }
        return Uri.fromFile(dest)
    }
}
