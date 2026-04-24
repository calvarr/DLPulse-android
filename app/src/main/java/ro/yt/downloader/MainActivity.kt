package ro.yt.downloader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var inputUrl: EditText
    private lateinit var btnGo: Button
    private lateinit var statusText: TextView
    private lateinit var errorText: TextView
    private lateinit var urlOptionsRow: LinearLayout
    private lateinit var switchNoPlaylist: SwitchCompat
    private lateinit var searchPanel: LinearLayout
    private lateinit var searchRecycler: RecyclerView
    private lateinit var cbSelectAll: CheckBox
    private lateinit var btnDownloadBatch: Button
    private lateinit var playlistPanel: LinearLayout
    private lateinit var playlistRecycler: RecyclerView
    private lateinit var cbPlaylistSelectAll: CheckBox
    private lateinit var btnDownloadPlaylistSelected: Button
    private lateinit var btnDownloadPlaylistFull: Button
    private lateinit var formatSpinner: Spinner
    private lateinit var btnDownload: Button
    private lateinit var progressPrepare: ProgressBar
    private lateinit var downloadProgressContainer: LinearLayout
    private lateinit var downloadProgressBar: ProgressBar
    private lateinit var downloadProgressText: TextView

    private lateinit var savePrefs: SaveLocationPrefs
    private var searchAdapter: SearchResultsAdapter? = null
    private var playlistAdapter: SearchResultsAdapter? = null
    private var currentVideoUrl: String? = null
    private var pendingAfterFolder: Runnable? = null

    private lateinit var btnIconUpdate: ImageButton
    private lateinit var btnIconDonate: ImageButton
    private lateinit var btnIconFolder: ImageButton
    private lateinit var btnIconCredits: ImageButton
    private lateinit var textStreamUrl: TextView
    private lateinit var btnLangMenu: Button
    private lateinit var terminalOverlay: View
    private lateinit var terminalLog: TextView
    private lateinit var terminalScroll: ScrollView
    private lateinit var terminalProgress: ProgressBar
    private lateinit var btnTerminalClose: Button

    private val openTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            savePrefs.setTreeUri(uri)
            pendingAfterFolder?.run()
        }
        pendingAfterFolder = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        savePrefs = SaveLocationPrefs(this)

        inputUrl = findViewById(R.id.inputUrl)
        btnGo = findViewById(R.id.btnGo)
        statusText = findViewById(R.id.statusText)
        errorText = findViewById(R.id.errorText)
        urlOptionsRow = findViewById(R.id.urlOptionsRow)
        switchNoPlaylist = findViewById(R.id.switchNoPlaylist)
        searchPanel = findViewById(R.id.searchPanel)
        searchRecycler = findViewById(R.id.searchRecycler)
        cbSelectAll = findViewById(R.id.cbSelectAll)
        btnDownloadBatch = findViewById(R.id.btnDownloadBatch)
        playlistPanel = findViewById(R.id.playlistPanel)
        playlistRecycler = findViewById(R.id.playlistRecycler)
        cbPlaylistSelectAll = findViewById(R.id.cbPlaylistSelectAll)
        btnDownloadPlaylistSelected = findViewById(R.id.btnDownloadPlaylistSelected)
        btnDownloadPlaylistFull = findViewById(R.id.btnDownloadPlaylistFull)
        formatSpinner = findViewById(R.id.formatSpinner)
        btnDownload = findViewById(R.id.btnDownload)
        progressPrepare = findViewById(R.id.progressPrepare)
        downloadProgressContainer = findViewById(R.id.downloadProgressContainer)
        downloadProgressBar = findViewById(R.id.downloadProgressBar)
        downloadProgressText = findViewById(R.id.downloadProgressText)

        searchRecycler.layoutManager = LinearLayoutManager(this)
        playlistRecycler.layoutManager = LinearLayoutManager(this)

        btnIconUpdate = findViewById(R.id.btnIconUpdate)
        btnIconDonate = findViewById(R.id.btnIconDonate)
        btnIconFolder = findViewById(R.id.btnIconFolder)
        btnIconCredits = findViewById(R.id.btnIconCredits)
        textStreamUrl = findViewById(R.id.textStreamUrl)
        btnLangMenu = findViewById(R.id.btnLangMenu)
        terminalOverlay = findViewById(R.id.terminalOverlay)
        terminalLog = findViewById(R.id.terminalLog)
        terminalScroll = findViewById(R.id.terminalScroll)
        terminalProgress = findViewById(R.id.terminalProgress)
        btnTerminalClose = findViewById(R.id.btnTerminalClose)

        btnIconUpdate.setOnClickListener { startManualDependencyUpdate() }
        btnIconDonate.setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://buymeacoffee.com/medcodex")
                )
            )
        }
        btnIconFolder.setOnClickListener {
            startActivity(Intent(this, PublicDownloadsActivity::class.java))
        }
        btnIconCredits.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.main_credits_title)
                .setMessage(R.string.main_credits_body)
                .setPositiveButton(R.string.main_close, null)
                .show()
        }
        refreshStreamUrlDisplay()
        syncLangButtonLabel()
        btnLangMenu.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menuInflater.inflate(R.menu.menu_language, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.lang_ro -> AppLocale.persist(this@MainActivity, AppLocale.LANG_RO)
                        R.id.lang_en -> AppLocale.persist(this@MainActivity, AppLocale.LANG_EN)
                        else -> return@setOnMenuItemClickListener false
                    }
                    true
                }
                show()
            }
        }
        btnTerminalClose.setOnClickListener { terminalOverlay.visibility = View.GONE }

        btnDownloadPlaylistSelected.text = getString(R.string.main_download_selected, 0)
        btnDownloadBatch.text = getString(R.string.main_download_selected, 0)

        loadFormats()
        prepareYtdlp()

        btnGo.setOnClickListener { onGo() }
        btnDownload.setOnClickListener { confirmDestinationAndRun { startDownloadSingle() } }
        btnDownloadBatch.setOnClickListener {
            searchAdapter?.let { confirmDestinationAndRun { startBatchDownload(it, btnDownloadBatch) } }
        }
        btnDownloadPlaylistSelected.setOnClickListener {
            playlistAdapter?.let { confirmDestinationAndRun { startBatchDownload(it, btnDownloadPlaylistSelected) } }
        }
        btnDownloadPlaylistFull.setOnClickListener {
            confirmDestinationAndRun { startDownloadPlaylistFull() }
        }

        findViewById<ImageButton>(R.id.btnSitesNote).setOnClickListener {
            showSitesNoteDialog()
        }
        if (savedInstanceState == null) {
            window.decorView.post { handleSendIntent(intent) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        window.decorView.post { handleSendIntent(intent) }
    }

    private fun showSitesNoteDialog() {
        val density = resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (12 * density).toInt()
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            text = buildString {
                append(getString(R.string.main_sites_note_intro))
                append("\n\n")
                append(getString(R.string.main_sites_info_body))
            }
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.steam_parchment))
            textSize = 13f
            setLineSpacing(0f, 1.18f)
            setPadding(padH, padV, padH, padV)
            movementMethod = LinkMovementMethod.getInstance()
            Linkify.addLinks(this, Linkify.WEB_URLS)
        }
        scroll.addView(tv)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_sites_note_title)
            .setView(scroll)
            .setPositiveButton(R.string.main_close, null)
            .show()
    }

    private fun handleSendIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            .ifEmpty {
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val combined = when {
            extraText.isNotEmpty() && subject.isNotEmpty() -> "$extraText\n$subject"
            extraText.isNotEmpty() -> extraText
            subject.isNotEmpty() -> subject
            else -> ""
        }
        if (combined.isEmpty()) {
            Toast.makeText(this, R.string.share_no_url_in_text, Toast.LENGTH_LONG).show()
            return
        }
        val url = ShareUrlParser.firstHttpUrl(combined) ?: run {
            Toast.makeText(this, R.string.share_no_url_in_text, Toast.LENGTH_LONG).show()
            return
        }
        showShareTargetMenu(url)
    }

    private fun showShareTargetMenu(url: String) {
        val items = arrayOf(
            getString(R.string.share_menu_video),
            getString(R.string.share_menu_audio),
            getString(R.string.share_menu_open_analyze)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showQualityPickerForShare(url, isVideo = true)
                    1 -> showQualityPickerForShare(url, isVideo = false)
                    2 -> {
                        inputUrl.setText(url)
                        onGo()
                    }
                }
            }
            .setNegativeButton(R.string.main_cancel, null)
            .show()
    }

    private fun showQualityPickerForShare(url: String, isVideo: Boolean) {
        val labels = resources.getStringArray(R.array.format_preset_labels)
        val start = if (isVideo) 0 else 5
        val end = if (isVideo) 4 else 9
        val options = labels.slice(start..end).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isVideo) R.string.share_pick_video_quality
                else R.string.share_pick_audio_quality
            )
            .setItems(options) { _, idx ->
                val presetIndex = start + idx
                inputUrl.setText(url)
                currentVideoUrl = YoutubeUrl.normalize(url)
                formatSpinner.setSelection(presetIndex)
                confirmDestinationAndRun { startDownloadSingle() }
            }
            .setNegativeButton(R.string.main_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshStreamUrlDisplay()
        syncLangButtonLabel()
    }

    private fun refreshStreamUrlDisplay() {
        val url = LocalStreamHolder.streamUrlForDisplay(this)
        textStreamUrl.text = url ?: getString(R.string.main_stream_url_unavailable)
    }

    private fun syncLangButtonLabel() {
        val locales = AppCompatDelegate.getApplicationLocales()
        val lang = if (!locales.isEmpty) {
            locales[0]?.language
        } else {
            resources.configuration.locales[0].language
        }
        btnLangMenu.text = if (lang == "en") {
            getString(R.string.lang_button_en)
        } else {
            getString(R.string.lang_button_ro)
        }
    }

    private fun appendTerminalLine(line: String) {
        runOnUiThread {
            terminalLog.append(line)
            if (!line.endsWith("\n")) {
                terminalLog.append("\n")
            }
            terminalScroll.post {
                terminalScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun finishTerminalUpdateUi() {
        terminalProgress.visibility = View.GONE
        btnTerminalClose.isEnabled = true
        btnIconUpdate.isEnabled = true
    }

    private fun startManualDependencyUpdate() {
        if (terminalOverlay.visibility == View.VISIBLE) {
            return
        }
        terminalOverlay.visibility = View.VISIBLE
        terminalLog.text = ""
        terminalProgress.visibility = View.VISIBLE
        btnTerminalClose.isEnabled = false
        btnIconUpdate.isEnabled = false

        val act = this@MainActivity
        Thread {
            appendTerminalLine(act.getString(R.string.main_term_banner))
            appendTerminalLine(act.getString(R.string.main_term_info_net))
            appendTerminalLine(act.getString(R.string.main_term_step_ytdlp))

            val app = application as? App
            val initErr = app?.initError
            if (initErr != null) {
                appendTerminalLine(act.getString(R.string.main_term_fail_init, initErr))
                appendTerminalLine(act.getString(R.string.main_term_hint_reinstall))
                runOnUiThread { finishTerminalUpdateUi() }
                return@Thread
            }

            val updateResult = runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(
                    applicationContext,
                    YoutubeDL.UpdateChannel._STABLE
                )
            }
            if (updateResult.isSuccess) {
                appendTerminalLine(act.getString(R.string.main_term_ok_ytdlp))
            } else {
                val msg = updateResult.exceptionOrNull()?.message
                    ?: act.getString(R.string.main_term_unknown_error)
                appendTerminalLine(act.getString(R.string.main_term_fail_ytdlp, msg))
            }

            appendTerminalLine(act.getString(R.string.main_term_step_ffmpeg))
            val ffOk = runCatching { FFmpeg.getInstance() }.isSuccess
            if (ffOk) {
                appendTerminalLine(act.getString(R.string.main_term_ok_ffmpeg))
            } else {
                appendTerminalLine(act.getString(R.string.main_term_warn_ffmpeg))
            }

            appendTerminalLine(act.getString(R.string.main_term_info_modules))
            appendTerminalLine("")
            appendTerminalLine(act.getString(R.string.main_term_done))
            runOnUiThread { finishTerminalUpdateUi() }
        }.start()
    }

    private fun loadFormats() {
        val labels = resources.getStringArray(R.array.format_preset_labels)
        formatSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels.toList()
        )
    }

    private fun confirmDestinationAndRun(action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_dialog_save_title)
            .setItems(
                arrayOf(
                    getString(R.string.main_save_public),
                    getString(R.string.main_save_other)
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        savePrefs.setDestination(SaveDestination.PUBLIC_DOWNLOADS)
                        action()
                    }
                    1 -> {
                        pendingAfterFolder = Runnable {
                            savePrefs.setDestination(SaveDestination.USER_PICKED_FOLDER)
                            action()
                        }
                        openTreeLauncher.launch(null)
                    }
                }
            }
            .setNegativeButton(R.string.main_cancel, null)
            .show()
    }

    private fun prepareYtdlp() {
        val app = application as? App
        val initErr = app?.initError
        if (initErr != null) {
            showError(getString(R.string.err_init_ytdlp, initErr))
            return
        }
        progressPrepare.visibility = View.VISIBLE
        btnGo.isEnabled = false
        btnDownload.isEnabled = false
        btnDownloadPlaylistSelected.isEnabled = false
        btnDownloadPlaylistFull.isEnabled = false
        Thread {
            val updateResult = runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(
                    applicationContext,
                    YoutubeDL.UpdateChannel._STABLE
                )
            }
            runOnUiThread {
                progressPrepare.visibility = View.GONE
                btnGo.isEnabled = true
                updateResult.exceptionOrNull()?.let { e ->
                    showError(
                        getString(
                            R.string.err_update_ytdlp,
                            e.message ?: e.toString()
                        )
                    )
                }
            }
        }.start()
    }

    private fun isUrl(s: String): Boolean =
        s.startsWith("http://", true) || s.startsWith("https://", true)

    private fun onGo() {
        val raw = inputUrl.text.toString().trim()
        if (raw.isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_url, Toast.LENGTH_SHORT).show()
            return
        }
        errorText.visibility = View.GONE
        if (isUrl(raw)) {
            runUrlFlow(raw)
        } else {
            runSearchFlow(raw)
        }
    }

    private fun runUrlFlow(raw: String) {
        progressPrepare.visibility = View.VISIBLE
        btnGo.isEnabled = false
        searchPanel.visibility = View.GONE
        searchAdapter = null
        playlistPanel.visibility = View.GONE
        playlistAdapter = null
        btnDownload.visibility = View.VISIBLE
        Thread {
            val r = YtdlpJson.describeUrl(applicationContext, raw)
            runOnUiThread urlFlow@{
                progressPrepare.visibility = View.GONE
                btnGo.isEnabled = true
                if (!r.ok) {
                    showError(
                        YtdlpError.sanitizeForUser(
                            this@MainActivity,
                            r.error ?: getString(R.string.err_url_unreachable)
                        )
                    )
                    return@urlFlow
                }
                currentVideoUrl = YoutubeUrl.normalize(raw)
                statusText.text = r.description
                statusText.visibility = View.VISIBLE

                val isListable =
                    (r.contentType == "playlist" || r.contentType == "channel") && r.count > 0
                if (isListable) {
                    playlistPanel.visibility = View.GONE
                    urlOptionsRow.visibility = View.GONE
                    btnDownload.visibility = View.GONE
                    btnDownload.isEnabled = false
                    progressPrepare.visibility = View.VISIBLE
                    Thread {
                        val entriesRes = YtdlpJson.fetchPlaylistEntries(applicationContext, raw)
                        runOnUiThread playlistUi@{
                            progressPrepare.visibility = View.GONE
                            entriesRes.exceptionOrNull()?.let { e ->
                                urlOptionsRow.visibility = View.VISIBLE
                                btnDownload.visibility = View.VISIBLE
                                btnDownload.isEnabled = true
                                showError(
                                    YtdlpError.sanitizeForUser(
                                        this@MainActivity,
                                        e.message ?: getString(R.string.err_playlist_load)
                                    )
                                )
                                return@playlistUi
                            }
                            val list = entriesRes.getOrNull().orEmpty()
                            if (list.isEmpty()) {
                                urlOptionsRow.visibility = View.VISIBLE
                                btnDownload.visibility = View.VISIBLE
                                btnDownload.isEnabled = true
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.toast_playlist_fallback,
                                    Toast.LENGTH_LONG
                                ).show()
                                return@playlistUi
                            }
                            playlistPanel.visibility = View.VISIBLE
                            urlOptionsRow.visibility = View.GONE
                            btnDownload.visibility = View.GONE
                            val adapter = SearchResultsAdapter(list)
                            adapter.onPlayInApp = { item -> startPlayInAppFromSearchItem(item) }
                            adapter.onOpenUrl = { url ->
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                            adapter.onSelectionChanged = {
                                updateBatchButton(adapter, btnDownloadPlaylistSelected)
                            }
                            playlistRecycler.adapter = adapter
                            playlistAdapter = adapter
                            cbPlaylistSelectAll.setOnCheckedChangeListener(null)
                            cbPlaylistSelectAll.isChecked = false
                            cbPlaylistSelectAll.setOnCheckedChangeListener { _, checked ->
                                adapter.selectAll(checked)
                            }
                            updateBatchButton(adapter, btnDownloadPlaylistSelected)
                        }
                    }.start()
                } else {
                    playlistPanel.visibility = View.GONE
                    playlistAdapter = null
                    if (r.contentType == "video") {
                        urlOptionsRow.visibility = View.GONE
                    } else {
                        urlOptionsRow.visibility = View.VISIBLE
                    }
                    btnDownload.visibility = View.VISIBLE
                    btnDownload.isEnabled = true
                }
            }
        }.start()
    }

    private fun runSearchFlow(query: String) {
        errorText.visibility = View.GONE
        progressPrepare.visibility = View.VISIBLE
        btnGo.isEnabled = false
        statusText.visibility = View.GONE
        urlOptionsRow.visibility = View.GONE
        playlistPanel.visibility = View.GONE
        playlistAdapter = null
        currentVideoUrl = null
        btnDownload.isEnabled = false
        Thread {
            val res = YtdlpJson.searchYoutube(applicationContext, query)
            runOnUiThread {
                progressPrepare.visibility = View.GONE
                btnGo.isEnabled = true
                res.exceptionOrNull()?.let { e ->
                    showError(
                        YtdlpError.sanitizeForUser(
                            this@MainActivity,
                            e.message ?: getString(R.string.err_search_failed)
                        )
                    )
                    return@runOnUiThread
                }
                val list = res.getOrNull() ?: emptyList()
                if (list.isEmpty()) {
                    showError(getString(R.string.err_no_results))
                    return@runOnUiThread
                }
                searchPanel.visibility = View.VISIBLE
                btnDownload.visibility = View.GONE
                val adapter = SearchResultsAdapter(list)
                adapter.onPlayInApp = { item -> startPlayInAppFromSearchItem(item) }
                adapter.onOpenUrl = { url ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                adapter.onSelectionChanged = { updateBatchButton(adapter, btnDownloadBatch) }
                searchRecycler.adapter = adapter
                searchAdapter = adapter
                cbSelectAll.setOnCheckedChangeListener(null)
                cbSelectAll.isChecked = false
                cbSelectAll.setOnCheckedChangeListener { _, checked ->
                    adapter.selectAll(checked)
                }
                updateBatchButton(adapter, btnDownloadBatch)
            }
        }.start()
    }

    private fun updateBatchButton(adapter: SearchResultsAdapter, button: Button) {
        val n = adapter.selectedCount()
        button.text = getString(R.string.main_download_selected, n)
        button.isEnabled = n > 0
    }

    private fun startPlayInAppFromSearchItem(item: SearchResultItem) {
        Toast.makeText(this, R.string.search_play_preparing, Toast.LENGTH_SHORT).show()
        Thread {
            val r = YtdlpPlayUrl.extractStreamUrlForPlayback(applicationContext, item.url)
            runOnUiThread {
                r.fold(
                    onSuccess = { streamUrl ->
                        startActivity(
                            Intent(this@MainActivity, PlayerActivity::class.java).apply {
                                putExtra(PlayerActivity.EXTRA_URI, streamUrl)
                                putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                            }
                        )
                    },
                    onFailure = { e ->
                        Toast.makeText(
                            this@MainActivity,
                            YtdlpError.sanitizeForUser(
                                this@MainActivity,
                                e.message ?: getString(R.string.search_play_failed)
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }.start()
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE
        statusText.visibility = View.GONE
        urlOptionsRow.visibility = View.GONE
        playlistPanel.visibility = View.GONE
        playlistAdapter = null
        btnDownload.isEnabled = false
    }

    private fun showDownloadProgressUi() {
        downloadProgressContainer.visibility = View.VISIBLE
        downloadProgressBar.progress = 0
        downloadProgressText.text = "0%"
    }

    private fun hideDownloadProgressUi() {
        downloadProgressContainer.visibility = View.GONE
    }

    private fun formatEtaLine(etaSeconds: Long): String {
        if (etaSeconds <= 0L) return ""
        val m = etaSeconds / 60
        val s = etaSeconds % 60
        return if (m > 0) {
            getString(R.string.eta_min_sec, m, s)
        } else {
            getString(R.string.eta_sec_only, s)
        }
    }

    private fun startDownloadSingle() {
        val url = currentVideoUrl ?: return
        val formatIndex = formatSpinner.selectedItemPosition
        if (formatIndex !in YtdlpPresets.ALL.indices) return
        val noPlaylist = switchNoPlaylist.isChecked
        progressPrepare.visibility = View.GONE
        showDownloadProgressUi()
        btnDownload.isEnabled = false
        btnGo.isEnabled = false
        Thread {
            val outputDir = File(getExternalFilesDir(null), "downloads").apply { mkdirs() }
            val result = YtdlpDownload.runDownload(
                applicationContext,
                url, outputDir, formatIndex, noPlaylist,
                onProgress = { progress, eta, line ->
                    runOnUiThread {
                        val pct = (progress * 100f).toInt().coerceIn(0, 100)
                        downloadProgressBar.progress = pct
                        val tail = line.trim().take(72)
                        downloadProgressText.text = buildString {
                            append("$pct%")
                            append(formatEtaLine(eta))
                            if (tail.isNotEmpty()) {
                                append("\n")
                                append(tail)
                            }
                        }
                    }
                }
            )
            val exported = runExportForPrefs(result.getOrNull().orEmpty())
            runOnUiThread {
                hideDownloadProgressUi()
                btnDownload.isEnabled = true
                btnGo.isEnabled = true
                result.exceptionOrNull()?.let {
                    showError(
                        getString(
                            R.string.err_download_local_failed,
                            YtdlpError.sanitizeForUser(
                                this@MainActivity,
                                it.message ?: getString(R.string.err_generic)
                            )
                        )
                    )
                    return@runOnUiThread
                }
                val files = result.getOrNull().orEmpty()
                if (files.isEmpty()) {
                    showError(getString(R.string.err_no_files_after))
                    return@runOnUiThread
                }
                toastExportResult(files, exported)
            }
        }.start()
    }

    private fun startDownloadPlaylistFull() {
        val url = currentVideoUrl ?: return
        val formatIndex = formatSpinner.selectedItemPosition
        if (formatIndex !in YtdlpPresets.ALL.indices) return
        progressPrepare.visibility = View.GONE
        showDownloadProgressUi()
        btnDownloadPlaylistFull.isEnabled = false
        btnDownloadPlaylistSelected.isEnabled = false
        btnGo.isEnabled = false
        Thread {
            val outputDir = File(getExternalFilesDir(null), "downloads").apply { mkdirs() }
            val result = YtdlpDownload.runDownload(
                applicationContext,
                url, outputDir, formatIndex, noPlaylist = false,
                onProgress = { progress, eta, line ->
                    runOnUiThread {
                        val pct = (progress * 100f).toInt().coerceIn(0, 100)
                        downloadProgressBar.progress = pct
                        val tail = line.trim().take(72)
                        downloadProgressText.text = buildString {
                            append(getString(R.string.progress_playlist, pct))
                            append(formatEtaLine(eta))
                            if (tail.isNotEmpty()) {
                                append("\n")
                                append(tail)
                            }
                        }
                    }
                }
            )
            val exported = runExportForPrefs(result.getOrNull().orEmpty())
            runOnUiThread {
                hideDownloadProgressUi()
                btnDownloadPlaylistFull.isEnabled = true
                btnDownloadPlaylistSelected.isEnabled =
                    (playlistAdapter?.selectedCount() ?: 0) > 0
                btnGo.isEnabled = true
                result.exceptionOrNull()?.let {
                    showError(
                        getString(
                            R.string.err_download_failed,
                            YtdlpError.sanitizeForUser(
                                this@MainActivity,
                                it.message ?: getString(R.string.err_generic)
                            )
                        )
                    )
                    return@runOnUiThread
                }
                val files = result.getOrNull().orEmpty()
                if (files.isEmpty()) {
                    showError(getString(R.string.err_no_files_after))
                    return@runOnUiThread
                }
                toastExportResult(files, exported)
            }
        }.start()
    }

    private fun startBatchDownload(adapter: SearchResultsAdapter, batchButton: Button) {
        val urls = adapter.getSelectedUrls()
        if (urls.isEmpty()) return
        val formatIndex = formatSpinner.selectedItemPosition
        if (formatIndex !in YtdlpPresets.ALL.indices) return
        val total = urls.size
        progressPrepare.visibility = View.GONE
        showDownloadProgressUi()
        batchButton.isEnabled = false
        btnGo.isEnabled = false
        val appCtx = applicationContext
        Thread {
            val outputDir = File(getExternalFilesDir(null), "downloads").apply { mkdirs() }
            val allFiles = mutableListOf<File>()
            val errors = mutableListOf<String>()
            urls.forEachIndexed { index, url ->
                val result = YtdlpDownload.runDownload(
                    applicationContext,
                    url, outputDir, formatIndex, noPlaylist = true,
                    onProgress = { progress, eta, line ->
                        runOnUiThread {
                            val pct = (progress * 100f).toInt().coerceIn(0, 100)
                            downloadProgressBar.progress = pct
                            val tail = line.trim().take(56)
                            downloadProgressText.text = buildString {
                                append(
                                    getString(
                                        R.string.progress_clip,
                                        index + 1,
                                        total,
                                        pct
                                    )
                                )
                                append(formatEtaLine(eta))
                                if (tail.isNotEmpty()) {
                                    append("\n")
                                    append(tail)
                                }
                            }
                        }
                    }
                )
                result.onSuccess { allFiles.addAll(it) }
                result.onFailure {
                    errors.add(it.message ?: appCtx.getString(R.string.err_generic))
                }
            }
            val exported = runExportForPrefs(allFiles)
            runOnUiThread {
                hideDownloadProgressUi()
                batchButton.isEnabled = adapter.selectedCount() > 0
                btnGo.isEnabled = true
                if (allFiles.isEmpty()) {
                    showError(
                        YtdlpError.sanitizeForUser(
                            this@MainActivity,
                            errors.firstOrNull()
                                ?: getString(R.string.err_no_successful)
                        )
                    )
                    return@runOnUiThread
                }
                errorText.visibility = View.GONE
                val dir = allFiles.first().parentFile?.absolutePath ?: ""
                val errHint = if (errors.isNotEmpty()) {
                    getString(
                        R.string.toast_some_errors,
                        YtdlpError.sanitizeForUser(
                            this@MainActivity,
                            errors.first()
                        )
                    )
                } else {
                    ""
                }
                Toast.makeText(
                    this,
                    getString(
                        R.string.toast_batch_done,
                        allFiles.size,
                        dir,
                        exported,
                        errHint
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun runExportForPrefs(files: List<File>): Int {
        if (files.isEmpty()) return 0
        return when (savePrefs.getDestination()) {
            SaveDestination.PRIVATE_APP_ONLY -> 0
            SaveDestination.PUBLIC_DOWNLOADS -> {
                var ok = 0
                for (f in files) {
                    if (runCatching { DownloadExporter.copyToPublicDownloads(this, f) }.getOrNull() != null) {
                        ok++
                    }
                }
                ok
            }
            SaveDestination.USER_PICKED_FOLDER -> {
                val uriStr = savePrefs.getTreeUriString() ?: return fallbackPublicExport(files)
                val uri = Uri.parse(uriStr)
                var ok = 0
                for (f in files) {
                    if (UserFolderExporter.copyFileToTree(this, uri, f)) ok++
                }
                if (ok == 0 && files.isNotEmpty()) fallbackPublicExport(files) else ok
            }
        }
    }

    private fun fallbackPublicExport(files: List<File>): Int {
        var ok = 0
        for (f in files) {
            if (runCatching { DownloadExporter.copyToPublicDownloads(this, f) }.getOrNull() != null) ok++
        }
        return ok
    }

    private fun toastExportResult(files: List<File>, exportedCount: Int) {
        val names = files.joinToString(", ") { it.name }
        val appDir = files.first().parentFile?.absolutePath ?: ""
        val mode = savePrefs.getDestination()
        val exportLine = when (mode) {
            SaveDestination.PRIVATE_APP_ONLY ->
                getString(R.string.export_private_only)
            SaveDestination.USER_PICKED_FOLDER ->
                when {
                    exportedCount >= files.size -> getString(R.string.export_user_ok)
                    exportedCount > 0 -> getString(
                        R.string.export_user_partial,
                        exportedCount,
                        files.size
                    )
                    else -> getString(R.string.export_user_fail)
                }
            SaveDestination.PUBLIC_DOWNLOADS ->
                when {
                    exportedCount >= files.size && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                        getString(R.string.export_public_q)
                    exportedCount >= files.size ->
                        getString(R.string.export_public_ok)
                    else ->
                        getString(R.string.export_public_partial, exportedCount, files.size)
                }
        }
        Toast.makeText(
            this,
            getString(R.string.toast_done_files, names, appDir, exportLine),
            Toast.LENGTH_LONG
        ).show()
    }
}
