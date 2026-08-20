package ro.yt.downloader

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Comenzi UPnP AVTransport: SetAVTransportURI / Play / Pause / Stop.
 */
object DlnaAvTransport {

    fun play(
        renderer: DlnaRenderer,
        streamUrl: String,
        title: String,
        mime: String
    ): Boolean {
        val meta = didlLite(streamUrl, title, mime)
        if (!soap(
                renderer.avTransportControlUrl,
                "SetAVTransportURI",
                """
                <InstanceID>0</InstanceID>
                <CurrentURI>${xmlEscape(streamUrl)}</CurrentURI>
                <CurrentURIMetaData>${xmlEscape(meta)}</CurrentURIMetaData>
                """.trimIndent()
            )
        ) {
            return false
        }
        return soap(
            renderer.avTransportControlUrl,
            "Play",
            """
            <InstanceID>0</InstanceID>
            <Speed>1</Speed>
            """.trimIndent()
        )
    }

    fun pause(renderer: DlnaRenderer): Boolean =
        soap(
            renderer.avTransportControlUrl,
            "Pause",
            "<InstanceID>0</InstanceID>"
        )

    fun stop(renderer: DlnaRenderer): Boolean =
        soap(
            renderer.avTransportControlUrl,
            "Stop",
            "<InstanceID>0</InstanceID>"
        )

    fun isPlaying(renderer: DlnaRenderer): Boolean? {
        val xml = soapResponse(
            renderer.avTransportControlUrl,
            "GetTransportInfo",
            "<InstanceID>0</InstanceID>"
        ) ?: return null
        val state = Regex(
            "<CurrentTransportState>([^<]+)</CurrentTransportState>",
            RegexOption.IGNORE_CASE
        ).find(xml)?.groupValues?.getOrNull(1)?.uppercase(Locale.ROOT)
        return when (state) {
            "PLAYING" -> true
            "PAUSED_PLAYBACK", "STOPPED", "NO_MEDIA_PRESENT" -> false
            else -> null
        }
    }

    private fun soap(controlUrl: String, action: String, bodyInner: String): Boolean {
        return soapResponse(controlUrl, action, bodyInner) != null
    }

    private fun soapResponse(controlUrl: String, action: String, bodyInner: String): String? {
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  $bodyInner
                </u:$action>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty(
                "SOAPACTION",
                "\"urn:schemas-upnp-org:service:AVTransport:1#$action\""
            )
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(envelope) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) text else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun didlLite(url: String, title: String, mime: String): String {
        val itemClass = when {
            mime.startsWith("audio/") -> "object.item.audioItem.musicTrack"
            else -> "object.item.videoItem"
        }
        val safeTitle = xmlEscape(title.ifBlank { "DLPulse" })
        val safeMime = xmlEscape(mime.ifBlank { "video/mp4" })
        val safeUrl = xmlEscape(url)
        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
              <item id="0" parentID="-1" restricted="1">
                <dc:title>$safeTitle</dc:title>
                <upnp:class>$itemClass</upnp:class>
                <res protocolInfo="http-get:*:$safeMime:*">$safeUrl</res>
              </item>
            </DIDL-Lite>
        """.trimIndent().replace("\n", "")
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
