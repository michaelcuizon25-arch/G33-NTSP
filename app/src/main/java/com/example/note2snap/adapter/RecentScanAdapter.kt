package com.example.note2snap.adapters

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
import com.example.note2snap.utils.NoteThumbnailLoader

class RecentScanAdapter(
    private val scans: MutableList<ScanHistory>,
    private val onItemClick: (ScanHistory) -> Unit,
    private val onStarClick: (ScanHistory) -> Unit,
    private val onMoreClick: (ScanHistory) -> Unit
) : RecyclerView.Adapter<RecentScanAdapter.RecentScanViewHolder>() {

    private var savedPaths: Set<String> = emptySet()
    private var starredPaths: Set<String> = emptySet()

    class RecentScanViewHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {

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
    ): RecentScanViewHolder {

        val view =
            LayoutInflater
                .from(parent.context)
                .inflate(
                    R.layout.item_recent_activity_card,
                    parent,
                    false
                )

        return RecentScanViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: RecentScanViewHolder,
        position: Int
    ) {
        val scan = scans[position]
        val path = scan.imagePath.orEmpty()

        holder.tvTitle.text = scan.title
        holder.tvDate.text = scan.date

        NoteThumbnailLoader.load(
            imageView = holder.ivThumbnail,
            imagePath = scan.imagePath,
            fallbackRes = R.drawable.ic_note_custom
        )

        // RESTORED: star and three dots are always visible.
        holder.btnFavorite.visibility = View.VISIBLE
        holder.btnMore.visibility = View.VISIBLE

        holder.btnFavorite.imageTintList = null
        holder.btnFavorite.clearColorFilter()

        val isSaved = savedPaths.contains(path)
        val isStarred = starredPaths.contains(path)

        holder.btnFavorite.setImageResource(
            if (isStarred) {
                R.drawable.ic_star_filled_custom
            } else {
                R.drawable.ic_star_outline_custom
            }
        )

        holder.btnFavorite.alpha =
            if (isSaved) 1f else 0.55f

        holder.btnFavorite.contentDescription =
            when {
                !isSaved -> "Save to Notes first"
                isStarred -> "Unfavorite note"
                else -> "Favorite note"
            }

        holder.itemView.setOnClickListener {
            onItemClick(scan)
        }

        holder.btnFavorite.setOnClickListener {
            onStarClick(scan)
        }

        holder.btnMore.setOnClickListener {
            onMoreClick(scan)
        }
    }

    override fun getItemCount(): Int =
        scans.size

    fun updateData(
        newScans: List<ScanHistory>,
        newSavedPaths: Set<String>,
        newStarredPaths: Set<String>
    ) {
        val oldScans = scans.toList()

        val diff =
            DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {

                    override fun getOldListSize() =
                        oldScans.size

                    override fun getNewListSize() =
                        newScans.size

                    override fun areItemsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int
                    ): Boolean {
                        val oldItem = oldScans[oldItemPosition]
                        val newItem = newScans[newItemPosition]

                        return oldItem.imagePath == newItem.imagePath &&
                                oldItem.timestamp == newItem.timestamp
                    }

                    override fun areContentsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int
                    ): Boolean {
                        return oldScans[oldItemPosition] ==
                                newScans[newItemPosition]
                    }
                }
            )

        scans.clear()
        scans.addAll(newScans)

        savedPaths = newSavedPaths
        starredPaths = newStarredPaths

        diff.dispatchUpdatesTo(this)
        notifyItemRangeChanged(0, scans.size)
    }
}
