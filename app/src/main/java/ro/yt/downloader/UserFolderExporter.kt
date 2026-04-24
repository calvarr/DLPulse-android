package ro.yt.downloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream

object UserFolderExporter {

    fun copyFileToTree(context: Context, treeUri: Uri, source: File): Boolean {
        if (!source.isFile || !source.canRead() || source.length() == 0L) return false
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        val mime = guessMime(source.name)
        val safeName = source.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dest = root.createFile(mime, safeName) ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(dest.uri)?.use { out ->
                FileInputStream(source).use { it.copyTo(out) }
            } ?: return false
            true
        }.getOrDefault(false)
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
}
