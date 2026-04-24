package ro.yt.downloader

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException

/**
 * Inițializare yt-dlp / ffmpeg pe main thread, cu context de aplicație (cerut de bibliotecă).
 */
class App : Application() {

    /** Dacă nu e null, init-ul a eșuat — UI-ul poate afișa mesajul. */
    var initError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        AppLocale.applyPersisted(applicationContext)
        initError = try {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
            null
        } catch (e: YoutubeDLException) {
            e.message ?: e.toString()
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }
}
