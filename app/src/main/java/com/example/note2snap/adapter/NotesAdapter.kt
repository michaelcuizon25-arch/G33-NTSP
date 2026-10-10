package com.example.note2snap.adapter

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
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.model.Note
import com.example.note2snap.utils.NoteThumbnailLoader
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray

class NotesAdapter(
    private var notes: List<Note>,
    private val onItemClick: (Note) -> Unit,
    private val onMoveClick: (Note) -> Unit,
    private val onDeleteClick: (Note) -> Unit,
    private val onToggleStarClick: (Note) -> Unit,
    private val selectionEnabled: Boolean = false,
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<NotesAdapter.NoteViewHolder>() {

    private val selectedNoteIds =
        mutableSetOf<Int>()

    enum class DisplayMode {
        LIST,
        GRID,
        COMPACT
    }

    private var displayMode: DisplayMode =
        DisplayMode.LIST

    private val VIEW_TYPE_LIST = 0
    private val VIEW_TYPE_GRID = 1

    class NoteViewHolder(view: View) :
        RecyclerView.ViewHolder(view) {

        val tvTitle: TextView =
            view.findViewById(R.id.tvNoteTitle)

        val tvDate: TextView =
            view.findViewById(R.id.tvNoteDate)

        val ivThumbnail: ImageView =
            view.findViewById(R.id.ivNoteThumbnail)

        val ivStar: ImageButton =
            view.findViewById(R.id.btnFavorite)

        val btnMore: ImageButton =
            view.findViewById(R.id.btnNoteMore)

        val cardNote:
            MaterialCardView? =
                view.findViewById(
                    R.id.cardNote
                )

        val thumbCard:
            MaterialCardView? =
                view.findViewById(
                    R.id.noteThumbCard
                )

        val pageCountBadge:
            TextView? =
                view.findViewById(
                    R.id.tvPageCountBadge
                )

        val rowRoot: View? =
            (view as? ViewGroup)?.getChildAt(0)
    }

    override fun getItemViewType(
        position: Int
    ): Int {
        return if (displayMode == DisplayMode.GRID) {
            VIEW_TYPE_GRID
        } else {
            VIEW_TYPE_LIST
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): NoteViewHolder {

        val layoutRes =
            if (viewType == VIEW_TYPE_GRID) {
                R.layout.item_note_grid
            } else {
                R.layout.item_note
            }

        val view =
            LayoutInflater.from(parent.context)
                .inflate(
                    layoutRes,
                    parent,
                    false
                )

        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: NoteViewHolder,
        position: Int
    ) {
        val note =
            notes[position]

        holder.tvTitle.text =
            note.title

        holder.tvDate.text =
            note.dateEdited

        holder.tvTitle.typeface =
            ResourcesCompat.getFont(
                holder.itemView.context,
                R.font.poppins_medium
            )

        holder.tvDate.typeface =
            ResourcesCompat.getFont(
                holder.itemView.context,
                R.font.poppins_regular
            )

        bindThumbnail(
            holder.ivThumbnail,
            note
        )

        bindPageCount(
            holder.pageCountBadge,
            note
        )

        holder.ivStar.imageTintList = null
        holder.ivStar.clearColorFilter()

        holder.ivStar.setImageResource(
            if (note.isStarred) {
                R.drawable.ic_star_filled_custom
            } else {
                R.drawable.ic_star_outline_custom
            }
        )

        applyDisplayMode(holder)

        bindSelectionState(
            holder,
            note
        )

        holder.ivStar.setOnClickListener {
            if (
                selectedNoteIds.isNotEmpty() &&
                selectionEnabled
            ) {
                toggleSelection(
                    note
                )
            } else {
                onToggleStarClick(
                    note
                )
            }
        }

        holder.itemView.setOnClickListener {
            if (
                selectedNoteIds.isNotEmpty() &&
                selectionEnabled
            ) {
                toggleSelection(
                    note
                )
            } else {
                onItemClick(
                    note
                )
            }
        }

        holder.itemView.setOnLongClickListener {
            if (
                selectionEnabled
            ) {
                toggleSelection(
                    note
                )

                true
            } else {
                false
            }
        }

        holder.btnMore.setOnClickListener {
            if (
                selectedNoteIds.isNotEmpty() &&
                selectionEnabled
            ) {
                toggleSelection(
                    note
                )
            } else {
                showNoteOptions(
                    holder.itemView,
                    note
                )
            }
        }
    }

    override fun getItemCount(): Int =
        notes.size

    fun updateNotes(
        newNotes: List<Note>
    ) {
        val oldNotes =
            notes

        val diffResult =
            DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int =
                        oldNotes.size

                    override fun getNewListSize(): Int =
                        newNotes.size

                    override fun areItemsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int
                    ): Boolean {
                        return oldNotes[
                            oldItemPosition
                        ].id ==
                                newNotes[
                                    newItemPosition
                                ].id
                    }

                    override fun areContentsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int
                    ): Boolean {
                        return oldNotes[
                            oldItemPosition
                        ] ==
                                newNotes[
                                    newItemPosition
                                ]
                    }
                }
            )

        notes =
            newNotes.toList()

        selectedNoteIds.retainAll(
            notes.map {
                it.id
            }.toSet()
        )

        diffResult.dispatchUpdatesTo(
            this
        )

        onSelectionChanged(
            selectedNoteIds.size
        )
    }

    fun selectedNotes(): List<Note> {
        return notes.filter {
            it.id in selectedNoteIds
        }
    }

    fun selectAll() {
        if (
            !selectionEnabled
        ) {
            return
        }

        selectedNoteIds.clear()

        selectedNoteIds.addAll(
            notes.map {
                it.id
            }
        )

        notifyDataSetChanged()

        onSelectionChanged(
            selectedNoteIds.size
        )
    }

    fun clearSelection() {
        if (
            selectedNoteIds.isEmpty()
        ) {
            return
        }

        selectedNoteIds.clear()

        notifyDataSetChanged()

        onSelectionChanged(
            0
        )
    }

    private fun toggleSelection(
        note: Note
    ) {
        if (
            !selectionEnabled
        ) {
            return
        }

        if (
            note.id in selectedNoteIds
        ) {
            selectedNoteIds.remove(
                note.id
            )
        } else {
            selectedNoteIds.add(
                note.id
            )
        }

        notifyItemChanged(
            notes.indexOfFirst {
                it.id == note.id
            }.coerceAtLeast(
                0
            )
        )

        onSelectionChanged(
            selectedNoteIds.size
        )
    }

    private fun bindSelectionState(
        holder: NoteViewHolder,
        note: Note
    ) {
        val selected =
            note.id in selectedNoteIds

        holder.cardNote?.apply {
            strokeWidth =
                (
                    if (
                        selected
                    ) {
                        2
                    } else {
                        1
                    } *
                    resources
                        .displayMetrics
                        .density
                ).toInt()

            strokeColor =
                ContextCompat.getColor(
                    context,
                    if (
                        selected
                    ) {
                        R.color.nts_blue
                    } else {
                        R.color.nts_card_outline
                    }
                )

            alpha =
                if (
                    selected
                ) {
                    1f
                } else {
                    0.98f
                }
        }
    }

    fun setDisplayMode(
        mode: DisplayMode
    ) {
        if (displayMode == mode) return

        displayMode = mode
        notifyDataSetChanged()
    }

    private fun applyDisplayMode(
        holder: NoteViewHolder
    ) {
        val density =
            holder.itemView.resources.displayMetrics.density

        fun dp(value: Int): Int =
            (value * density).toInt()

        if (displayMode != DisplayMode.GRID) {
            val rowHeight =
                when (displayMode) {
                    DisplayMode.LIST -> dp(68)
                    DisplayMode.GRID -> dp(112)
                    DisplayMode.COMPACT -> dp(54)
                }

            holder.rowRoot?.layoutParams =
                holder.rowRoot?.layoutParams?.apply {
                    height = rowHeight
                }
        }

        holder.ivThumbnail.scaleType =
            ImageView.ScaleType.CENTER_CROP

        holder.ivThumbnail.layoutParams =
            holder.ivThumbnail.layoutParams.apply {
                width =
                    ViewGroup.LayoutParams.MATCH_PARENT

                height =
                    ViewGroup.LayoutParams.MATCH_PARENT
            }

        if (
            displayMode !=
                DisplayMode.GRID
        ) {
            val thumbWidth =
                if (
                    displayMode ==
                        DisplayMode.COMPACT
                ) {
                    46
                } else {
                    60
                }

            val thumbHeight =
                if (
                    displayMode ==
                        DisplayMode.COMPACT
                ) {
                    38
                } else {
                    50
                }

            holder.thumbCard?.layoutParams =
                holder.thumbCard?.layoutParams?.apply {
                    width =
                        dp(
                            thumbWidth
                        )

                    height =
                        dp(
                            thumbHeight
                        )
                }
        }

        holder.tvTitle.textSize =
            when (displayMode) {
                DisplayMode.LIST -> 11.5f
                DisplayMode.GRID -> 11f
                DisplayMode.COMPACT -> 10.5f
            }

        holder.tvDate.textSize =
            when (displayMode) {
                DisplayMode.LIST -> 9f
                DisplayMode.GRID -> 8.5f
                DisplayMode.COMPACT -> 8f
            }

        holder.tvDate.visibility =
            if (displayMode == DisplayMode.COMPACT) {
                View.GONE
            } else {
                View.VISIBLE
            }

        holder.ivStar.layoutParams =
            holder.ivStar.layoutParams.apply {
                width = dp(
                    if (displayMode == DisplayMode.COMPACT) 28 else 32
                )
                height = dp(
                    if (displayMode == DisplayMode.COMPACT) 28 else 32
                )
            }

        holder.btnMore.layoutParams =
            holder.btnMore.layoutParams.apply {
                width = dp(
                    if (displayMode == DisplayMode.COMPACT) 28 else 30
                )
                height = dp(
                    if (displayMode == DisplayMode.COMPACT) 28 else 30
                )
            }
    }

    private fun bindPageCount(
        badge: TextView?,
        note: Note
    ) {
        if (
            badge == null
        ) {
            return
        }

        val pageCount =
            runCatching {
                val sourceCount =
                    note.sourceImagePathsJson
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            JSONArray(
                                it
                            ).length()
                        }
                        ?: 0

                val contentCount =
                    note.pageContentsJson
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            JSONArray(
                                it
                            ).length()
                        }
                        ?: 0

                maxOf(
                    sourceCount,
                    contentCount,
                    1
                )
            }.getOrDefault(
                1
            )

        if (
            pageCount > 1
        ) {
            badge.visibility =
                View.VISIBLE

            badge.text =
                "$pageCount pages"
        } else {
            badge.visibility =
                View.GONE
        }
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
                        ContextCompat.getColor(
                            context,
                            R.color.nts_background
                        ),
                        28f,
                        anchor
                    )
            }

        sheet.addView(
            View(context).apply {
                background =
                    roundedBackground(
                        ContextCompat.getColor(
                            context,
                            R.color.nts_blue_line
                        ),
                        99f,
                        anchor
                    )
            },
            LinearLayout.LayoutParams(
                dp(anchor, 42),
                dp(anchor, 4)
            ).apply {
                gravity =
                    Gravity.CENTER_HORIZONTAL
                bottomMargin =
                    dp(anchor, 16)
            }
        )

        sheet.addView(
            TextView(context).apply {
                text = "Note options"
                textSize = 20f
                typeface =
                    ResourcesCompat.getFont(
                        context,
                        R.font.apple_garamond_bold
                    )
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        R.color.nts_text
                    )
                )
            }
        )

        sheet.addView(
            TextView(context).apply {
                text = note.title
                textSize = 10.5f
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        R.color.nts_text_secondary
                    )
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

                background =
                    roundedBackground(
                        ContextCompat.getColor(
                            context,
                            R.color.nts_surface
                        ),
                        20f,
                        anchor,
                        ContextCompat.getColor(
                            context,
                            R.color.nts_blue_line
                        )
                    )

                setPadding(
                    dp(anchor, 5),
                    dp(anchor, 5),
                    dp(anchor, 5),
                    dp(anchor, 5)
                )
            }

        card.addView(
            createOptionRow(
                anchor,
                R.drawable.ic_option_move,
                "Move to folder",
                "Organize this note inside a folder."
            ) {
                dialog.dismiss()
                onMoveClick(note)
            }
        )

        card.addView(
            createOptionRow(
                anchor,
                if (note.isStarred)
                    R.drawable.ic_star_filled_custom
                else
                    R.drawable.ic_star_outline_custom,
                if (note.isStarred)
                    "Unfavorite note"
                else
                    "Favorite note",
                if (note.isStarred)
                    "Remove this note from favorites."
                else
                    "Keep this note easy to find."
            ) {
                dialog.dismiss()
                onToggleStarClick(note)
            }
        )

        card.addView(
            createOptionRow(
                anchor,
                R.drawable.ic_option_delete,
                "Delete note",
                "Permanently remove this note.",
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

        val context =
            anchor.context

        return LinearLayout(context).apply {
            orientation =
                LinearLayout.HORIZONTAL

            gravity =
                Gravity.CENTER_VERTICAL

            setPadding(
                dp(anchor, 9),
                dp(anchor, 10),
                dp(anchor, 9),
                dp(anchor, 10)
            )

            isClickable = true
            isFocusable = true

            val icon =
                ImageView(context).apply {
                    setImageResource(iconRes)
                    imageTintList = null
                }

            addView(
                icon,
                LinearLayout.LayoutParams(
                    dp(anchor, 30),
                    dp(anchor, 30)
                )
            )

            val labels =
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

            labels.addView(
                TextView(context).apply {
                    text = title
                    textSize = 12.5f
                    typeface =
                        ResourcesCompat.getFont(
                            context,
                            R.font.poppins_medium
                        )

                    setTextColor(
                        if (destructive) {
                            "#D94B62".toColorInt()
                        } else {
                            ContextCompat.getColor(
                                context,
                                R.color.nts_text
                            )
                        }
                    )
                }
            )

            labels.addView(
                TextView(context).apply {
                    text = subtitle
                    textSize = 9.5f
                    typeface =
                        ResourcesCompat.getFont(
                            context,
                            R.font.poppins_regular
                        )
                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.nts_text_secondary
                        )
                    )
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

            setOnClickListener {
                action()
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

    private fun roundedBackground(
        fillColor: Int,
        radiusDp: Float,
        anchor: View,
        strokeColor: Int? = null
    ): GradientDrawable {

        return GradientDrawable().apply {
            shape =
                GradientDrawable.RECTANGLE

            cornerRadius =
                radiusDp *
                        anchor.resources
                            .displayMetrics
                            .density

            setColor(fillColor)

            if (strokeColor != null) {
                setStroke(
                    dp(anchor, 1),
                    strokeColor
                )
            }
        }
    }
}