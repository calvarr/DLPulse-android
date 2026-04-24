package ro.yt.downloader

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GitHubReleaseInfo(
    val tagName: String,
    /** Fără prefix „v”, pentru comparare cu [android.content.pm.PackageInfo.versionName]. */
    val versionCore: String,
    val htmlUrl: String,
    val apkBrowserDownloadUrl: String?
)

/**
 * Ultimul release stabil de pe GitHub ([releases/latest](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)).
 */
object GitHubLatestRelease {

    private const val API_LATEST =
        "https://api.github.com/repos/calvarr/DLPulse-android/releases/latest"

    fun fetchLatest(): Result<GitHubReleaseInfo> = runCatching {
        val conn = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "DLPulse-Android-UpdateCheck")
            connectTimeout = 15_000
            readTimeout = 15_000
            useCaches = false
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader()
                .use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            parseJson(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseJson(json: String): GitHubReleaseInfo {
        val o = JSONObject(json)
        val tag = o.getString("tag_name").trim()
        val html = o.getString("html_url")
        val core = AppVersion.normalizeForCompare(tag)
        var apkUrl: String? = null
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                if (url.isNotBlank() && name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = url
                    if (name.startsWith("DLPulse", ignoreCase = true)) break
                }
            }
        }
        return GitHubReleaseInfo(
            tagName = tag,
            versionCore = core,
            htmlUrl = html,
            apkBrowserDownloadUrl = apkUrl
        )
    }
}
