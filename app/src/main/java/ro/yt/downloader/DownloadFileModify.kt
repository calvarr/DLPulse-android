package ro.yt.downloader

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.util.Locale

object DownloadFileModify {

    private const val SUBFOLDER = "DLPulse"

    sealed class DeleteOutcome {
        data object Success : DeleteOutcome()
        data object Failed : DeleteOutcome()
        data class NeedsUserConsent(val intentSender: IntentSender) : DeleteOutcome()
    }

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
            entry.contentUri?.let { uri ->
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                    }
                    context.contentResolver.update(uri, values, null, null)
                }
            }
            return true
        }

        entry.contentUri?.let { uri ->
            return renameMediaStore(context, uri, safe)
        }

        return false
    }

    /**
     * Mută fișierul într-un subfolder din Download/DLPulse.
     * [destRelativeInsidePublic] e gol pentru rădăcină, sau ex. `audio`, `video/sport`.
     */
    fun moveToDlpulseSubfolder(
        context: Context,
        entry: DownloadedFileEntry,
        destRelativeInsidePublic: String
    ): Boolean {
        val destRel = normalizeRelative(destRelativeInsidePublic)
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBFOLDER
        )
        val destDir = safeResolvedDirectory(baseDir, destRel) ?: return false
        if (!destDir.exists() && !destDir.mkdirs()) return false
        if (!destDir.isDirectory) return false

        val currentRel = relativeInsideDlpulse(entry.file)
            ?: relativeInsideDlpulseFromUri(context, entry.contentUri)
        if (currentRel != null && normalizeRelative(currentRel) == destRel) {
            return true
        }

        val name = entry.title
        val destFile = File(destDir, name)
        if (destFile.exists()) return false

        val mediaRelativePath = mediaStoreRelativePathFor(destRel)

        entry.file?.let { src ->
            if (!src.exists()) return false
            if (src.parentFile?.canonicalPath == destDir.canonicalPath) return true
            val moved = src.renameTo(destFile) || copyThenDelete(src, destFile)
            if (!moved) return false
            scanPath(context, destFile.absolutePath)
            src.parentFile?.absolutePath?.let { scanPath(context, it) }
            val uri = entry.contentUri ?: lookupMediaUriForPublicFile(context, destFile)
                ?: lookupMediaUriForPublicFile(context, src)
            if (uri != null) {
                updateMediaStoreRelativePath(context, uri, mediaRelativePath, name)
            }
            return true
        }

        entry.contentUri?.let { uri ->
            if (updateMediaStoreRelativePath(context, uri, mediaRelativePath, name)) {
                return true
            }
            // Fallback: copiază conținutul în folderul țintă, apoi șterge sursa.
            if (!copyUriToPublicFile(context, uri, destFile)) return false
            return when (deleteMediaRow(context, uri)) {
                is DeleteOutcome.Success -> true
                else -> deleteViaDocumentFile(context, uri)
            }
        }

        return false
    }

    fun delete(context: Context, entry: DownloadedFileEntry): DeleteOutcome {
        // Prefer MediaStore URI (Android 10+ scoped storage) — păstrat și când există File pe disc.
        entry.contentUri?.let { uri ->
            when (val r = deleteMediaRow(context, uri)) {
                is DeleteOutcome.Success -> {
                    runCatching { entry.file?.takeIf { it.exists() }?.delete() }
                    entry.file?.parentFile?.absolutePath?.let { scanPath(context, it) }
                    return DeleteOutcome.Success
                }
                is DeleteOutcome.NeedsUserConsent -> return r
                is DeleteOutcome.Failed -> {
                    if (deleteViaDocumentFile(context, uri)) {
                        runCatching { entry.file?.takeIf { it.exists() }?.delete() }
                        entry.file?.parentFile?.absolutePath?.let { scanPath(context, it) }
                        return DeleteOutcome.Success
                    }
                }
            }
        }

        entry.file?.let { f ->
            if (!f.exists()) {
                // Fișierul e deja lipsă — curăță eventualul rând MediaStore.
                lookupMediaUriBroad(context, f)?.let { uri ->
                    when (val r = deleteMediaRow(context, uri)) {
                        is DeleteOutcome.Success -> return DeleteOutcome.Success
                        is DeleteOutcome.NeedsUserConsent -> return r
                        is DeleteOutcome.Failed -> Unit
                    }
                }
                return DeleteOutcome.Success
            }

            // Spațiu privat al aplicației — File.delete e suficient.
            if (isAppPrivateFile(context, f)) {
                return if (f.delete()) {
                    f.parentFile?.absolutePath?.let { scanPath(context, it) }
                    DeleteOutcome.Success
                } else {
                    DeleteOutcome.Failed
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = lookupMediaUriBroad(context, f)
                if (uri != null) {
                    when (val r = deleteMediaRow(context, uri)) {
                        is DeleteOutcome.Success -> {
                            runCatching { if (f.exists()) f.delete() }
                            f.parentFile?.absolutePath?.let { scanPath(context, it) }
                            return DeleteOutcome.Success
                        }
                        is DeleteOutcome.NeedsUserConsent -> return r
                        is DeleteOutcome.Failed -> Unit
                    }
                }
            }

            if (f.delete()) {
                f.parentFile?.absolutePath?.let { scanPath(context, it) }
                return DeleteOutcome.Success
            }
        }

        return DeleteOutcome.Failed
    }

    private fun isAppPrivateFile(context: Context, file: File): Boolean {
        val roots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.getExternalFilesDir(null),
            context.externalCacheDir
        )
        val path = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        return roots.any { root ->
            val rp = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
            path.startsWith(rp)
        }
    }

    private fun deleteViaDocumentFile(context: Context, uri: Uri): Boolean =
        runCatching {
            val d = DocumentFile.fromSingleUri(context, uri)
            d != null && d.exists() && !d.isDirectory && d.delete()
        }.getOrDefault(false)

    private fun deleteMediaRow(context: Context, uri: Uri): DeleteOutcome {
        return try {
            if (context.contentResolver.delete(uri, null, null) > 0) {
                DeleteOutcome.Success
            } else {
                DeleteOutcome.Failed
            }
        } catch (e: RecoverableSecurityException) {
            consentSenderFromRecoverable(e)?.let { DeleteOutcome.NeedsUserConsent(it) }
                ?: createDeleteRequestSender(context, listOf(uri))?.let { DeleteOutcome.NeedsUserConsent(it) }
                ?: DeleteOutcome.Failed
        } catch (_: SecurityException) {
            createDeleteRequestSender(context, listOf(uri))?.let { DeleteOutcome.NeedsUserConsent(it) }
                ?: DeleteOutcome.Failed
        } catch (_: Exception) {
            DeleteOutcome.Failed
        }
    }

    private fun consentSenderFromRecoverable(e: RecoverableSecurityException): IntentSender? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            e.userAction.actionIntent.intentSender
        } else {
            null
        }
    }

    private fun createDeleteRequestSender(context: Context, uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
        return runCatching {
            val pi: PendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
            pi.intentSender
        }.getOrNull()
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
        return queryUriByNameAndRelativePaths(context, name, relVariants)
    }

    private fun lookupMediaUriBroad(context: Context, file: File): Uri? {
        lookupMediaUriForPublicFile(context, file)?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val name = file.name
        val size = file.length()
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val collections = listOf(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        )
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
        val args = arrayOf(name, size.toString())
        for (collection in collections) {
            runCatching {
                resolver.query(collection, projection, sel, args, null)?.use { c ->
                    if (c.moveToFirst()) {
                        return ContentUris.withAppendedId(collection, c.getLong(0))
                    }
                }
            }
        }
        return null
    }

    private fun queryUriByNameAndRelativePaths(
        context: Context,
        name: String,
        relVariants: List<String>
    ): Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val collections = listOf(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
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
                            return ContentUris.withAppendedId(collection, c.getLong(0))
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

    private fun relativeInsideDlpulse(file: File?): String? {
        if (file == null) return null
        val parent = file.parentFile ?: return null
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBFOLDER
        )
        val pCanon = runCatching { parent.canonicalPath }.getOrNull() ?: return null
        val bCanon = runCatching { baseDir.canonicalPath }.getOrNull() ?: return null
        if (pCanon == bCanon) return ""
        if (!pCanon.startsWith("$bCanon/")) return null
        return pCanon.removePrefix("$bCanon/").replace('\\', '/')
    }

    private fun relativeInsideDlpulseFromUri(context: Context, uri: Uri?): String? {
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                null,
                null,
                null
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val rp = c.getString(0) ?: return@use null
                val n = rp.trim().replace('\\', '/').trimEnd('/')
                val prefix = "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER"
                val prefixLo = prefix.lowercase(Locale.ROOT)
                val nLo = n.lowercase(Locale.ROOT)
                when {
                    nLo == prefixLo -> ""
                    nLo.startsWith("$prefixLo/") -> n.substring(prefix.length + 1)
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun mediaStoreRelativePathFor(destRel: String): String {
        val mid = "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER"
        val folder = if (destRel.isEmpty()) mid else "$mid/$destRel"
        return if (folder.endsWith("/")) folder else "$folder/"
    }

    private fun updateMediaStoreRelativePath(
        context: Context,
        uri: Uri,
        relativePath: String,
        displayName: String
    ): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            }
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun copyUriToPublicFile(context: Context, uri: Uri, dest: File): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out) }
            } ?: return false
            scanPath(context, dest.absolutePath)
            true
        }.getOrDefault(false)
    }

    private fun copyThenDelete(src: File, dest: File): Boolean {
        return try {
            FileInputStream(src).use { inp ->
                dest.outputStream().use { out -> inp.copyTo(out) }
            }
            src.delete() || !src.exists()
        } catch (_: Exception) {
            runCatching { if (dest.exists()) dest.delete() }
            false
        }
    }

    private fun normalizeRelative(path: String): String =
        path.trim().replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

    private fun safeResolvedDirectory(base: File, relativePath: String): File? {
        if (relativePath.isEmpty()) return base
        return runCatching {
            val child = File(base, relativePath)
            val baseCanon = base.canonicalFile
            val childCanon = child.canonicalFile
            if (!childCanon.path.startsWith(baseCanon.path)) null else childCanon
        }.getOrNull()
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
