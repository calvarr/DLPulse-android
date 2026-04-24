package ro.yt.downloader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Servește un fișier local pe HTTP (inclusiv Range) pentru Chromecast.
 *
 * HEAD: un singur Content-Length (prin constructorul NanoHTTPD cu lungimea corectă),
 * nu addHeader duplicat — unele playere refuză răspunsul.
 * Fișiere pe disc: [RandomAccessFile] pentru seek la Range (mai predictibil decât FileChannel).
 */
class LocalStreamServer(
    private val appContext: Context,
    private val mimeType: String,
    private val file: File?,
    private val contentUri: Uri?,
    port: Int
) : NanoHTTPD("0.0.0.0", port) {

    private fun log(msg: String) = DebugFileLog.append(appContext, "cast_debug.log", msg)

    private fun contentLength(): Long {
        file?.let { return it.length() }
        contentUri?.let { u ->
            appContext.contentResolver.openFileDescriptor(u, "r")?.use { return it.statSize }
        }
        return 0L
    }

    private fun openStreamAt(start: Long): InputStream? {
        file?.let { f ->
            return try {
                val raf = RandomAccessFile(f, "r")
                if (start > 0L) raf.seek(start)
                object : InputStream() {
                    override fun read(): Int = raf.read()
                    override fun read(b: ByteArray, off: Int, len: Int) = raf.read(b, off, len)
                    override fun close() {
                        raf.close()
                    }

                    override fun available(): Int {
                        val remaining = raf.length() - raf.filePointer
                        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    }
                }
            } catch (e: Exception) {
                log("openStreamAt file FAIL start=$start: ${e.message}")
                null
            }
        }
        contentUri?.let { u ->
            return try {
                val pfd = appContext.contentResolver.openFileDescriptor(u, "r") ?: return null
                val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                if (start > 0L) {
                    try {
                        stream.channel.position(start)
                    } catch (_: Exception) {
                        stream.close()
                        return openContentUriSkipping(u, start)
                    }
                }
                stream
            } catch (e: Exception) {
                log("openStreamAt contentUri FAIL start=$start: ${e.message}")
                null
            }
        }
        return null
    }

    private fun openContentUriSkipping(u: Uri, start: Long): InputStream? {
        return try {
            val pfd = appContext.contentResolver.openFileDescriptor(u, "r") ?: return null
            val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            var left = start
            while (left > 0L) {
                val n = stream.skip(left)
                if (n <= 0L) {
                    stream.close()
                    return null
                }
                left -= n
            }
            stream
        } catch (e: Exception) {
            log("openContentUriSkipping FAIL: ${e.message}")
            null
        }
    }

    private fun addCors(resp: Response) {
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Headers", "Range, Content-Type, Accept-Encoding")
        resp.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
        resp.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.substringBefore('?')
        if (path != "/stream") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
        val remote = session.headers["x-forwarded-for"]?.split(",")?.firstOrNull()?.trim()
            ?: session.remoteIpAddress
        val rangeHdr = session.headers["range"] ?: session.headers["Range"] ?: "-"
        log("stream ${session.method} remote=$remote range=$rangeHdr")
        when (session.method) {
            Method.OPTIONS -> {
                val r = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
                addCors(r)
                return r
            }
            Method.HEAD -> {
                val total = contentLength()
                if (total <= 0L) {
                    log("HEAD: empty contentLength")
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "empty")
                }
                val r = newFixedLengthResponse(Response.Status.OK, mimeType, null, total)
                r.addHeader("Accept-Ranges", "bytes")
                addCors(r)
                return r
            }
            Method.GET -> {
                val total = contentLength()
                if (total <= 0L) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "empty")
                }
                val rangeHeader = session.headers["range"] ?: session.headers["Range"]
                var start = 0L
                var end = total - 1L
                val hasRange = rangeHeader != null && rangeHeader.startsWith("bytes=")
                if (hasRange) {
                    val spec = rangeHeader!!.substring(6).split("-")
                    if (spec.isNotEmpty() && spec[0].isNotEmpty()) {
                        start = spec[0].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    }
                    if (spec.size > 1 && spec[1].isNotEmpty()) {
                        end = spec[1].toLongOrNull()?.coerceAtMost(total - 1L) ?: end
                    }
                    end = end.coerceAtMost(total - 1L)
                }
                if (start > end || start >= total) {
                    val r = newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE,
                        "text/plain",
                        "range"
                    )
                    r.addHeader("Content-Range", "bytes */$total")
                    addCors(r)
                    return r
                }
                val len = end - start + 1L
                val stream = openStreamAt(start)
                    ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "io")
                val status =
                    if (hasRange) Response.Status.PARTIAL_CONTENT else Response.Status.OK
                val resp = newFixedLengthResponse(status, mimeType, stream, len)
                resp.addHeader("Accept-Ranges", "bytes")
                if (status == Response.Status.PARTIAL_CONTENT) {
                    resp.addHeader("Content-Range", "bytes $start-$end/$total")
                }
                addCors(resp)
                return resp
            }
            else -> return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                "text/plain",
                "method"
            )
        }
    }
}

