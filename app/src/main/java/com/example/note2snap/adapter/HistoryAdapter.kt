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

    private val selectedKeys =
        mutableSetOf<String>()

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

        val more: ImageButton =
            view.findViewById(
                R.id.btnHistoryMore
            )

        val pageCount:
            TextView =
                view.findViewById(
                    R.id.tvHistoryPageCount
                )

        val card:
            com.google.android.material.card.MaterialCardView? =
                view as?
                    com.google.android.material.card.MaterialCardView
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

        bindPageCount(
            holder.pageCount,
            item
        )

        bindThumbnail(
            holder.thumbnail,
            item.imagePath
        )

        bindSelectionState(
            holder,
            item
        )

        holder.itemView
            .setOnClickListener {
                if (
                    selectedKeys.isNotEmpty()
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
                    selectedKeys.isNotEmpty()
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

        val validKeys =
            historyList.map {
                selectionKey(
                    it
                )
            }.toSet()

        selectedKeys.retainAll(
            validKeys
        )

        diff.dispatchUpdatesTo(
            this
        )

        onSelectionChanged(
            selectedKeys.size
        )
    }

    fun selectedItems(): List<ScanHistory> {
        return historyList.filter {
            selectionKey(
                it
            ) in selectedKeys
        }
    }

    fun selectAll() {
        selectedKeys.clear()

        selectedKeys.addAll(
            historyList.map {
                selectionKey(
                    it
                )
            }
        )

        notifyDataSetChanged()

        onSelectionChanged(
            selectedKeys.size
        )
    }

    fun clearSelection() {
        if (
            selectedKeys.isEmpty()
        ) {
            return
        }

        selectedKeys.clear()

        notifyDataSetChanged()

        onSelectionChanged(
            0
        )
    }

    private fun toggleSelection(
        item: ScanHistory
    ) {
        val key =
            selectionKey(
                item
            )

        if (
            key in selectedKeys
        ) {
            selectedKeys.remove(
                key
            )
        } else {
            selectedKeys.add(
                key
            )
        }

        val index =
            historyList.indexOfFirst {
                selectionKey(
                    it
                ) ==
                    key
            }

        if (
            index >= 0
        ) {
            notifyItemChanged(
                index
            )
        }

        onSelectionChanged(
            selectedKeys.size
        )
    }

    private fun selectionKey(
        item: ScanHistory
    ): String {
        return "${item.imagePath}|${item.timestamp}"
    }

    private fun bindSelectionState(
        holder: HistoryViewHolder,
        item: ScanHistory
    ) {
        val selected =
            selectionKey(
                item
            ) in selectedKeys

        holder.card?.apply {
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
                androidx.core.content.ContextCompat.getColor(
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

    private fun bindPageCount(
        badge: TextView,
        item: ScanHistory
    ) {
        val pageCount =
            runCatching {
                val sourceCount =
                    item.sourceImagePathsJson
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
                    item.pageContentsJson
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