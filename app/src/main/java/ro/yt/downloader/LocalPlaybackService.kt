package ro.yt.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/**
 * Foreground service that owns an ExoPlayer instance for local playback.
 * The PlayerActivity binds to this service to show a UI PlayerView while the
 * service keeps playback alive when the app is backgrounded / screen is locked.
 */
class LocalPlaybackService : Service() {

    private val CHANNEL_ID = "local_playback"
    private val NOTIFICATION_ID = 9201

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var player: ExoPlayer
    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): LocalPlaybackService = this@LocalPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Ensure ExoPlayer keeps device awake during playback when appropriate
        player.setWakeMode(C.WAKE_MODE_LOCAL)

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.cast_stream_notification_title)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Accept a list of URIs passed as EXTRA_URI_LIST or a single EXTRA_URI
        val list = intent?.getStringArrayExtra(PlayerActivity.EXTRA_URI_LIST)
        val single = intent?.getStringExtra(PlayerActivity.EXTRA_URI)
        val uriStrings: Array<String>? = when {
            !list.isNullOrEmpty() -> list
            !single.isNullOrEmpty() -> arrayOf(single)
            else -> null
        }

        if (!uriStrings.isNullOrEmpty()) {
            val titleOverride = intent.getStringExtra(PlayerActivity.EXTRA_TITLE)
            val mediaItems = uriStrings.map { uriStr ->
                val title = when {
                    uriStrings.size == 1 && !titleOverride.isNullOrBlank() -> titleOverride
                    else -> uriStr
                }
                MediaItem.Builder()
                    .setUri(uriStr)
                    .setMediaId(uriStr)
                    .build()
            }
            player.setMediaItems(mediaItems)
            player.prepare()
            player.playWhenReady = true
            // Update notification title if provided
            val notif = buildNotification(titleOverride ?: getString(R.string.cast_stream_notification_title))
            ContextCompat.startForegroundService(applicationContext, Intent(applicationContext, LocalPlaybackService::class.java))
            // Update foreground notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                startForeground(NOTIFICATION_ID, notif)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notif)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // Keep service running even if unbound - activity may come back later
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try {
            player.release()
        } catch (e: Exception) { /* ignore */ }
        releaseWakeLock()
        stopForeground(true)
        super.onDestroy()
    }

    fun getPlayer(): ExoPlayer = player

    private fun buildNotification(text: String): android.app.Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_cast)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        return b.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.cast_stream_channel_name), NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ro.yt.downloader:localPlayback").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }
}
