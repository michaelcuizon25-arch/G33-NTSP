package com.example.note2snap.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.model.Note

class RecentActivityAdapter(
    private val notes: MutableList<Note>,
    private val onItemClick: (Note) -> Unit
) : RecyclerView.Adapter<RecentActivityAdapter.RecentViewHolder>() {

    class RecentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvRecentNoteTitle)
        val tvDate: TextView = itemView.findViewById(R.id.tvRecentNoteDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_activity_card, parent, false)
        return RecentViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecentViewHolder, position: Int) {
        if (notes.isEmpty()) return

        val realPosition = position % notes.size
        val note = notes[realPosition]

        holder.tvTitle.text = note.title
        holder.tvDate.text = note.dateEdited ?: ""

        holder.itemView.setOnClickListener {
            onItemClick(note)
        }
    }

    override fun getItemCount(): Int {
        return if (notes.isEmpty()) 0 else Int.MAX_VALUE
    }

    /**
     * Updates notes efficiently using DiffUtil instead of notifyDataSetChanged().
     */
    fun updateNotes(newNotes: List<Note>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = notes.size
            override fun getNewListSize(): Int = newNotes.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return notes[oldItemPosition].id == newNotes[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return notes[oldItemPosition] == newNotes[newItemPosition]
            }
        }

        val diffResult = DiffUtil.calculateDiff(diffCallback)
        notes.clear()
        notes.addAll(newNotes)
        diffResult.dispatchUpdatesTo(this)
    }
}