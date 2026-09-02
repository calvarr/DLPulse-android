package ro.yt.downloader

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

/**
 * Folder public **Download/DLPulse** — creat la nevoie, reutilizat la update/reinstalare
 * (rămâne pe stocarea partajată, nu în spațiul privat al aplicației).
 */
object DlpulseStorage {

    const val SUBFOLDER = "DLPulse"

    fun rootDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBFOLDER
        )

    /** Creează rădăcina dacă lipsește. Reușește și dacă folderul există deja. */
    fun ensureRoot(context: Context): Boolean {
        val dir = rootDir()
        if (dir.isDirectory) return true
        if (!dir.mkdirs() && !dir.isDirectory) return false
        MediaScannerConnection.scanFile(context, arrayOf(dir.absolutePath), null, null)
        return dir.isDirectory
    }

    fun normalizeRelative(path: String): String =
        path.trim().replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

    fun resolveDirectory(relativeInsideDlpulse: String): File? {
        val base = rootDir()
        val rel = normalizeRelative(relativeInsideDlpulse)
        if (rel.isEmpty()) return base
        return runCatching {
            val child = File(base, rel).canonicalFile
            val baseCanon = base.canonicalFile
            if (!child.path.startsWith(baseCanon.path)) null else child
        }.getOrNull()
    }

    fun ensureDirectory(context: Context, relativeInsideDlpulse: String): File? {
        if (!ensureRoot(context)) return null
        val dir = resolveDirectory(relativeInsideDlpulse) ?: return null
        if (dir.isDirectory) return dir
        if (!dir.mkdirs() && !dir.isDirectory) return null
        MediaScannerConnection.scanFile(context, arrayOf(dir.absolutePath), null, null)
        return dir.takeIf { it.isDirectory }
    }

    fun listImmediateSubfolders(relativeInsideDlpulse: String): List<String> {
        val dir = resolveDirectory(relativeInsideDlpulse) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { it.name }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?: emptyList()
    }

    fun displayPath(relativeInsideDlpulse: String): String {
        val rel = normalizeRelative(relativeInsideDlpulse)
        return if (rel.isEmpty()) {
            "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER/$rel"
        }
    }
}
