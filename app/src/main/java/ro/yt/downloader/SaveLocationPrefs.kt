package ro.yt.downloader

import android.content.Context
import android.net.Uri

enum class SaveDestination {
    PUBLIC_DOWNLOADS,
    PRIVATE_APP_ONLY,
    USER_PICKED_FOLDER
}

class SaveLocationPrefs(context: Context) {

    private val p = context.applicationContext.getSharedPreferences("save_location", Context.MODE_PRIVATE)

    fun getDestination(): SaveDestination {
        val v = p.getInt(KEY_MODE, 0)
        val all = SaveDestination.values()
        val d = if (v in all.indices) all[v] else SaveDestination.PUBLIC_DOWNLOADS
        if (d == SaveDestination.PRIVATE_APP_ONLY) {
            setDestination(SaveDestination.PUBLIC_DOWNLOADS)
            return SaveDestination.PUBLIC_DOWNLOADS
        }
        return d
    }

    fun setDestination(d: SaveDestination) {
        p.edit().putInt(KEY_MODE, d.ordinal).apply()
    }

    /** Cale relativă în Download/DLPulse (gol = rădăcină). */
    fun getDlpulseRelativePath(): String =
        DlpulseStorage.normalizeRelative(p.getString(KEY_DLPULSE_REL, "") ?: "")

    fun setDlpulseRelativePath(path: String) {
        p.edit().putString(KEY_DLPULSE_REL, DlpulseStorage.normalizeRelative(path)).apply()
    }

    fun getTreeUriString(): String? = p.getString(KEY_TREE_URI, null)?.takeIf { it.isNotBlank() }

    fun setTreeUri(uri: Uri?) {
        if (uri == null) {
            p.edit().remove(KEY_TREE_URI).apply()
        } else {
            p.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        }
    }

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_DLPULSE_REL = "dlpulse_rel"
    }
}