object LocalStreamHolder {

    private val lock = Any()
    private var instance: LocalStreamServer? = null
    private var lastUrl: String? = null
    private var lastFilePath: String? = null
    private var lastContentUriStr: String? = null

    fun stop() {
        synchronized(lock) {
            runCatching { instance?.stop() }
            instance = null
            lastUrl = null
            lastFilePath = null
            lastContentUriStr = null
        }
    }

    /**
     * URL afișat în UI: dacă serverul HTTP rulează, adresa reală; altfel previzualizare
     * `http://&lt;IP LAN&gt;:8765/stream` (primul port încercat la pornire).
     */
    fun streamUrlForDisplay(context: Context): String? {
        synchronized(lock) {
            val s = instance
            if (s != null && s.isAlive && lastUrl != null) {
                return lastUrl
            }
        }
        val ip = NetworkUtil.localIpv4(context.applicationContext) ?: return null
        return "http://$ip:8765/stream"
    }

    /**
     * Repornește serverul doar dacă instanța anterioară nu mai e vie sau sursa s-a schimbat.
     */
    fun ensureStreamRunning(
        context: Context,
        file: File?,
        contentUri: Uri?,
        mime: String
    ): String? {
        val fp = file?.absolutePath
        val us = contentUri?.toString()
        synchronized(lock) {
            val s = instance
            if (s != null && s.isAlive && fp == lastFilePath && us == lastContentUriStr) {
                return lastUrl
            }
        }
        return start(context, file, contentUri, mime)
    }

    fun start(context: Context, file: File?, contentUri: Uri?, mime: String): String? {
        stop()
        val app = context.applicationContext
        if (!CastMimeHelper.isDefaultReceiverSupported(mime)) {
            DebugFileLog.append(
                app,
                "cast_debug.log",
                "WARN: MIME '$mime' poate fi respins de Default Media Receiver (preferă MP4/WebM)."
            )
        }
        for (p in 8765..8815) {
            val server = LocalStreamServer(app, mime, file, contentUri, p)
            try {
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                val ip = NetworkUtil.localIpv4(app) ?: run {
                    DebugFileLog.append(
                        context,
                        "cast_debug.log",
                        "LocalStreamHolder: localIpv4 null (ex. doar VPN 100.64/10)"
                    )
                    server.stop()
                    return null
                }
                val url = "http://$ip:$p/stream"
                synchronized(lock) {
                    instance = server
                    lastUrl = url
                    lastFilePath = file?.absolutePath
                    lastContentUriStr = contentUri?.toString()
                }
                DebugFileLog.append(
                    context,
                    "cast_debug.log",
                    "LocalStreamHolder: $url mime=$mime"
                )
                Thread {
                    val open = NetworkUtil.isTcpPortOpen("127.0.0.1", p, timeoutMs = 800)
                    if (!open) {
                        DebugFileLog.append(
                            app,
                            "cast_debug.log",
                            "WARN: self-test TCP 127.0.0.1:$p failed"
                        )
                    }
                }.start()
                return url
            } catch (_: Exception) {
                runCatching { server.stop() }
            }
        }
        DebugFileLog.append(context, "cast_debug.log", "LocalStreamHolder: no free port 8765-8815")
        return null
    }
}
