package ro.yt.downloader

import android.content.Context

/**
 * Mesajele yt-dlp includ adesea multe WARNING-uri; pentru UI păstrăm liniile utile.
 */
object YtdlpError {

    fun sanitizeForUser(context: Context, raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return context.getString(R.string.ytdlp_err_unknown)
        val errorLines = text.lines()
            .map { it.trim() }
            .filter { it.startsWith("ERROR", ignoreCase = true) }
        val base = if (errorLines.isNotEmpty()) {
            errorLines.joinToString("\n").take(600)
        } else {
            val noWarnings = text.lines()
                .filterNot { it.trimStart().startsWith("WARNING", ignoreCase = true) }
                .joinToString("\n")
                .trim()
            (if (noWarnings.isNotBlank()) noWarnings else text).take(600)
        }
        return appendNetworkHint(context, base)
    }

    private fun appendNetworkHint(context: Context, msg: String): String {
        val m = msg.lowercase()
        if ("errno 7" in m || "no address associated" in m || "name or service not known" in m) {
            return msg + "\n\n" + context.getString(R.string.ytdlp_hint_network)
        }
        return msg
    }
}
