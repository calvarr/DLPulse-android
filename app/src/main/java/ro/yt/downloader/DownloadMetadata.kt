package ro.yt.downloader

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class MediaMetadataInfo(
    val title: String? = null,
    val uploader: String? = null,
    val channel: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val track: String? = null,
    val durationSeconds: Long? = null,
    val sourceUrl: String? = null
) {
    fun primaryCreator(): String? {
        artist?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        uploader?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return channel?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun subtitle(): String? {
        val creator = primaryCreator()
        val albumPart = album?.trim()?.takeIf { it.isNotEmpty() }
        val trackPart = track?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            artist != null && artist.isNotBlank() -> buildList {
                add(artist.trim())
                albumPart?.let { add(it) }
                trackPart?.let { add(it) }
            }.joinToString(" · ")
            creator != null && albumPart != null -> "$creator · $albumPart"
            creator != null -> creator
            albumPart != null -> albumPart
            else -> null
        }
    }
}

/**
 * Sidecar JSON lângă fișierul media ({basename}.dlpulse.json).
 * La descărcare se convertește din yt-dlp .info.json.
 */
object DownloadMetadata {

    const val SIDECAR_SUFFIX = ".dlpulse.json"
    private const val INFO_JSON_SUFFIX = ".info.json"
    private const val SUBFOLDER = "DLPulse"

    // ConcurrentHashMap nu acceptă null — ținem „fără metadata” separat.
    private val cache = ConcurrentHashMap<String, MediaMetadataInfo>()
    private val emptyKeys = ConcurrentHashMap.newKeySet<String>()
    private val io = Executors.newSingleThreadExecutor()

    fun loadAsync(
        context: Context,
        entry: DownloadedFileEntry,
        onResult: (MediaMetadataInfo?) -> Unit
    ) {
        val appCtx = context.applicationContext
        val key = entry.stableKey()
        if (key in emptyKeys) {
            onResult(null)
            return
        }
        cache[key]?.let {
            onResult(it)
            return
        }
        io.execute {
            val loaded = runCatching { loadUncached(appCtx, entry) }.getOrNull()
            putCache(key, loaded)
            runCatching { onResult(loaded) }
        }
    }

    fun isMetadataSidecar(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(SIDECAR_SUFFIX) || lower.endsWith(INFO_JSON_SUFFIX)
    }

    fun sidecarFileNameForMedia(mediaFileName: String): String {
        val base = mediaFileName.substringBeforeLast('.')
        return "$base$SIDECAR_SUFFIX"
    }

    fun findJsonBeside(media: File): File? {
        val parent = media.parentFile ?: return null
        val base = media.name.substringBeforeLast('.')
        val sidecar = File(parent, "$base$SIDECAR_SUFFIX")
        if (sidecar.isFile && sidecar.length() > 0L) return sidecar
        val infoJson = File(parent, "$base$INFO_JSON_SUFFIX")
        if (infoJson.isFile && infoJson.length() > 0L) return infoJson
        return null
    }

    fun load(context: Context, entry: DownloadedFileEntry): MediaMetadataInfo? {
        val appCtx = context.applicationContext
        val key = entry.stableKey()
        if (key in emptyKeys) return null
        cache[key]?.let { return it }
        val loaded = runCatching { loadUncached(appCtx, entry) }.getOrNull()
        putCache(key, loaded)
        return loaded
    }

    /** Doar din cache — sigur pe UI thread (filtru, bind rapid). */
    fun peekCached(entry: DownloadedFileEntry): MediaMetadataInfo? {
        val key = entry.stableKey()
        if (key in emptyKeys) return null
        return cache[key]
    }

    fun invalidateCache(key: String) {
        cache.remove(key)
        emptyKeys.remove(key)
    }

    fun clearCache() {
        cache.clear()
        emptyKeys.clear()
    }

    private fun putCache(key: String, value: MediaMetadataInfo?) {
        if (value == null) {
            cache.remove(key)
            emptyKeys.add(key)
        } else {
            emptyKeys.remove(key)
            cache[key] = value
        }
    }

    private fun loadUncached(context: Context, entry: DownloadedFileEntry): MediaMetadataInfo? {
        return runCatching {
            val media = resolveMediaFile(context, entry)
            if (media != null) {
                findJsonBeside(media)?.let { json -> loadFromFile(json) }?.let { return@runCatching it }
            }
            loadFromRetriever(context, entry)
        }.getOrNull()
    }

    fun loadFromFile(file: File): MediaMetadataInfo? {
        return runCatching {
            if (!file.isFile || file.length() <= 0L || file.length() > 2_000_000L) return null
            val json = JSONObject(file.readText())
            if (file.name.lowercase(Locale.ROOT).endsWith(INFO_JSON_SUFFIX)) {
                fromYtdlpInfoJson(json)
            } else {
                fromSidecarJson(json)
            }
        }.getOrNull()
    }

    fun writeSidecar(media: File, info: MediaMetadataInfo) {
        val parent = media.parentFile ?: return
        val out = File(parent, sidecarFileNameForMedia(media.name))
        out.writeText(toSidecarJson(info).toString(2))
    }

    /** Convertește {base}.info.json yt-dlp în {base}.dlpulse.json normalizat. */
    fun ingestInfoJsonBeside(media: File): MediaMetadataInfo? {
        val parent = media.parentFile ?: return null
        val base = media.name.substringBeforeLast('.')
        val infoJson = File(parent, "$base$INFO_JSON_SUFFIX")
        if (!infoJson.isFile) return null
        val info = loadFromFile(infoJson) ?: return null
        writeSidecar(media, info)
        runCatching { infoJson.delete() }
        return info
    }

