package com.example.note2snap.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.model.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val note = notes[position]

        holder.tvTitle.text = note.title

        // Uses dateEdited if available; otherwise falls back to formatting timestamp
        if (!note.dateEdited.isNullOrEmpty()) {
            holder.tvDate.text = note.dateEdited
        } else {
            val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            holder.tvDate.text = dateFormat.format(Date(note.timestamp))
        }

        holder.itemView.setOnClickListener {
            onItemClick(note)
        }
    }

    override fun getItemCount(): Int = notes.size

    /**
     * Updates notes efficiently using DiffUtil.
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