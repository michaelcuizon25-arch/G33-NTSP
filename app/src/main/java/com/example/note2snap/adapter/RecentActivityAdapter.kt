package com.example.note2snap.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.model.Note
import com.example.note2snap.utils.NoteThumbnailLoader
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentActivityAdapter(
    private val notes: MutableList<Note>,
    private val onItemClick: (Note) -> Unit,

    // Defaults keep your current HomeFragment constructor compatible.
    private val onToggleStarClick: ((Note) -> Unit)? = null,
    private val onEditClick: ((Note) -> Unit)? = null,
    private val onMoveClick: ((Note) -> Unit)? = null,
    private val onDeleteClick: ((Note) -> Unit)? = null
) : RecyclerView.Adapter<RecentActivityAdapter.RecentViewHolder>() {

    class RecentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView =
            itemView.findViewById(R.id.tvRecentNoteTitle)

        val tvDate: TextView =
            itemView.findViewById(R.id.tvRecentNoteDate)

        val ivThumbnail: ImageView =
            itemView.findViewById(R.id.ivNoteIcon)

        val btnFavorite: ImageButton =
            itemView.findViewById(R.id.btnRecentFavorite)

        val btnMore: ImageButton =
            itemView.findViewById(R.id.btnRecentMore)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecentViewHolder {

        val view =
            LayoutInflater.from(parent.context)
                .inflate(
                    R.layout.item_recent_activity_card,
                    parent,
                    false
                )

        return RecentViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: RecentViewHolder,
        position: Int
    ) {
        val note = notes[position]

        holder.tvTitle.text = note.title

        holder.tvDate.text =
            if (!note.dateEdited.isNullOrEmpty()) {
                note.dateEdited
            } else {
                SimpleDateFormat(
                    "MMM d, yyyy",
                    Locale.getDefault()
                ).format(
                    Date(note.timestamp)
                )
            }

        bindThumbnail(
            holder.ivThumbnail,
            note
        )

        // Star is ALWAYS visible so the user can favorite or unfavorite.
        holder.btnFavorite.visibility =
            View.VISIBLE

        holder.btnFavorite.imageTintList = null
        holder.btnFavorite.clearColorFilter()

        holder.btnFavorite.setImageResource(
            if (note.isStarred) {
                R.drawable.ic_star_filled_custom
            } else {
                R.drawable.ic_star_outline_custom
            }
        )

        holder.btnFavorite.alpha = 1f
        holder.btnFavorite.contentDescription =
            if (note.isStarred) {
                "Unfavorite note"
            } else {
                "Favorite note"
            }

        holder.itemView.setOnClickListener {
            onItemClick(note)
        }

        holder.btnFavorite.setOnClickListener {
            onToggleStarClick?.invoke(note)
        }

        holder.btnMore.setOnClickListener {
            showNoteOptions(
                holder.itemView,
                note
            )
        }
    }

    override fun getItemCount(): Int =
        notes.size

    fun updateNotes(
        newNotes: List<Note>
    ) {
        val oldNotes =
            notes.toList()

        val diffCallback =
            object : DiffUtil.Callback() {

                override fun getOldListSize(): Int =
                    oldNotes.size

                override fun getNewListSize(): Int =
                    newNotes.size

                override fun areItemsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int
                ): Boolean {
                    return oldNotes[oldItemPosition].id ==
                            newNotes[newItemPosition].id
                }

                override fun areContentsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int
                ): Boolean {
                    return oldNotes[oldItemPosition] ==
                            newNotes[newItemPosition]
                }
            }

        val diffResult =
            DiffUtil.calculateDiff(
                diffCallback
            )

        notes.clear()
        notes.addAll(newNotes)

        diffResult.dispatchUpdatesTo(this)
    }

    private fun bindThumbnail(
        imageView: ImageView,
        note: Note
    ) {
        NoteThumbnailLoader.load(
            imageView = imageView,
            imagePath = note.imagePath,
            fallbackRes =
                R.drawable.ic_note_custom
        )
    }

    private fun showNoteOptions(
        anchor: View,
        note: Note
    ) {
        val context =
            anchor.context

        val dialog =
            BottomSheetDialog(context)

        val sheet =
            LinearLayout(context).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(anchor, 18),
                    dp(anchor, 12),
                    dp(anchor, 18),
                    dp(anchor, 24)
                )

                background =
                    roundedBackground(
                        "#FFF9FF",
                        28f,
                        anchor
                    )
            }

        val handle =
            View(context).apply {
                background =
                    roundedBackground(
                        "#D8D8DF",
                        99f,
                        anchor
                    )
            }

        sheet.addView(
            handle,
            LinearLayout.LayoutParams(
                dp(anchor, 42),
                dp(anchor, 4)
            ).apply {
                gravity =
                    Gravity.CENTER_HORIZONTAL

                bottomMargin =
                    dp(anchor, 18)
            }
        )

        sheet.addView(
            TextView(context).apply {
                text = "Note options"
                textSize = 20f
                typeface =
                    android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(
                    "#202127".toColorInt()
                )
            }
        )

        sheet.addView(
            TextView(context).apply {
                text = note.title
                textSize = 11f
                maxLines = 1
                setTextColor(
                    "#888892".toColorInt()
                )

                setPadding(
                    0,
                    dp(anchor, 3),
                    0,
                    dp(anchor, 14)
                )
            }
        )

        val card =
            LinearLayout(context).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(anchor, 6),
                    dp(anchor, 6),
                    dp(anchor, 6),
                    dp(anchor, 6)
                )

                background =
                    roundedBackground(
                        "#FFFFFF",
                        20f,
                        anchor,
                        "#ECECF2"
                    )
            }

        card.addView(
            createOptionRow(
                anchor = anchor,
                iconRes =
                    R.drawable.ic_option_edit,
                title = "Edit note",
                subtitle =
                    "Open and edit this note",
                accent =
                    "#5A7FDB"
            ) {
                dialog.dismiss()

                if (onEditClick != null) {
                    onEditClick.invoke(note)
                } else {
                    onItemClick(note)
                }
            }
        )

        card.addView(
            divider(anchor)
        )

        card.addView(
            createOptionRow(
                anchor = anchor,
                iconRes =
                    R.drawable.ic_star_outline_custom,
                title =
                    if (note.isStarred)
                        "Unfavorite note"
                    else
                        "Favorite note",
                subtitle =
                    if (note.isStarred)
                        "Remove from your favorites"
                    else
                        "Keep this note easy to find",
                accent =
                    "#D3A33D"
            ) {
                dialog.dismiss()
                onToggleStarClick?.invoke(note)
            }
        )

        card.addView(
            divider(anchor)
        )

        card.addView(
            createOptionRow(
                anchor = anchor,
                iconRes =
                    R.drawable.ic_option_move,
                title = "Move to folder",
                subtitle =
                    "Organize this note",
                accent =
                    "#7B66C6"
            ) {
                dialog.dismiss()
                onMoveClick?.invoke(note)
            }
        )

        card.addView(
            divider(anchor)
        )

        card.addView(
            createOptionRow(
                anchor = anchor,
                iconRes =
                    R.drawable.ic_option_delete,
                title = "Delete note",
                subtitle =
                    "Permanently remove this note",
                accent =
                    "#D94A4A"
            ) {
                dialog.dismiss()
                onDeleteClick?.invoke(note)
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
        accent: String,
        onClick: () -> Unit
    ): View {
        val context =
            anchor.context

        val row =
            LinearLayout(context).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(anchor, 8),
                    dp(anchor, 9),
                    dp(anchor, 8),
                    dp(anchor, 9)
                )

                isClickable = true
                isFocusable = true

                setOnClickListener {
                    onClick()
                }
            }

        val iconBackground =
            LinearLayout(context).apply {
                gravity =
                    Gravity.CENTER

                background =
                    roundedBackground(
                        if (accent == "#D94A4A")
                            "#FFF0F0"
                        else if (accent == "#D3A33D")
                            "#FFF7DF"
                        else
                            "#EEF3FF",
                        15f,
                        anchor
                    )
            }

        val icon =
            ImageView(context).apply {
                setImageResource(iconRes)

                setColorFilter(
                    accent.toColorInt()
                )

                setPadding(
                    dp(anchor, 10),
                    dp(anchor, 10),
                    dp(anchor, 10),
                    dp(anchor, 10)
                )
            }

        iconBackground.addView(
            icon,
            LinearLayout.LayoutParams(
                dp(anchor, 46),
                dp(anchor, 46)
            )
        )

        val textContainer =
            LinearLayout(context).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(anchor, 12),
                    0,
                    0,
                    0
                )
            }

        textContainer.addView(
            TextView(context).apply {
                text = title
                textSize = 13.5f

                typeface =
                    android.graphics.Typeface.DEFAULT_BOLD

                setTextColor(
                    if (accent == "#D94A4A")
                        "#C84343".toColorInt()
                    else
                        "#202127".toColorInt()
                )
            }
        )

        textContainer.addView(
            TextView(context).apply {
                text = subtitle
                textSize = 10f
                setTextColor(
                    "#888892".toColorInt()
                )
                setPadding(
                    0,
                    dp(anchor, 2),
                    0,
                    0
                )
            }
        )

        row.addView(iconBackground)

        row.addView(
            textContainer,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        return row
    }

    private fun divider(
        anchor: View
    ): View {
        return View(anchor.context).apply {
            setBackgroundColor(
                "#ECECF2".toColorInt()
            )

            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(anchor, 1)
                ).apply {
                    marginStart =
                        dp(anchor, 66)
                }
        }
    }

    private fun roundedBackground(
        color: String,
        radiusDp: Float,
        anchor: View,
        strokeColor: String? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape =
                GradientDrawable.RECTANGLE

            setColor(
                color.toColorInt()
            )

            cornerRadius =
                radiusDp *
                        anchor.resources
                            .displayMetrics
                            .density

            if (strokeColor != null) {
                setStroke(
                    dp(anchor, 1),
                    strokeColor.toColorInt()
                )
            }
        }
    }

    private fun dp(
        anchor: View,
        value: Int
    ): Int {
        return (
                value *
                        anchor.resources
                            .displayMetrics
                            .density
                ).toInt()
    }
}
