package com.example.note2snap.activities

import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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

    private var selectedColorHex = "#AFC4F6"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_folder)

        val ivPreview = findViewById<ImageView>(R.id.ivFolderPreview)
        val etName = findViewById<EditText>(R.id.etFolderName)
        val btnCreate = findViewById<Button>(R.id.btnCreateFolder)
        val btnBack = findViewById<View>(R.id.btnBackCreateFolder)

        btnBack?.setOnClickListener { finish() }

        fun updateFolderColor(hex: String) {
            selectedColorHex = hex
            ivPreview?.setColorFilter(Color.parseColor(hex))
        }

        findViewById<View>(R.id.colorBlue)?.setOnClickListener { updateFolderColor("#AFC4F6") }
        findViewById<View>(R.id.colorGreen)?.setOnClickListener { updateFolderColor("#C7EAC2") }
        findViewById<View>(R.id.colorYellow)?.setOnClickListener { updateFolderColor("#FFF79A") }
        findViewById<View>(R.id.colorPurple)?.setOnClickListener { updateFolderColor("#D8CFF5") }
        findViewById<View>(R.id.colorPink)?.setOnClickListener { updateFolderColor("#F9C9DE") }
        findViewById<View>(R.id.colorOrange)?.setOnClickListener { updateFolderColor("#FBCB93") }
        findViewById<View>(R.id.colorRed)?.setOnClickListener { updateFolderColor("#F3A6A6") }
        findViewById<View>(R.id.colorGray)?.setOnClickListener { updateFolderColor("#E3E3E3") }

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

                    // 2. Create Folder database object
                    val newFolder = Folder(
                        name = name,
                        dateCreated = currentDate
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