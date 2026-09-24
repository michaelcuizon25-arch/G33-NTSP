package com.example.note2snap.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.adapter.HistoryAdapter
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.ScanHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryFragment : Fragment() {

    private var historyAdapter: HistoryAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_history, container, false)

        val rvHistory = view.findViewById<RecyclerView>(R.id.rvHistory)
        val llEmptyHistory = view.findViewById<LinearLayout>(R.id.llEmptyHistory)

        rvHistory?.layoutManager = LinearLayoutManager(requireContext())

        historyAdapter = HistoryAdapter(
            historyList = emptyList(),
            onItemClick = { item ->
                val intent = Intent(requireContext(), PdfViewerActivity::class.java).apply {
                    putExtra("SCAN_ID", item.id) // Corrected to SCAN_ID to avoid ID collision
                    putExtra("TITLE", item.title)
                    putExtra("IMAGE_PATH", item.imagePath)
                }
                startActivity(intent)
            },
            onItemLongClick = { item ->
                showOptionsDialog(item)
            }
        )
        rvHistory?.adapter = historyAdapter

        // Observe database updates in real time
        AppDatabase.getDatabase(requireContext()).appDao().getAllScanHistory()
            .observe(viewLifecycleOwner) { historyList ->
                if (historyList.isNullOrEmpty()) {
                    llEmptyHistory?.visibility = View.VISIBLE
                    rvHistory?.visibility = View.GONE
                } else {
                    llEmptyHistory?.visibility = View.GONE
                    rvHistory?.visibility = View.VISIBLE
                    historyAdapter?.updateData(historyList)
                }
            }

        return view
    }

    private fun showOptionsDialog(item: ScanHistory) {
        val options = arrayOf("Edit Title", "Delete Note")

        AlertDialog.Builder(requireContext())
            .setTitle(item.title)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> showEditTitleDialog(item)
                    1 -> confirmDeleteNote(item)
                }
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun showEditTitleDialog(item: ScanHistory) {
        val input = EditText(requireContext()).apply {
            setText(item.title)
            setSelection(item.title.length)
            setPadding(40, 32, 40, 32)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Rename Scan")
            .setView(input)
            .setPositiveButton("Save") { dialog, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val dao = AppDatabase.getDatabase(requireContext()).appDao()

                        // 1. Update ScanHistory table
                        val updated = item.copy(title = newTitle)
                        dao.updateScanHistory(updated)

                        // 2. Sync title with Notes table matching imagePath
                        if (!item.imagePath.isNullOrEmpty()) {
                            dao.updateNoteTitleByPath(item.imagePath, newTitle)
                        }

                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Title updated", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "Title cannot be empty", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun confirmDeleteNote(item: ScanHistory) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Scan?")
            .setMessage("Are you sure you want to delete \"${item.title}\"?")
            .setPositiveButton("Delete") { dialog, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val dao = AppDatabase.getDatabase(requireContext()).appDao()

                    // Delete from both ScanHistory and Notes tables
                    dao.deleteScanHistory(item)
                    if (!item.imagePath.isNullOrEmpty()) {
                        dao.deleteNoteByPath(item.imagePath)
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "History deleted", Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }
}