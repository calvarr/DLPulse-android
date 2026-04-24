package ro.yt.downloader

import android.util.Patterns

object ShareUrlParser {

    /** Extrage primul URL http(s) din textul partajat (titlu + link, etc.). */
    fun firstHttpUrl(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val m = Patterns.WEB_URL.matcher(t)
        return if (m.find()) m.group().trim() else null
    }
}
