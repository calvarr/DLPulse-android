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

    sealed class MoveOutcome {
        data object Success : MoveOutcome()
        data object Failed : MoveOutcome()
        data class NeedsUserConsent(val intentSender: IntentSender) : MoveOutcome()
    }

    sealed class RenameOutcome {
        data object Success : RenameOutcome()
        data object Failed : RenameOutcome()
        data class NeedsUserConsent(val intentSender: IntentSender) : RenameOutcome()
    }

    fun rename(context: Context, entry: DownloadedFileEntry, newName: String): RenameOutcome {
        val safe = sanitizeNewFileName(newName) ?: return RenameOutcome.Failed
        if (safe == entry.title) return RenameOutcome.Success

        val uri = resolveMediaUri(context, entry)

        // Pe Android 10+ redenumirea MediaStore e calea corectă pentru fișiere publice.
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (val r = renameMediaStoreOutcome(context, uri, safe)) {
                is RenameOutcome.Success -> {
                    entry.file?.let { f ->
                        if (f.exists()) {
                            val dest = File(f.parentFile ?: return@let, safe)
                            if (!dest.exists()) runCatching { f.renameTo(dest) }
                            scanPath(context, dest.absolutePath)
                        }
                    }
                    return RenameOutcome.Success
                }
                is RenameOutcome.NeedsUserConsent -> return r
                is RenameOutcome.Failed -> Unit
            }
        }

        entry.file?.let { f ->
            if (!f.exists()) return RenameOutcome.Failed
            val parent = f.parentFile ?: return RenameOutcome.Failed
            val dest = File(parent, safe)
            if (dest.exists()) return RenameOutcome.Failed
            if (f.renameTo(dest)) {
                scanPath(context, dest.absolutePath)
                return RenameOutcome.Success
            }
            if (isAppPrivateFile(context, f)) return RenameOutcome.Failed
            if (uri != null) {
                createWriteRequestSender(context, listOf(uri))?.let {
                    return RenameOutcome.NeedsUserConsent(it)
                }
            }
            return RenameOutcome.Failed
        }

        if (uri != null) {
            return renameMediaStoreOutcome(context, uri, safe)
        }

        return RenameOutcome.Failed
    }

    /**
     * Mută fișierul într-un subfolder din Download/DLPulse.
     * [destRelativeInsidePublic] e gol pentru rădăcină, sau ex. `audio`, `video/sport`.
     *
     * Permisiunile READ_MEDIA_* / „Fișiere și media” sunt doar de citire — pe Android 10+
     * mutarea cere consimțământ de scriere (dialog sistem) când aplicația nu deține fișierul.
     */
    fun moveToDlpulseSubfolder(
        context: Context,
        entry: DownloadedFileEntry,
        destRelativeInsidePublic: String
    ): MoveOutcome {
        val destRel = normalizeRelative(destRelativeInsidePublic)
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBFOLDER
        )
        val destDir = safeResolvedDirectory(baseDir, destRel) ?: return MoveOutcome.Failed
        if (!destDir.exists() && !destDir.mkdirs()) {
            // mkdirs poate eșua pe scoped storage; MediaStore RELATIVE_PATH tot poate muta.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return MoveOutcome.Failed
        }
        if (destDir.exists() && !destDir.isDirectory) return MoveOutcome.Failed

        val currentRel = relativeInsideDlpulse(entry.file)
            ?: relativeInsideDlpulseFromUri(context, entry.contentUri)
        if (currentRel != null && normalizeRelative(currentRel) == destRel) {
            return MoveOutcome.Success
        }

        val name = entry.title
        val destFile = File(destDir, name)
        val uri = resolveMediaUri(context, entry)
        if (destFile.exists()) {
            val src = entry.file
            val alreadyAtDest = src != null &&
                runCatching { src.canonicalFile == destFile.canonicalFile }.getOrDefault(false)
            if (alreadyAtDest) return MoveOutcome.Success
            val srcMissing = (src == null || !src.exists()) &&
                (uri == null || !mediaUriExists(context, uri))
            // Copiere anterioară + consimțământ ștergere: destinația există, sursa a dispărut.
            if (srcMissing) return MoveOutcome.Success
            return MoveOutcome.Failed
        }

        val mediaRelativePath = mediaStoreRelativePathFor(destRel)

        // Cu acces „Toate fișierele”, mutarea pe disc e cea mai fiabilă.
        if (AppStartupPermissions.canManageAllFiles()) {
            entry.file?.let { src ->
                if (src.exists()) {
                    if (!destDir.exists()) destDir.mkdirs()
                    val moved = src.renameTo(destFile) || copyThenDelete(src, destFile)
                    if (moved) {
                        scanPath(context, destFile.absolutePath)
                        src.parentFile?.absolutePath?.let { scanPath(context, it) }
                        uri?.let { updateMediaStoreRelativePath(context, it, mediaRelativePath, name) }
                        return MoveOutcome.Success
                    }
                }
            }
        }

        // 1) Preferă MediaStore (mută fizic fișierul pe Android 10+).
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (val r = updateMediaStoreRelativePathOutcome(context, uri, mediaRelativePath, name)) {
                is MoveOutcome.Success -> {
                    // Dacă File.rename n-a avut loc, curățăm calea veche dacă mai există.
                    entry.file?.takeIf { it.exists() && it.absolutePath != destFile.absolutePath }?.let { old ->
                        runCatching { old.delete() }
                        old.parentFile?.absolutePath?.let { scanPath(context, it) }
                    }
                    if (destFile.exists()) scanPath(context, destFile.absolutePath)
                    return MoveOutcome.Success
                }
                is MoveOutcome.NeedsUserConsent -> return r
                is MoveOutcome.Failed -> Unit
            }
        }

        // 2) Fallback File (spațiu privat / Android vechi).
        entry.file?.let { src ->
            if (!src.exists()) {
                // Doar URI — deja încercat mai sus.
            } else if (src.parentFile?.canonicalPath == destDir.canonicalPath) {
                return MoveOutcome.Success
            } else {
                val moved = src.renameTo(destFile) || copyThenDelete(src, destFile)
                if (moved) {
                    scanPath(context, destFile.absolutePath)
                    src.parentFile?.absolutePath?.let { scanPath(context, it) }
                    uri?.let { updateMediaStoreRelativePath(context, it, mediaRelativePath, name) }
                    return MoveOutcome.Success
                }
                if (isAppPrivateFile(context, src)) return MoveOutcome.Failed
            }
        }

        // 3) Copiere din URI + ștergere sursă.
        if (uri != null) {
            if (destDir.exists() || destDir.mkdirs()) {
                if (copyUriToPublicFile(context, uri, destFile)) {
                    return when (val d = deleteMediaRow(context, uri)) {
                        is DeleteOutcome.Success -> MoveOutcome.Success
                        is DeleteOutcome.NeedsUserConsent ->
                            // Fișierul e deja la destinație; cerem consimțământ doar pentru ștergerea sursei.
                            MoveOutcome.NeedsUserConsent(d.intentSender)
                        is DeleteOutcome.Failed -> {
                            if (deleteViaDocumentFile(context, uri)) MoveOutcome.Success
                            else MoveOutcome.Success // destinația există; sursa poate rămâne (rar)
                        }
                    }
                }
            }
            // Ultima șansă: cere acces de scriere, apoi UI reîncearcă mutarea.
            createWriteRequestSender(context, listOf(uri))?.let {
                return MoveOutcome.NeedsUserConsent(it)
            }
        }

        return MoveOutcome.Failed
    }

    fun delete(context: Context, entry: DownloadedFileEntry): DeleteOutcome {
        // Cu MANAGE_EXTERNAL_STORAGE, File.delete/rename funcționează pe stocarea publică.
        if (AppStartupPermissions.canManageAllFiles()) {
            entry.file?.let { f ->
                if (f.exists() && f.delete()) {
                    f.parentFile?.absolutePath?.let { scanPath(context, it) }
                    entry.contentUri?.let { uri ->
                        runCatching { context.contentResolver.delete(uri, null, null) }
                    }
                    return DeleteOutcome.Success
                }
            }
        }

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
                lookupMediaUriBroad(context, f)?.let { uri ->
                    when (val r = deleteMediaRow(context, uri)) {
                        is DeleteOutcome.Success -> return DeleteOutcome.Success
                        is DeleteOutcome.NeedsUserConsent -> return r
                        is DeleteOutcome.Failed -> Unit
                    }
                }
                return DeleteOutcome.Success
            }

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

    private fun resolveMediaUri(context: Context, entry: DownloadedFileEntry): Uri? {
        entry.contentUri?.let { return it }
        val f = entry.file ?: return null
        return lookupMediaUriBroad(context, f)
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

    private fun mediaUriExists(context: Context, uri: Uri): Boolean =
        runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.moveToFirst() } == true
        }.getOrDefault(false)

    private fun deleteMediaRow(context: Context, uri: Uri): DeleteOutcome {
        return try {
            val deleted = context.contentResolver.delete(uri, null, null)
            when {
                deleted > 0 -> DeleteOutcome.Success
                !mediaUriExists(context, uri) -> DeleteOutcome.Success
                else -> {
                    // Unele OEM-uri returnează 0 + SecurityException nu e aruncat — cere dialog sistem.
                    createDeleteRequestSender(context, listOf(uri))?.let {
                        DeleteOutcome.NeedsUserConsent(it)
                    } ?: DeleteOutcome.Failed
                }
            }
        } catch (e: RecoverableSecurityException) {
            // Pe API 30+ preferăm createDeleteRequest (sistemul șterge direct).
            createDeleteRequestSender(context, listOf(uri))?.let { DeleteOutcome.NeedsUserConsent(it) }
                ?: consentSenderFromRecoverable(e)?.let { DeleteOutcome.NeedsUserConsent(it) }
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

    private fun createWriteRequestSender(context: Context, uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
        return runCatching {
            val pi: PendingIntent = MediaStore.createWriteRequest(context.contentResolver, uris)
            pi.intentSender
        }.getOrNull()
    }

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
        return updateMediaStoreRelativePathOutcome(context, uri, relativePath, displayName) is MoveOutcome.Success
    }

    private fun updateMediaStoreRelativePathOutcome(
        context: Context,
        uri: Uri,
        relativePath: String,
        displayName: String
    ): MoveOutcome {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            }
            if (context.contentResolver.update(uri, values, null, null) > 0) {
                MoveOutcome.Success
            } else {
                createWriteRequestSender(context, listOf(uri))?.let { MoveOutcome.NeedsUserConsent(it) }
                    ?: MoveOutcome.Failed
            }
        } catch (e: RecoverableSecurityException) {
            createWriteRequestSender(context, listOf(uri))?.let { MoveOutcome.NeedsUserConsent(it) }
                ?: consentSenderFromRecoverable(e)?.let { MoveOutcome.NeedsUserConsent(it) }
                ?: MoveOutcome.Failed
        } catch (_: SecurityException) {
            createWriteRequestSender(context, listOf(uri))?.let { MoveOutcome.NeedsUserConsent(it) }
                ?: MoveOutcome.Failed
        } catch (_: Exception) {
            MoveOutcome.Failed
        }
    }

    private fun renameMediaStoreOutcome(
        context: Context,
        uri: Uri,
        displayName: String
    ): RenameOutcome {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            }
            if (context.contentResolver.update(uri, values, null, null) > 0) {
                RenameOutcome.Success
            } else {
                createWriteRequestSender(context, listOf(uri))?.let { RenameOutcome.NeedsUserConsent(it) }
                    ?: RenameOutcome.Failed
            }
        } catch (e: RecoverableSecurityException) {
            createWriteRequestSender(context, listOf(uri))?.let { RenameOutcome.NeedsUserConsent(it) }
                ?: consentSenderFromRecoverable(e)?.let { RenameOutcome.NeedsUserConsent(it) }
                ?: RenameOutcome.Failed
        } catch (_: SecurityException) {
            createWriteRequestSender(context, listOf(uri))?.let { RenameOutcome.NeedsUserConsent(it) }
                ?: RenameOutcome.Failed
        } catch (_: Exception) {
            RenameOutcome.Failed
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

    private fun scanPath(context: Context, path: String) {
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }
}
