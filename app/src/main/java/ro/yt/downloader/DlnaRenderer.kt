package ro.yt.downloader

/**
 * Renderer UPnP/DLNA descoperit pe LAN (Fire TV și alte TV-uri Amazon cu DLNA).
 */
data class DlnaRenderer(
    val name: String,
    val baseUrl: String,
    val avTransportControlUrl: String,
    val udn: String
) {
    fun displayLabel(): String = name.ifBlank { baseUrl }
}
