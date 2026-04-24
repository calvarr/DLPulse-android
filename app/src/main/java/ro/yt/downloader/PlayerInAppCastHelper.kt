package ro.yt.downloader

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import java.io.File

/**
 * Proiecție din [PlayerActivity]: pornește [LocalStreamHolder], încarcă media pe sesiunea Cast
 * (același flux ca în [PublicDownloadsActivity]).
 */
class PlayerInAppCastHelper(
    private val activity: AppCompatActivity,
    private val castContext: CastContext,
    private val entries: List<DownloadedFileEntry>,
    private val currentMediaIndex: () -> Int,
    private val pauseLocalPlayback: () -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var castOpToken = 0
    private var pendingLoadRunnable: Runnable? = null

    private data class PendingCast(
        val url: String,
        val mime: String,
        val title: String,
        val opToken: Int,
    )

    fun onSessionStarted() {
        if (entries.isEmpty()) return
        CastStreamService.start(activity)
        pauseLocalPlayback()
        loadCurrentTrack()
    }

    fun onSessionEndedOrAborted() {
        castOpToken++
        pendingLoadRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingLoadRunnable = null
        LocalStreamHolder.stop()
        CastStreamService.stop(activity.applicationContext)
    }

    /** Dacă utilizatorul intră în player cu Cast deja conectat, dar fără media încărcată. */
    fun maybeLoadIfConnectedButIdle() {
        if (entries.isEmpty()) return
        val session = castContext.sessionManager.currentCastSession ?: return
        if (!session.isConnected) return
        val client = session.remoteMediaClient ?: return
        val st = client.mediaStatus?.playerState
        val busy = st == MediaStatus.PLAYER_STATE_PLAYING ||
            st == MediaStatus.PLAYER_STATE_BUFFERING ||
            st == MediaStatus.PLAYER_STATE_LOADING
        if (busy) return
        if (client.mediaInfo != null) return
        CastStreamService.start(activity)
        pauseLocalPlayback()
        loadCurrentTrack()
    }

    private fun isUiSafe(): Boolean =
        !activity.isFinishing && !activity.isDestroyed

    private fun streamMime(entry: DownloadedFileEntry): String {
        return if (entry.file != null) {
            val mr = CastMimeHelper.forFile(entry.file)
            if (!mr.supported && mr.hintResId != null) {
                val resId = mr.hintResId
                mainHandler.post {
                    if (isUiSafe()) {
                        Toast.makeText(activity, resId, Toast.LENGTH_LONG).show()
                    }
                }
            }
            DownloadMime.normalizeForCast(mr.mime, entry.title)
        } else {
            DownloadMime.normalizeForCast(entry.mime, entry.title)
        }
    }

    private fun openStream(entry: DownloadedFileEntry, opToken: Int): PendingCast? {
        val mime = streamMime(entry)
        val url = LocalStreamHolder.ensureStreamRunning(
            activity,
            entry.file,
            entry.contentUri,
            mime
        ) ?: run {
            if (isUiSafe()) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.player_cast_no_http),
                    Toast.LENGTH_LONG
                ).show()
            }
            return null
        }
        return PendingCast(url, mime, entry.title, opToken)
    }

    private fun loadCurrentTrack() {
        castOpToken++
        val opToken = castOpToken
        val idx = currentMediaIndex().coerceIn(0, entries.lastIndex)
        val entry = entries[idx]
        val client = castContext.sessionManager.currentCastSession?.remoteMediaClient
        if (client == null) return
        val pending = openStream(entry, opToken) ?: return
        safeStopThenLoad(client, pending)
    }

    private fun safeStopThenLoad(client: RemoteMediaClient, pending: PendingCast) {
        val token = pending.opToken
        val proceed = Runnable {
            if (!isUiSafe() || token != castOpToken) return@Runnable
            scheduleLoadCast(pending)
        }
        try {
            @Suppress("DEPRECATION")
            client.stop().setResultCallback { mainHandler.post(proceed) }
        } catch (_: Exception) {
            mainHandler.postDelayed(proceed, 350L)
        }
    }

    private fun scheduleLoadCast(p: PendingCast) {
        pendingLoadRunnable?.let { mainHandler.removeCallbacks(it) }
        val token = p.opToken
        val r = Runnable {
            if (!isUiSafe() || token != castOpToken) return@Runnable
            loadCastMedia(p.url, p.mime, p.title, 0, token)
        }
        pendingLoadRunnable = r
        mainHandler.postDelayed(r, 900L)
    }

    private fun loadCastMedia(url: String, mime: String, title: String, attempt: Int, opToken: Int) {
        if (opToken != castOpToken) return
        val client = castContext.sessionManager.currentCastSession?.remoteMediaClient
        if (client == null) {
            if (isUiSafe()) {
                Toast.makeText(activity, R.string.player_cast_no_session, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val castMime = DownloadMime.normalizeForCast(mime, title)
        val metaType =
            if (castMime.startsWith("audio")) MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
            else MediaMetadata.MEDIA_TYPE_MOVIE
        val metadata = MediaMetadata(metaType).apply {
            putString(MediaMetadata.KEY_TITLE, title)
        }
        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(castMime)
            .setMetadata(metadata)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()
        Thread {
            val headOk = NetworkUtil.isHttpHeadOk(url)
            mainHandler.post {
                if (!isUiSafe() || opToken != castOpToken) return@post
                if (!headOk && isUiSafe()) {
                    Toast.makeText(
                        activity,
                        R.string.player_cast_head_warn,
                        Toast.LENGTH_LONG
                    ).show()
                }
                executeCastLoad(client, request, url, castMime, title, attempt, opToken)
            }
        }.start()
    }

    private fun executeCastLoad(
        client: RemoteMediaClient,
        request: MediaLoadRequestData,
        url: String,
        castMime: String,
        title: String,
        attempt: Int,
        opToken: Int,
    ) {
        try {
            @Suppress("DEPRECATION")
            client.load(request).setResultCallback { result ->
                mainHandler.post {
                    if (!isUiSafe() || opToken != castOpToken) return@post
                    if (!result.status.isSuccess) {
                        if (attempt < 2) {
                            mainHandler.postDelayed(
                                { loadCastMedia(url, castMime, title, attempt + 1, opToken) },
                                1800L
                            )
                        } else if (isUiSafe()) {
                            Toast.makeText(
                                activity,
                                activity.getString(
                                    R.string.player_cast_load_failed,
                                    result.status.statusMessage ?: result.status.toString()
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            if (attempt < 2 && isUiSafe()) {
                mainHandler.postDelayed(
                    { loadCastMedia(url, castMime, title, attempt + 1, opToken) },
                    1800L
                )
            }
        }
    }
}

fun Context.resolveDownloadedEntriesForCast(uriStrings: Array<out String>): List<DownloadedFileEntry> =
    uriStrings.mapNotNull { resolveOneUriForCast(it) }

private fun Context.resolveOneUriForCast(uriString: String): DownloadedFileEntry? {
    val uri = Uri.parse(uriString)
    return when (uri.scheme?.lowercase()) {
        "content" -> {
            val mime = contentResolver.getType(uri)?.trim()?.takeIf { it.isNotEmpty() }
                ?: DownloadMime.guessFromFileName(
                    displayNameForContentUri(uri) ?: uri.lastPathSegment ?: ""
                )
            val title = displayNameForContentUri(uri)
                ?: uri.lastPathSegment
                ?: getString(R.string.default_media_title)
            DownloadedFileEntry(title, mime, null, uri, 0L)
        }
        "file" -> {
            val path = uri.path ?: return null
            val f = File(path)
            if (!f.isFile) return null
            val mime = DownloadMime.guessFromFileName(f.name)
            DownloadedFileEntry(f.name, mime, f, null, f.length())
        }
        else -> null
    }
}

private fun Context.displayNameForContentUri(uri: Uri): String? {
    if (uri.scheme != "content") return null
    return contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
}
