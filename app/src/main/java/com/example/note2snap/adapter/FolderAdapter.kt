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
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.model.Folder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView

class FolderAdapter(
    private var folderList: List<Folder>,
    private val onItemClick: (Folder) -> Unit,
    private val onEditClick: (Folder) -> Unit,
    private val onArchiveClick: (Folder) -> Unit,
    private val onDeleteClick: (Folder) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    private var folderNoteCounts:
        Map<Int, Int> =
            emptyMap()

    private var archivedFolderIds:
        Set<Int> =
            emptySet()

    class FolderViewHolder(
        view: View
    ) : RecyclerView.ViewHolder(view) {

        val cardFolder:
            MaterialCardView =
                view.findViewById(
                    R.id.cardFolder
                )

        val folderTab:
            MaterialCardView =
                view.findViewById(
                    R.id.folderTab
                )

        val tvFolderName:
            TextView =
                view.findViewById(
                    R.id.tvFolderName
                )

        val tvFolderCount:
            TextView =
                view.findViewById(
                    R.id.tvFolderCount
                )

        val tvArchivedBadge:
            TextView =
                view.findViewById(
                    R.id.tvArchivedBadge
                )

        val btnFolderMore:
            ImageView =
                view.findViewById(
                    R.id.btnFolderMore
                )
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): FolderViewHolder {

        return FolderViewHolder(
            LayoutInflater
                .from(
                    parent.context
                )
                .inflate(
                    R.layout.item_folder,
                    parent,
                    false
                )
        )
    }

    override fun onBindViewHolder(
        holder: FolderViewHolder,
        position: Int
    ) {

        val folder =
            folderList[position]

        val context =
            holder.itemView.context

        val folderColor =
            runCatching {
                folder.colorHex.toColorInt()
            }.getOrDefault(
                "#AFC4F6".toColorInt()
            )

        val surface =
            ContextCompat.getColor(
                context,
                R.color.nts_surface
            )

        holder.cardFolder.setCardBackgroundColor(
            ColorUtils.blendARGB(
                surface,
                folderColor,
                0.34f
            )
        )

        holder.folderTab.setCardBackgroundColor(
            ColorUtils.blendARGB(
                surface,
                folderColor,
                0.50f
            )
        )

        val stroke =
            ColorUtils.blendARGB(
                ContextCompat.getColor(
                    context,
                    R.color.nts_outline
                ),
                folderColor,
                0.10f
            )

        holder.cardFolder.strokeColor =
            stroke

        holder.folderTab.strokeColor =
            stroke

        holder.tvFolderName.text =
            folder.name

        val count =
            folderNoteCounts[
                folder.id
            ] ?: 0

        holder.tvFolderCount.text =
            if (
                count == 1
            ) {
                "1 note"
            } else {
                "$count notes"
            }

        val isArchived =
            folder.id in
                archivedFolderIds

        holder.tvArchivedBadge.visibility =
            if (
                isArchived
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        holder.cardFolder.alpha =
            if (
                isArchived
            ) {
                0.82f
            } else {
                1f
            }

        val openFolder =
            View.OnClickListener {
                onItemClick(
                    folder
                )
            }

        holder.cardFolder
            .setOnClickListener(
                openFolder
            )

        holder.folderTab
            .setOnClickListener(
                openFolder
            )

        holder.btnFolderMore
            .setOnClickListener {
                showFolderOptions(
                    holder.itemView,
                    folder,
                    isArchived
                )
            }
    }

    override fun getItemCount(): Int =
        folderList.size

    fun updateFolderNoteCounts(
        counts: Map<Int, Int>
    ) {
        folderNoteCounts =
            counts.toMap()

        notifyDataSetChanged()
    }

    fun updateArchivedFolderIds(
        ids: Set<Int>
    ) {
        archivedFolderIds =
            ids.toSet()

        notifyDataSetChanged()
    }

    fun updateFolders(
        newFolders: List<Folder>
    ) {

        val oldFolders =
            folderList

        val diff =
            DiffUtil.calculateDiff(
                object :
                    DiffUtil.Callback() {

                    override fun getOldListSize(): Int =
                        oldFolders.size

                    override fun getNewListSize(): Int =
                        newFolders.size

                    override fun areItemsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int
                    ): Boolean {

                        return oldFolders[
                            oldItemPosition
                        ].id ==
                            newFolders[
                                newItemPosition
                            ].id
                    }

                    override fun areContentsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int
                    ): Boolean {

                        return oldFolders[
                            oldItemPosition
                        ] ==
                            newFolders[
                                newItemPosition
                            ]
                    }
                }
            )

        folderList =
            newFolders.toList()

        diff.dispatchUpdatesTo(
            this
        )
    }

    private fun showFolderOptions(
        anchor: View,
        folder: Folder,
        isArchived: Boolean
    ) {

        val context =
            anchor.context

        val dialog =
            BottomSheetDialog(
                context
            )

        val sheet =
            LinearLayout(
                context
            ).apply {

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
                        colorHex(
                            context,
                            R.color.nts_background
                        ),
                        28f,
                        anchor
                    )
            }

        sheet.addView(
            View(
                context
            ).apply {

                background =
                    roundedBackground(
                        colorHex(
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
                    dp(anchor, 18)
            }
        )

        sheet.addView(
            TextView(
                context
            ).apply {

                text =
                    "Folder options"

                textSize =
                    20f

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
            TextView(
                context
            ).apply {

                text =
                    folder.name

                textSize =
                    10.5f

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

                setPadding(
                    0,
                    dp(anchor, 3),
                    0,
                    dp(anchor, 14)
                )
            }
        )

        val card =
            LinearLayout(
                context
            ).apply {

                orientation =
                    LinearLayout.VERTICAL

                background =
                    roundedBackground(
                        colorHex(
                            context,
                            R.color.nts_surface
                        ),
                        20f,
                        anchor,
                        colorHex(
                            context,
                            R.color.nts_blue_line
                        )
                    )
            }

        card.addView(
            optionRow(
                anchor,
                R.drawable.ic_option_edit,
                "Rename folder",
                "Change the folder name",
                false
            ) {
                dialog.dismiss()
                onEditClick(
                    folder
                )
            }
        )

        card.addView(
            optionRow(
                anchor,
                if (
                    isArchived
                ) {
                    R.drawable.ic_restore
                } else {
                    R.drawable.ic_archive
                },
                if (
                    isArchived
                ) {
                    "Restore folder"
                } else {
                    "Archive folder"
                },
                if (
                    isArchived
                ) {
                    "Return it to your active folders"
                } else {
                    "Hide it without deleting notes"
                },
                false
            ) {
                dialog.dismiss()
                onArchiveClick(
                    folder
                )
            }
        )

        card.addView(
            optionRow(
                anchor,
                R.drawable.ic_option_delete,
                "Delete folder",
                "Remove the folder permanently",
                true
            ) {
                dialog.dismiss()
                onDeleteClick(
                    folder
                )
            }
        )

        sheet.addView(
            card
        )

        dialog.setContentView(
            sheet
        )

        dialog.show()
    }

    private fun optionRow(
        anchor: View,
        iconRes: Int,
        title: String,
        subtitle: String,
        destructive: Boolean,
        action: () -> Unit
    ): View {

        val context =
            anchor.context

        return LinearLayout(
            context
        ).apply {

            orientation =
                LinearLayout.HORIZONTAL

            gravity =
                Gravity.CENTER_VERTICAL

            setPadding(
                dp(anchor, 12),
                dp(anchor, 11),
                dp(anchor, 12),
                dp(anchor, 11)
            )

            addView(
                ImageView(
                    context
                ).apply {

                    setImageResource(
                        iconRes
                    )

                    setColorFilter(
                        ContextCompat.getColor(
                            context,
                            if (
                                destructive
                            ) {
                                android.R.color.holo_red_dark
                            } else {
                                R.color.nts_blue
                            }
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    dp(anchor, 22),
                    dp(anchor, 22)
                )
            )

            val labels =
                LinearLayout(
                    context
                ).apply {

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
                TextView(
                    context
                ).apply {

                    text =
                        title

                    textSize =
                        12f

                    typeface =
                        ResourcesCompat.getFont(
                            context,
                            R.font.poppins_medium
                        )

                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (
                                destructive
                            ) {
                                android.R.color.holo_red_dark
                            } else {
                                R.color.nts_text
                            }
                        )
                    )
                }
            )

            labels.addView(
                TextView(
                    context
                ).apply {

                    text =
                        subtitle

                    textSize =
                        9f

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

            isClickable =
                true

            isFocusable =
                true

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

    private fun colorHex(
        context: android.content.Context,
        colorRes: Int
    ): String {

        val color =
            ContextCompat.getColor(
                context,
                colorRes
            )

        return String.format(
            "#%06X",
            0xFFFFFF and color
        )
    }

    private fun roundedBackground(
        fillColor: String,
        radiusDp: Float,
        anchor: View,
        strokeColor: String? = null
    ): GradientDrawable {

        return GradientDrawable().apply {

            shape =
                GradientDrawable.RECTANGLE

            cornerRadius =
                radiusDp *
                    anchor.resources
                        .displayMetrics
                        .density

            setColor(
                Color.parseColor(
                    fillColor
                )
            )

            if (
                strokeColor != null
            ) {
                setStroke(
                    dp(
                        anchor,
                        1
                    ),
                    Color.parseColor(
                        strokeColor
                    )
                )
            }
        }
    }
}
