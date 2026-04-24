package ro.yt.downloader

import android.content.Context
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.media.RemoteMediaClient

/** Acțiuni comune pentru Chromecast (notificare + UI în aplicație). */
object CastPlaybackHelper {

    fun remoteClient(context: Context): RemoteMediaClient? =
        runCatching {
            CastContext.getSharedInstance(context.applicationContext)
                ?.sessionManager
                ?.currentCastSession
                ?.remoteMediaClient
        }.getOrNull()

    fun isPlaying(client: RemoteMediaClient): Boolean {
        val st = client.mediaStatus ?: return false
        return st.playerState == MediaStatus.PLAYER_STATE_PLAYING ||
            st.playerState == MediaStatus.PLAYER_STATE_BUFFERING
    }

    fun togglePlayPause(context: Context): Boolean {
        val client = remoteClient(context) ?: return false
        return if (isPlaying(client)) {
            client.pause()
            true
        } else {
            client.play()
            true
        }
    }

    fun seekRelative(context: Context, deltaMs: Long): Boolean {
        val client = remoteClient(context) ?: return false
        val pos = client.approximateStreamPosition
        val dur = client.streamDuration
        var target = pos + deltaMs
        if (target < 0L) target = 0L
        if (dur > 0L && target > dur) target = dur
        client.seek(target)
        return true
    }

    fun stopMedia(context: Context): Boolean {
        val client = remoteClient(context) ?: return false
        @Suppress("DEPRECATION")
        client.stop()
        return true
    }

    /** Închide proiecția (sesiune + server local + serviciu în prim-plan). */
    fun disconnectCast(context: Context) {
        val app = context.applicationContext
        runCatching {
            CastContext.getSharedInstance(app)?.sessionManager?.endCurrentSession(true)
        }
        LocalStreamHolder.stop()
        CastStreamService.stop(app)
        app.sendBroadcast(
            android.content.Intent(CastUiBridge.ACTION_CAST_UI_RESET).setPackage(app.packageName)
        )
    }
}

object CastUiBridge {
    const val ACTION_CAST_UI_RESET = "ro.yt.downloader.CAST_UI_RESET"
}
