package ro.yt.downloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/**
 * Thumbnail pentru fișiere salvate: sidecar (.jpg) de la yt-dlp, artwork încorporat, sau frame video.
 */
object DownloadArtwork {

    private val io = Executors.newFixedThreadPool(2)
    private val memory = object : LruCache<String, Bitmap>(24) {}

    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "image")

    fun isImageFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in imageExts
    }

    /** Fișiere imagine care sunt doar coperți lângă un media cu același basename. */
    fun isThumbnailSidecar(name: String, siblingNames: Set<String>): Boolean {
        if (!isImageFileName(name)) return false
        val base = name.substringBeforeLast('.')
        return siblingNames.any { sibling ->
            if (isImageFileName(sibling)) return@any false
            sibling.substringBeforeLast('.') == base
        }
    }

    fun findSidecarFile(entry: DownloadedFileEntry): File? {
        val media = entry.file ?: return null
        val parent = media.parentFile ?: return null
        val base = media.name.substringBeforeLast('.')
        for (ext in listOf("jpg", "jpeg", "png", "webp")) {
            val candidate = File(parent, "$base.$ext")
            if (candidate.isFile && candidate.length() > 0L) return candidate
        }
        return null
    }

    fun findSidecarBeside(media: File): File? {
        val parent = media.parentFile ?: return null
        val base = media.name.substringBeforeLast('.')
        for (ext in listOf("jpg", "jpeg", "png", "webp")) {
            val candidate = File(parent, "$base.$ext")
            if (candidate.isFile && candidate.length() > 0L) return candidate
        }
        return null
    }

    fun bind(imageView: ImageView, entry: DownloadedFileEntry, placeholderRes: Int) {
        val key = entry.stableKey()
        imageView.tag = key
        memory.get(key)?.let {
            imageView.setImageBitmap(it)
            return
        }
        findSidecarFile(entry)?.let { file ->
            imageView.setImageResource(placeholderRes)
            io.execute {
                val bmp = decodeSampled(file, 256, 144)
                if (bmp != null) {
                    memory.put(key, bmp)
                    imageView.post {
                        if (imageView.tag == key) imageView.setImageBitmap(bmp)
                    }
                }
            }
            return
        }
        imageView.setImageResource(placeholderRes)
        val appCtx = imageView.context.applicationContext
        io.execute {
            val bmp = extractBitmap(appCtx, entry)
            if (bmp != null) {
                memory.put(key, bmp)
                imageView.post {
                    if (imageView.tag == key) imageView.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun extractBitmap(context: Context, entry: DownloadedFileEntry): Bitmap? {
        embeddedPicture(context, entry)?.let { bytes ->
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            entry.contentUri?.let { uri ->
                runCatching {
                    context.contentResolver.loadThumbnail(uri, Size(256, 144), null)
                }.getOrNull()?.let { return it }
            }
        }
        return videoFrame(context, entry)
    }

    private fun embeddedPicture(context: Context, entry: DownloadedFileEntry): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            when {
                entry.file != null && entry.file.exists() ->
                    retriever.setDataSource(entry.file.absolutePath)
                entry.contentUri != null ->
                    retriever.setDataSource(context, entry.contentUri)
                else -> return null
            }
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun videoFrame(context: Context, entry: DownloadedFileEntry): Bitmap? {
        val mime = entry.mime.lowercase()
        val looksVideo = mime.startsWith("video/") ||
            entry.title.endsWith(".mp4", true) ||
            entry.title.endsWith(".webm", true) ||
            entry.title.endsWith(".mkv", true)
        if (!looksVideo) return null
        val retriever = MediaMetadataRetriever()
        return try {
            when {
                entry.file != null && entry.file.exists() ->
                    retriever.setDataSource(entry.file.absolutePath)
                entry.contentUri != null ->
                    retriever.setDataSource(context, entry.contentUri)
                else -> return null
            }
            retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeSampled(file: File, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            var halfH = bounds.outHeight / 2
            var halfW = bounds.outWidth / 2
            while (halfH / sample >= reqH && halfW / sample >= reqW) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }
}
