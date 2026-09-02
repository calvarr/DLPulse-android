package ro.yt.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject

data class UrlDescribeResult(
    val ok: Boolean,
    val description: String = "",
    val contentType: String = "unknown",
    val count: Int = 0,
    val error: String? = null
)

data class SearchResultItem(
    val id: String,
    val title: String,
    val url: String,
    val thumbnail: String?,
    val durationSeconds: Long? = null,
    val metadata: MediaMetadataInfo? = null
) {
    fun subtitle(): String? = metadata?.subtitle()
}

object YtdlpJson {

    private fun YoutubeDLRequest.addProbeOptions(url: String, extractorArgs: String?) {
        addOption("--no-warnings")
        addOption("-J")
        addOption("--skip-download")
        if (YoutubeUrl.isYouTubePage(url) && !extractorArgs.isNullOrBlank()) {
            addOption("--extractor-args", extractorArgs)
        }
        addOption("--sleep-interval", "2")
    }

    /**
     * Metadate URL (video / playlist / canal), ca /api/info pe server.
     */
    fun describeUrl(context: Context, url: String): UrlDescribeResult {
        val targetUrl = YoutubeUrl.normalize(url)
        val attempts = if (YoutubeUrl.isYouTubePage(targetUrl)) {
            YtdlpYoutubeClients.extractorArgAttempts()
        } else {
            listOf(null)
        }
        var lastError: String? = null
        for (extractorArgs in attempts) {
            val outcome = runCatching {
                val req = YoutubeDLRequest(targetUrl).apply {
                    addProbeOptions(targetUrl, extractorArgs)
                }
                YoutubeDL.getInstance().execute(req)
            }
            val response = outcome.getOrNull()
            if (response == null) {
                lastError = outcome.exceptionOrNull()?.message
                    ?: outcome.exceptionOrNull()?.toString()
                continue
            }
            if (response.exitCode != 0) {
                lastError = (response.err.takeIf { it.isNotBlank() } ?: response.out).ifBlank {
                    context.getString(R.string.ytdlp_err_read_url, response.exitCode)
                }
                continue
            }
            val json = runCatching { JSONObject(response.out.trim()) }.getOrElse {
                return UrlDescribeResult(
                    false,
                    error = context.getString(R.string.ytdlp_err_unexpected_response)
                )
            }
            return parseInfoJson(context, json)
        }
        return UrlDescribeResult(
            false,
            error = YtdlpError.sanitizeForUser(
                context,
                lastError ?: context.getString(R.string.ytdlp_err_unexpected_response)
            )
        )
    }

    private fun parseInfoJson(context: Context, info: JSONObject): UrlDescribeResult {
        val kind = (info.optString("_type", "video")).lowercase()
        val title = info.optString("title").ifBlank {
            context.getString(R.string.ytdlp_no_title)
        }
        val extractor = (info.optString("extractor")).lowercase()
        val idStr = info.optString("id", "")
        val entries = info.optJSONArray("entries")
        val count = when {
            entries != null -> entries.length()
            else -> 0
        }

        return when (kind) {
            "playlist" -> {
                val isChannel = "channel" in extractor || (idStr.isNotEmpty() && "UC" in idStr)
                val desc = if (isChannel) {
                    context.getString(R.string.ytdlp_desc_channel, title, count)
                } else {
                    context.getString(R.string.ytdlp_desc_playlist, title, count)
                }
                val type = if (isChannel) "channel" else "playlist"
                UrlDescribeResult(true, desc, type, count)
            }
            else -> UrlDescribeResult(
                true,
                context.getString(R.string.ytdlp_desc_video, title),
                "video",
                0
            )
        }
    }

    /**
     * Căutare YouTube, ca /api/search (ytsearch12).
     */
    fun searchYoutube(context: Context, query: String, maxResults: Int = 40): Result<List<SearchResultItem>> {
        val searchUrl = "ytsearch$maxResults:$query"
        val response = runCatching {
            val req = YoutubeDLRequest(searchUrl).apply {
                addOption("--no-warnings")
                addOption("-J")
                addOption("--skip-download")
                addOption("--flat-playlist")
                // Flat search: fără player_client forțat (default yt-dlp).
            }
            YoutubeDL.getInstance().execute(req)
        }.getOrElse { e -> return Result.failure(e) }

        if (response.exitCode != 0) {
            val err = response.err.takeIf { it.isNotBlank() } ?: response.out
            return Result.failure(
                IllegalStateException(
                    err.ifBlank { context.getString(R.string.ytdlp_search_failed) }
                )
            )
        }
        val root = runCatching { JSONObject(response.out.trim()) }.getOrElse {
            return Result.failure(
                IllegalStateException(context.getString(R.string.ytdlp_invalid_json))
            )
        }
        if (root.optString("_type", "") != "playlist") {
            return Result.success(emptyList())
        }
        return Result.success(parseFlatPlaylistEntries(context, root))
    }

