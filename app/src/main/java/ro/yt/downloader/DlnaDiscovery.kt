package ro.yt.downloader

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Descoperire SSDP pentru MediaRenderer / AVTransport (Fire TV etc.).
 */
object DlnaDiscovery {

    private val searchTargets = listOf(
        "urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "ssdp:all"
    )

    fun discover(context: Context, timeoutMs: Long = 4500L): List<DlnaRenderer> {
        val found = ConcurrentHashMap<String, DlnaRenderer>()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val lock = wifi?.createMulticastLock("dlpulse-dlna")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            for (st in searchTargets) {
                runCatching { ssdpSearch(st, timeoutMs / searchTargets.size, found) }
            }
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
        return found.values
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .distinctBy { it.udn.ifBlank { it.avTransportControlUrl } }
    }

    private fun ssdpSearch(
        searchTarget: String,
        timeoutMs: Long,
        out: ConcurrentHashMap<String, DlnaRenderer>
    ) {
        val socket = DatagramSocket().apply {
            soTimeout = 400
            broadcast = true
            reuseAddress = true
        }
        try {
            val body = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: 239.255.255.250:1900\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 2\r\n")
                append("ST: $searchTarget\r\n")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName("239.255.255.250")
            socket.send(DatagramPacket(body, body.size, group, 1900))

            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(800L)
            val buf = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charset.forName("UTF-8"))
                    val location = headerValue(text, "LOCATION") ?: continue
                    val renderer = runCatching { fetchRenderer(location) }.getOrNull() ?: continue
                    val key = renderer.udn.ifBlank { renderer.avTransportControlUrl }
                    out.putIfAbsent(key, renderer)
                } catch (_: Exception) {
                    // timeout / parse — continuă până la deadline
                }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun headerValue(response: String, name: String): String? {
        val lines = response.split("\r\n", "\n")
        val prefix = "$name:"
        for (line in lines) {
            if (line.startsWith(prefix, ignoreCase = true)) {
                return line.substring(prefix.length).trim()
            }
        }
        return null
    }

    private fun fetchRenderer(location: String): DlnaRenderer? {
        val conn = (URL(location).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            parseDeviceDescription(location, xml)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseDeviceDescription(location: String, xml: String): DlnaRenderer? {
        val base = location.substringBeforeLast('/').let {
            // LOCATION e absolut; URL de bază = scheme://host:port
            try {
                val u = URL(location)
                "${u.protocol}://${u.host}${if (u.port != -1) ":${u.port}" else ""}"
            } catch (_: Exception) {
                it
            }
        }
        var friendlyName = ""
        var udn = ""
        var inAvTransport = false
        var controlUrl: String? = null
        var serviceType = ""

        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase(Locale.ROOT)) {
                        "friendlyname" -> friendlyName = parser.nextText().trim()
                        "udn" -> if (udn.isEmpty()) udn = parser.nextText().trim()
                        "service" -> {
                            inAvTransport = false
                            serviceType = ""
                            controlUrl = null
                        }
                        "servicetype" -> {
                            serviceType = parser.nextText().trim()
                            inAvTransport = serviceType.contains("AVTransport", ignoreCase = true)
                        }
                        "controlurl" -> {
                            val raw = parser.nextText().trim()
                            if (inAvTransport || serviceType.contains("AVTransport", ignoreCase = true)) {
                                controlUrl = resolveUrl(base, location, raw)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("service", ignoreCase = true) &&
                        serviceType.contains("AVTransport", ignoreCase = true) &&
                        !controlUrl.isNullOrBlank()
                    ) {
                        return DlnaRenderer(
                            name = friendlyName.ifBlank { "Amazon / DLNA" },
                            baseUrl = base,
                            avTransportControlUrl = controlUrl!!,
                            udn = udn
                        )
                    }
                }
            }
            event = parser.next()
        }
        return null
    }

    private fun resolveUrl(baseRoot: String, location: String, maybeRelative: String): String {
        if (maybeRelative.startsWith("http://", true) || maybeRelative.startsWith("https://", true)) {
            return maybeRelative
        }
        return try {
            URL(URL(location), maybeRelative).toString()
        } catch (_: Exception) {
            val path = if (maybeRelative.startsWith("/")) maybeRelative else "/$maybeRelative"
            baseRoot.trimEnd('/') + path
        }
    }
}
