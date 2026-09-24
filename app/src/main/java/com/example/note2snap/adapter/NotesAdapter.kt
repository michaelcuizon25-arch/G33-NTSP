package com.example.note2snap.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.model.Note
import com.google.android.material.bottomsheet.BottomSheetDialog

class NotesAdapter(
    private var notes: List<Note>,
    private val onItemClick: (Note) -> Unit,
    private val onMoveClick: (Note) -> Unit,
    private val onDeleteClick: (Note) -> Unit,
    private val onToggleStarClick: (Note) -> Unit
) : RecyclerView.Adapter<NotesAdapter.NoteViewHolder>() {

    class NoteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvNoteTitle)
        val tvDate: TextView = view.findViewById(R.id.tvNoteDate)
        val ivStar: ImageView? = view.findViewById(R.id.btnFavorite)
        val btnMore: ImageView? = view.findViewById(R.id.btnNoteMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_note, parent, false)

        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        val note = notes[position]

        holder.tvTitle.text = note.title
        holder.tvDate.text = note.dateEdited

        holder.ivStar?.visibility =
            if (note.isStarred) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            onItemClick(note)
        }

        holder.btnMore?.setOnClickListener {
            showNoteOptions(holder.itemView, note)
        }
    }

    override fun getItemCount(): Int = notes.size

    fun updateNotes(newNotes: List<Note>) {
        notes = newNotes
        notifyDataSetChanged()
    }

    private fun showNoteOptions(anchor: View, note: Note) {
        val context = anchor.context
        val dialog = BottomSheetDialog(context)

        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(anchor, 18), dp(anchor, 12), dp(anchor, 18), dp(anchor, 24))
            background = roundedBackground(
                "#FFF9FF",
                28f,
                anchor
            )
        }

        sheet.addView(
            View(context).apply {
                background = roundedBackground(
                    "#D7D4DC",
                    3f,
                    anchor
                )
            },
            LinearLayout.LayoutParams(
                dp(anchor, 42),
                dp(anchor, 4)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(anchor, 18)
            }
        )

        sheet.addView(
            TextView(context).apply {
                text = "Note options"
                textSize = 21f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor("#171717".toColorInt())
            }
        )

        sheet.addView(
            TextView(context).apply {
                text = note.title
                textSize = 11f
                setTextColor("#777780".toColorInt())
                setPadding(0, dp(anchor, 4), 0, dp(anchor, 14))
            }
        )

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(
                "#FFFFFF",
                20f,
                anchor,
                "#ECECF2"
            )
            setPadding(
                dp(anchor, 6),
                dp(anchor, 6),
                dp(anchor, 6),
                dp(anchor, 6)
            )
        }

        card.addView(
            createOptionRow(
                anchor = anchor,
                iconRes = R.drawable.ic_option_move,
                title = "Move to folder",
                subtitle = "Organize this note inside a folder."
            ) {
                dialog.dismiss()
                onMoveClick(note)
            }
        )

        card.addView(
            createOptionRow(
                anchor = anchor,
                iconRes = R.drawable.ic_option_star,
                title = if (note.isStarred) "Unstar note" else "Star note",
                subtitle = if (note.isStarred) {
                    "Remove this note from favorites."
                } else {
                    "Keep this note easy to find."
                }
            ) {
                dialog.dismiss()
                onToggleStarClick(note)
            }
        )

        card.addView(
            createOptionRow(
                anchor = anchor,
                iconRes = R.drawable.ic_option_delete,
                title = "Delete note",
                subtitle = "Permanently remove this note.",
                destructive = true
            ) {
                dialog.dismiss()
                onDeleteClick(note)
            }
        )

        sheet.addView(card)

        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun createOptionRow(
        anchor: View,
        iconRes: Int,
        title: String,
        subtitle: String,
        destructive: Boolean = false,
        action: () -> Unit
    ): View {
        val context = anchor.context

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(anchor, 10),
                dp(anchor, 11),
                dp(anchor, 10),
                dp(anchor, 11)
            )
            isClickable = true
            isFocusable = true
            background = roundedBackground(
                "#FFFFFF",
                16f,
                anchor
            )

            val iconBox = LinearLayout(context).apply {
                gravity = Gravity.CENTER
                background = roundedBackground(
                    if (destructive) "#FFF0F3" else "#EEF2FF",
                    14f,
                    anchor
                )
            }

            iconBox.addView(
                ImageView(context).apply {
                    setImageResource(iconRes)
                },
                LinearLayout.LayoutParams(
                    dp(anchor, 22),
                    dp(anchor, 22)
                )
            )

            addView(
                iconBox,
                LinearLayout.LayoutParams(
                    dp(anchor, 44),
                    dp(anchor, 44)
                )
            )

            val labels = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(anchor, 12), 0, 0, 0)
            }

            labels.addView(
                TextView(context).apply {
                    text = title
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(
                        if (destructive) {
                            "#D94B62".toColorInt()
                        } else {
                            "#171717".toColorInt()
                        }
                    )
                }
            )

            labels.addView(
                TextView(context).apply {
                    text = subtitle
                    textSize = 10f
                    setTextColor("#777780".toColorInt())
                    setPadding(0, dp(anchor, 2), 0, 0)
                }
            )

            addView(
                labels,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            setOnClickListener { action() }
        }
    }

    private fun dp(anchor: View, value: Int): Int {
        return (value * anchor.resources.displayMetrics.density).toInt()
    }

    private fun roundedBackground(
        fillColor: String,
        radiusDp: Float,
        anchor: View,
        strokeColor: String? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius =
                radiusDp * anchor.resources.displayMetrics.density
            setColor(Color.parseColor(fillColor))

            if (strokeColor != null) {
                setStroke(
                    dp(anchor, 1),
                    Color.parseColor(strokeColor)
                )
            }
        }
    }
}
