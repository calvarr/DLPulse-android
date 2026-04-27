package ro.yt.downloader

import android.net.Uri

/**
 * Playlist-uri / linkuri din YouTube Music folosesc adesea `music.youtube.com`.
 * Pentru yt-dlp e mai stabil același conținut sub `www.youtube.com`.
 */
object YoutubeUrl {

    /** True for watch / youtu.be / Shorts / Music hosts (not bare ``soundcloud.com`` etc.). */
    fun isYouTubePage(url: String): Boolean {
        val t = url.trim()
        if (t.isEmpty() || !t.startsWith("http", ignoreCase = true)) return false
        return try {
            val h = Uri.parse(t).host?.lowercase() ?: return false
            h == "youtu.be" ||
                h.contains("youtube.com") ||
                h.endsWith(".youtube.com")
        } catch (_: Exception) {
            false
        }
    }

    fun normalize(url: String): String {
        val t = url.trim()
        if (t.isEmpty() || !t.startsWith("http", ignoreCase = true)) return t
        if (!isYouTubePage(t)) return t
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
