package ro.yt.downloader

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import ro.yt.downloader.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var castContext: CastContext? = null
    private var castMediaCallback: RemoteMediaClient.Callback? = null
    private var inAppCast: PlayerInAppCastHelper? = null
    private var mediaSession: MediaSession? = null
    private var fullscreenUiActive = false

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            inAppCast?.onSessionStarted()
            ensureCastMediaCallback(session)
            updateCastRemoteUi()
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            inAppCast?.onSessionEndedOrAborted()
            unregisterCastMediaCallback()
            updateCastRemoteUi()
        }

        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {
            inAppCast?.onSessionEndedOrAborted()
            unregisterCastMediaCallback()
            updateCastRemoteUi()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            inAppCast?.maybeLoadIfConnectedButIdle()
            ensureCastMediaCallback(session)
            updateCastRemoteUi()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            inAppCast?.onSessionEndedOrAborted()
            unregisterCastMediaCallback()
            updateCastRemoteUi()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val list = intent.getStringArrayExtra(EXTRA_URI_LIST)
        val single = intent.getStringExtra(EXTRA_URI)
        val uriStrings: Array<String> = when {
            !list.isNullOrEmpty() -> list
            !single.isNullOrEmpty() -> arrayOf(single)
            else -> {
                finish()
                return
            }
        }

        val resolvedForCast = resolveDownloadedEntriesForCast(uriStrings)
        val titleOverride = intent.getStringExtra(EXTRA_TITLE)?.trim()?.takeIf { it.isNotEmpty() }

        castContext = runCatching { CastContext.getSharedInstance(this) }.getOrNull()
        if (castContext == null || resolvedForCast.isEmpty()) {
            binding.playerCastRouteButton.visibility = View.GONE
        } else {
            CastRouteUi.setUpMediaRouteButton(this, binding.playerCastRouteButton)
        }

        binding.playerBtnBack.setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (fullscreenUiActive) {
                        applyFullscreenUi(false)
                    } else {
                        finish()
                    }
                }
            }
        )

        binding.playerCastPlayPause.setOnClickListener {
            CastPlaybackHelper.togglePlayPause(this)
            CastStreamService.instance?.refreshMediaNotification()
            updateCastRemoteUi()
        }
        binding.playerCastSeekBack.setOnClickListener {
            CastPlaybackHelper.seekRelative(this, -15_000L)
            CastStreamService.instance?.refreshMediaNotification()
        }
        binding.playerCastSeekFwd.setOnClickListener {
            CastPlaybackHelper.seekRelative(this, 15_000L)
            CastStreamService.instance?.refreshMediaNotification()
        }
        binding.playerCastDisconnect.setOnClickListener {
            CastPlaybackHelper.disconnectCast(this)
            updateCastRemoteUi()
        }

        castContext?.sessionManager?.addSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )

        val needsNetworkWake = uriStrings.any { s ->
            s.startsWith("http://", ignoreCase = true) ||
                s.startsWith("https://", ignoreCase = true)
        }
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN)
            .build()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(
                if (needsNetworkWake) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL
            )
            .build()
        player = exo
        binding.playerView.player = exo
        binding.playerView.setFullscreenButtonClickListener { enterFullscreen ->
            applyFullscreenUi(enterFullscreen)
        }
        exo.setMediaItems(buildMediaItemsForSession(uriStrings, titleOverride))
        exo.prepare()
        exo.playWhenReady = true

        mediaSession = MediaSession.Builder(this, exo)
            .setId("ro.yt.downloader.player")
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, PlayerActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

        if (castContext != null && resolvedForCast.isNotEmpty()) {
            inAppCast = PlayerInAppCastHelper(
                activity = this,
                castContext = castContext!!,
                entries = resolvedForCast,
                currentMediaIndex = { player?.currentMediaItemIndex ?: 0 },
                pauseLocalPlayback = {
                    player?.pause()
                    player?.playWhenReady = false
                }
            )
        }

        castContext?.sessionManager?.currentCastSession?.let { ensureCastMediaCallback(it) }

        updateCastRemoteUi()
    }

    /**
     * Titlu pentru BT / AVRCP; ExoPlayer completează din fișier (ID3 etc.) când există.
     */
    private fun buildMediaItemsForSession(
        uriStrings: Array<String>,
        titleOverride: String?
    ): List<MediaItem> =
        uriStrings.map { uriStr ->
            val uri = Uri.parse(uriStr)
            val entry = resolveDownloadedEntriesForCast(arrayOf(uriStr)).firstOrNull()
            val title = when {
                uriStrings.size == 1 && !titleOverride.isNullOrBlank() -> titleOverride
                else -> entry?.title ?: uri.lastPathSegment ?: uriStr
            }
            MediaItem.Builder()
                .setUri(uri)
                .setMediaId(uriStr)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setDisplayTitle(title)
                        .build()
                )
                .build()
        }

    private fun applyFullscreenUi(enter: Boolean) {
        fullscreenUiActive = enter
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enter) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            binding.playerTopChrome.visibility = View.GONE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            binding.playerTopChrome.visibility = View.VISIBLE
        }
    }

    private fun ensureCastMediaCallback(session: CastSession) {
        unregisterCastMediaCallback()
        val client = session.remoteMediaClient ?: return
        val cb = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                runOnUiThread { updateCastRemoteUi() }
            }
        }
        castMediaCallback = cb
        client.registerCallback(cb)
    }

    private fun unregisterCastMediaCallback() {
        val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
        val cb = castMediaCallback ?: return
        runCatching { client?.unregisterCallback(cb) }
        castMediaCallback = null
    }

    private fun updateCastRemoteUi() {
        val row = binding.playerCastRemoteRow
        val client = CastPlaybackHelper.remoteClient(this)
        val connected = castContext?.sessionManager?.currentCastSession?.isConnected == true
        if (!connected || client == null) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        val meta = client.mediaInfo?.metadata
        binding.playerCastTitle.text =
            meta?.getString(CastMediaMetadata.KEY_TITLE) ?: getString(R.string.cast_bar_title)
        val playing = CastPlaybackHelper.isPlaying(client)
        binding.playerCastPlayPause.setImageResource(
            if (playing) R.drawable.ic_action_pause else R.drawable.ic_action_play
        )
    }

    override fun onStart() {
        super.onStart()
        castContext?.sessionManager?.currentCastSession?.let { ensureCastMediaCallback(it) }
        inAppCast?.maybeLoadIfConnectedButIdle()
        updateCastRemoteUi()
    }

    override fun onStop() {
        unregisterCastMediaCallback()
        super.onStop()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        binding.playerView.player = null
        castContext?.sessionManager?.removeSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        castContext = null
        inAppCast = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_URI_LIST = "uri_list"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MIME = "mime"
    }
}
