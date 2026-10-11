package com.example.note2snap.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.adapter.HistoryAdapter
import com.example.note2snap.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class UnsavedNotesActivity : AppCompatActivity() {

    private lateinit var rvUnsavedNotes: RecyclerView
    private lateinit var llEmptyUnsaved: LinearLayout
    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unsaved_notes)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        rvUnsavedNotes = findViewById(R.id.rvUnsavedNotes)
        llEmptyUnsaved = findViewById(R.id.llEmptyUnsaved)

        rvUnsavedNotes.layoutManager = LinearLayoutManager(this)

        historyAdapter = HistoryAdapter(
            historyList = emptyList(),
            onItemClick = { item ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val dao = AppDatabase.getDatabase(applicationContext).appDao()
                    val existingNote = if (item.imagePath.isNotBlank()) {
                        dao.getNoteByPath(item.imagePath)
                    } else null

                    val sourcePaths = runCatching {
                        val array = JSONArray(item.sourceImagePathsJson)
                        List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() }
                    }.getOrDefault(listOfNotNull(item.imagePath.takeIf { it.isNotBlank() }))

                    val pageContents = runCatching {
                        val array = JSONArray(item.pageContentsJson)
                        List(array.length()) { index -> array.optString(index) }
                    }.getOrDefault(emptyList())

                    withContext(Dispatchers.Main) {
                        val intent = Intent(this@UnsavedNotesActivity, PdfViewerActivity::class.java).apply {
                            putExtra("NOTE_ID", existingNote?.id ?: -1)
                            putExtra("TITLE", item.title)
                            putExtra("IMAGE_PATH", item.imagePath)
                            if (existingNote == null) {
                                pageContents.firstOrNull()?.let { putExtra("CONTENT", it) }
                                putStringArrayListExtra("IMAGE_PATHS", ArrayList(sourcePaths))
                                putStringArrayListExtra("PAGE_CONTENTS", ArrayList(pageContents))
                            }
                        }
                        startActivity(intent)
                    }
                }
            },
            onItemLongClick = {},
            onSelectionChanged = {}
        )

        rvUnsavedNotes.adapter = historyAdapter

        observeUnsavedScans()
    }

    private fun observeUnsavedScans() {
        AppDatabase.getDatabase(this)
            .appDao()
            .getUnsavedScans()
            .observe(this) { unsavedList ->
                if (unsavedList.isNullOrEmpty()) {
                    llEmptyUnsaved.visibility = View.VISIBLE
                    rvUnsavedNotes.visibility = View.GONE
                } else {
                    llEmptyUnsaved.visibility = View.GONE
                    rvUnsavedNotes.visibility = View.VISIBLE
                    historyAdapter.updateData(unsavedList)
                }
            }
    }
}