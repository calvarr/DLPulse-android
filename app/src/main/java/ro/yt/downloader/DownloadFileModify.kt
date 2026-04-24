package ro.yt.downloader

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

object DownloadFileModify {

    fun rename(context: Context, entry: DownloadedFileEntry, newName: String): Boolean {
        val safe = sanitizeNewFileName(newName) ?: return false
        if (safe == entry.title) return true

        entry.file?.let { f ->
            if (!f.exists()) return false
            val parent = f.parentFile ?: return false
            val dest = File(parent, safe)
            if (dest.exists()) return false
            if (!f.renameTo(dest)) return false
            scanPath(context, dest.absolutePath)
            return true
        }

        entry.contentUri?.let { uri ->
            return renameMediaStore(context, uri, safe)
        }

        return false
    }

    fun delete(context: Context, entry: DownloadedFileEntry): Boolean {
        entry.contentUri?.let { uri ->
            if (deleteMediaRow(context, uri)) {
                entry.file?.parentFile?.absolutePath?.let { scanPath(context, it) }
                runCatching { entry.file?.delete() }
                return true
            }
        }
        entry.file?.let { f ->
            if (!f.exists()) return false
            if (f.delete()) {
                f.parentFile?.absolutePath?.let { scanPath(context, it) }
                return true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = lookupMediaUriForPublicFile(context, f)
                if (uri != null && deleteMediaRow(context, uri)) {
                    runCatching { if (f.exists()) f.delete() }
                    f.parentFile?.absolutePath?.let { scanPath(context, it) }
                    return true
                }
            }
        }
        return false
    }

    private fun deleteMediaRow(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (_: RecoverableSecurityException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Găsește rândul MediaStore pentru un fișier din stocarea partajată (ex. Download/…),
     * unde [File.delete] eșuează fără ștergere prin URI (Android 10+).
     */
    private fun lookupMediaUriForPublicFile(context: Context, file: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val name = file.name
        val parent = file.parentFile ?: return null
        val relVariants = relativePathVariantsForDownloadsSubtree(parent)
        if (relVariants.isEmpty()) return null
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val collections = listOf(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        )
        for (collection in collections) {
            for (rp in relVariants) {
                val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                    "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?)"
                val rpNoSlash = rp.trimEnd('/')
                val rpSlash = if (rpNoSlash.endsWith("/")) rpNoSlash else "$rpNoSlash/"
                val args = arrayOf(name, rpSlash, rpNoSlash)
                runCatching {
                    resolver.query(collection, projection, sel, args, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val id = c.getLong(0)
                            return ContentUris.withAppendedId(collection, id)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun relativePathVariantsForDownloadsSubtree(parent: File): List<String> {
        val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val pCanon = runCatching { parent.canonicalPath }.getOrNull() ?: return emptyList()
        val rCanon = runCatching { downloadsRoot.canonicalPath }.getOrNull() ?: return emptyList()
        if (!pCanon.startsWith(rCanon)) return emptyList()
        val inner = pCanon.removePrefix(rCanon).trim('/').replace('\\', '/')
        val mid = Environment.DIRECTORY_DOWNLOADS
        val base = if (inner.isEmpty()) mid else "$mid/$inner"
        val out = LinkedHashSet<String>()
        for (b in listOf(base, base.lowercase(Locale.ROOT))) {
            out.add("$b/")
            out.add(b)
        }
        return out.toList()
    }

    private fun sanitizeNewFileName(name: String): String? {
        val t = name.trim()
        if (t.isEmpty()) return null
        if ('/' in t || '\\' in t || t == "." || t == "..") return null
        return t
    }

    private fun renameMediaStore(context: Context, uri: Uri, displayName: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            }
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun scanPath(context: Context, path: String) {
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }
}
