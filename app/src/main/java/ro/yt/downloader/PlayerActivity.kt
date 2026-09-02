package ro.yt.downloader

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.session.MediaSession
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import ro.yt.downloader.databinding.ActivityPlayerBinding

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var castContext: CastContext? = null
    private var castMediaCallback: RemoteMediaClient.Callback? = null
    private var inAppCast: PlayerInAppCastHelper? = null
    private var mediaSession: MediaSession? = null
    private var fullscreenUiActive = false
    private var screenOffReceiverRegistered = false

    /**
     * La autoblocare (ecran stins) oprim redarea locală.
     * Chromecast rămâne neatins — doar ExoPlayer pe telefon.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                pauseLocalPlayback()
            }
        }
    }

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
        val audioUrl = intent.getStringExtra(EXTRA_URI_AUDIO)?.trim()?.takeIf { it.isNotEmpty() }
        val httpHeaders = readHttpHeadersExtra()
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
        if (resolvedForCast.isEmpty()) {
            binding.playerCastRouteButton.visibility = View.GONE
        } else {
            binding.playerCastRouteButton.visibility = View.VISIBLE
            binding.playerCastRouteButton.setOnClickListener {
                CastTargetChooser.show(
                    activity = this,
                    onChromecast = {
                        if (castContext == null) {
                            android.widget.Toast.makeText(
                                this,
                                R.string.cast_needs_play_services,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            return@show
                        }
                        val selector = androidx.mediarouter.media.MediaRouteSelector.Builder()
                            .addControlCategory(
                                com.google.android.gms.cast.CastMediaControlIntent.categoryForCast(
                                    com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                                )
                            )
                            .build()
                        androidx.mediarouter.app.MediaRouteChooserDialog(this).apply {
                            setRouteSelector(selector)
                            show()
                        }
                    },
                    onAmazonDevice = { renderer ->
                        startAmazonFromPlayer(resolvedForCast, renderer)
                    }
                )
            }
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

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN)
            .build()
        val needsHttpHeaders = httpHeaders.isNotEmpty() ||
            uriStrings.any { it.startsWith("http://") || it.startsWith("https://") } ||
            !audioUrl.isNullOrBlank()
        val streamDataSourceFactory = if (needsHttpHeaders) {
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(
                    httpHeaders.ifEmpty {
                        mapOf(
                            "User-Agent" to
                                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                            "Referer" to "https://www.youtube.com/",
                            "Origin" to "https://www.youtube.com"
                        )
                    }
                )
        } else {
            null
        }
        val exoBuilder = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            // Fără wake lock: redarea nu trebuie să continue după autoblocare.
            .setWakeMode(C.WAKE_MODE_NONE)
        if (streamDataSourceFactory != null) {
            exoBuilder.setMediaSourceFactory(DefaultMediaSourceFactory(streamDataSourceFactory))
        }
        val exo = exoBuilder.build()
        player = exo
        binding.playerView.player = exo
        binding.playerView.setFullscreenButtonClickListener { enterFullscreen ->
            applyFullscreenUi(enterFullscreen)
        }
        if (!audioUrl.isNullOrBlank() && uriStrings.size == 1) {
            // DASH YouTube: video + audio pe URL-uri separate.
            val mediaSourceFactory = DefaultMediaSourceFactory(
                streamDataSourceFactory ?: DefaultHttpDataSource.Factory()
            )
            val title = titleOverride.orEmpty().ifBlank { "stream" }
            val videoItem = MediaItem.Builder()
                .setUri(uriStrings[0])
                .setMediaId(uriStrings[0])
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setDisplayTitle(title)
                        .build()
                )
                .build()
            val audioItem = MediaItem.fromUri(audioUrl)
            val merged = MergingMediaSource(
                mediaSourceFactory.createMediaSource(videoItem),
                mediaSourceFactory.createMediaSource(audioItem)
            )
            exo.setMediaSource(merged)
        } else {
            exo.setMediaItems(buildMediaItemsForSession(uriStrings, titleOverride))
        }
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
                pauseLocalPlayback = { pauseLocalPlayback() }
            )
        }

        castContext?.sessionManager?.currentCastSession?.let { ensureCastMediaCallback(it) }

        updateCastRemoteUi()
        registerScreenOffReceiver()
    }

    private var amazonCastSession: AmazonCastSession? = null

    private fun startAmazonFromPlayer(entries: List<DownloadedFileEntry>, renderer: DlnaRenderer) {
        amazonCastSession?.stop()
        pauseLocalPlayback()
        amazonCastSession = AmazonCastSession(
            appContext = applicationContext,
            renderer = renderer,
            entries = entries,
            onFinished = {
                runOnUiThread {
                    amazonCastSession = null
                    android.widget.Toast.makeText(
                        this,
                        R.string.cast_amazon_finished,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onError = { msg ->
                runOnUiThread {
                    amazonCastSession = null
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            },
            onStatus = { title ->
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.cast_amazon_started, "$title → ${renderer.displayLabel()}"),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        ).also { it.start() }
    }

    private fun pauseLocalPlayback() {
        player?.pause()
        player?.playWhenReady = false
    }

    private fun registerScreenOffReceiver() {
        if (screenOffReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenOffReceiverRegistered = true
    }

    private fun unregisterScreenOffReceiver() {
        if (!screenOffReceiverRegistered) return
        runCatching { unregisterReceiver(screenOffReceiver) }
        screenOffReceiverRegistered = false
    }

    private fun readHttpHeadersExtra(): Map<String, String> {
        val bundle = intent.getBundleExtra(EXTRA_HTTP_HEADERS) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in bundle.keySet()) {
            val v = bundle.getString(key)?.trim().orEmpty()
            if (!key.isNullOrBlank() && v.isNotEmpty()) out[key] = v
        }
        return out
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
        // La autoblocare activity trece în onStop; asigurăm pauza și dacă broadcast-ul întârzie.
        if (!isChangingConfigurations) {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            val interactive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                pm?.isInteractive == true
            } else {
                @Suppress("DEPRECATION")
                pm?.isScreenOn == true
            }
            if (!interactive) {
                pauseLocalPlayback()
            }
        }
        unregisterCastMediaCallback()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterScreenOffReceiver()
        amazonCastSession?.stop()
        amazonCastSession = null
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
        /** URL audio separat (DASH), opțional. */
        const val EXTRA_URI_AUDIO = "uri_audio"
        /** Headere HTTP pentru CDN YouTube (Bundle String→String). */
        const val EXTRA_HTTP_HEADERS = "http_headers"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MIME = "mime"
    }
}
