package com.example.note2snap.activities
import java.io.File
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.note2snap.R
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Folder
import com.example.note2snap.model.Note
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SaveFolderActivity : AppCompatActivity() {

    private var imagePath: String? = null
    private var noteTitle: String = "Scanned Note"
    private var noteContent: String = ""

    private val folderMap = mutableMapOf<Int, Folder>() // Maps RadioButton view IDs to Folder objects

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_save_folder)

        // Receive data passed from capture/scan screen
        imagePath = intent.getStringExtra("IMAGE_PATH")
        noteTitle = intent.getStringExtra("TITLE") ?: "Scanned Note"
        noteContent = intent.getStringExtra("CONTENT") ?: ""

        val btnSave = findViewById<Button>(R.id.btnSaveNote)
        val fabAddFolder = findViewById<FloatingActionButton>(R.id.fabAddFolderInSave)
        val radioGroup = findViewById<RadioGroup>(R.id.rgFolders)

        // Dynamically populate RadioGroup from Room DB
        loadFoldersIntoRadioGroup(radioGroup)

        btnSave?.setOnClickListener {
            val selectedRadioId = radioGroup.checkedRadioButtonId

            if (selectedRadioId != -1) {
                val selectedFolder = folderMap[selectedRadioId]
                if (selectedFolder != null) {
                    saveNoteToSelectedFolder(selectedFolder)
                } else {
                    Toast.makeText(this, "Selected folder not found", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Please select a folder first", Toast.LENGTH_SHORT).show()
            }
        }

        fabAddFolder?.setOnClickListener {
            startActivity(Intent(this, CreateFolderActivity::class.java))
        }
    }

    private fun loadFoldersIntoRadioGroup(radioGroup: RadioGroup) {
        val dao = AppDatabase.getDatabase(this).appDao()

        lifecycleScope.launch {
            dao.getAllFolders().collectLatest { folders ->
                radioGroup.removeAllViews()
                folderMap.clear()

                if (folders.isEmpty()) {
                    Toast.makeText(this@SaveFolderActivity, "No folders available. Create one first!", Toast.LENGTH_LONG).show()
                    return@collectLatest
                }

                folders.forEach { folder ->
                    val radioButton = RadioButton(this@SaveFolderActivity).apply {
                        id = View.generateViewId()
                        text = folder.name
                        textSize = 16f
                        setTextColor(Color.BLACK)
                        setPadding(16, 16, 16, 16)
                    }

                    radioGroup.addView(radioButton)
                    folderMap[radioButton.id] = folder
                }

                // Select the first folder by default
                if (radioGroup.childCount > 0) {
                    (radioGroup.getChildAt(0) as RadioButton).isChecked = true
                }
            }
        }
    }

    private fun saveNoteToSelectedFolder(folder: Folder) {
        lifecycleScope.launch(Dispatchers.IO) {
            val sourcePath = imagePath
            var savedFilePath = sourcePath ?: ""

            // 1. Copy physical file into phone storage folder: Documents/[folderName]/
            if (!sourcePath.isNullOrEmpty()) {
                val storageDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                val targetFolder = File(storageDir, folder.name)

                if (!targetFolder.exists()) {
                    targetFolder.mkdirs()
                }

                val sourceFile = File(sourcePath)
                if (sourceFile.exists()) {
                    val destFile = File(targetFolder, sourceFile.name)
                    sourceFile.copyTo(destFile, overwrite = true)
                    savedFilePath = destFile.absolutePath
                }
            }

            // Calculate file size for sorting
            val fileSize = if (savedFilePath.isNotEmpty()) File(savedFilePath).length() else 0L
            val currentDate = java.text.SimpleDateFormat("MMMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date())

            // 2. Save note record to Room Database WITH folderId
            val newNote = Note(
                folderId = folder.id, // Foreign key link to Folder
                title = noteTitle,
                content = noteContent,
                imagePath = savedFilePath,
                dateEdited = currentDate,
                timestamp = System.currentTimeMillis(),
                fileSizeBytes = fileSize
            )

            AppDatabase.getDatabase(this@SaveFolderActivity).appDao().insertNote(newNote)

            launch(Dispatchers.Main) {
                Toast.makeText(this@SaveFolderActivity, "Saved to ${folder.name}!", Toast.LENGTH_SHORT).show()

                // Open PDF/Reviewer viewer
                val intent = Intent(this@SaveFolderActivity, PdfViewerActivity::class.java).apply {
                    putExtra("TITLE", newNote.title)
                    putExtra("CONTENT", newNote.content)
                    putExtra("IMAGE_PATH", savedFilePath)
                }
                startActivity(intent)
                finish()
            }
        }
    }
}