package ro.yt.downloader

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.app.ShareCompat

object DownloadFileActions {

    fun openPlayerInApp(activity: Activity, entry: DownloadedFileEntry) {
        openPlayerPlaylist(activity, listOf(entry))
    }

    /** Redare ExoPlayer: unul sau mai mulți itemi ca playlist (ordinea din listă). */
    fun openPlayerPlaylist(activity: Activity, entries: List<DownloadedFileEntry>) {
        if (entries.isEmpty()) return
        try {
            val uris = entries.map { it.playUri(activity).toString() }.toTypedArray()
            activity.startActivity(
                Intent(activity, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_URI_LIST, uris)
                }
            )
        } catch (e: Exception) {
            Toast.makeText(
                activity,
                activity.getString(R.string.download_cannot_play, e.message ?: activity.getString(R.string.err_generic)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun openPlayerExternal(activity: Activity, entry: DownloadedFileEntry) {
        try {
            val uri = entry.playUri(activity)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, entry.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(
                Intent.createChooser(view, activity.getString(R.string.download_chooser_external))
            )
        } catch (_: Exception) {
            Toast.makeText(
                activity,
                R.string.download_no_player_app,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun share(activity: Activity, entry: DownloadedFileEntry) {
        try {
            val uri = entry.playUri(activity)
            val mime = entry.mime.ifBlank { "*/*" }
            ShareCompat.IntentBuilder(activity)
                .setStream(uri)
                .setType(mime)
                .setChooserTitle(activity.getString(R.string.share_chooser_title))
                .apply {
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.clipData = ClipData.newUri(
                        activity.contentResolver,
                        entry.title,
                        uri
                    )
                }
                .startChooser()
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun shareMultiple(activity: Activity, entries: List<DownloadedFileEntry>) {
        if (entries.isEmpty()) return
        try {
            val uris = ArrayList(entries.map { it.playUri(activity) })
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(
                    activity.contentResolver,
                    entries[0].title,
                    uris[0]
                ).also { cd ->
                    for (i in 1 until uris.size) {
                        cd.addItem(ClipData.Item(uris[i]))
                    }
                }
            }
            activity.startActivity(
                Intent.createChooser(send, activity.getString(R.string.share_chooser_multiple_title))
            )
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
