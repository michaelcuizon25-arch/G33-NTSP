package com.example.note2snap.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.model.Note

class NotesAdapter(
    private var notes: List<Note>,
    private val onItemClick: (Note) -> Unit,
    private val onMoveClick: (Note) -> Unit,
    private val onDeleteClick: ((Note) -> Unit)? = null,
    private val onToggleStarClick: ((Note) -> Unit)? = null
) : RecyclerView.Adapter<NotesAdapter.NoteViewHolder>() {

    class NoteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvNoteTitle)
        val tvDate: TextView = view.findViewById(R.id.tvNoteDate)
        val ivStar: ImageView = view.findViewById(R.id.btnFavorite)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        val note = notes[position]
        holder.tvTitle.text = note.title
        holder.tvDate.text = note.dateEdited
        holder.ivStar.visibility = if (note.isStarred) View.VISIBLE else View.GONE

        // Tap note to open details
        holder.itemView.setOnClickListener {
            onItemClick(note)
        }

        // Long press note to show options menu
        holder.itemView.setOnLongClickListener { view ->
            showNoteOptionsPopup(view, note)
            true
        }
    }

    override fun getItemCount(): Int = notes.size

    fun updateNotes(newNotes: List<Note>) {
        this.notes = newNotes
        notifyDataSetChanged()
    }

    private fun showNoteOptionsPopup(view: View, note: Note) {
        val popup = PopupMenu(view.context, view)
        popup.menu.add("📂 Move to Folder")
        if (onToggleStarClick != null) {
            popup.menu.add(if (note.isStarred) "⭐ Unstar Note" else "⭐ Star Note")
        }
        if (onDeleteClick != null) {
            popup.menu.add("🗑️ Delete Note")
        }

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.title) {
                "📂 Move to Folder" -> onMoveClick(note)
                "⭐ Star Note", "⭐ Unstar Note" -> onToggleStarClick?.invoke(note)
                "🗑️ Delete Note" -> onDeleteClick?.invoke(note)
            }
            true
        }
        popup.show()
    }
}