    /**
     * Căutare SoundCloud (``scsearchN:``), ca în ``yt_core.search_soundcloud``.
     */
    fun searchSoundcloud(context: Context, query: String, maxResults: Int = 40): Result<List<SearchResultItem>> {
        val searchUrl = "scsearch$maxResults:${query.trim()}"
        val response = runCatching {
            val req = YoutubeDLRequest(searchUrl).apply {
                addOption("--no-warnings")
                addOption("-J")
                addOption("--skip-download")
                addOption("--flat-playlist")
            }
            YoutubeDL.getInstance().execute(req)
        }.getOrElse { e -> return Result.failure(e) }

        if (response.exitCode != 0) {
            val err = response.err.takeIf { it.isNotBlank() } ?: response.out
            return Result.failure(
                IllegalStateException(
                    err.ifBlank { context.getString(R.string.ytdlp_search_failed) }
                )
            )
        }
        val root = runCatching { JSONObject(response.out.trim()) }.getOrElse {
            return Result.failure(
                IllegalStateException(context.getString(R.string.ytdlp_invalid_json))
            )
        }
        if (root.optString("_type", "") != "playlist") {
            return Result.success(emptyList())
        }
        return Result.success(parseFlatPlaylistEntries(context, root))
    }

    /**
     * Intrări din playlist / canal (URL), fără a descărca fișiere.
     */
    fun fetchPlaylistEntries(context: Context, url: String): Result<List<SearchResultItem>> {
        val targetUrl = YoutubeUrl.normalize(url)
        val response = runCatching {
            val req = YoutubeDLRequest(targetUrl).apply {
                addOption("--no-warnings")
                addOption("-J")
                addOption("--skip-download")
                addOption("--flat-playlist")
                // Flat playlist: default yt-dlp (fără android/web forțat).
                addOption("--sleep-interval", "2")
            }
            YoutubeDL.getInstance().execute(req)
        }.getOrElse { e -> return Result.failure(e) }

        if (response.exitCode != 0) {
            val err = response.err.takeIf { it.isNotBlank() } ?: response.out
            return Result.failure(
                IllegalStateException(
                    err.ifBlank { context.getString(R.string.ytdlp_playlist_read_failed) }
                )
            )
        }
        val root = runCatching { JSONObject(response.out.trim()) }.getOrElse {
            return Result.failure(
                IllegalStateException(context.getString(R.string.ytdlp_invalid_json))
            )
        }
        if (root.optString("_type", "") != "playlist") {
            return Result.success(emptyList())
        }
        return Result.success(parseFlatPlaylistEntries(context, root))
    }

    private fun durationFromFlatEntry(e: JSONObject): Long? {
        if (e.has("duration") && !e.isNull("duration")) {
            val d = e.optDouble("duration", Double.NaN)
            if (!d.isNaN() && d > 0) return d.toLong()
        }
        val ds = e.optString("duration_string").trim()
        if (ds.isNotEmpty()) {
            val parts = ds.split(":").mapNotNull { it.trim().toLongOrNull() }
            if (parts.isNotEmpty()) {
                var total = 0L
                for (p in parts) total = total * 60 + p
                if (total > 0) return total
            }
        }
        return null
    }

    private fun thumbFromFlatEntry(e: JSONObject): String? {
        val t = e.optString("thumbnail").trim()
        if (t.isNotEmpty()) return t
        val arr = e.optJSONArray("thumbnails") ?: return null
        if (arr.length() == 0) return null
        val last = arr.optJSONObject(arr.length() - 1) ?: return null
        val u = last.optString("url").trim()
        return u.ifBlank { null }
    }

    /**
     * Intrări din playlist flat (YouTube, SoundCloud, …), ca ``_parse_search_entries`` din ``yt_core``.
     */
    private fun parseFlatPlaylistEntries(context: Context, root: JSONObject): List<SearchResultItem> {
        val entries = root.optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<SearchResultItem>()
        val noTitle = context.getString(R.string.ytdlp_no_title)
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            var pageUrl = e.optString("webpage_url").ifBlank { e.optString("url") }.trim()
            if (pageUrl.isBlank()) {
                val idOnly = e.optString("id", "").trim()
                pageUrl = if (idOnly.length == 11 && !idOnly.startsWith("UC")) {
                    "https://www.youtube.com/watch?v=$idOnly"
                } else {
                    ""
                }
            }
            if (pageUrl.isBlank()) continue

            val pageUrlNorm = if (YoutubeUrl.isYouTubePage(pageUrl)) {
                YoutubeUrl.normalize(pageUrl)
            } else {
                pageUrl
            }

            val idStr = e.optString("id", "").ifBlank { pageUrlNorm }
            val title = e.optString("title").ifBlank { noTitle }

            var thumb = thumbFromFlatEntry(e)
            if (thumb.isNullOrBlank()) {
                val vid = idStr.trim()
                val isYtVideoId = vid.length == 11 && !vid.startsWith("UC")
                if (isYtVideoId && YoutubeUrl.isYouTubePage(pageUrlNorm)) {
                    thumb = "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
                }
            }
            val durationSeconds = durationFromFlatEntry(e)
            val metadata = DownloadMetadata.fromFlatEntry(e)
            out.add(SearchResultItem(idStr, title, pageUrlNorm, thumb, durationSeconds, metadata))
        }
        return out
    }
}
