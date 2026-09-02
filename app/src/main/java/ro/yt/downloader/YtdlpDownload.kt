package ro.yt.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.io.File

private val FORMATS_VIDEO_TO_TRY = listOf(
    "bestvideo+bestaudio/best",
    "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best",
    "bestvideo[ext=mp4]+bestaudio/best",
    "bestvideo[ext=webm]+bestaudio[ext=webm]/best",
    "bestvideo+bestaudio",
    "best[ext=mp4]/best[ext=webm]/best",
    "best[ext=mp4]",
    "best[ext=webm]",
    "best",
    "besteffort",
    "worst"
)

private val FORMATS_AUDIO_TO_TRY = listOf(
    "download/bestaudio/best",
    "bestaudio[format_id=download]/bestaudio/best",
    "bestaudio[ext=flac]/bestaudio[ext=wav]/bestaudio[ext=alac]/bestaudio/best",
    "bestaudio/best",
    "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio/best",
    "ba/b",
    "bestaudio*",
    "bestaudio",
    "best[ext=mp4]/best[ext=webm]/best",
    "best",
    "bv*+ba/b",
    "besteffort",
    "worst"
)

/** Preferă default-urile yt-dlp; fallback-urile vechi rămân ca rezervă. */
private val YT_EXTRACTOR_ATTEMPTS: List<String?> = YtdlpYoutubeClients.extractorArgAttempts()

object YtdlpDownload {

    private fun isFormatNotAvailable(msg: String): Boolean {
        val m = msg.lowercase()
        return "format is not available" in m ||
            "requested format" in m ||
            ("no video formats" in m && "available" in m) ||
            "unable to download" in m && "format" in m
    }

    private fun responseText(response: YoutubeDLResponse): String {
        val a = response.err.trim()
        val b = response.out.trim()
        return when {
            a.isNotBlank() && b.isNotBlank() -> "$a\n$b"
            a.isNotBlank() -> a
            else -> b
        }
    }

    fun runDownload(
        appContext: Context,
        url: String,
        outputDir: File,
        presetIndex: Int,
        noPlaylist: Boolean,
        onProgress: ((Float, Long, String) -> Unit)? = null
    ): Result<List<File>> {
        val targetUrl = YoutubeUrl.normalize(url)
        val attempts = if (YoutubeUrl.isYouTubePage(targetUrl)) {
            YT_EXTRACTOR_ATTEMPTS
        } else {
            listOf(null)
        }
        var lastMsg = ""
        for (extractorArgs in attempts) {
            val result = runDownloadWithExtractor(
                appContext, targetUrl, outputDir, presetIndex, noPlaylist, extractorArgs, onProgress
            )
            if (result.isSuccess) return result
            lastMsg = result.exceptionOrNull()?.message.orEmpty()
            // Continuă cu următorul client YouTube; pentru non-YT rămâne o singură încercare.
        }
        return Result.failure(
            IllegalStateException(YtdlpError.sanitizeForUser(appContext, lastMsg))
        )
    }

