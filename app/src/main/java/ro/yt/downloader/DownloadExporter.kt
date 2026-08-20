package ro.yt.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Copiază un fișier în **Descărcări/DLPulse**[/subfolder] ca să fie vizibil în managerul de fișiere.
 */
object DownloadExporter {

    fun copyToPublicDownloads(
        context: Context,
        source: File,
        relativeInsideDlpulse: String = ""
    ): Uri? {
        if (!source.isFile || !source.canRead() || source.length() == 0L) {
            return null
        }
        val rel = DlpulseStorage.normalizeRelative(relativeInsideDlpulse)
        DlpulseStorage.ensureDirectory(context, rel) ?: return null
        val mime = guessMime(source.name)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertMediaStore(context, source, mime, rel)
        } else {
            copyLegacyPublic(context, source, rel)
        }
    }

    /** Copiază media + eventuala copertă sidecar (.jpg etc.). */
    fun copyMediaWithSidecar(
        context: Context,
        media: File,
        relativeInsideDlpulse: String
    ): Boolean {
        val uri = copyToPublicDownloads(context, media, relativeInsideDlpulse) ?: return false
        DownloadArtwork.findSidecarBeside(media)?.let { thumb ->
            runCatching { copyToPublicDownloads(context, thumb, relativeInsideDlpulse) }
        }
        return uri != Uri.EMPTY
    }

    private fun guessMime(name: String): String {
        return when {
            name.endsWith(".mp4", true) -> "video/mp4"
            name.endsWith(".webm", true) -> "video/webm"
            name.endsWith(".mkv", true) -> "video/x-matroska"
            name.endsWith(".mp3", true) -> "audio/mpeg"
            name.endsWith(".m4a", true) -> "audio/mp4"
            name.endsWith(".opus", true) -> "audio/opus"
            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
            name.endsWith(".png", true) -> "image/png"
            name.endsWith(".webp", true) -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun mediaStoreRelativePath(relativeInsideDlpulse: String): String {
        val mid = "${Environment.DIRECTORY_DOWNLOADS}/${DlpulseStorage.SUBFOLDER}"
        val folder = if (relativeInsideDlpulse.isEmpty()) mid else "$mid/$relativeInsideDlpulse"
        return if (folder.endsWith("/")) folder else "$folder/"
    }

    private fun insertMediaStore(
        context: Context,
        source: File,
        mime: String,
        relativeInsideDlpulse: String
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, mediaStoreRelativePath(relativeInsideDlpulse))
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

    private fun copyLegacyPublic(
        context: Context,
        source: File,
        relativeInsideDlpulse: String
    ): Uri? {
        val dir = DlpulseStorage.ensureDirectory(context, relativeInsideDlpulse) ?: return null
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
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        return Uri.fromFile(dest)
    }
}
