package ro.yt.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * Obține un URL HTTP(S) redatabil direct în ExoPlayer (fără browser), prin yt-dlp ``-g``.
 */
object YtdlpPlayUrl {

    private const val YT_EXTRACTOR_PRIMARY = "youtube:player_client=android,web"
    private const val YT_EXTRACTOR_FALLBACK = "youtube:player_client=tv_embedded"

    fun extractStreamUrlForPlayback(context: Context, pageUrl: String): Result<String> {
        val raw = pageUrl.trim()
        val url = if (YoutubeUrl.isYouTubePage(raw)) YoutubeUrl.normalize(raw) else raw
        val attempts: List<Triple<String?, String, Boolean>> =
            if (YoutubeUrl.isYouTubePage(url)) {
                listOf(
                    Triple(YT_EXTRACTOR_PRIMARY, "best[ext=mp4]/best[ext=webm]/best", false),
                    Triple(YT_EXTRACTOR_PRIMARY, "18/best[height<=480]/worst", false),
                    Triple(YT_EXTRACTOR_FALLBACK, "best[ext=mp4]/best[ext=webm]/best", false),
                    Triple(YT_EXTRACTOR_FALLBACK, "18/best[height<=480]/worst", true)
                )
            } else {
                listOf(
                    Triple(null, "bestaudio/best", false),
                    Triple(null, "bestaudio*", true),
                    Triple(null, "best/bestaudio", true)
                )
            }
        var lastError: Throwable? = null
        for ((extractor, format, allowFirstOfMany) in attempts) {
            val r = runExtractWithFormat(context, url, extractor, format, allowFirstOfMany)
            if (r.isSuccess) return r
            lastError = r.exceptionOrNull()
        }
        return Result.failure(
            lastError ?: IllegalStateException(context.getString(R.string.search_play_no_url))
        )
    }

    private fun runExtractWithFormat(
        context: Context,
        pageUrl: String,
        extractorArgs: String?,
        formatSpec: String,
        allowFirstOfMany: Boolean
    ): Result<String> {
        val req = YoutubeDLRequest(pageUrl).apply {
            addOption("--no-warnings")
            addOption("--no-playlist")
            addOption("-f", formatSpec)
            addOption("-g")
            if (!extractorArgs.isNullOrBlank()) {
                addOption("--extractor-args", extractorArgs)
            }
        }
        val resp = runCatching { YoutubeDL.getInstance().execute(req) }.getOrElse { return Result.failure(it) }
        if (resp.exitCode != 0) {
            val msg = (resp.err + "\n" + resp.out).trim().ifBlank {
                context.getString(R.string.err_download_exit_code, resp.exitCode)
            }
            return Result.failure(IllegalStateException(msg))
        }
        val lines = httpLines(resp.out)
        return when {
            lines.size == 1 -> Result.success(lines[0])
            lines.isEmpty() -> Result.failure(
                IllegalStateException(context.getString(R.string.search_play_no_url))
            )
            allowFirstOfMany -> Result.success(lines[0])
            else -> Result.failure(
                IllegalStateException(context.getString(R.string.search_play_multi_stream))
            )
        }
    }

    private fun httpLines(out: String): List<String> =
        out.trim().lines()
            .map { it.trim() }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
}
