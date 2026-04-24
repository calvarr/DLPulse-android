package ro.yt.downloader

/**
 * Aceleași preseturi ca în [yt_core].FORMAT_PRESETS (index 0..9).
 */
data class FormatPreset(
    val label: String,
    val formatSpec: String,
    /** Preseturi video 0–4: merge final în mp4. */
    val videoMergeMp4: Boolean = false,
    /** -x + --audio-format / --audio-quality */
    val audioExtract: Boolean = false,
    val audioCodec: String? = null,
    val audioQuality: String? = null,
    /** Doar audio OPUS: fără -x, descarcă fluxul audio nativ. */
    val rawAudioNoExtract: Boolean = false
)

object YtdlpPresets {

    val ALL: List<FormatPreset> = listOf(
        // „/best” = fallback dacă nu există pereche video+audio de combinat (ca în yt-dlp docs)
        FormatPreset("Video – cea mai bună calitate (video+audio)", "bestvideo+bestaudio/best", videoMergeMp4 = true),
        FormatPreset("Video 1080p", "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best", videoMergeMp4 = true),
        FormatPreset("Video 720p", "bestvideo[height<=720]+bestaudio/best[height<=720]/best", videoMergeMp4 = true),
        FormatPreset("Video 480p", "bestvideo[height<=480]+bestaudio/best[height<=480]/best", videoMergeMp4 = true),
        FormatPreset("Video 360p", "bestvideo[height<=360]+bestaudio/best[height<=360]/best", videoMergeMp4 = true),
        FormatPreset("Doar audio – MP3 320 kbps", "bestaudio/best", audioExtract = true, audioCodec = "mp3", audioQuality = "0"),
        FormatPreset("Doar audio – MP3 192 kbps", "bestaudio/best", audioExtract = true, audioCodec = "mp3", audioQuality = "2"),
        FormatPreset("Doar audio – MP3 128 kbps", "bestaudio/best", audioExtract = true, audioCodec = "mp3", audioQuality = "5"),
        FormatPreset("Doar audio – M4A (AAC)", "bestaudio/best", audioExtract = true, audioCodec = "m4a", audioQuality = "0"),
        FormatPreset("Doar audio – OPUS", "bestaudio/best", rawAudioNoExtract = true)
    )

    fun isVideoPresetIndex(index: Int): Boolean = index in 0..4
}