    private fun runDownloadWithExtractor(
        appContext: Context,
        url: String,
        outputDir: File,
        presetIndex: Int,
        noPlaylist: Boolean,
        youtubeExtractorArgs: String?,
        onProgress: ((Float, Long, String) -> Unit)? = null
    ): Result<List<File>> {
        if (presetIndex !in YtdlpPresets.ALL.indices) {
            return Result.failure(
                IllegalArgumentException(appContext.getString(R.string.err_preset_invalid))
            )
        }
        val preset = YtdlpPresets.ALL[presetIndex]
        val isVideoPreset = YtdlpPresets.isVideoPresetIndex(presetIndex)
        val forceMp3ForYoutubeNativeAudio = (presetIndex == 5 || presetIndex == 6) && YoutubeUrl.isYouTubePage(url)
        val formatsToTry = buildFormatsToTry(preset.formatSpec, isVideoPreset)

        val before = outputDir.listFiles()?.filter { it.isFile }?.map { it.name to it.length() }?.toSet() ?: emptySet()

        var lastErr: String? = null
        for ((i, fmt) in formatsToTry.withIndex()) {
            val req = buildRequest(
                url, outputDir, fmt, preset, i, noPlaylist, isVideoPreset,
                youtubeExtractorArgs, forceMp3ForYoutubeNativeAudio
            )
            val response = runCatching {
                if (onProgress != null) {
                    val procId = "dl-${System.nanoTime()}"
                    YoutubeDL.getInstance().execute(req, procId) { progress, eta, line ->
                        onProgress.invoke(progress, eta, line)
                    }
                } else {
                    YoutubeDL.getInstance().execute(req)
                }
            }.getOrElse { e ->
                // Biblioteca aruncă pe exitCode != 0 — tratează ca eșec de format când e cazul.
                lastErr = e.message.orEmpty()
                if (lastErr.isNotBlank() && isFormatNotAvailable(lastErr)) {
                    return@getOrElse null
                }
                // Bot/auth pe clientul curent — nu mai încerca restul formatelor.
                return Result.failure(e)
            }
            if (response == null) {
                continue
            }
            if (response.exitCode != 0) {
                lastErr = responseText(response)
                if (!lastErr.isNullOrBlank() && isFormatNotAvailable(lastErr)) {
                    continue
                }
                return Result.failure(
                    IllegalStateException(
                        lastErr.ifBlank {
                            appContext.getString(R.string.err_download_exit_code, response.exitCode)
                        }
                    )
                )
            }
            lastErr = null
            break
        }
        if (lastErr != null) {
            return Result.failure(IllegalStateException(lastErr))
        }

        val after = outputDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val newFiles = after.filter { f -> (f.name to f.length()) !in before }
            .sortedBy { it.lastModified() }
        if (newFiles.isEmpty()) {
            return Result.failure(
                IllegalStateException(appContext.getString(R.string.err_no_new_output_file))
            )
        }
        return Result.success(newFiles)
    }

    private fun buildFormatsToTry(formatSpec: String, isVideoPreset: Boolean): List<String> {
        val rest = if (isVideoPreset) FORMATS_VIDEO_TO_TRY else FORMATS_AUDIO_TO_TRY
        val seen = LinkedHashSet<String>()
        val out = ArrayList<String>()
        for (f in listOf(formatSpec) + rest) {
            if (seen.add(f)) out.add(f)
        }
        return out
    }

    private fun buildRequest(
        url: String,
        outputDir: File,
        format: String,
        preset: FormatPreset,
        attemptIndex: Int,
        noPlaylist: Boolean,
        isVideoPreset: Boolean,
        youtubeExtractorArgs: String?,
        forceMp3ForYoutubeNativeAudio: Boolean
    ): YoutubeDLRequest {
        return when (format) {
            "besteffort" -> requestBestEffort(url, outputDir, preset, noPlaylist, youtubeExtractorArgs, forceMp3ForYoutubeNativeAudio)
            "worst" -> requestWorst(url, outputDir, preset, noPlaylist, youtubeExtractorArgs, forceMp3ForYoutubeNativeAudio)
            else -> requestNormal(
                url, outputDir, format, preset, attemptIndex, noPlaylist, isVideoPreset,
                youtubeExtractorArgs, forceMp3ForYoutubeNativeAudio
            )
        }
    }

