package ro.yt.downloader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Permisiuni cerute la prima deschidere.
 *
 * Notă Android: la instalare se acordă doar permisiunile „normale” (internet etc.).
 * Media, notificări și „Acces la toate fișierele” se cer la runtime / în Setări.
 */
object AppStartupPermissions {

    fun runtimePermissionsToRequest(context: Context): Array<String> {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (!granted(context, Manifest.permission.READ_MEDIA_VIDEO)) {
                need.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (!granted(context, Manifest.permission.READ_MEDIA_AUDIO)) {
                need.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (!granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
                need.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= 28 &&
                !granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            ) {
                need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        return need.toTypedArray()
    }

    fun hasMediaReadAccess(context: Context): Boolean {
        if (canManageAllFiles()) return true
        return when {
            Build.VERSION.SDK_INT >= 33 ->
                granted(context, Manifest.permission.READ_MEDIA_VIDEO) &&
                    granted(context, Manifest.permission.READ_MEDIA_AUDIO)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> true
        }
    }

    /** Acces complet: mutare/ștergere fără dialog MediaStore pe fiecare fișier. */
    fun canManageAllFiles(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun needsAllFilesAccessPrompt(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !canManageAllFiles()
    }

    fun allFilesAccessSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
