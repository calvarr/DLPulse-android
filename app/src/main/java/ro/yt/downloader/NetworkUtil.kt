package ro.yt.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Collections

object NetworkUtil {

    /**
     * IPv4 din **rețeaua locală fizică** (Wi‑Fi sau Ethernet), pentru URL-ul HTTP al Chromecast.
     * Nu folosește Tailscale, alte VPN-uri, date mobile sau alte overlay-uri — TV-ul trebuie să poată
     * ajunge la același LAN ca telefonul.
     */
    fun localIpv4(context: Context): String? {
        val all = linkedSetOf<Inet4Address>()
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return pickBest(fallbackLanInterfacesOnly())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!isLocalLanTransport(caps)) continue
                val link = cm.getLinkProperties(network) ?: continue
                for (la in link.linkAddresses) {
                    val a = la.address
                    if (!a.isLoopbackAddress && a is Inet4Address) {
                        all.add(a)
                    }
                }
            }
        }
        if (all.isEmpty()) {
            all.addAll(fallbackLanInterfacesOnly())
        }
        return pickBest(all.toList())
    }

    /** Doar Wi‑Fi sau Ethernet, fără VPN (Tailscale apare de obicei ca VPN). */
    private fun isLocalLanTransport(caps: NetworkCapabilities): Boolean {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Fallback: doar interfețe tip wlan / eth / hotspot (ap0), fără tun, wg, rmnet (LTE), etc.
     */
    private fun fallbackLanInterfacesOnly(): List<Inet4Address> {
        val out = mutableListOf<Inet4Address>()
        try {
            Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).forEach { ni ->
                if (!looksLikeWifiEthernetOrHotspotIface(ni.name)) return@forEach
                Collections.list(ni.inetAddresses).forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        out.add(addr)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun looksLikeWifiEthernetOrHotspotIface(name: String): Boolean {
        val n = name.lowercase()
        if (n.startsWith("tun") || n.startsWith("wg") || n.contains("tailscale") ||
            n.startsWith("ppp") || n.contains("nordlynx") || n.startsWith("rmnet") ||
            n.startsWith("pdp") || n.startsWith("ccmn") || n.startsWith("v4-rmnet")
        ) {
            return false
        }
        return n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("eth") ||
            n.startsWith("ap") || n == "swlan0" || n.contains("wlan")
    }

    /** 100.64.0.0/10 — RFC 6598, folosit des de VPN (ex. Tailscale); nu e LAN-ul Wi‑Fi. */
    private fun isCgnatOrVpnOverlay(ip: Inet4Address): Boolean {
        val b = ip.address
        if (b.size < 4) return false
        val o1 = b[0].toInt() and 0xff
        val o2 = b[1].toInt() and 0xff
        return o1 == 100 && o2 in 64..127
    }

    private fun isPrivateLan(ip: Inet4Address): Boolean {
        val b = ip.address
        if (b.size < 4) return false
        val o1 = b[0].toInt() and 0xff
        val o2 = b[1].toInt() and 0xff
        if (o1 == 10) return true
        if (o1 == 172 && o2 in 16..31) return true
        if (o1 == 192 && o2 == 168) return true
        return false
    }

    private fun pickBest(candidates: List<Inet4Address>): String? {
        if (candidates.isEmpty()) return null
        val noOverlay = candidates.filter { !isCgnatOrVpnOverlay(it) }
        if (noOverlay.isEmpty()) return null
        val privateLan = noOverlay.filter { isPrivateLan(it) }
        if (privateLan.isNotEmpty()) {
            // Preferă subnet-ul tipic acasă (192.168 / 10.) înainte de 172.16–31 (unele hotspot-uri / docker).
            privateLan.sortedWith(
                compareBy<Inet4Address> { !it.hostAddress!!.startsWith("192.168.") }
                    .thenBy { !it.hostAddress!!.startsWith("10.") }
            ).firstOrNull()?.hostAddress?.let { return it }
        }
        return noOverlay.firstOrNull()?.hostAddress
    }

    /** Verificare TCP; nu apela pe main thread dacă timeout mare. */
    fun isTcpPortOpen(host: String, port: Int, timeoutMs: Int = 1500): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * HEAD simplu (fără Range) — dacă eșuează de pe telefon, Chromecast poate avea aceeași problemă.
     */
    fun isHttpHeadOk(url: String, timeoutMs: Int = 2500): Boolean {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
            }
            val code = c.responseCode
            c.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }
}