    private fun requestNormal(
        url: String,
        outputDir: File,
        format: String,
        preset: FormatPreset,
        attemptIndex: Int,
        noPlaylist: Boolean,
        isVideoPreset: Boolean,
        youtubeExtractorArgs: String?,
        forceMp3ForYoutubeNativeAudio: Boolean
    ): YoutubeDLRequest {
        return YoutubeDLRequest(url).apply {
            addOption("--no-warnings")
            addOption("-f", format)
            addOption("-o", "${outputDir.absolutePath}/%(title)s.%(ext)s")
            if (noPlaylist) addOption("--no-playlist")
            if (YoutubeUrl.isYouTubePage(url) && !youtubeExtractorArgs.isNullOrBlank()) {
                addOption("--extractor-args", youtubeExtractorArgs)
            }
            if (attemptIndex == 0) {
                addOption("--sleep-interval", "2")
                addOption("--limit-rate", "5M")
            }
            if (attemptIndex == 0 && preset.formatSort != null) {
                addOption("--format-sort", preset.formatSort)
            }
            if (isVideoPreset && preset.videoMergeMp4 && format !in listOf("best", "besteffort", "worst")) {
                addOption("--merge-output-format", "mp4")
            }
            when {
                preset.audioExtract && preset.audioCodec != null -> {
                    addOption("-x")
                    addOption("--audio-format", preset.audioCodec)
                    addOption("--audio-quality", preset.audioQuality ?: "0")
                }
                forceMp3ForYoutubeNativeAudio -> {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                }
            }
            applyEmbedMetadataForAudio(preset, forceMp3ForYoutubeNativeAudio)
            applyWriteThumbnailSidecar()
            applyWriteInfoJson()
        }
    }
    private fun YoutubeDLRequest.applyEmbedMetadataForAudio(
        preset: FormatPreset,
        forceMp3ForYoutubeNativeAudio: Boolean = false
    ) {
        val shouldEmbed = (preset.audioExtract && !preset.rawAudioNoExtract) || forceMp3ForYoutubeNativeAudio
        if (!shouldEmbed) return
        addOption("--embed-metadata")
        addOption("--embed-thumbnail")
    }

    /** Copertă pe disc lângă media (pentru player/cast). */
    private fun YoutubeDLRequest.applyWriteThumbnailSidecar() {
        addOption("--write-thumbnail")
        addOption("--convert-thumbnails", "jpg")
    }

    /** Metadate yt-dlp pe disc — convertite în .dlpulse.json la export. */
    private fun YoutubeDLRequest.applyWriteInfoJson() {
        addOption("--write-info-json")
    }

    private fun requestBestEffort(
        url: String,
        outputDir: File,
        preset: FormatPreset,
        noPlaylist: Boolean,
        youtubeExtractorArgs: String?,
        forceMp3ForYoutubeNativeAudio: Boolean
    ): YoutubeDLRequest {
        return YoutubeDLRequest(url).apply {
            addOption("--no-warnings")
            addOption("-f", "besteffort")
            addOption("-o", "${outputDir.absolutePath}/%(title)s.%(ext)s")
            if (noPlaylist) addOption("--no-playlist")
            if (YoutubeUrl.isYouTubePage(url) && !youtubeExtractorArgs.isNullOrBlank()) {
                addOption("--extractor-args", youtubeExtractorArgs)
            }
            if (preset.audioExtract && preset.audioCodec != null) {
                addOption("-x")
                addOption("--audio-format", preset.audioCodec)
                addOption("--audio-quality", preset.audioQuality ?: "0")
            } else if (forceMp3ForYoutubeNativeAudio) {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
            }
            applyEmbedMetadataForAudio(preset, forceMp3ForYoutubeNativeAudio)
            applyWriteThumbnailSidecar()
            applyWriteInfoJson()
        }
    }

    private fun requestWorst(
        url: String,
        outputDir: File,
        preset: FormatPreset,
        noPlaylist: Boolean,
        youtubeExtractorArgs: String?,
        forceMp3ForYoutubeNativeAudio: Boolean
    ): YoutubeDLRequest {
        return YoutubeDLRequest(url).apply {
            addOption("--no-warnings")
            addOption("-f", "worst")
            addOption("-o", "${outputDir.absolutePath}/%(title)s.%(ext)s")
            if (noPlaylist) addOption("--no-playlist")
            if (YoutubeUrl.isYouTubePage(url) && !youtubeExtractorArgs.isNullOrBlank()) {
                addOption("--extractor-args", youtubeExtractorArgs)
            }
            if (preset.audioExtract && preset.audioCodec != null) {
                addOption("-x")
                addOption("--audio-format", preset.audioCodec)
                addOption("--audio-quality", preset.audioQuality ?: "0")
            } else if (forceMp3ForYoutubeNativeAudio) {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
            }
            applyEmbedMetadataForAudio(preset, forceMp3ForYoutubeNativeAudio)
            applyWriteThumbnailSidecar()
            applyWriteInfoJson()
        }
    }
}
