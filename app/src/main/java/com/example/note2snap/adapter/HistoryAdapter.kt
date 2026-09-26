package com.example.note2snap.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.model.ScanHistory
import java.io.File

class HistoryAdapter(
    private var historyList: List<ScanHistory>,
    private val onItemClick: (ScanHistory) -> Unit,
    private val onMoreClick: (ScanHistory) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    class HistoryViewHolder(
        view: View
    ) : RecyclerView.ViewHolder(view) {

        val ivHistoryThumbnail: ImageView =
            view.findViewById(
                R.id.ivHistoryThumbnail
            )

        val tvScanTitle: TextView =
            view.findViewById(
                R.id.tvScanTitle
            )

        val tvScanSyncStatus: TextView =
            view.findViewById(
                R.id.tvScanSyncStatus
            )

        val tvScanDate: TextView =
            view.findViewById(
                R.id.tvScanDate
            )

        val btnHistoryMore: ImageButton =
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

        holder.tvScanTitle.text =
            item.title

        holder.tvScanSyncStatus.text =
            if (item.isSyncedLocal) {
                "Saved locally"
            } else {
                "Not synced"
            }

        holder.tvScanDate.text =
            item.date

        bindThumbnail(
            holder.ivHistoryThumbnail,
            item.imagePath
        )

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }

        holder.btnHistoryMore
            .setOnClickListener {
                onMoreClick(item)
            }
    }

    override fun getItemCount(): Int =
        historyList.size

    fun updateItems(
        items: List<ScanHistory>
    ) {
        historyList = items
        notifyDataSetChanged()
    }

    private fun bindThumbnail(
        imageView: ImageView,
        imagePath: String
    ) {
        if (imagePath.isBlank()) {
            imageView.setImageResource(
                R.drawable.ic_history
            )
            return
        }

        runCatching {
            val uri =
                if (
                    imagePath.startsWith(
                        "content://"
                    ) ||
                    imagePath.startsWith(
                        "file://"
                    )
                ) {
                    Uri.parse(imagePath)
                } else {
                    Uri.fromFile(
                        File(imagePath)
                    )
                }

            imageView.setImageURI(uri)
        }.onFailure {
            imageView.setImageResource(
                R.drawable.ic_history
            )
        }
    }
}
