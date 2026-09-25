package com.laksh.jarvismusic

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.laksh.jarvismusic.api.ApiSong

/** One upcoming song and its index in the player's playlist. */
data class QueueEntry(val playerIndex: Int, val song: ApiSong)

class QueueAdapter(
    private val onSongClick: (QueueEntry) -> Unit,
    private val onRemoveClick: (QueueEntry) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    val entries = mutableListOf<QueueEntry>()
    var dragEnabled = true

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<QueueEntry>, canDrag: Boolean) {
        entries.clear()
        entries.addAll(list)
        dragEnabled = canDrag
        notifyDataSetChanged()
    }

    fun moveItem(from: Int, to: Int) {
        val item = entries.removeAt(from)
        entries.add(to, item)
        notifyItemMoved(from, to)
    }

    inner class QueueViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgArt: ImageView = view.findViewById(R.id.img_queue_art)
        val tvTitle: TextView = view.findViewById(R.id.tv_queue_title)
        val tvArtist: TextView = view.findViewById(R.id.tv_queue_artist)
        val btnRemove: ImageView = view.findViewById(R.id.btn_remove_queue)
        val dragHandle: ImageView = view.findViewById(R.id.img_drag_handle)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue_song, parent, false)
        val holder = QueueViewHolder(view)

        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onSongClick(entries[position])
        }
        holder.btnRemove.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onRemoveClick(entries[position])
        }
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && dragEnabled) onStartDrag(holder)
            false
        }
        return holder
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val song = entries[position].song
        holder.tvTitle.text = song.displayTitle
        holder.tvArtist.text = song.displayArtist
        holder.imgArt.loadArt(song.image)
        holder.dragHandle.visibility = if (dragEnabled) View.VISIBLE else View.GONE
    }

    override fun getItemCount() = entries.size
}
