package ro.yt.downloader

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

data class DlpulseDirectoryListing(
    val subfolders: List<String>,
    val files: List<DownloadedFileEntry>
)

object DownloadsIndex {

    private const val SUBFOLDER = "DLPulse"

    /**
     * Toate fișierele din **Download/DLPulse** (public): scanare directă pe disc + MediaStore
     * (Downloads + Files), cu deduplicare. Necesită permisiuni de citire pe Android 6+.
     */
    fun listPublicDlpulseOnly(context: Context): List<DownloadedFileEntry> {
        val byKey = LinkedHashMap<String, DownloadedFileEntry>()

        fun dedupeKey(name: String, size: Long) =
            "${name.lowercase(Locale.ROOT)}_$size"

        fun putMerged(key: String, entry: DownloadedFileEntry) {
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = entry
                return
            }
            if (existing.file == null && entry.file != null) {
                byKey[key] = entry
                return
            }
            if (existing.file != null && entry.file == null) {
                return
            }
            if (entry.sortKey > existing.sortKey) {
                byKey[key] = entry
            }
        }

        scanPublicFolderToMap(byKey, ::dedupeKey, ::putMerged)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryDownloadsCollection(context, byKey, ::dedupeKey, ::putMerged)
            queryFilesCollection(context, byKey, ::dedupeKey, ::putMerged)
        }

        return byKey.values.sortedByDescending { it.sortKey }
    }

    private fun scanPublicFolderToMap(
        byKey: MutableMap<String, DownloadedFileEntry>,
        dedupeKey: (String, Long) -> String,
        putMerged: (String, DownloadedFileEntry) -> Unit
    ) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBFOLDER
        )
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.canRead() && f.length() > 0L) {
                val key = dedupeKey(f.name, f.length())
                putMerged(
                    key,
                    DownloadedFileEntry(
                        title = f.name,
                        mime = DownloadMime.guessFromFileName(f.name),
                        file = f,
                        contentUri = null,
                        sortKey = f.lastModified()
                    )
                )
            }
        }
    }

    private fun queryDownloadsCollection(
        context: Context,
        byKey: MutableMap<String, DownloadedFileEntry>,
        dedupeKey: (String, Long) -> String,
        putMerged: (String, DownloadedFileEntry) -> Unit
    ) {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val base = "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER"
        val baseLower = base.lowercase(Locale.ROOT)
        val selection = buildString {
            append("${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ")
            append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ")
            append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ")
            append("${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ")
            append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
        }
        val args = arrayOf(
            base,
            "$base/%",
            "%/$SUBFOLDER/%",
            baseLower,
            "$baseLower/%"
        )
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        ingestMediaQuery(resolver, collection, projection, selection, args, sort, byKey, dedupeKey, putMerged)
    }

    private fun queryFilesCollection(
        context: Context,
        byKey: MutableMap<String, DownloadedFileEntry>,
        dedupeKey: (String, Long) -> String,
        putMerged: (String, DownloadedFileEntry) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val base = "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER"
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(
            "$base/%",
            "%/${SUBFOLDER}/%",
            base,
            "%/${SUBFOLDER.lowercase(Locale.ROOT)}/%"
        )
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        ingestMediaQuery(resolver, collection, projection, selection, args, sort, byKey, dedupeKey, putMerged)
    }

    private fun ingestMediaQuery(
        resolver: android.content.ContentResolver,
        collection: Uri,
        projection: Array<String>,
        selection: String,
        args: Array<String>,
        sort: String,
        byKey: MutableMap<String, DownloadedFileEntry>,
        dedupeKey: (String, Long) -> String,
        putMerged: (String, DownloadedFileEntry) -> Unit
    ) {
        runCatching {
            resolver.query(collection, projection, selection, args, sort)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val rel = c.getString(iPath) ?: continue
                    if (!isUnderDlpulsePublicFolder(rel)) continue
                    val id = c.getLong(iId)
                    val name = c.getString(iName) ?: continue
                    val size = c.getLong(iSize)
                    val mime = c.getString(iMime) ?: DownloadMime.guessFromFileName(name)
                    val modSec = c.getLong(iMod)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val key = dedupeKey(name, size)
                    putMerged(
                        key,
                        DownloadedFileEntry(
                            title = name,
                            mime = mime,
                            file = null,
                            contentUri = uri,
                            sortKey = modSec * 1000L,
                            sizeBytes = size
                        )
                    )
                }
            }
        }
    }

    private fun isUnderDlpulsePublicFolder(relativePath: String): Boolean {
        val n = relativePath.trim().replace('\\', '/').lowercase(Locale.ROOT)
        val needle = "${Environment.DIRECTORY_DOWNLOADS.lowercase(Locale.ROOT)}/$SUBFOLDER".lowercase(Locale.ROOT)
        val needleLo = "${Environment.DIRECTORY_DOWNLOADS.lowercase(Locale.ROOT)}/${SUBFOLDER.lowercase(Locale.ROOT)}"
        return n == needle || n.startsWith("$needle/") || n.startsWith("$needleLo/")
    }

    fun listEntries(context: Context): List<DownloadedFileEntry> {
        val byKey = LinkedHashMap<String, DownloadedFileEntry>()

        fun putEntry(key: String, entry: DownloadedFileEntry) {
            if (!byKey.containsKey(key)) {
                byKey[key] = entry
            }
        }

        val privateDir = File(context.getExternalFilesDir(null), "downloads")
        privateDir.listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { f ->
                val key = "${f.name}_${f.length()}"
                putEntry(
                    key,
                    DownloadedFileEntry(
                        title = f.name,
                        mime = DownloadMime.guessFromFileName(f.name),
                        file = f,
                        contentUri = null,
                        sortKey = f.lastModified()
                    )
                )
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryLegacyMediaStore(context, byKey)
        } else {
            val pub = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                SUBFOLDER
            )
            pub.listFiles()?.filter { it.isFile && it.length() > 0L }?.forEach { f ->
                val key = "${f.name}_${f.length()}"
                putEntry(
                    key,
                    DownloadedFileEntry(
                        title = f.name,
                        mime = DownloadMime.guessFromFileName(f.name),
                        file = f,
                        contentUri = null,
                        sortKey = f.lastModified()
                    )
                )
            }
        }

        return byKey.values.sortedByDescending { it.sortKey }
    }

    private fun queryLegacyMediaStore(
        context: Context,
        byKey: MutableMap<String, DownloadedFileEntry>
    ) {
        val basePath = "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER"
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(basePath, "$basePath/%")
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        resolver.query(collection, projection, selection, args, sort)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: continue
                val size = c.getLong(iSize)
                val mime = c.getString(iMime) ?: DownloadMime.guessFromFileName(name)
                val modSec = c.getLong(iMod)
                val uri = ContentUris.withAppendedId(collection, id)
                val key = "${name}_$size"
                if (!byKey.containsKey(key)) {
                    byKey[key] = DownloadedFileEntry(
                        title = name,
                        mime = mime,
                        file = null,
                        contentUri = uri,
                        sortKey = modSec * 1000L,
                        sizeBytes = size
                    )
                }
            }
        }
    }

    /**
     * Conținutul unui subfolder din **Download/DLPulse** (foldere + fișiere directe).
     * [relativePathInsidePublic] e gol pentru rădăcină, sau ex. `audio`, `video/sport`.
     */
    fun listPublicDlpulseDirectory(
        context: Context,
        relativePathInsidePublic: String
    ): DlpulseDirectoryListing {
        val rel = normalizeRelativePathInsidePublic(relativePathInsidePublic)
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBFOLDER
        )
        val dir = safeResolvedDirectory(baseDir, rel)
            ?: return DlpulseDirectoryListing(emptyList(), emptyList())
        if (!dir.isDirectory) {
            return DlpulseDirectoryListing(emptyList(), emptyList())
        }

        val subfolders = dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

        val diskFiles = mutableListOf<DownloadedFileEntry>()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.canRead() && f.length() > 0L) {
                diskFiles.add(
                    DownloadedFileEntry(
                        title = f.name,
                        mime = DownloadMime.guessFromFileName(f.name),
                        file = f,
                        contentUri = null,
                        sortKey = f.lastModified(),
                        sizeBytes = f.length()
                    )
                )
            }
        }

        val byKey = LinkedHashMap<String, DownloadedFileEntry>()
        fun putMerged(e: DownloadedFileEntry) {
            val k = "${e.title.lowercase(Locale.ROOT)}_${e.effectiveSizeForDedupe()}"
            val existing = byKey[k]
            if (existing == null) {
                byKey[k] = e
                return
            }
            if (existing.file == null && e.file != null) {
                byKey[k] = e
                return
            }
            if (existing.file != null && e.file == null) return
            if (e.sortKey > existing.sortKey) byKey[k] = e
        }
        diskFiles.forEach { putMerged(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreFilesInDirectory(context, rel).forEach { putMerged(it) }
        }

        val files = byKey.values.sortedByDescending { it.sortKey }
        return DlpulseDirectoryListing(subfolders, files)
    }

    private fun normalizeRelativePathInsidePublic(path: String): String {
        return path.trim().replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
    }

    private fun safeResolvedDirectory(base: File, relativePath: String): File? {
        if (relativePath.isEmpty()) return base
        return runCatching {
            val child = File(base, relativePath)
            val baseCanon = base.canonicalFile
            val childCanon = child.canonicalFile
            if (!childCanon.path.startsWith(baseCanon.path)) null else childCanon
        }.getOrNull()
    }

    private fun mediaStoreDirPathVariants(relativeInsidePublic: String): List<String> {
        val mid = "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER"
        val sub = normalizeRelativePathInsidePublic(relativeInsidePublic)
        val folder = if (sub.isEmpty()) mid else "$mid/$sub"
        val withSlash = "$folder/"
        val noSlash = folder.trimEnd('/')
        val variants = mutableListOf(withSlash, noSlash)
        if (folder != folder.lowercase(Locale.ROOT)) {
            variants.add(withSlash.lowercase(Locale.ROOT))
            variants.add(noSlash.lowercase(Locale.ROOT))
        }
        return variants.distinct()
    }

    private fun mediaStoreFilesInDirectory(
        context: Context,
        relativeInsidePublic: String
    ): List<DownloadedFileEntry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val paths = mediaStoreDirPathVariants(relativeInsidePublic)
        if (paths.isEmpty()) return emptyList()
        val placeholders = paths.joinToString(",") { "?" }
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} IN ($placeholders) AND " +
            "${MediaStore.MediaColumns.SIZE} > 0"
        val args = paths.toTypedArray()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val out = mutableListOf<DownloadedFileEntry>()
        val resolver = context.contentResolver
        val collections = listOf(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        )
        val seenUri = mutableSetOf<String>()
        for (collection in collections) {
            runCatching {
                resolver.query(collection, projection, selection, args, sort)?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        val rel = c.getString(iPath) ?: continue
                        if (!isExactDirectoryMatch(rel, paths)) continue
                        val id = c.getLong(iId)
                        val name = c.getString(iName) ?: continue
                        val size = c.getLong(iSize)
                        val mime = c.getString(iMime) ?: DownloadMime.guessFromFileName(name)
                        val modSec = c.getLong(iMod)
                        val uri = ContentUris.withAppendedId(collection, id)
                        val uriStr = uri.toString()
                        if (uriStr in seenUri) continue
                        seenUri.add(uriStr)
                        out.add(
                            DownloadedFileEntry(
                                title = name,
                                mime = mime,
                                file = null,
                                contentUri = uri,
                                sortKey = modSec * 1000L,
                                sizeBytes = size
                            )
                        )
                    }
                }
            }
        }
        return out
    }

    private fun isExactDirectoryMatch(relativePath: String, candidates: List<String>): Boolean {
        val n = relativePath.trim().replace('\\', '/')
        val normalized = n.trimEnd('/').lowercase(Locale.ROOT)
        for (c in candidates) {
            val cn = c.trim().replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)
            if (normalized == cn) return true
        }
        return false
    }

    /**
     * Creează un subfolder în **Download/DLPulse** sub [parentRelativePath] (gol = rădăcină DLPulse).
     */
    fun createDlpulseSubfolder(context: Context, parentRelativePath: String, rawName: String): Boolean {
        val folderName = sanitizeNewFolderName(rawName) ?: return false
        val rel = normalizeRelativePathInsidePublic(parentRelativePath)
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBFOLDER
        )
        val parentDir = safeResolvedDirectory(baseDir, rel) ?: return false
        if (!parentDir.isDirectory) return false
        val newDir = File(parentDir, folderName)
        if (newDir.exists()) return false
        if (!newDir.mkdir()) return false
        MediaScannerConnection.scanFile(
            context,
            arrayOf(newDir.absolutePath),
            null,
            null
        )
        return true
    }

    private fun sanitizeNewFolderName(raw: String): String? {
        val t = raw.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
        if (t.isEmpty() || t == "." || t == "..") return null
        return if (t.length > 80) t.take(80) else t
    }
}
