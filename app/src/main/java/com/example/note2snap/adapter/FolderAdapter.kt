package com.example.note2snap.adapter

import android.content.res.ColorStateList
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
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.model.Folder
import com.google.android.material.bottomsheet.BottomSheetDialog

class FolderAdapter(
    private var folderList: List<Folder>,
    private val onItemClick: (Folder) -> Unit,
    private val onEditClick: (Folder) -> Unit,
    private val onDeleteClick: (Folder) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    class FolderViewHolder(
        view: View
    ) : RecyclerView.ViewHolder(view) {

        val ivFolderIcon: ImageView =
            view.findViewById(
                R.id.ivFolderIcon
            )

        val tvFolderName: TextView =
            view.findViewById(
                R.id.tvFolderName
            )

        val btnFolderMore: ImageView =
            view.findViewById(
                R.id.btnFolderMore
            )
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): FolderViewHolder {

        val view =
            LayoutInflater
                .from(parent.context)
                .inflate(
                    R.layout.item_folder,
                    parent,
                    false
                )

        return FolderViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: FolderViewHolder,
        position: Int
    ) {

        val folder =
            folderList[position]

        holder.tvFolderName.text =
            folder.name

        val folderColor =
            try {
                folder.colorHex.toColorInt()
            } catch (_: Exception) {
                "#AFC4F6".toColorInt()
            }

        holder.ivFolderIcon
            .setImageResource(
                R.drawable.ic_folder_cute
            )

        ImageViewCompat
            .setImageTintList(
                holder.ivFolderIcon,
                ColorStateList.valueOf(
                    folderColor
                )
            )

        holder.itemView
            .setOnClickListener {
                onItemClick(folder)
            }

        holder.btnFolderMore
            .setOnClickListener {
                showFolderOptions(
                    holder.itemView,
                    folder
                )
            }
    }

    override fun getItemCount(): Int =
        folderList.size

    fun updateFolders(
        newFolders: List<Folder>
    ) {

        val oldFolders =
            folderList

        val diff =
            DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {

                    override fun getOldListSize() =
                        oldFolders.size

                    override fun getNewListSize() =
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

        diff.dispatchUpdatesTo(this)
    }

    private fun showFolderOptions(
        anchor: View,
        folder: Folder
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
                        colorHex(context, R.color.nts_background),
                        28f,
                        anchor
                    )
            }

        sheet.addView(
            View(context).apply {

                background =
                    roundedBackground(
                        colorHex(context, R.color.nts_blue_line),
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
            TextView(context).apply {

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
            TextView(context).apply {

                text =
                    folder.name

                textSize =
                    11f

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
            LinearLayout(context).apply {

                orientation =
                    LinearLayout.VERTICAL

                background =
                    roundedBackground(
                        colorHex(context, R.color.nts_surface),
                        20f,
                        anchor,
                        colorHex(context, R.color.nts_blue_line)
                    )
            }

        card.addView(
            optionRow(
                anchor =
                    anchor,
                iconRes =
                    R.drawable.ic_option_edit,
                title =
                    "Rename folder",
                destructive =
                    false
            ) {
                dialog.dismiss()
                onEditClick(folder)
            }
        )

        card.addView(
            optionRow(
                anchor =
                    anchor,
                iconRes =
                    R.drawable.ic_option_delete,
                title =
                    "Delete folder",
                destructive =
                    true
            ) {
                dialog.dismiss()
                onDeleteClick(folder)
            }
        )

        sheet.addView(card)

        dialog.setContentView(
            sheet
        )

        dialog.show()
    }

    private fun optionRow(
        anchor: View,
        iconRes: Int,
        title: String,
        destructive: Boolean,
        action: () -> Unit
    ): View {

        val context =
            anchor.context

        val subtitle =
            if (destructive) {
                "Permanently remove this folder"
            } else {
                "Change the folder name"
            }

        return LinearLayout(context).apply {
            orientation =
                LinearLayout.HORIZONTAL

            gravity =
                Gravity.CENTER_VERTICAL

            setPadding(
                dp(anchor, 10),
                dp(anchor, 10),
                dp(anchor, 10),
                dp(anchor, 10)
            )

            isClickable = true
            isFocusable = true

            val iconBox =
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER
                    background =
                        roundedBackground(
                            colorHex(
                                context,
                                R.color.nts_surface_blue_soft
                            ),
                            14f,
                            anchor
                        )
                }

            val icon =
                ImageView(context).apply {
                    setImageResource(
                        iconRes
                    )
                    imageTintList = null
                }

            iconBox.addView(
                icon,
                LinearLayout.LayoutParams(
                    dp(anchor, 24),
                    dp(anchor, 24)
                )
            )

            addView(
                iconBox,
                LinearLayout.LayoutParams(
                    dp(anchor, 42),
                    dp(anchor, 42)
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
                fillColor.toColorInt()
            )

            if (strokeColor != null) {

                setStroke(
                    dp(anchor, 1),
                    strokeColor.toColorInt()
                )
            }
        }
    }
}
