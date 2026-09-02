package ro.yt.downloader

/**
 * Strategii YouTube pentru yt-dlp.
 * `null` = clienții default din yt-dlp (recomandat; se actualizează odată cu nightly).
 * Forțarea pe `android,web` / `tv_embedded` eșuează des (PO token / client scos).
 */
object YtdlpYoutubeClients {

    /** Încercări în ordine: default → safari/embed → TV → iOS → android (legacy). */
    fun extractorArgAttempts(): List<String?> = listOf(
        null,
        "youtube:player_client=web_safari,web_embedded",
        "youtube:player_client=tv,web_safari",
        "youtube:player_client=ios",
        "youtube:player_client=mweb,web_embedded"
    )

    fun looksLikeBotOrAuthBlock(msg: String): Boolean {
        val m = msg.lowercase()
        return "confirm you’re not a bot" in m ||
            "confirm you're not a bot" in m ||
            "sign in to confirm" in m ||
            "login required" in m ||
            ("bot" in m && "sign in" in m) ||
            "use --cookies" in m
    }
}
