package ro.yt.downloader

object DownloadMime {
    fun guessFromFileName(name: String): String = when {
        name.endsWith(".mp4", true) -> "video/mp4"
        name.endsWith(".webm", true) -> "video/webm"
        name.endsWith(".mkv", true) -> "video/x-matroska"
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".opus", true) -> "audio/opus"
        name.endsWith(".ogg", true) -> "audio/ogg"
        else -> "application/octet-stream"
    }

    /** MIME stabil pentru Default Media Receiver (Chromecast). */
    fun normalizeForCast(mime: String, fileName: String): String {
        val m = mime.trim().lowercase()
        if (m.startsWith("video/") || m.startsWith("audio/")) {
            return m
        }
        val g = guessFromFileName(fileName)
        return if (g != "application/octet-stream") g else "video/mp4"
    }
}
