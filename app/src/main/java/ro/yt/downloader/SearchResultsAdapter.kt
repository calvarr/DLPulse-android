package ro.yt.downloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class SearchResultsAdapter(
    private val items: List<SearchResultItem>
) : RecyclerView.Adapter<SearchResultsAdapter.VH>() {

    private val selected = LinkedHashSet<Int>()

    /** Redare stream în ExoPlayer (URL extras cu yt-dlp). */
    var onPlayInApp: ((SearchResultItem) -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null

    fun selectAll(select: Boolean) {
        selected.clear()
        if (select) {
            items.indices.forEach { selected.add(it) }
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    fun selectedCount(): Int = selected.size

    fun getSelectedUrls(): List<String> = selected.sorted().map { items[it].url }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        val subtitle = item.subtitle()
        if (!subtitle.isNullOrBlank()) {
            holder.subtitle.text = subtitle
            holder.subtitle.visibility = View.VISIBLE
        } else {
            holder.subtitle.visibility = View.GONE
        }
        val durationText = formatDuration(item.durationSeconds)
        if (durationText != null) {
            holder.duration.text = durationText
            holder.duration.visibility = View.VISIBLE
        } else {
            holder.duration.visibility = View.GONE
        }
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = selected.contains(position)
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selected.add(position) else selected.remove(position)
            onSelectionChanged?.invoke()
        }
        val thumbUrl = item.thumbnail?.trim().orEmpty()
        if (thumbUrl.isNotEmpty()) {
            holder.thumb.load(thumbUrl) { crossfade(true) }
        } else {
            holder.thumb.setImageDrawable(null)
        }
        holder.btnPlay.setOnClickListener { onPlayInApp?.invoke(item) }
    }

    private fun formatDuration(seconds: Long?): String? {
        if (seconds == null || seconds <= 0) return null
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%d:%02d", m, s)
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val checkBox: CheckBox = itemView.findViewById(R.id.checkBox)
        val thumb: ImageView = itemView.findViewById(R.id.thumbnail)
        val title: TextView = itemView.findViewById(R.id.title)
        val subtitle: TextView = itemView.findViewById(R.id.subtitle)
        val duration: TextView = itemView.findViewById(R.id.duration)
        val btnPlay: ImageButton = itemView.findViewById(R.id.btnPlay)
    }
}
