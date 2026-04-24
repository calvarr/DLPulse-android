package ro.yt.downloader

import android.content.Context

/** Dezactivat: nu mai scriem fișiere log pe disc. */
object DebugFileLog {

    @Suppress("UNUSED_PARAMETER")
    fun append(context: Context, fileName: String, message: String) {
        // no-op
    }
}
