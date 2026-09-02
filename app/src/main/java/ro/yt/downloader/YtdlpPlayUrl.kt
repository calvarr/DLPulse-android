package ro.yt.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * Obține un URL HTTP(S) redatabil direct în ExoPlayer (fără browser), prin yt-dlp ``-g``.
 */
object YtdlpPlayUrl {

    private val YT_FORMAT_ATTEMPTS = listOf(
        "best[ext=mp4]/best[ext=webm]/best" to false,
        "18/best[height<=480]/worst" to true
    )

    fun extractStreamUrlForPlayback(context: Context, pageUrl: String): Result<String> {
        val raw = pageUrl.trim()
        val url = if (YoutubeUrl.isYouTubePage(raw)) YoutubeUrl.normalize(raw) else raw
        val extractorAttempts: List<String?> =
            if (YoutubeUrl.isYouTubePage(url)) {
                YtdlpYoutubeClients.extractorArgAttempts()
            } else {
                listOf(null)
            }
        val formatAttempts =
            if (YoutubeUrl.isYouTubePage(url)) {
                YT_FORMAT_ATTEMPTS
            } else {
                listOf(
                    "bestaudio/best" to false,
                    "bestaudio*" to true,
                    "best/bestaudio" to true
                )
            }
        var lastError: Throwable? = null
        for (extractor in extractorAttempts) {
            for ((format, allowFirstOfMany) in formatAttempts) {
                val r = runExtractWithFormat(context, url, extractor, format, allowFirstOfMany)
                if (r.isSuccess) return r
                lastError = r.exceptionOrNull()
                val msg = lastError?.message.orEmpty()
                // Bot-check pe clientul curent — treci la următorul client, nu la alt format.
                if (YtdlpYoutubeClients.looksLikeBotOrAuthBlock(msg)) break
            }
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
