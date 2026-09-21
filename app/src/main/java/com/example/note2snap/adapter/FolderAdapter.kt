package com.example.note2snap.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.model.Folder

class FolderAdapter(
    private val folderList: List<Folder>,
    private val onItemClick: (Folder) -> Unit,
    private val onEditClick: (Folder) -> Unit,
    private val onDeleteClick: (Folder) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivFolderIcon: ImageView = view.findViewById(R.id.ivFolderIcon)
        val tvFolderName: TextView = view.findViewById(R.id.tvFolderName)
        val btnFolderMore: ImageView = view.findViewById(R.id.btnFolderMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return FolderViewHolder(view)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val folder = folderList[position]
        holder.tvFolderName.text = folder.name

        // Open folder contents / reviewer files on click
        holder.itemView.setOnClickListener {
            onItemClick(folder)
        }

        // 3-dots Options Popup Menu
        holder.btnFolderMore.setOnClickListener { view ->
            val popup = PopupMenu(view.context, view)
            popup.menu.add("Edit")
            popup.menu.add("Delete")

            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Edit" -> onEditClick(folder)
                    "Delete" -> onDeleteClick(folder)
                }
                true
            }
            popup.show()
        }
    }

    override fun getItemCount(): Int = folderList.size
}