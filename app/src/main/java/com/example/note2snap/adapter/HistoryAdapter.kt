package com.example.note2snap.adapter

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.model.ScanHistory
import java.io.File
import org.json.JSONArray

class HistoryAdapter(
    private var historyList: List<ScanHistory>,
    private val onItemClick: (ScanHistory) -> Unit,
    private val onItemLongClick: (ScanHistory) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private val selectedIds =
        linkedSetOf<Int>()

    class HistoryViewHolder(
        view: View
    ) : RecyclerView.ViewHolder(view) {

        val thumbnail: ImageView =
            view.findViewById(
                R.id.ivHistoryThumbnail
            )

        val title: TextView =
            view.findViewById(
                R.id.tvHistoryTitle
            )

        val date: TextView =
            view.findViewById(
                R.id.tvHistoryDate
            )

        val pageBadge: TextView =
            view.findViewById(
                R.id.tvHistoryPageBadge
            )

        val more: ImageButton =
            view.findViewById(
                R.id.btnHistoryMore
            )
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): HistoryViewHolder {

        val view =
            LayoutInflater
                .from(parent.context)
                .inflate(
                    R.layout.item_history,
                    parent,
                    false
                )

        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: HistoryViewHolder,
        position: Int
    ) {
        val item =
            historyList[position]

        holder.title.text =
            item.title

        holder.date.text =
            item.date

        val pageCount =
            historyPageCount(
                item
            )

        holder.pageBadge.visibility =
            if (
                pageCount >
                1
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        holder.pageBadge.text =
            "$pageCount pages"

        bindThumbnail(
            holder.thumbnail,
            item.imagePath
        )

        val selected =
            selectedIds.contains(
                item.id
            )

        val card =
            holder.itemView as?
                com.google.android.material.card.MaterialCardView

        card?.strokeWidth =
            if (
                selected
            ) {
                3
            } else {
                1
            }

        card?.strokeColor =
            androidx.core.content.ContextCompat
                .getColor(
                    holder.itemView.context,
                    if (
                        selected
                    ) {
                        R.color.nts_blue
                    } else {
                        R.color.nts_outline
                    }
                )

        holder.more.visibility =
            if (
                selectedIds.isNotEmpty()
            ) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }

        holder.itemView
            .setOnClickListener {
                if (
                    selectedIds.isNotEmpty()
                ) {
                    toggleSelection(
                        item
                    )
                } else {
                    onItemClick(
                        item
                    )
                }
            }

        holder.itemView
            .setOnLongClickListener {
                toggleSelection(
                    item
                )

                true
            }

        holder.more
            .setOnClickListener {
                if (
                    selectedIds.isNotEmpty()
                ) {
                    toggleSelection(
                        item
                    )
                } else {
                    onItemLongClick(
                        item
                    )
                }
            }
    }

    override fun getItemCount(): Int =
        historyList.size

    fun selectedItems():
        List<ScanHistory> =
        historyList.filter {
            selectedIds.contains(
                it.id
            )
        }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(
            0
        )
    }

    fun selectAll() {
        selectedIds.clear()

        selectedIds.addAll(
            historyList.map {
                it.id
            }
        )

        notifyDataSetChanged()

        onSelectionChanged(
            selectedIds.size
        )
    }

    private fun toggleSelection(
        item: ScanHistory
    ) {
        if (
            selectedIds.contains(
                item.id
            )
        ) {
            selectedIds.remove(
                item.id
            )
        } else {
            selectedIds.add(
                item.id
            )
        }

        notifyDataSetChanged()

        onSelectionChanged(
            selectedIds.size
        )
    }

    fun updateData(
        newHistory: List<ScanHistory>
    ) {
        val oldHistory =
            historyList

        val diff =
            DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {

                    override fun getOldListSize(): Int =
                        oldHistory.size

                    override fun getNewListSize(): Int =
                        newHistory.size

                    override fun areItemsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int
                    ): Boolean {

                        val oldItem =
                            oldHistory[
                                oldItemPosition
                            ]

                        val newItem =
                            newHistory[
                                newItemPosition
                            ]

                        return oldItem.imagePath ==
                                newItem.imagePath &&
                                oldItem.timestamp ==
                                newItem.timestamp
                    }

                    override fun areContentsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int
                    ): Boolean {

                        return oldHistory[
                            oldItemPosition
                        ] ==
                                newHistory[
                                    newItemPosition
                                ]
                    }
                }
            )

        historyList =
            newHistory.toList()

        diff.dispatchUpdatesTo(this)
    }

    private fun historyPageCount(
        item: ScanHistory
    ): Int {
        if (
            item.sourceImagePathsJson
                .isBlank()
        ) {
            return 1
        }

        return runCatching {
            JSONArray(
                item.sourceImagePathsJson
            ).length()
                .coerceAtLeast(
                    1
                )
        }.getOrDefault(
            1
        )
    }

    private fun bindThumbnail(
        imageView: ImageView,
        imagePath: String?
    ) {
        imageView.imageTintList = null
        imageView.clearColorFilter()
        imageView.setPadding(
            0,
            0,
            0,
            0
        )
        imageView.scaleType =
            ImageView.ScaleType.CENTER_CROP

        if (!imagePath.isNullOrBlank()) {

            val file =
                File(imagePath)

            if (file.exists()) {

                val options =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }

                BitmapFactory.decodeFile(
                    file.absolutePath,
                    options
                )

                options.inSampleSize =
                    calculateInSampleSize(
                        options,
                        160,
                        120
                    )

                options.inJustDecodeBounds =
                    false

                val bitmap =
                    BitmapFactory.decodeFile(
                        file.absolutePath,
                        options
                    )

                if (bitmap != null) {
                    imageView.setImageBitmap(
                        bitmap
                    )
                    return
                }
            }
        }

        imageView.scaleType =
            ImageView.ScaleType.FIT_CENTER

        imageView.setImageResource(
            R.drawable.ic_note_custom
        )
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {

        val height =
            options.outHeight

        val width =
            options.outWidth

        var inSampleSize =
            1

        if (
            height > reqHeight ||
            width > reqWidth
        ) {

            var halfHeight =
                height / 2

            var halfWidth =
                width / 2

            while (
                halfHeight / inSampleSize >=
                reqHeight &&
                halfWidth / inSampleSize >=
                reqWidth
            ) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
            .coerceAtLeast(1)
    }
}