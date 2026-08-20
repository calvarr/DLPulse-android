package ro.yt.downloader

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Sesiune simplă de cast Amazon/DLNA: un renderer + playlist local HTTP.
 */
class AmazonCastSession(
    private val appContext: Context,
    private val renderer: DlnaRenderer,
    private val entries: List<DownloadedFileEntry>,
    private val onFinished: () -> Unit,
    private val onError: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var index = 0
    private var stopped = false
    private var pollRunnable: Runnable? = null
    private var wasPlaying = false

    fun start() {
        playCurrent()
    }

    fun stop() {
        stopped = true
        pollRunnable?.let { main.removeCallbacks(it) }
        pollRunnable = null
        Thread {
            runCatching { DlnaAvTransport.stop(renderer) }
            LocalStreamHolder.stop()
        }.start()
    }

    fun togglePause() {
        Thread {
            val playing = DlnaAvTransport.isPlaying(renderer)
            if (playing == true) {
                DlnaAvTransport.pause(renderer)
            } else {
                // Unele renderere nu reiau din Pause — re-Play.
                playCurrent(restartStream = false)
            }
        }.start()
    }

    private fun playCurrent(restartStream: Boolean = true) {
        if (stopped || index !in entries.indices) {
            onFinished()
            return
        }
        val entry = entries[index]
        onStatus(entry.title)
        Thread {
            val mime = if (entry.file != null) {
                CastMimeHelper.forFile(entry.file).mime
            } else {
                entry.mime
            }.let { DownloadMime.normalizeForCast(it, entry.title) }

            val url = if (restartStream) {
                LocalStreamHolder.ensureStreamRunning(
                    appContext,
                    entry.file,
                    entry.contentUri,
                    mime
                )
            } else {
                LocalStreamHolder.streamUrlForDisplay(appContext)
            }
            if (url == null) {
                main.post { onError(appContext.getString(R.string.cast_http_start_failed)) }
                return@Thread
            }
            val ok = DlnaAvTransport.play(renderer, url, entry.title, mime)
            if (!ok) {
                main.post {
                    onError(appContext.getString(R.string.cast_amazon_play_failed, renderer.displayLabel()))
                }
                return@Thread
            }
            wasPlaying = false
            main.post { schedulePoll() }
        }.start()
    }

    private fun schedulePoll() {
        pollRunnable?.let { main.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (stopped) return
                Thread {
                    val playing = DlnaAvTransport.isPlaying(renderer)
                    main.post {
                        if (stopped) return@post
                        when (playing) {
                            true -> wasPlaying = true
                            false -> {
                                if (wasPlaying) {
                                    // A terminat piesa curentă.
                                    index++
                                    if (index < entries.size) {
                                        playCurrent()
                                    } else {
                                        stop()
                                        onFinished()
                                    }
                                    return@post
                                }
                            }
                            null -> Unit
                        }
                        pollRunnable = this
                        main.postDelayed(this, 2500L)
                    }
                }.start()
            }
        }
        pollRunnable = r
        main.postDelayed(r, 2500L)
    }
}
