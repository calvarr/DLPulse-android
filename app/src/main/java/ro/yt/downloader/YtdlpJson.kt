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
    val thumbnail: String?
)

object YtdlpJson {

    private fun YoutubeDLRequest.addProbeOptions() {
        addOption("--no-warnings")
        addOption("-J")
        addOption("--skip-download")
        addOption("--extractor-args", "youtube:player_client=android,web")
        addOption("--sleep-interval", "2")
    }

    /**
     * Metadate URL (video / playlist / canal), ca /api/info pe server.
     */
    fun describeUrl(context: Context, url: String): UrlDescribeResult {
        val targetUrl = YoutubeUrl.normalize(url)
        val response = runCatching {
            val req = YoutubeDLRequest(targetUrl).apply {
                addProbeOptions()
            }
            YoutubeDL.getInstance().execute(req)
        }.getOrElse { e ->
            return UrlDescribeResult(false, error = e.message ?: e.toString())
        }
        if (response.exitCode != 0) {
            val err = response.err.takeIf { it.isNotBlank() } ?: response.out
            return UrlDescribeResult(
                false,
                error = err.ifBlank {
                    context.getString(R.string.ytdlp_err_read_url, response.exitCode)
                }
            )
        }
        val json = runCatching { JSONObject(response.out.trim()) }.getOrElse {
            return UrlDescribeResult(
                false,
                error = context.getString(R.string.ytdlp_err_unexpected_response)
            )
        }
        return parseInfoJson(context, json)
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
    fun searchYoutube(context: Context, query: String, maxResults: Int = 12): Result<List<SearchResultItem>> {
        val searchUrl = "ytsearch$maxResults:$query"
        val response = runCatching {
            val req = YoutubeDLRequest(searchUrl).apply {
                addOption("--no-warnings")
                addOption("-J")
                addOption("--skip-download")
                addOption("--flat-playlist")
                addOption("--extractor-args", "youtube:player_client=android,web")
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
        return Result.success(parsePlaylistEntries(context, root))
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
                addOption("--extractor-args", "youtube:player_client=android,web")
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
        return Result.success(parsePlaylistEntries(context, root))
    }

    private fun parsePlaylistEntries(context: Context, root: JSONObject): List<SearchResultItem> {
        val entries = root.optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<SearchResultItem>()
        val noTitle = context.getString(R.string.ytdlp_no_title)
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            var vid = e.optString("id", "")
            if (vid.isEmpty()) {
                val u = e.optString("url", "")
                vid = u.substringAfter("watch?v=").substringBefore("&").trim()
            }
            if (vid.isEmpty()) continue
            val title = e.optString("title").ifBlank { noTitle }
            val pageUrl = e.optString("url").ifBlank { "https://www.youtube.com/watch?v=$vid" }
            val isVideoId = vid.length == 11 && !vid.startsWith("UC")
            var thumb = e.optString("thumbnail").takeIf { it.isNotBlank() }
            if (thumb == null && isVideoId) {
                thumb = "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
            }
            out.add(SearchResultItem(vid, title, pageUrl, thumb))
        }
        return out
    }
}
