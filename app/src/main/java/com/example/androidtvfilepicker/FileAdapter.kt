package com.example.androidtvfilepicker

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/** Shows storage volumes, folders, and files in a D-pad friendly list. */
class FileAdapter(
    private val onClick: (Entry) -> Unit
) : RecyclerView.Adapter<FileAdapter.Holder>() {

    enum class Kind { ROOT, NETWORK, FOLDER, FILE }

    data class Entry(val file: File, val name: String, val kind: Kind, val size: Long = 0L)

    private var items: List<Entry> = emptyList()

    /** Absolute paths of the selected files (multiple selection). */
    val selected = LinkedHashSet<String>()

    fun submit(newItems: List<Entry>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun indexOfPath(path: String): Int = items.indexOfFirst { it.file.absolutePath == path }

    fun toggle(entry: Entry) {
        val path = entry.file.absolutePath
        if (!selected.add(path)) selected.remove(path)
        val index = items.indexOf(entry)
        if (index >= 0) notifyItemChanged(index)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
        val detail: TextView = view.findViewById(R.id.detail)
        val check: ImageView = view.findViewById(R.id.check)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        val holder = Holder(view)
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onClick(items[position])
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        val context = holder.itemView.context
        holder.name.text = entry.name
        holder.icon.setImageResource(
            when (entry.kind) {
                Kind.ROOT -> R.drawable.ic_storage
                Kind.NETWORK -> R.drawable.ic_network
                Kind.FOLDER -> R.drawable.ic_folder
                Kind.FILE -> R.drawable.ic_file
            }
        )
        holder.detail.text = when (entry.kind) {
            Kind.ROOT -> context.getString(R.string.kind_storage)
            Kind.NETWORK -> context.getString(R.string.kind_network)
            Kind.FOLDER -> context.getString(R.string.kind_folder)
            Kind.FILE -> Formatter.formatShortFileSize(context, entry.size)
        }
        holder.check.visibility =
            if (selected.contains(entry.file.absolutePath)) View.VISIBLE else View.INVISIBLE
    }

    override fun getItemCount(): Int = items.size
}
