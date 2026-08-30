package ro.yt.downloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class SafBrowseListing(
    val subfolders: List<String>,
    val files: List<DownloadedFileEntry>
)

/**
 * Listare foldere + fișiere sub un [treeUri] (OPEN_DOCUMENT_TREE), la calea [pathSegments] din rădăcina tree.
 */
object SafDirectoryListing {

    fun list(context: Context, treeUri: Uri, pathSegments: List<String>): SafBrowseListing {
        var dir = DocumentFile.fromTreeUri(context, treeUri) ?: return SafBrowseListing(emptyList(), emptyList())
        for (seg in pathSegments) {
            dir = dir.findFile(seg) ?: return SafBrowseListing(emptyList(), emptyList())
            if (!dir.isDirectory) return SafBrowseListing(emptyList(), emptyList())
        }
        val folders = mutableListOf<String>()
        val files = mutableListOf<DownloadedFileEntry>()
        val childDocs = dir.listFiles()
        val siblingNames = childDocs.filter { it.isFile }.mapNotNull { it.name }.toSet()
        for (child in childDocs) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                folders.add(name)
            } else if (child.isFile) {
                if (DownloadArtwork.isThumbnailSidecar(name, siblingNames)) continue
                if (DownloadMetadata.isMetadataSidecar(name)) continue
                val mime = child.type?.takeIf { it.isNotBlank() }
                    ?: DownloadMime.guessFromFileName(name)
                val len = runCatching { child.length() }.getOrDefault(-1L)
                files.add(
                    DownloadedFileEntry(
                        title = name,
                        mime = mime,
                        file = null,
                        contentUri = child.uri,
                        sortKey = runCatching { child.lastModified() }.getOrDefault(0L),
                        sizeBytes = len
                    )
                )
            }
        }
        folders.sortWith(String.CASE_INSENSITIVE_ORDER)
        files.sortByDescending { it.sortKey }
        return SafBrowseListing(folders, files)
    }

    fun createSubfolder(context: Context, treeUri: Uri, pathSegments: List<String>, rawName: String): Boolean {
        val safe = sanitizeFolderName(rawName) ?: return false
        val parent = resolveDirectory(context, treeUri, pathSegments) ?: return false
        if (parent.findFile(safe) != null) return false
        return parent.createDirectory(safe) != null
    }

    fun deleteFolder(context: Context, treeUri: Uri, pathSegments: List<String>, folderName: String): Boolean {
        val parent = resolveDirectory(context, treeUri, pathSegments) ?: return false
        val target = parent.findFile(folderName) ?: return false
        if (!target.isDirectory) return false
        return deleteDocumentTree(target)
    }

    private fun deleteDocumentTree(dir: DocumentFile): Boolean {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                if (!deleteDocumentTree(child)) return false
            } else if (child.isFile) {
                if (!child.delete()) return false
            }
        }
        return dir.delete()
    }

    /** Mută un fișier (DocumentFile) în folderul țintă sub [destPathSegments]. */
    fun moveFile(
        context: Context,
        treeUri: Uri,
        entry: DownloadedFileEntry,
        destPathSegments: List<String>
    ): Boolean {
        val uri = entry.contentUri ?: return false
        val destDir = resolveDirectory(context, treeUri, destPathSegments) ?: return false
        if (destDir.findFile(entry.title) != null) return false
        val source = DocumentFile.fromSingleUri(context, uri) ?: return false
        if (!source.exists() || source.isDirectory) return false

        val parent = source.parentFile
        if (parent != null) {
            val moved = runCatching {
                android.provider.DocumentsContract.moveDocument(
                    context.contentResolver,
                    uri,
                    parent.uri,
                    destDir.uri
                )
            }.getOrNull()
            if (moved != null) return true
        }

        val mime = entry.mime.ifBlank { "application/octet-stream" }
        val created = destDir.createFile(mime, entry.title) ?: return false
        return try {
            context.contentResolver.openInputStream(uri)?.use { inp ->
                context.contentResolver.openOutputStream(created.uri)?.use { out ->
                    inp.copyTo(out)
                } ?: return false
            } ?: return false
            source.delete()
        } catch (_: Exception) {
            runCatching { created.delete() }
            false
        }
    }

    /** Listează folderele din tree (cale relativă ca listă de segmente), fără [currentPath]. */
    fun listMoveDestinations(
        context: Context,
        treeUri: Uri,
        currentPath: List<String>
    ): List<List<String>> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = linkedSetOf<List<String>>()
        out.add(emptyList())
        fun walk(dir: DocumentFile, rel: List<String>) {
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                if (!child.isDirectory || name.startsWith(".")) continue
                val childRel = rel + name
                out.add(childRel)
                if (childRel.size < 6) walk(child, childRel)
            }
        }
        walk(root, emptyList())
        return out.filter { it != currentPath }
    }

    private fun resolveDirectory(context: Context, treeUri: Uri, pathSegments: List<String>): DocumentFile? {
        var dir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        for (seg in pathSegments) {
            dir = dir.findFile(seg) ?: return null
            if (!dir.isDirectory) return null
        }
        return dir
    }

    private fun sanitizeFolderName(raw: String): String? {
        val t = raw.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
        if (t.isEmpty() || t == "." || t == "..") return null
        if (t.length > 80) return t.take(80)
        return t
    }
}
