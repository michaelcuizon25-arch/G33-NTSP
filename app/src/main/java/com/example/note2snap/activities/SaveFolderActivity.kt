package com.example.note2snap.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.note2snap.R
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Note
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SaveFolderActivity : AppCompatActivity() {

    private var imagePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_save_folder)

        // Receive image/file path passed from capture screen
        imagePath = intent.getStringExtra("IMAGE_PATH")

        val btnSave = findViewById<Button>(R.id.btnSaveNote)
        val fabAddFolder = findViewById<FloatingActionButton>(R.id.fabAddFolderInSave)
        val radioGroup = findViewById<RadioGroup>(R.id.rgFolders)

        btnSave?.setOnClickListener {
            val selectedId = radioGroup.checkedRadioButtonId

            if (selectedId != -1) {
                val radioButton = findViewById<RadioButton>(selectedId)
                val selectedFolderName = radioButton.text.toString()

                // Save file to physical folder & Room database
                saveNoteToSelectedFolder(selectedFolderName)
            } else {
                Toast.makeText(this, "Please select a folder first", Toast.LENGTH_SHORT).show()
            }
        }

        fabAddFolder?.setOnClickListener {
            startActivity(Intent(this, CreateFolderActivity::class.java))
        }
    }

    private fun saveNoteToSelectedFolder(folderName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val sourcePath = imagePath
            var savedFilePath = sourcePath

            // 1. Copy physical file into phone storage folder: Documents/[folderName]/
            if (!sourcePath.isNullOrEmpty()) {
                val storageDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                val targetFolder = File(storageDir, folderName)

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

            // 2. Save note record to Room Database
            val currentDate = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date())
            val newNote = Note(
                title = "Scanned Note ($folderName)",
                imagePath = savedFilePath ?: "",
                dateEdited = currentDate
            )

            AppDatabase.getDatabase(this@SaveFolderActivity).appDao().insertNote(newNote)

            launch(Dispatchers.Main) {
                Toast.makeText(this@SaveFolderActivity, "Saved to $folderName!", Toast.LENGTH_SHORT).show()

                // Open PDF/Reviewer viewer
                val intent = Intent(this@SaveFolderActivity, PdfViewerActivity::class.java)
                intent.putExtra("TITLE", newNote.title)
                intent.putExtra("IMAGE_PATH", savedFilePath)
                startActivity(intent)
                finish()
            }
        }
    }
}