package ro.yt.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject

/**
 * Stream redatabil în ExoPlayer (URL + headere; uneori video+audio separate).
 * Download-ul merge cu ffmpeg; play-ul direct trebuie să gestioneze CDN YouTube (headere)
 * și formatele DASH (două URL-uri).
 */
data class PlayableStream(
    val videoUrl: String,
    val audioUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Obține URL-uri HTTP(S) pentru redare directă, prin yt-dlp ``-J`` (nu doar ``-g``).
 */
object YtdlpPlayUrl {

    /**
     * Preferă progressive (un singur URL), apoi DASH video+audio (Exo merge), apoi best-effort.
     */
    private val YT_FORMAT_ATTEMPTS = listOf(
        "b*[vcodec!=none][acodec!=none][protocol^=http]/b[ext=mp4]/18/22",
        "bv*[height<=720][protocol^=http]+ba[protocol^=http]/b",
        "bv*+ba/b",
        "b/best",
        "worst"
    )

    private val NON_YT_FORMAT_ATTEMPTS = listOf(
        "bestaudio/best",
        "bestaudio*",
        "best/bestaudio",
        "b/best"
    )

    fun extractForPlayback(context: Context, pageUrl: String): Result<PlayableStream> {
        val raw = pageUrl.trim()
        val url = if (YoutubeUrl.isYouTubePage(raw)) YoutubeUrl.normalize(raw) else raw
        val extractorAttempts: List<String?> =
            if (YoutubeUrl.isYouTubePage(url)) {
                YtdlpYoutubeClients.extractorArgAttempts()
            } else {
                listOf(null)
            }
        val formatAttempts =
            if (YoutubeUrl.isYouTubePage(url)) YT_FORMAT_ATTEMPTS else NON_YT_FORMAT_ATTEMPTS

        var lastError: Throwable? = null
        for (extractor in extractorAttempts) {
            for (format in formatAttempts) {
                val r = runExtractJson(context, url, extractor, format)
                if (r.isSuccess) return r
                lastError = r.exceptionOrNull()
                val msg = lastError?.message.orEmpty()
                if (YtdlpYoutubeClients.looksLikeBotOrAuthBlock(msg)) break
            }
        }
        // Fallback vechi: -g (uneori funcționează când -J e zgomotos).
        for (extractor in extractorAttempts) {
            val r = runExtractGetUrl(context, url, extractor)
            if (r.isSuccess) return r
            lastError = r.exceptionOrNull()
            if (YtdlpYoutubeClients.looksLikeBotOrAuthBlock(lastError?.message.orEmpty())) break
        }
        return Result.failure(
            lastError ?: IllegalStateException(context.getString(R.string.search_play_no_url))
        )
    }

    /** Compat: doar URL principal (fără headere / audio separat). */
    fun extractStreamUrlForPlayback(context: Context, pageUrl: String): Result<String> =
        extractForPlayback(context, pageUrl).map { it.videoUrl }

    private fun runExtractJson(
        context: Context,
        pageUrl: String,
        extractorArgs: String?,
        formatSpec: String
    ): Result<PlayableStream> {
        val req = YoutubeDLRequest(pageUrl).apply {
            addOption("--no-warnings")
            addOption("--no-playlist")
            addOption("-f", formatSpec)
            addOption("-J")
            addOption("--skip-download")
            if (!extractorArgs.isNullOrBlank()) {
                addOption("--extractor-args", extractorArgs)
            }
        }
        val resp = runCatching { YoutubeDL.getInstance().execute(req) }.getOrElse {
            return Result.failure(it)
        }
        if (resp.exitCode != 0) {
            val msg = (resp.err + "\n" + resp.out).trim().ifBlank {
                context.getString(R.string.err_download_exit_code, resp.exitCode)
            }
            return Result.failure(IllegalStateException(msg))
        }
        val jsonText = resp.out.trim().lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("{") }
            ?: return Result.failure(
                IllegalStateException(context.getString(R.string.search_play_no_url))
            )
        val root = runCatching { JSONObject(jsonText) }.getOrElse {
            return Result.failure(
                IllegalStateException(context.getString(R.string.ytdlp_invalid_json))
            )
        }
        return parsePlayableFromInfo(context, root)
    }

    private fun parsePlayableFromInfo(context: Context, info: JSONObject): Result<PlayableStream> {
        val requested = info.optJSONArray("requested_formats")
        if (requested != null && requested.length() >= 2) {
            val a = requested.optJSONObject(0)
            val b = requested.optJSONObject(1)
            val urlA = a?.optString("url").orEmpty().trim()
            val urlB = b?.optString("url").orEmpty().trim()
            if (urlA.startsWith("http") && urlB.startsWith("http")) {
                val vHasVideo = a.optString("vcodec").let { it.isNotBlank() && it != "none" }
                val videoObj = if (vHasVideo) a else b
                val audioObj = if (vHasVideo) b else a
                val videoUrl = videoObj.optString("url").trim()
                val audioUrl = audioObj.optString("url").trim()
                val headers = mergeHeaders(
                    headersFrom(videoObj),
                    headersFrom(audioObj),
                    headersFrom(info)
                )
                if (videoUrl.startsWith("http") && audioUrl.startsWith("http")) {
                    return Result.success(PlayableStream(videoUrl, audioUrl, headers))
                }
            }
        }

        val url = info.optString("url").trim()
        if (url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        ) {
            return Result.success(
                PlayableStream(
                    videoUrl = url,
                    audioUrl = null,
                    headers = headersFrom(info)
                )
            )
        }
        return Result.failure(
            IllegalStateException(context.getString(R.string.search_play_no_url))
        )
    }

    private fun headersFrom(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val h = obj.optJSONObject("http_headers") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = h.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = h.optString(k, "").trim()
            if (k.isNotBlank() && v.isNotBlank()) out[k] = v
        }
        return out
    }

    private fun mergeHeaders(vararg maps: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in maps) out.putAll(m)
        // Referer ajută CDN-ul YouTube când lipsește din dump.
        if (!out.keys.any { it.equals("Referer", ignoreCase = true) }) {
            out["Referer"] = "https://www.youtube.com/"
        }
        if (!out.keys.any { it.equals("Origin", ignoreCase = true) }) {
            out["Origin"] = "https://www.youtube.com"
        }
        return out
    }

    private fun runExtractGetUrl(
        context: Context,
        pageUrl: String,
        extractorArgs: String?
    ): Result<PlayableStream> {
        val formats = listOf(
            "b*[vcodec!=none][acodec!=none]/b" to false,
            "bv*+ba/b" to true,
            "18/best[height<=480]/worst" to true
        )
        var last: Throwable? = null
        for ((format, allowMulti) in formats) {
            val outcome = runCatching {
                val req = YoutubeDLRequest(pageUrl).apply {
                    addOption("--no-warnings")
                    addOption("--no-playlist")
                    addOption("-f", format)
                    addOption("-g")
                    if (!extractorArgs.isNullOrBlank()) {
                        addOption("--extractor-args", extractorArgs)
                    }
                }
                YoutubeDL.getInstance().execute(req)
            }
            val resp = outcome.getOrNull()
            if (resp == null) {
                last = outcome.exceptionOrNull()
                continue
            }
            if (resp.exitCode != 0) {
                last = IllegalStateException(
                    (resp.err + "\n" + resp.out).trim().ifBlank {
                        context.getString(R.string.err_download_exit_code, resp.exitCode)
                    }
                )
                continue
            }
            val lines = httpLines(resp.out)
            when {
                lines.size == 1 -> {
                    return Result.success(
                        PlayableStream(
                            videoUrl = lines[0],
                            headers = defaultYoutubeHeaders(pageUrl)
                        )
                    )
                }
                lines.size >= 2 && allowMulti -> {
                    return Result.success(
                        PlayableStream(
                            videoUrl = lines[0],
                            audioUrl = lines[1],
                            headers = defaultYoutubeHeaders(pageUrl)
                        )
                    )
                }
                lines.isEmpty() -> {
                    last = IllegalStateException(context.getString(R.string.search_play_no_url))
                }
                else -> {
                    last = IllegalStateException(context.getString(R.string.search_play_multi_stream))
                }
            }
        }
        return Result.failure(
            last ?: IllegalStateException(context.getString(R.string.search_play_no_url))
        )
    }

    private fun defaultYoutubeHeaders(pageUrl: String): Map<String, String> {
        if (!YoutubeUrl.isYouTubePage(pageUrl)) return emptyMap()
        return mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Referer" to "https://www.youtube.com/",
            "Origin" to "https://www.youtube.com"
        )
    }

    private fun httpLines(out: String): List<String> =
        out.trim().lines()
            .map { it.trim() }
            .filter {
                it.startsWith("http://", ignoreCase = true) ||
                    it.startsWith("https://", ignoreCase = true)
            }
}
