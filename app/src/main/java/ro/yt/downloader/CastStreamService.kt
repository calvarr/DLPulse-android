package ro.yt.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient

/**
 * Serviciu în prim-plan: ține NanoHTTPD în viață și afișează notificare cu controale Cast
 * (play/pause, ±15s, oprire, deconectare).
 */
class CastStreamService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var castContext: CastContext? = null
    private var mediaCallback: RemoteMediaClient.Callback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            registerMediaCallback(session)
            refreshMediaNotification()
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            unregisterMediaCallback()
            refreshMediaNotification()
        }

        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {
            unregisterMediaCallback()
            refreshMediaNotification()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            registerMediaCallback(session)
            refreshMediaNotification()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            unregisterMediaCallback()
            refreshMediaNotification()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.cast_stream_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        castContext = runCatching { CastContext.getSharedInstance(this) }.getOrNull()
        castContext?.sessionManager?.addSessionManagerListener(
            sessionListener,
            CastSession::class.java
        )
        val session = castContext?.sessionManager?.currentCastSession
        if (session?.isConnected == true) {
            registerMediaCallback(session)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification(buildNotification())
        acquireWakeLock()
        return START_STICKY
    }

    fun refreshMediaNotification() {
        mainHandler.post {
            startForegroundWithNotification(buildNotification())
        }
    }

    private fun startForegroundWithNotification(notif: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun registerMediaCallback(session: CastSession) {
        unregisterMediaCallback()
        val client = session.remoteMediaClient ?: return
        val cb = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                mainHandler.post { refreshMediaNotification() }
            }
        }
        mediaCallback = cb
        client.registerCallback(cb)
    }

    private fun unregisterMediaCallback() {
        val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
        val cb = mediaCallback ?: return
        runCatching { client?.unregisterCallback(cb) }
        mediaCallback = null
    }

    private fun piBroadcast(action: String, requestCode: Int): PendingIntent {
        val i = Intent(this, CastControlReceiver::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(this, requestCode, i, flags)
    }

    private fun buildNotification(): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this,
            100,
            Intent(this, PublicDownloadsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val client = CastPlaybackHelper.remoteClient(this)
        val connected = castContext?.sessionManager?.currentCastSession?.isConnected == true
        val meta = client?.mediaInfo?.metadata
        val title = meta?.getString(MediaMetadata.KEY_TITLE)
            ?: getString(R.string.cast_stream_notification_title)
        val subtitle = when {
            !connected -> getString(R.string.cast_notif_waiting)
            client == null -> getString(R.string.cast_stream_notification_text)
            else -> getString(R.string.cast_notif_connected)
        }

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_cast)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(connected)

        if (client != null && connected) {
            val playing = CastPlaybackHelper.isPlaying(client)
            val playPauseIcon = if (playing) R.drawable.ic_action_pause else R.drawable.ic_action_play
            val playPauseText = if (playing) {
                getString(R.string.cast_notif_pause)
            } else {
                getString(R.string.cast_notif_play)
            }
            b.addAction(
                playPauseIcon,
                playPauseText,
                piBroadcast(CastControlReceiver.ACTION_TOGGLE, 1)
            )
            b.addAction(
                0,
                getString(R.string.cast_notif_back_15),
                piBroadcast(CastControlReceiver.ACTION_SEEK_BACK, 2)
            )
            b.addAction(
                0,
                getString(R.string.cast_notif_fwd_15),
                piBroadcast(CastControlReceiver.ACTION_SEEK_FWD, 3)
            )
            b.addAction(
                R.drawable.ic_close,
                getString(R.string.cast_notif_disconnect),
                piBroadcast(CastControlReceiver.ACTION_DISCONNECT, 4)
            )
            b.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }

        return b.build()
    }

    override fun onDestroy() {
        unregisterMediaCallback()
        castContext?.sessionManager?.removeSessionManagerListener(
            sessionListener,
            CastSession::class.java
        )
        castContext = null
        instance = null
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ro.yt.downloader:castStream"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "cast_stream"
        private const val NOTIFICATION_ID = 9101

        @Volatile
        var instance: CastStreamService? = null
            private set

        fun start(context: Context) {
            val app = context.applicationContext
            ContextCompat.startForegroundService(
                app,
                Intent(app, CastStreamService::class.java)
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, CastStreamService::class.java)
            )
        }
    }
}
