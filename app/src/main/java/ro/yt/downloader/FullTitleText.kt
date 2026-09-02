package ro.yt.downloader

import android.util.TypedValue
import android.widget.TextView

/**
 * Titlu / nume fișier pe mai multe rânduri, cu font mai mic când textul e lung.
 */
object FullTitleText {

    private const val MAX_LINES = 5
    private const val MAX_SP = 13f
    private const val MID_SP = 11.5f
    private const val MIN_SP = 10f

    fun bind(view: TextView, text: String) {
        view.maxLines = MAX_LINES
        view.ellipsize = android.text.TextUtils.TruncateAt.END
        view.text = text
        val sp = when {
            text.length > 140 -> MIN_SP
            text.length > 90 -> MID_SP
            text.length > 55 -> 12f
            else -> MAX_SP
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }
}
