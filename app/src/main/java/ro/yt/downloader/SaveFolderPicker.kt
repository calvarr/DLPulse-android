package ro.yt.downloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Dialog: navighează în Download/DLPulse, creează foldere, salvează aici sau alege cale custom (SAF).
 */
object SaveFolderPicker {

    fun show(
        activity: AppCompatActivity,
        prefs: SaveLocationPrefs,
        onSaveInDlpulse: () -> Unit,
        onPickCustomFolder: () -> Unit
    ) {
        DlpulseStorage.ensureRoot(activity)
        var currentRel = prefs.getDlpulseRelativePath()

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_save_folder_picker, null)
        val pathLabel = view.findViewById<TextView>(R.id.savePathLabel)
        val list = view.findViewById<RecyclerView>(R.id.saveFolderList)
        val btnNew = view.findViewById<MaterialButton>(R.id.btnSaveNewFolder)
        val btnCustom = view.findViewById<MaterialButton>(R.id.btnSaveCustom)

        list.layoutManager = LinearLayoutManager(activity)
        val rows = mutableListOf<String>()
        val adapter = object : RecyclerView.Adapter<FolderVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderVH {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_save_folder_row, parent, false)
                return FolderVH(v)
            }

            override fun getItemCount(): Int = rows.size

            override fun onBindViewHolder(holder: FolderVH, position: Int) {
                val name = rows[position]
                val isUp = name == ".."
                holder.name.text = if (isUp) {
                    activity.getString(R.string.save_picker_up)
                } else {
                    name
                }
                val icon = ContextCompat.getDrawable(
                    activity,
                    if (isUp) R.drawable.ic_nav_back else R.drawable.ic_folder
                )
                holder.name.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
                holder.itemView.setOnClickListener {
                    if (isUp) {
                        currentRel = parentRelative(currentRel)
                    } else {
                        currentRel = DlpulseStorage.normalizeRelative(
                            if (currentRel.isEmpty()) name else "$currentRel/$name"
                        )
                    }
                    refresh()
                }
            }

            fun refresh() {
                pathLabel.text = DlpulseStorage.displayPath(currentRel)
                rows.clear()
                if (currentRel.isNotEmpty()) rows.add("..")
                rows.addAll(DlpulseStorage.listImmediateSubfolders(currentRel))
                notifyDataSetChanged()
            }
        }
        list.adapter = adapter

        fun refresh() = adapter.refresh()
        refresh()

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.main_dialog_save_title)
            .setView(view)
            .setPositiveButton(R.string.save_picker_here) { _, _ ->
                prefs.setDestination(SaveDestination.PUBLIC_DOWNLOADS)
                prefs.setDlpulseRelativePath(currentRel)
                onSaveInDlpulse()
            }
            .setNegativeButton(R.string.main_cancel, null)
            .create()

        btnNew.setOnClickListener {
            showNewFolderDialog(activity, currentRel) {
                refresh()
            }
        }
        btnCustom.setOnClickListener {
            dialog.dismiss()
            onPickCustomFolder()
        }

        dialog.show()
    }

    private fun parentRelative(rel: String): String {
        val n = DlpulseStorage.normalizeRelative(rel)
        if (n.isEmpty()) return ""
        val idx = n.lastIndexOf('/')
        return if (idx <= 0) "" else n.substring(0, idx)
    }

    private fun showNewFolderDialog(
        activity: AppCompatActivity,
        parentRel: String,
        onCreated: () -> Unit
    ) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val input = EditText(activity)
        val container = FrameLayout(activity).apply {
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(pad, pad / 2, pad, pad) }
            )
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.save_picker_new_folder)
            .setView(container)
            .setPositiveButton(R.string.browse_ok) { _, _ ->
                val raw = input.text.toString()
                val ok = DownloadsIndex.createDlpulseSubfolder(activity, parentRel, raw)
                if (ok) {
                    onCreated()
                    Toast.makeText(activity, R.string.browse_new_folder_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, R.string.browse_new_folder_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.main_cancel, null)
            .show()
    }

    private class FolderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.saveFolderRowName)
    }
}
