package ro.yt.downloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Acțiuni din notificarea de proiecție (play/pause, seek, oprire).
 */
class CastControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        when (intent?.action) {
            ACTION_TOGGLE -> CastPlaybackHelper.togglePlayPause(app)
            ACTION_SEEK_BACK -> CastPlaybackHelper.seekRelative(app, -15_000L)
            ACTION_SEEK_FWD -> CastPlaybackHelper.seekRelative(app, 15_000L)
            ACTION_STOP_MEDIA -> CastPlaybackHelper.stopMedia(app)
            ACTION_DISCONNECT -> CastPlaybackHelper.disconnectCast(app)
        }
        CastStreamService.instance?.refreshMediaNotification()
    }

    companion object {
        const val ACTION_TOGGLE = "ro.yt.downloader.CAST_TOGGLE"
        const val ACTION_SEEK_BACK = "ro.yt.downloader.CAST_SEEK_BACK"
        const val ACTION_SEEK_FWD = "ro.yt.downloader.CAST_SEEK_FWD"
        const val ACTION_STOP_MEDIA = "ro.yt.downloader.CAST_STOP_MEDIA"
        const val ACTION_DISCONNECT = "ro.yt.downloader.CAST_DISCONNECT"
    }
}
