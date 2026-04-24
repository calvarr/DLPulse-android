package ro.yt.downloader

import android.content.Context

private const val PREFS = "dlpulse_update_check"
private const val KEY_LAST_NETWORK_CHECK_MS = "last_network_check_ms"
private const val KEY_DISMISSED_TAG = "dismissed_offer_tag"
private const val KEY_CACHED_REMOTE_VERSION = "cached_remote_version"
private const val KEY_CACHED_REMOTE_TAG = "cached_remote_tag"

/** Pauză între apeluri la API GitHub (economisește rate limit). */
private const val MIN_INTERVAL_MS = 24L * 60 * 60 * 1000

class UpdateCheckPrefs(context: Context) {

    private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun shouldRunNetworkCheck(): Boolean {
        val last = p.getLong(KEY_LAST_NETWORK_CHECK_MS, 0L)
        return System.currentTimeMillis() - last >= MIN_INTERVAL_MS
    }

    fun markNetworkCheckDone() {
        p.edit().putLong(KEY_LAST_NETWORK_CHECK_MS, System.currentTimeMillis()).apply()
    }

    fun isOfferDismissedForTag(tagName: String): Boolean =
        p.getString(KEY_DISMISSED_TAG, null) == tagName

    fun dismissOfferForTag(tagName: String) {
        p.edit().putString(KEY_DISMISSED_TAG, tagName).apply()
    }

    fun cacheRemoteRelease(versionCore: String, tagName: String) {
        p.edit()
            .putString(KEY_CACHED_REMOTE_VERSION, versionCore)
            .putString(KEY_CACHED_REMOTE_TAG, tagName)
            .apply()
    }

    fun getCachedRemoteVersion(): String? =
        p.getString(KEY_CACHED_REMOTE_VERSION, null)?.takeIf { it.isNotBlank() }
}
