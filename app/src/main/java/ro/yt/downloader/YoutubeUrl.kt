package ro.yt.downloader

import android.net.Uri

/**
 * Playlist-uri / linkuri din YouTube Music folosesc adesea `music.youtube.com`.
 * Pentru yt-dlp e mai stabil același conținut sub `www.youtube.com`.
 */
object YoutubeUrl {

    fun normalize(url: String): String {
        val t = url.trim()
        if (t.isEmpty() || !t.startsWith("http", ignoreCase = true)) return t
        return try {
            val u = Uri.parse(t)
            val host = u.host?.lowercase() ?: return t
            if (host == "music.youtube.com" || host.endsWith(".music.youtube.com")) {
                u.buildUpon()
                    .authority("www.youtube.com")
                    .build()
                    .toString()
            } else {
                t
            }
        } catch (_: Exception) {
            t
        }
    }
}
