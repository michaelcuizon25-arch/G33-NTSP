package com.example.note2snap.activities

import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.note2snap.R
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Folder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CreateFolderActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_folder)

        val etName = findViewById<EditText>(R.id.etFolderName)
        val btnCreate = findViewById<Button>(R.id.btnCreateFolder)
        val btnBack = findViewById<View>(R.id.btnBackCreateFolder)

        btnBack?.setOnClickListener { finish() }

        btnCreate?.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isNotEmpty()) {
                val currentDate = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date())

                lifecycleScope.launch(Dispatchers.IO) {
                    // 1. Create physical directory in phone storage
                    val storageDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    val physicalFolder = File(storageDir, name)
                    if (!physicalFolder.exists()) {
                        physicalFolder.mkdirs()
                    }

                    // 2. Create Folder entity object matching your model
                    val newFolder = Folder(
                        name = name,
                        dateCreated = currentDate,
                        timestamp = System.currentTimeMillis()
                    )

                    // 3. Save folder entry to Room Database
                    AppDatabase.getDatabase(this@CreateFolderActivity).appDao().insertFolder(newFolder)

                    launch(Dispatchers.Main) {
                        Toast.makeText(this@CreateFolderActivity, "Folder '$name' Created", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } else {
                etName.error = "Please enter a folder name"
            }
        }
    }
}