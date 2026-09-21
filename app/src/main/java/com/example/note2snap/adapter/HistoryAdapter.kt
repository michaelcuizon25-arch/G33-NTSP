package com.example.note2snap.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.model.ScanHistory

class HistoryAdapter(
    private val historyList: List<ScanHistory>,
    private val onItemClick: (ScanHistory) -> Unit,
    private val onItemLongClick: (ScanHistory) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    class HistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvScanTitle: TextView = view.findViewById(R.id.tvScanTitle)
        val tvScanSyncStatus: TextView = view.findViewById(R.id.tvScanSyncStatus)
        val tvScanDate: TextView = view.findViewById(R.id.tvScanDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val item = historyList[position]
        holder.tvScanTitle.text = item.title
        holder.tvScanSyncStatus.text = if (item.isSyncedLocal) "Synced to Local" else "Unsynced"
        holder.tvScanDate.text = item.date

        holder.itemView.setOnClickListener { onItemClick(item) }

        holder.itemView.setOnLongClickListener {
            onItemLongClick(item)
            true
        }
    }

    override fun getItemCount(): Int = historyList.size
}