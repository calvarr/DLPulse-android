package ro.yt.downloader

import android.content.Context
import android.net.Uri

/** Persistă URI-ul de tree (SAF) pentru navigare în Music / Video / etc. */
class BrowseStoragePrefs(context: Context) {

    private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getSafTreeUri(): Uri? =
        p.getString(KEY_SAF_TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun setSafTreeUri(uri: Uri?) {
        p.edit().apply {
            if (uri == null) remove(KEY_SAF_TREE) else putString(KEY_SAF_TREE, uri.toString())
        }.apply()
    }

    companion object {
        private const val PREFS = "dlpulse_browse_storage"
        private const val KEY_SAF_TREE = "saf_tree_uri"
    }
}
