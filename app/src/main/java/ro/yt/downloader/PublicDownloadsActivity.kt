package ro.yt.downloader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.provider.DocumentsContract
import java.io.File
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PublicDownloadsActivity : AppCompatActivity() {

    private sealed class BrowseRow {
        data class FolderRow(val name: String) : BrowseRow()
        data class FileRow(val entry: DownloadedFileEntry) : BrowseRow()
    }

    private enum class BrowseLocation { DLPULSE, SAF_LIBRARY }

    private lateinit var toolbar: Toolbar
    private lateinit var inputFilter: EditText
    private lateinit var cbBrowseSelectAllFiles: CheckBox
    private lateinit var emptyView: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var selectionBar: View
    private lateinit var selectionCountLabel: TextView
    private lateinit var btnSelectionPlay: ImageButton
    private lateinit var btnSelectionShare: ImageButton
    private lateinit var btnSelectionCast: ImageButton

    private lateinit var castPlaybackBar: View
    private lateinit var castPlaybackTitle: TextView
    private lateinit var castBarPlayPause: ImageButton
    private lateinit var castBarSeekBack: TextView
    private lateinit var castBarSeekFwd: TextView
    private lateinit var castBarDisconnect: ImageButton

    private var allEntries: List<DownloadedFileEntry> = emptyList()
    /** Subfoldere în directorul curent (mod „pe foldere”). */
    private var subfoldersInDir: List<String> = emptyList()
    private var displayedBrowseRows: List<BrowseRow> = emptyList()
    /** Cale relativă în Download/DLPulse (goală = rădăcină). */
    private var browseRelativePath: String = ""
    /** Dacă e true, lista plată ca înainte (toate fișierele din toate subfolderele). */
    private var flatViewAllFiles: Boolean = false
    private lateinit var browsePrefs: BrowseStoragePrefs
    private var browseLocation = BrowseLocation.DLPULSE
    private var safTreeUri: Uri? = null
    private val safPathSegments = mutableListOf<String>()
    private val selectedKeys = linkedSetOf<String>()
    private var syncingSelectAllCheck = false

    private var castContext: CastContext? = null
    /** Așteaptă conectarea la TV: păstrăm intrarea ca să putem reporni NanoHTTPD la onSessionStarted. */
    private var pendingCastSession: PendingCastSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingLoadRunnable: Runnable? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Invalidează stop()/load() vechi când utilizatorul pornește o proiecție nouă (evită crash/race). */
    private var castOpToken = 0
    private var lastLoggedCastState: String? = null
    /** Evită spam la Toast pentru același IDLE_REASON_ERROR. */
    private var notifiedCastIdleError = false

    private data class PendingCast(val url: String, val mime: String, val title: String, val opToken: Int)

    private data class PendingCastSession(
        val entry: DownloadedFileEntry,
        val streamMime: String,
        val opToken: Int
    )

    private data class CastPlaylistState(
        val entries: List<DownloadedFileEntry>,
        var index: Int = 0,
        val opToken: Int
    )

    private var castPlaylist: CastPlaylistState? = null
    private var castMediaCallback: RemoteMediaClient.Callback? = null

    private val castStateListener = CastStateListener {
        invalidateOptionsMenu()
        updateCastPlaybackBar()
    }

    private val castUiResetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            mainHandler.post {
                castPlaylist = null
                pendingCastSession = null
                pendingLoadRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingLoadRunnable = null
                updateCastPlaybackBar()
            }
        }
    }

    private val storagePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        reloadFromDisk()
    }

    private val pickSafTreeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        browsePrefs.setSafTreeUri(uri)
        enterSafMode(uri)
    }

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            acquireWifiHighPerf()
            CastStreamService.start(this@PublicDownloadsActivity)
            ensureCastCallbackRegistered()
            pendingCastSession?.let { pcs ->
                if (pcs.opToken != castOpToken) {
                    pendingCastSession = null
                    return@let
                }
                val url = LocalStreamHolder.ensureStreamRunning(
                    this@PublicDownloadsActivity,
                    pcs.entry.file,
                    pcs.entry.contentUri,
                    pcs.streamMime
                )
                pendingCastSession = null
                if (url == null) {
                    castPlaylist = null
                    CastStreamService.stop(this@PublicDownloadsActivity)
                    Toast.makeText(
                        this@PublicDownloadsActivity,
                        R.string.cast_toast_http_after_connect,
                        Toast.LENGTH_LONG
                    ).show()
                    return@let
                }
                val p = PendingCast(url, pcs.streamMime, pcs.entry.title, pcs.opToken)
                scheduleLoadCast(p)
            }
            updateCastPlaybackBar()
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            castOpToken++
            notifiedCastIdleError = false
            lastLoggedCastState = null
            pendingCastSession = null
            castPlaylist = null
            LocalStreamHolder.stop()
            CastStreamService.stop(this@PublicDownloadsActivity)
            Toast.makeText(
                this@PublicDownloadsActivity,
                getString(R.string.cast_connect_failed, error),
                Toast.LENGTH_LONG
            ).show()
            updateCastPlaybackBar()
        }

        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {
            castOpToken++
            lastLoggedCastState = null
            notifiedCastIdleError = false
            pendingCastSession = null
            castPlaylist = null
            unregisterCastMediaCallback()
            LocalStreamHolder.stop()
            CastStreamService.stop(this@PublicDownloadsActivity)
            releaseWifiHighPerf()
            invalidateOptionsMenu()
            updateCastPlaybackBar()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            CastStreamService.start(this@PublicDownloadsActivity)
            ensureCastCallbackRegistered()
            pendingCastSession?.let { pcs ->
                if (pcs.opToken != castOpToken) {
                    pendingCastSession = null
                    return@let
                }
                val url = LocalStreamHolder.ensureStreamRunning(
                    this@PublicDownloadsActivity,
                    pcs.entry.file,
                    pcs.entry.contentUri,
                    pcs.streamMime
                )
                pendingCastSession = null
                if (url == null) {
                    castPlaylist = null
                    CastStreamService.stop(this@PublicDownloadsActivity)
                    return@let
                }
                val p = PendingCast(url, pcs.streamMime, pcs.entry.title, pcs.opToken)
                scheduleLoadCast(p)
            }
            updateCastPlaybackBar()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            castOpToken++
            notifiedCastIdleError = false
            lastLoggedCastState = null
            pendingCastSession = null
            castPlaylist = null
            LocalStreamHolder.stop()
            CastStreamService.stop(this@PublicDownloadsActivity)
            Toast.makeText(
                this@PublicDownloadsActivity,
                getString(R.string.cast_resume_failed, error),
                Toast.LENGTH_LONG
            ).show()
            updateCastPlaybackBar()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browsePrefs = BrowseStoragePrefs(this)
        setContentView(R.layout.activity_public_downloads)
        toolbar = findViewById(R.id.toolbarPublicDownloads)
        inputFilter = findViewById(R.id.inputPublicFilter)
        cbBrowseSelectAllFiles = findViewById(R.id.cbBrowseSelectAllFiles)
        emptyView = findViewById(R.id.publicDownloadsEmpty)
        recycler = findViewById(R.id.recyclerPublicDownloads)
        selectionBar = findViewById(R.id.selectionActionBar)
        selectionCountLabel = findViewById(R.id.selectionCountLabel)
        btnSelectionPlay = findViewById(R.id.btnSelectionPlay)
        btnSelectionShare = findViewById(R.id.btnSelectionShare)
        btnSelectionCast = findViewById(R.id.btnSelectionCast)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolbar.setNavigationOnClickListener {
            if (!handleBrowseNavigateUp()) finish()
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!handleBrowseNavigateUp()) finish()
                }
            }
        )

        castContext = runCatching { CastContext.getSharedInstance(this) }.getOrNull()
        if (castContext == null) {
            btnSelectionCast.visibility = View.GONE
        }

        castPlaybackBar = findViewById(R.id.castPlaybackBar)
        castPlaybackTitle = findViewById(R.id.castPlaybackTitle)
        castBarPlayPause = findViewById(R.id.castBarPlayPause)
        castBarSeekBack = findViewById(R.id.castBarSeekBack)
        castBarSeekFwd = findViewById(R.id.castBarSeekFwd)
        castBarDisconnect = findViewById(R.id.castBarDisconnect)
        if (castContext != null) {
            CastRouteUi.setUpMediaRouteButton(this, findViewById(R.id.castRouteButtonDownloads))
            castBarPlayPause.setOnClickListener {
                CastPlaybackHelper.togglePlayPause(this)
                CastStreamService.instance?.refreshMediaNotification()
                updateCastPlaybackBar()
            }
            castBarSeekBack.setOnClickListener {
                CastPlaybackHelper.seekRelative(this, -15_000L)
                CastStreamService.instance?.refreshMediaNotification()
            }
            castBarSeekFwd.setOnClickListener {
                CastPlaybackHelper.seekRelative(this, 15_000L)
                CastStreamService.instance?.refreshMediaNotification()
            }
            castBarDisconnect.setOnClickListener { disconnectCast() }
        } else {
            castPlaybackBar.visibility = View.GONE
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = PublicBrowseAdapter()
        updateToolbarBrowseSubtitle()

        btnSelectionPlay.setOnClickListener {
            val list = selectedEntriesInListOrder()
            if (list.isNotEmpty()) DownloadFileActions.openPlayerPlaylist(this, list)
        }
        btnSelectionShare.setOnClickListener {
            val list = selectedEntriesInListOrder()
            if (list.isNotEmpty()) DownloadFileActions.shareMultiple(this, list)
        }
        btnSelectionCast.setOnClickListener {
            val list = selectedEntriesInListOrder()
            if (list.isNotEmpty()) startCastPlaylist(list)
        }

        inputFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })

        cbBrowseSelectAllFiles.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSelectAllCheck) return@setOnCheckedChangeListener
            val keys = fileKeysInCurrentView()
            if (isChecked) {
                selectedKeys.addAll(keys)
            } else {
                keys.forEach { selectedKeys.remove(it) }
            }
            (recycler.adapter as? PublicBrowseAdapter)?.notifyDataSetChanged()
            updateSelectionUi()
        }

        ensureStoragePermissionsThenLoad()
        consumeLaunchCastIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            consumeLaunchCastIntent(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_public_downloads, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val connected = castContext?.sessionManager?.currentCastSession?.isConnected == true
        menu.findItem(R.id.action_disconnect_cast)?.isVisible = connected
        menu.findItem(R.id.action_browse_flat_toggle)?.title = getString(
            if (flatViewAllFiles) R.string.menu_browse_show_folders
            else R.string.menu_browse_show_flat
        )
        menu.findItem(R.id.action_new_folder)?.isVisible =
            (browseLocation == BrowseLocation.DLPULSE && !flatViewAllFiles) ||
                browseLocation == BrowseLocation.SAF_LIBRARY
        menu.findItem(R.id.action_browse_flat_toggle)?.isVisible = browseLocation == BrowseLocation.DLPULSE
        menu.findItem(R.id.action_browse_saved_library)?.isVisible =
            browseLocation == BrowseLocation.DLPULSE && browsePrefs.getSafTreeUri() != null
        menu.findItem(R.id.action_browse_pick_library)?.isVisible = browseLocation == BrowseLocation.DLPULSE
        menu.findItem(R.id.action_browse_dlpulse_downloads)?.isVisible = browseLocation == BrowseLocation.SAF_LIBRARY
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_browse_flat_toggle) {
            if (browseLocation != BrowseLocation.DLPULSE) return true
            flatViewAllFiles = !flatViewAllFiles
            if (!flatViewAllFiles) browseRelativePath = ""
            selectedKeys.clear()
            reloadFromDisk()
            invalidateOptionsMenu()
            return true
        }
        if (item.itemId == R.id.action_disconnect_cast) {
            disconnectCast()
            return true
        }
        if (item.itemId == R.id.action_open_dlpulse_folder) {
            DownloadsFolderOpener.openDlpulseFolder(this)
            return true
        }
        if (item.itemId == R.id.action_browse_device_storage) {
            DownloadsFolderOpener.openPrimaryStorageBrowser(this)
            return true
        }
        if (item.itemId == R.id.action_new_folder) {
            showNewFolderDialog()
            return true
        }
        if (item.itemId == R.id.action_browse_pick_library) {
            launchPickSafTree()
            return true
        }
        if (item.itemId == R.id.action_browse_saved_library) {
            openSavedSafLibrary()
            return true
        }
        if (item.itemId == R.id.action_browse_dlpulse_downloads) {
            exitSafToDlpulse()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            castUiResetReceiver,
            IntentFilter(CastUiBridge.ACTION_CAST_UI_RESET),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        castContext?.addCastStateListener(castStateListener)
        castContext?.sessionManager?.addSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        updateCastPlaybackBar()
    }

    override fun onStop() {
        unregisterReceiver(castUiResetReceiver)
        castContext?.removeCastStateListener(castStateListener)
        castContext?.sessionManager?.removeSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        syncCastStateFromSystem()
        if (castContext?.sessionManager?.currentCastSession?.remoteMediaClient != null) {
            ensureCastCallbackRegistered()
        }
        if (hasStorageReadPermission() || browseLocation == BrowseLocation.SAF_LIBRARY) {
            reloadFromDisk()
        }
        invalidateOptionsMenu()
        updateCastPlaybackBar()
    }

    override fun onDestroy() {
        pendingLoadRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingLoadRunnable = null
        unregisterCastMediaCallback()
        val connected = castContext?.sessionManager?.currentCastSession?.isConnected == true
        if (!connected) {
            CastStreamService.stop(this)
        }
        super.onDestroy()
    }

    private fun selectedEntriesInListOrder(): List<DownloadedFileEntry> {
        if (selectedKeys.isEmpty()) return emptyList()
        val set = selectedKeys.toSet()
        return displayedBrowseRows.mapNotNull { row ->
            if (row is BrowseRow.FileRow && row.entry.stableKey() in set) row.entry else null
        }
    }

    /** Doar fișierele vizibile în listă (fără rânduri de tip folder). */
    private fun fileKeysInCurrentView(): List<String> =
        displayedBrowseRows.mapNotNull { row ->
            if (row is BrowseRow.FileRow) row.entry.stableKey() else null
        }

    private fun syncSelectAllCheckboxState() {
        val keys = fileKeysInCurrentView()
        if (keys.isEmpty()) {
            cbBrowseSelectAllFiles.visibility = View.GONE
            return
        }
        cbBrowseSelectAllFiles.visibility = View.VISIBLE
        syncingSelectAllCheck = true
        cbBrowseSelectAllFiles.isChecked = keys.all { it in selectedKeys }
        syncingSelectAllCheck = false
    }

    private fun updateSelectionUi() {
        val n = selectedKeys.size
        selectionBar.visibility = if (n > 0) View.VISIBLE else View.GONE
        selectionCountLabel.text = when (n) {
            0 -> ""
            1 -> getString(R.string.browse_selected_one)
            else -> getString(R.string.browse_selected_n, n)
        }
        val padBottom = if (n > 0) (88 * resources.displayMetrics.density).toInt() else 0
        recycler.setPadding(0, 0, 0, padBottom)
        (recycler.adapter as? PublicBrowseAdapter)?.notifyDataSetChanged()
        syncSelectAllCheckboxState()
    }

    private fun isCastUiSafe(): Boolean =
        !isFinishing && (Build.VERSION.SDK_INT < 17 || !isDestroyed)

    private fun syncCastStateFromSystem() {
        val connected = castContext?.sessionManager?.currentCastSession?.isConnected == true
        if (!connected && (castPlaylist != null || pendingCastSession != null)) {
            castPlaylist = null
            pendingCastSession = null
            pendingLoadRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingLoadRunnable = null
        }
    }

    private fun updateCastPlaybackBar() {
        if (!isCastUiSafe() || !::castPlaybackBar.isInitialized) return
        if (castContext == null) {
            castPlaybackBar.visibility = View.GONE
            return
        }
        val connected = castContext?.sessionManager?.currentCastSession?.isConnected == true
        val client = CastPlaybackHelper.remoteClient(this)
        if (!connected || client == null) {
            castPlaybackBar.visibility = View.GONE
            return
        }
        castPlaybackBar.visibility = View.VISIBLE
        val meta = client.mediaInfo?.metadata
        castPlaybackTitle.text =
            meta?.getString(MediaMetadata.KEY_TITLE) ?: getString(R.string.cast_bar_title)
        val playing = CastPlaybackHelper.isPlaying(client)
        castBarPlayPause.setImageResource(
            if (playing) R.drawable.ic_action_pause else R.drawable.ic_action_play
        )
    }

    private fun logCastStatusIfChanged() {
        if (!isCastUiSafe()) return
        val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
        val st = client.mediaStatus ?: return
        val line =
            "CastStatus playerState=${st.playerState} idleReason=${st.idleReason} streamPos=${st.streamPosition}ms"
        if (line == lastLoggedCastState) return
        lastLoggedCastState = line
        DebugFileLog.append(this, "cast_debug.log", line)
        if (st.playerState == MediaStatus.PLAYER_STATE_IDLE &&
            st.idleReason == MediaStatus.IDLE_REASON_ERROR
        ) {
            DebugFileLog.append(
                this,
                "cast_debug.log",
                getString(R.string.cast_log_idle_error)
            )
            if (!notifiedCastIdleError && isCastUiSafe()) {
                notifiedCastIdleError = true
                Toast.makeText(
                    this,
                    R.string.cast_toast_playback_error,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        if (st.playerState == MediaStatus.PLAYER_STATE_PLAYING ||
            st.playerState == MediaStatus.PLAYER_STATE_BUFFERING
        ) {
            notifiedCastIdleError = false
        }
    }

    private fun ensureCastCallbackRegistered() {
        if (castMediaCallback != null) return
        val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
        val cb = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                logCastStatusIfChanged()
                onCastMediaStatusUpdated()
                updateCastPlaybackBar()
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

    private fun onCastMediaStatusUpdated() {
        val pl = castPlaylist ?: return
        val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
        val status = client.mediaStatus ?: return
        if (status.playerState != MediaStatus.PLAYER_STATE_IDLE) return
        if (status.idleReason != MediaStatus.IDLE_REASON_FINISHED) return
        val next = pl.index + 1
        if (next >= pl.entries.size) {
            castPlaylist = null
            if (pl.entries.size > 1) {
                runOnUiThread {
                    Toast.makeText(
                        this@PublicDownloadsActivity,
                        R.string.cast_playlist_finished,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            return
        }
        pl.index = next
        val entry = pl.entries[next]
        runOnUiThread {
            loadCastForConnectedSession(entry)
        }
    }

    private fun hasStorageReadPermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= 33 -> {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_MEDIA_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
            else -> true
        }
    }

    private fun ensureStoragePermissionsThenLoad() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                need.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                need.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (need.isEmpty()) {
            reloadFromDisk()
        } else {
            storagePermLauncher.launch(need.toTypedArray())
        }
    }

    private fun acquireWifiHighPerf() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "ro.yt.downloader.cast"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiHighPerf() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun disconnectCast() {
        castOpToken++
        lastLoggedCastState = null
        notifiedCastIdleError = false
        pendingCastSession = null
        castPlaylist = null
        pendingLoadRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingLoadRunnable = null
        LocalStreamHolder.stop()
        CastStreamService.stop(this)
        unregisterCastMediaCallback()
        val sm = castContext?.sessionManager
        runCatching {
            sm?.currentCastSession?.remoteMediaClient?.stop()
        }
        runCatching {
            sm?.endCurrentSession(true)
        }
        releaseWifiHighPerf()
        invalidateOptionsMenu()
        updateCastPlaybackBar()
        Toast.makeText(this, R.string.cast_projection_closed, Toast.LENGTH_LONG).show()
    }

    private fun reloadFromDisk(clearSelection: Boolean = true) {
        Thread {
            when (browseLocation) {
                BrowseLocation.SAF_LIBRARY -> {
                    var tree = safTreeUri ?: browsePrefs.getSafTreeUri()?.also { safTreeUri = it }
                    if (tree == null) {
                        runOnUiThread {
                            browseLocation = BrowseLocation.DLPULSE
                            if (clearSelection) selectedKeys.clear() else pruneStaleSelectionKeys()
                            updateToolbarBrowseSubtitle()
                            reloadFromDisk(clearSelection)
                        }
                        return@Thread
                    }
                    safTreeUri = tree
                    val listing = SafDirectoryListing.list(
                        this@PublicDownloadsActivity,
                        tree,
                        safPathSegments.toList()
                    )
                    runOnUiThread {
                        subfoldersInDir = listing.subfolders
                        allEntries = listing.files
                        if (clearSelection) selectedKeys.clear() else pruneStaleSelectionKeys()
                        updateToolbarBrowseSubtitle()
                        applyFilter()
                        updateSelectionUi()
                    }
                }
                BrowseLocation.DLPULSE -> {
                    if (flatViewAllFiles) {
                        val list = DownloadsIndex.listPublicDlpulseOnly(this@PublicDownloadsActivity)
                        runOnUiThread {
                            subfoldersInDir = emptyList()
                            allEntries = list
                            if (clearSelection) selectedKeys.clear() else pruneStaleSelectionKeys()
                            updateToolbarBrowseSubtitle()
                            applyFilter()
                            updateSelectionUi()
                        }
                    } else {
                        val listing = DownloadsIndex.listPublicDlpulseDirectory(
                            this@PublicDownloadsActivity,
                            browseRelativePath
                        )
                        runOnUiThread {
                            subfoldersInDir = listing.subfolders
                            allEntries = listing.files
                            if (clearSelection) selectedKeys.clear() else pruneStaleSelectionKeys()
                            updateToolbarBrowseSubtitle()
                            applyFilter()
                            updateSelectionUi()
                        }
                    }
                }
            }
        }.start()
    }

    private fun pruneStaleSelectionKeys() {
        val valid = allEntries.map { it.stableKey() }.toSet()
        selectedKeys.retainAll(valid)
    }

    /**
     * @return true dacă s-a consumat evenimentul (nu închide activitatea).
     */
    private fun handleBrowseNavigateUp(): Boolean {
        return when (browseLocation) {
            BrowseLocation.SAF_LIBRARY -> {
                if (safPathSegments.isNotEmpty()) {
                    safPathSegments.removeAt(safPathSegments.lastIndex)
                    selectedKeys.clear()
                    reloadFromDisk()
                    true
                } else {
                    exitSafToDlpulse()
                    true
                }
            }
            BrowseLocation.DLPULSE -> {
                if (!flatViewAllFiles && browseRelativePath.isNotEmpty()) {
                    val parts = browseRelativePath.split('/').filter { it.isNotEmpty() }
                    browseRelativePath = if (parts.size <= 1) "" else parts.dropLast(1).joinToString("/")
                    selectedKeys.clear()
                    reloadFromDisk()
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun enterSafMode(treeUri: Uri) {
        safTreeUri = treeUri
        safPathSegments.clear()
        browseLocation = BrowseLocation.SAF_LIBRARY
        flatViewAllFiles = false
        browseRelativePath = ""
        selectedKeys.clear()
        reloadFromDisk()
        invalidateOptionsMenu()
        Toast.makeText(this, R.string.browse_saf_attached, Toast.LENGTH_SHORT).show()
    }

    private fun exitSafToDlpulse() {
        browseLocation = BrowseLocation.DLPULSE
        safTreeUri = null
        safPathSegments.clear()
        selectedKeys.clear()
        reloadFromDisk()
        invalidateOptionsMenu()
    }

    private fun openSavedSafLibrary() {
        val u = browsePrefs.getSafTreeUri() ?: return
        enterSafMode(u)
    }

    private fun launchPickSafTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:${Environment.DIRECTORY_MUSIC}"
                    )
                )
            }
        }
        pickSafTreeLauncher.launch(intent)
    }

    private fun showNewFolderDialog() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val input = EditText(this).apply {
            hint = getString(R.string.browse_new_folder_hint)
        }
        val container = FrameLayout(this).apply {
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(pad, pad / 2, pad, pad) }
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browse_new_folder_title)
            .setView(container)
            .setPositiveButton(R.string.browse_ok) { _, _ ->
                val name = input.text.toString()
                val ok = when (browseLocation) {
                    BrowseLocation.DLPULSE -> {
                        if (flatViewAllFiles) {
                            false
                        } else {
                            DownloadsIndex.createDlpulseSubfolder(this, browseRelativePath, name)
                        }
                    }
                    BrowseLocation.SAF_LIBRARY -> {
                        val t = safTreeUri ?: browsePrefs.getSafTreeUri()
                        if (t == null) false
                        else SafDirectoryListing.createSubfolder(this, t, safPathSegments, name)
                    }
                }
                if (ok) {
                    reloadFromDisk(clearSelection = false)
                    Toast.makeText(this, R.string.browse_new_folder_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.browse_new_folder_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.main_cancel, null)
            .show()
    }

    private fun navigateIntoFolder(name: String) {
        when (browseLocation) {
            BrowseLocation.DLPULSE -> {
                browseRelativePath = if (browseRelativePath.isEmpty()) name else "$browseRelativePath/$name"
            }
            BrowseLocation.SAF_LIBRARY -> {
                safPathSegments.add(name)
            }
        }
        selectedKeys.clear()
        reloadFromDisk()
    }

    private fun updateToolbarBrowseSubtitle() {
        supportActionBar?.subtitle = when (browseLocation) {
            BrowseLocation.SAF_LIBRARY -> {
                if (safPathSegments.isEmpty()) {
                    getString(R.string.browse_subtitle_saf_root)
                } else {
                    safPathSegments.joinToString(" › ")
                }
            }
            BrowseLocation.DLPULSE -> when {
                flatViewAllFiles -> getString(R.string.browse_subtitle_flat)
                browseRelativePath.isEmpty() -> getString(R.string.browse_subtitle_root)
                else -> browseRelativePath.replace("/", " › ")
            }
        }
    }

    /** Intent cu cale fișier: pornește proiecția fără să revii la MainActivity. */
    private fun consumeLaunchCastIntent(i: Intent?) {
        val path = i?.getStringExtra(EXTRA_LAUNCH_CAST_FILE) ?: return
        i.removeExtra(EXTRA_LAUNCH_CAST_FILE)
        val f = File(path)
        if (!f.isFile) return
        val mime = DownloadMime.guessFromFileName(f.name)
        val entry = DownloadedFileEntry(
            title = f.name,
            mime = mime,
            file = f,
            contentUri = null,
            sortKey = f.lastModified(),
            sizeBytes = f.length()
        )
        mainHandler.postDelayed({
            if (!isCastUiSafe()) return@postDelayed
            startCastPlaylist(listOf(entry))
        }, 500L)
    }

    private fun applyFilter() {
        val q = inputFilter.text.toString().trim().lowercase()
        val folderRows = if (flatViewAllFiles) {
            emptyList()
        } else {
            subfoldersInDir
                .filter { fq -> q.isEmpty() || fq.lowercase().contains(q) }
                .map { BrowseRow.FolderRow(it) }
        }
        val filteredFiles = if (q.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { it.title.lowercase().contains(q) }
        }
        val fileRows = filteredFiles.map { BrowseRow.FileRow(it) }
        displayedBrowseRows = folderRows + fileRows
        (recycler.adapter as? PublicBrowseAdapter)?.submit(displayedBrowseRows)

        val totalShown = displayedBrowseRows.size
        when {
            browseLocation == BrowseLocation.DLPULSE && !hasStorageReadPermission() -> {
                emptyView.visibility = View.VISIBLE
                emptyView.text = getString(R.string.browse_empty_permission)
            }
            subfoldersInDir.isEmpty() && allEntries.isEmpty() && !flatViewAllFiles -> {
                emptyView.visibility = View.VISIBLE
                emptyView.text = getString(R.string.browse_empty_folder)
            }
            allEntries.isEmpty() && flatViewAllFiles -> {
                emptyView.visibility = View.VISIBLE
                emptyView.text = getString(R.string.browse_empty_no_files)
            }
            totalShown == 0 -> {
                emptyView.visibility = View.VISIBLE
                emptyView.text = getString(R.string.browse_empty_no_match, q)
            }
            else -> emptyView.visibility = View.GONE
        }
        syncSelectAllCheckboxState()
    }

    private fun showFileMenu(entry: DownloadedFileEntry, anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menuInflater.inflate(R.menu.menu_download_file, popup.menu)
        forcePopupMenuIcons(popup)
        applyFileMenuIconOnly(popup.menu)
        if (castContext == null) {
            popup.menu.findItem(R.id.action_cast_file)?.isVisible = false
        }
        popup.menu.findItem(R.id.action_rename_file)?.isVisible =
            browseLocation != BrowseLocation.SAF_LIBRARY
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_play_file -> {
                    DownloadFileActions.openPlayerInApp(this, entry)
                    true
                }
                R.id.action_share_file -> {
                    DownloadFileActions.share(this, entry)
                    true
                }
                R.id.action_cast_file -> {
                    startCastPlaylist(listOf(entry))
                    true
                }
                R.id.action_rename_file -> {
                    showRenameFileDialog(entry)
                    true
                }
                R.id.action_delete_file -> {
                    showDeleteFileDialog(entry)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameFileDialog(entry: DownloadedFileEntry) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(entry.title)
            setSelection(entry.title.length)
        }
        val container = FrameLayout(this).apply {
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(pad, pad / 2, pad, pad)
                }
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browse_rename_title)
            .setView(container)
            .setPositiveButton(R.string.browse_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == entry.title) return@setPositiveButton
                if (DownloadFileModify.rename(this, entry, name)) {
                    reloadFromDisk(clearSelection = false)
                    Toast.makeText(this, R.string.browse_rename_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.browse_rename_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.main_cancel, null)
            .show()
    }

    private fun showDeleteFileDialog(entry: DownloadedFileEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browse_delete_confirm_title)
            .setMessage(getString(R.string.browse_delete_confirm_message, entry.title))
            .setPositiveButton(R.string.browse_delete) { _, _ ->
                if (DownloadFileModify.delete(this, entry)) {
                    reloadFromDisk(clearSelection = false)
                    Toast.makeText(this, R.string.browse_delete_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.browse_delete_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.main_cancel, null)
            .show()
    }

    private fun applyFileMenuIconOnly(menu: Menu) {
        menu.findItem(R.id.action_play_file)?.let { item ->
            item.setTitle("")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                item.contentDescription = getString(R.string.cd_menu_play_file)
            }
        }
        menu.findItem(R.id.action_share_file)?.let { item ->
            item.setTitle("")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                item.contentDescription = getString(R.string.cd_menu_share_file)
            }
        }
        menu.findItem(R.id.action_cast_file)?.let { item ->
            item.setTitle("")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                item.contentDescription = getString(R.string.cd_menu_cast_file)
            }
        }
        menu.findItem(R.id.action_rename_file)?.let { item ->
            item.setTitle("")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                item.contentDescription = getString(R.string.cd_menu_rename_file)
            }
        }
        menu.findItem(R.id.action_delete_file)?.let { item ->
            item.setTitle("")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                item.contentDescription = getString(R.string.cd_menu_delete_file)
            }
        }
    }

    private fun forcePopupMenuIcons(popup: PopupMenu) {
        try {
            val f = PopupMenu::class.java.getDeclaredField("mPopup")
            f.isAccessible = true
            val helper = f.get(popup) ?: return
            val m = helper.javaClass.getMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
            m.invoke(helper, true)
        } catch (_: Exception) {
        }
    }

    private fun showCastDevicePicker() {
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                )
            )
            .build()
        val dialog = MediaRouteChooserDialog(this)
        dialog.setRouteSelector(selector)
        dialog.show()
    }

    private fun streamMimeForEntry(entry: DownloadedFileEntry, opToken: Int): String {
        return if (entry.file != null) {
            val mr = CastMimeHelper.forFile(entry.file)
            if (!mr.supported && mr.hintResId != null) {
                val resId = mr.hintResId
                mainHandler.post {
                    if (isCastUiSafe() && opToken == castOpToken) {
                        Toast.makeText(this@PublicDownloadsActivity, resId, Toast.LENGTH_LONG).show()
                    }
                }
            }
            DownloadMime.normalizeForCast(mr.mime, entry.title)
        } else {
            DownloadMime.normalizeForCast(entry.mime, entry.title)
        }
    }

    private fun openStreamForCast(entry: DownloadedFileEntry, opToken: Int): PendingCast? {
        val streamMime = streamMimeForEntry(entry, opToken)
        val url = LocalStreamHolder.ensureStreamRunning(
            this,
            entry.file,
            entry.contentUri,
            streamMime
        ) ?: return null
        return PendingCast(url, streamMime, entry.title, opToken)
    }

    private fun safeStopThenLoad(client: RemoteMediaClient, pending: PendingCast) {
        val token = pending.opToken
        val proceed = Runnable {
            if (!isCastUiSafe() || token != castOpToken) return@Runnable
            scheduleLoadCast(pending)
        }
        try {
            @Suppress("DEPRECATION")
            client.stop().setResultCallback { mainHandler.post(proceed) }
        } catch (e: Exception) {
            DebugFileLog.append(this, "cast_debug.log", "safeStopThenLoad: ${e.message}")
            mainHandler.postDelayed(proceed, 350L)
        }
    }

    private fun startCastPlaylist(entries: List<DownloadedFileEntry>) {
        if (entries.isEmpty()) return
        if (castContext == null) {
            Toast.makeText(
                this,
                R.string.cast_needs_play_services,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        castOpToken++
        val opToken = castOpToken
        lastLoggedCastState = null
        notifiedCastIdleError = false
        pendingLoadRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingLoadRunnable = null

        castPlaylist = CastPlaylistState(entries.toList(), 0, opToken)
        CastStreamService.start(this)

        val sm = castContext!!.sessionManager
        val client = sm.currentCastSession?.remoteMediaClient
        val first = entries[0]
        val streamMime = streamMimeForEntry(first, opToken)

        if (client != null) {
            val p = openStreamForCast(first, opToken) ?: run {
                CastStreamService.stop(this)
                Toast.makeText(
                    this,
                    R.string.cast_http_start_failed,
                    Toast.LENGTH_LONG
                ).show()
                castPlaylist = null
                return
            }
            ensureCastCallbackRegistered()
            safeStopThenLoad(client, p)
        } else {
            pendingCastSession = PendingCastSession(first, streamMime, opToken)
            Toast.makeText(
                this,
                R.string.cast_pick_tv,
                Toast.LENGTH_SHORT
            ).show()
            showCastDevicePicker()
        }
    }

    private fun loadCastForConnectedSession(entry: DownloadedFileEntry) {
        val pl = castPlaylist ?: return
        val opToken = pl.opToken
        CastStreamService.start(this)
        val p = openStreamForCast(entry, opToken) ?: run {
            Toast.makeText(
                this,
                R.string.cast_next_file_failed,
                Toast.LENGTH_LONG
            ).show()
            castPlaylist = null
            return
        }
        val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient ?: run {
            castPlaylist = null
            return
        }
        ensureCastCallbackRegistered()
        DebugFileLog.append(
            this,
            "cast_debug.log",
            "playlist idx=${castPlaylist?.index} title=${entry.title} url=${p.url} mime=${p.mime}"
        )
        safeStopThenLoad(client, p)
    }

    private fun scheduleLoadCast(p: PendingCast) {
        pendingLoadRunnable?.let { mainHandler.removeCallbacks(it) }
        val token = p.opToken
        val r = Runnable {
            if (!isCastUiSafe() || token != castOpToken) return@Runnable
            loadCastMedia(p.url, p.mime, p.title, 0, token)
        }
        pendingLoadRunnable = r
        mainHandler.postDelayed(r, 900)
    }

    private fun loadCastMedia(url: String, mime: String, title: String, attempt: Int, opToken: Int) {
        if (opToken != castOpToken) return
        val session = castContext?.sessionManager?.currentCastSession
        val client = session?.remoteMediaClient
        if (client == null) {
            if (isCastUiSafe()) {
                Toast.makeText(this, R.string.player_cast_no_session, Toast.LENGTH_SHORT).show()
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
        DebugFileLog.append(
            this,
            "cast_debug.log",
            "loadCast attempt=$attempt url=$url type=$castMime opToken=$opToken (preCheck HEAD…)"
        )
        Thread {
            val headOk = NetworkUtil.isHttpHeadOk(url)
            DebugFileLog.append(
                applicationContext,
                "cast_debug.log",
                "preCast HEAD url=$url ok=$headOk"
            )
            mainHandler.post {
                if (!isCastUiSafe() || opToken != castOpToken) return@post
                if (!headOk) {
                    Toast.makeText(
                        this,
                        R.string.cast_head_wifi_long,
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
        opToken: Int
    ) {
        try {
            @Suppress("DEPRECATION")
            client.load(request).setResultCallback { result ->
                mainHandler.post {
                    if (!isCastUiSafe() || opToken != castOpToken) return@post
                    DebugFileLog.append(
                        this,
                        "cast_debug.log",
                        "loadCast result success=${result.status.isSuccess} code=${result.status.statusCode} msg=${result.status.statusMessage}"
                    )
                    if (!result.status.isSuccess) {
                        if (attempt < 2) {
                            mainHandler.postDelayed(
                                { loadCastMedia(url, castMime, title, attempt + 1, opToken) },
                                1800L
                            )
                        } else {
                            Toast.makeText(
                                this,
                                getString(
                                    R.string.player_cast_load_failed,
                                    result.status.statusMessage ?: result.status.toString()
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        ensureCastCallbackRegistered()
                        updateCastPlaybackBar()
                    }
                }
            }
        } catch (e: Exception) {
            DebugFileLog.append(this, "cast_debug.log", "loadCast exception: ${e.message}")
            if (attempt < 2 && isCastUiSafe()) {
                mainHandler.postDelayed(
                    { loadCastMedia(url, castMime, title, attempt + 1, opToken) },
                    1800L
                )
            }
        }
    }

    private inner class PublicBrowseAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<BrowseRow> = emptyList()

        fun submit(list: List<BrowseRow>) {
            rows = list
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is BrowseRow.FolderRow -> 0
            is BrowseRow.FileRow -> 1
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                val v = inflater.inflate(R.layout.item_public_folder_row, parent, false)
                FolderVH(v)
            } else {
                val v = inflater.inflate(R.layout.item_public_download_row, parent, false)
                FileVH(v)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is BrowseRow.FolderRow -> (holder as FolderVH).bind(row.name)
                is BrowseRow.FileRow -> (holder as FileVH).bind(row.entry)
            }
        }

        inner class FolderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name: TextView = itemView.findViewById(R.id.rowFolderName)

            fun bind(folderName: String) {
                name.text = folderName
                itemView.setOnClickListener { navigateIntoFolder(folderName) }
            }
        }

        inner class FileVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val checkbox: CheckBox = itemView.findViewById(R.id.rowCheckbox)
            private val title: TextView = itemView.findViewById(R.id.rowFileTitle)
            private val more: ImageButton = itemView.findViewById(R.id.btnRowMore)

            fun bind(entry: DownloadedFileEntry) {
                val key = entry.stableKey()
                title.text = entry.title
                more.setOnClickListener { showFileMenu(entry, more) }

                checkbox.setOnCheckedChangeListener(null)
                checkbox.isChecked = key in selectedKeys
                checkbox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedKeys.add(key) else selectedKeys.remove(key)
                    updateSelectionUi()
                }
                title.setOnClickListener {
                    checkbox.isChecked = !checkbox.isChecked
                }
            }
        }
    }

    companion object {
        const val EXTRA_LAUNCH_CAST_FILE = "launch_cast_file"
    }
}
