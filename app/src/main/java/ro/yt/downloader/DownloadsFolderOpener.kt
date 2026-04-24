package ro.yt.downloader

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast

object DownloadsFolderOpener {

    private const val SUBFOLDER = "DLPulse"

    /**
     * Încearcă să deschidă folderul public **Download/DLPulse** în managerul de fișiere.
     */
    fun openDlpulseFolder(context: Context) {
        val intents = mutableListOf<Intent>()

        val docUri = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3A" +
                Environment.DIRECTORY_DOWNLOADS + "%2F" + SUBFOLDER
        )
        intents.add(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )

        intents.add(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                    DocumentsContract.Document.MIME_TYPE_DIR
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )

        intents.add(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))

        for (intent in intents) {
            try {
                context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.downloads_folder_chooser_title))
                )
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(
            context,
            context.getString(R.string.downloads_folder_manual_hint),
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Deschide aplicația „Fișiere” / Documents UI la rădăcinea stocării interne, ca utilizatorul
     * să navigheze în foldere permise (Download, Music, DCIM, etc.).
     */
    fun openPrimaryStorageBrowser(context: Context) {
        val candidates = listOf(
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"),
            Uri.parse("content://com.android.externalstorage.documents/root/primary"),
        )
        for (u in candidates) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(u, DocumentsContract.Document.MIME_TYPE_DIR)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
                return
            } catch (_: Exception) {
            }
        }
        openDlpulseFolder(context)
    }
}
