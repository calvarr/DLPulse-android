package ro.yt.downloader

import android.app.ProgressDialog
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Alege Chromecast (Google Cast) sau Amazon Fire TV (DLNA).
 * Difuzoarele Echo nu acceptă cast third-party — mesaj explicativ.
 */
object CastTargetChooser {

    fun show(
        activity: AppCompatActivity,
        onChromecast: () -> Unit,
        onAmazonDevice: (DlnaRenderer) -> Unit
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.cast_target_title)
            .setItems(
                arrayOf(
                    activity.getString(R.string.cast_target_chromecast),
                    activity.getString(R.string.cast_target_amazon)
                )
            ) { _, which ->
                when (which) {
                    0 -> onChromecast()
                    1 -> pickAmazonDevice(activity, onAmazonDevice)
                }
            }
            .setNegativeButton(R.string.main_cancel, null)
            .show()
    }

    private fun pickAmazonDevice(
        activity: AppCompatActivity,
        onPicked: (DlnaRenderer) -> Unit
    ) {
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(activity).apply {
            setMessage(activity.getString(R.string.cast_amazon_scanning))
            setCancelable(true)
            show()
        }
        val main = Handler(Looper.getMainLooper())
        Thread {
            val devices = runCatching {
                DlnaDiscovery.discover(activity.applicationContext)
            }.getOrDefault(emptyList())
            main.post {
                runCatching { progress.dismiss() }
                if (!activity.isFinishing) {
                    if (devices.isEmpty()) {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.cast_target_amazon)
                            .setMessage(R.string.cast_amazon_none_found)
                            .setPositiveButton(R.string.main_close, null)
                            .show()
                    } else {
                        val labels = devices.map { it.displayLabel() }.toTypedArray()
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.cast_amazon_pick_device)
                            .setItems(labels) { _, idx ->
                                onPicked(devices[idx])
                            }
                            .setNegativeButton(R.string.main_cancel, null)
                            .show()
                    }
                }
            }
        }.start()
    }
}