    fun fromYtdlpInfoJson(json: JSONObject): MediaMetadataInfo {
        val artist = firstNonBlank(
            json.optString("artist"),
            json.optString("creator"),
            json.optJSONArray("artists")?.optString(0)
        )
        return MediaMetadataInfo(
            title = json.optString("title").trim().ifBlank { null },
            uploader = json.optString("uploader").trim().ifBlank { null },
            channel = json.optString("channel").trim().ifBlank { null },
            artist = artist,
            album = json.optString("album").trim().ifBlank { null },
            track = json.optString("track").trim().ifBlank { null },
            durationSeconds = durationFromJson(json),
            sourceUrl = firstNonBlank(
                json.optString("webpage_url"),
                json.optString("original_url"),
                json.optString("url")
            )
        )
    }

    fun fromFlatEntry(e: JSONObject): MediaMetadataInfo {
        val artist = firstNonBlank(
            e.optString("artist"),
            e.optString("creator"),
            e.optJSONArray("artists")?.optString(0)
        )
        return MediaMetadataInfo(
            title = e.optString("title").trim().ifBlank { null },
            uploader = e.optString("uploader").trim().ifBlank { null },
            channel = e.optString("channel").trim().ifBlank { null },
            artist = artist,
            album = e.optString("album").trim().ifBlank { null },
            track = e.optString("track").trim().ifBlank { null },
            durationSeconds = durationFromFlatEntry(e),
            sourceUrl = firstNonBlank(
                e.optString("webpage_url"),
                e.optString("url")
            )
        )
    }

    private fun fromSidecarJson(json: JSONObject): MediaMetadataInfo {
        return MediaMetadataInfo(
            title = json.optString("title").trim().ifBlank { null },
            uploader = json.optString("uploader").trim().ifBlank { null },
            channel = json.optString("channel").trim().ifBlank { null },
            artist = json.optString("artist").trim().ifBlank { null },
            album = json.optString("album").trim().ifBlank { null },
            track = json.optString("track").trim().ifBlank { null },
            durationSeconds = json.optLong("durationSeconds", -1L).takeIf { it > 0L },
            sourceUrl = json.optString("sourceUrl").trim().ifBlank { null }
        )
    }

    private fun toSidecarJson(info: MediaMetadataInfo): JSONObject {
        return JSONObject().apply {
            info.title?.let { put("title", it) }
            info.uploader?.let { put("uploader", it) }
            info.channel?.let { put("channel", it) }
            info.artist?.let { put("artist", it) }
            info.album?.let { put("album", it) }
            info.track?.let { put("track", it) }
            info.durationSeconds?.let { put("durationSeconds", it) }
            info.sourceUrl?.let { put("sourceUrl", it) }
        }
    }

    fun resolveMediaFile(context: Context, entry: DownloadedFileEntry): File? {
        entry.file?.takeIf { it.exists() }?.let { return it }
        val name = entry.title
        val rel = relativePathFromUri(context, entry.contentUri) ?: return null
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBFOLDER
        )
        val dir = if (rel.isEmpty()) baseDir else File(baseDir, rel)
        val candidate = File(dir, name)
        return candidate.takeIf { it.isFile }
    }

    fun resolveSidecarFiles(context: Context, entry: DownloadedFileEntry): List<File> {
        val media = resolveMediaFile(context, entry) ?: return emptyList()
        val out = mutableListOf<File>()
        DownloadArtwork.findSidecarBeside(media)?.let { out.add(it) }
        findJsonBeside(media)?.let { out.add(it) }
        return out
    }

    private fun relativePathFromUri(context: Context, uri: Uri?): String? {
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

    private fun loadFromRetriever(context: Context, entry: DownloadedFileEntry): MediaMetadataInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            when {
                entry.file != null && entry.file.exists() ->
                    retriever.setDataSource(entry.file.absolutePath)
                entry.contentUri != null ->
                    retriever.setDataSource(context, entry.contentUri)
                else -> return null
            }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            if (listOf(artist, album, title, author).all { it.isNullOrBlank() } && durationMs == null) {
                return null
            }
            MediaMetadataInfo(
                title = title?.trim()?.ifBlank { null },
                uploader = author?.trim()?.ifBlank { null },
                artist = artist?.trim()?.ifBlank { null },
                album = album?.trim()?.ifBlank { null },
                durationSeconds = durationMs?.let { it / 1000L }?.takeIf { it > 0L }
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun durationFromJson(json: JSONObject): Long? {
        if (json.has("duration") && !json.isNull("duration")) {
            val d = json.optDouble("duration", Double.NaN)
            if (!d.isNaN() && d > 0) return d.toLong()
        }
        return null
    }

    private fun durationFromFlatEntry(e: JSONObject): Long? {
        if (e.has("duration") && !e.isNull("duration")) {
            val d = e.optDouble("duration", Double.NaN)
            if (!d.isNaN() && d > 0) return d.toLong()
        }
        return null
    }

    private fun firstNonBlank(vararg values: String?): String? {
        for (v in values) {
            val t = v?.trim()
            if (!t.isNullOrEmpty()) return t
        }
        return null
    }
}
