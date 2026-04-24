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
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                folders.add(name)
            } else if (child.isFile) {
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
