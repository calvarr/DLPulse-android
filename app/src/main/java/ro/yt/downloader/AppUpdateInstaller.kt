package ro.yt.downloader

import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarcă APK-ul de pe GitHub în cache și deschide instalatorul Android.
 * Update in-place funcționează doar dacă APK-ul are aceeași semnătură ca aplicația instalată.
 */
object AppUpdateInstaller {

    fun start(activity: AppCompatActivity, apkUrl: String, versionLabel: String) {
        if (!canInstallPackages(activity)) {
            Toast.makeText(activity, R.string.update_need_unknown_sources, Toast.LENGTH_LONG).show()
            openUnknownSourcesSettings(activity)
            return
        }

        @Suppress("DEPRECATION")
        val progress = ProgressDialog(activity).apply {
            setTitle(R.string.update_downloading_title)
            setMessage(activity.getString(R.string.update_downloading_message, versionLabel))
            setCancelable(false)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            show()
        }
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = runCatching { downloadApk(activity, apkUrl) { pct ->
                main.post {
                    if (progress.isShowing) progress.progress = pct
                }
            } }
            main.post {
                runCatching { progress.dismiss() }
                if (activity.isFinishing) return@post
                result.fold(
                    onSuccess = { apkFile ->
                        launchInstall(activity, apkFile)
                    },
                    onFailure = { e ->
                        Toast.makeText(
                            activity,
                            activity.getString(
                                R.string.update_download_failed,
                                e.message ?: activity.getString(R.string.err_generic)
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }.start()
    }

    private fun canInstallPackages(activity: AppCompatActivity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun openUnknownSourcesSettings(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            runCatching { activity.startActivity(intent) }
        }
    }

    private fun downloadApk(
        activity: AppCompatActivity,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): File {
        val dir = File(activity.cacheDir, "apk-updates").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val outFile = File(dir, "DLPulse-update.apk")
        val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "DLPulse-Android-Updater")
            setRequestProperty("Accept", "application/octet-stream")
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            useCaches = false
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val total = conn.contentLengthLong.takeIf { it > 0L } ?: -1L
            conn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var readTotal = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        readTotal += n
                        if (total > 0L) {
                            val pct = ((readTotal * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }
            if (outFile.length() <= 0L) {
                throw IllegalStateException("APK empty")
            }
            onProgress(100)
            return outFile
        } finally {
            conn.disconnect()
        }
    }

    private fun launchInstall(activity: AppCompatActivity, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Toast.makeText(activity, R.string.update_install_prompt, Toast.LENGTH_LONG).show()
        activity.startActivity(intent)
    }
}
