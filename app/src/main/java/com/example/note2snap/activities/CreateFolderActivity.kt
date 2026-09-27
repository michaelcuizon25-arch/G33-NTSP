package com.example.note2snap.activities

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.example.note2snap.R
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Folder
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CreateFolderActivity : AppCompatActivity() {

    private var selectedColorHex = "#AFC4F6"

    private lateinit var folderPreview: ImageView
    private lateinit var etName: EditText
    private lateinit var btnCreate: MaterialButton

    private lateinit var colorViews: List<Pair<View, String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_folder)

        etName = findViewById(R.id.etFolderName)
        btnCreate = findViewById(R.id.btnCreateFolder)
        val btnBack = findViewById<View>(R.id.btnBackCreateFolder)
        folderPreview = findViewById(R.id.ivFolderPreview)

        colorViews = listOf(
            findViewById<View>(R.id.colorBlue) to "#AFC4F6",
            findViewById<View>(R.id.colorGreen) to "#A9DDB8",
            findViewById<View>(R.id.colorYellow) to "#F4D77D",
            findViewById<View>(R.id.colorPurple) to "#C6B7E8",
            findViewById<View>(R.id.colorPink) to "#F2B8CF",
            findViewById<View>(R.id.colorOrange) to "#F3B274",
            findViewById<View>(R.id.colorRed) to "#E8A0A0",
            findViewById<View>(R.id.colorGray) to "#BFC3CA"
        )

        btnBack.setOnClickListener {
            finish()
        }

        setupColorPicker()
        updateFolderPreview()
        updateCreateButtonState("")

        etName.doAfterTextChanged {
            updateCreateButtonState(it?.toString()?.trim().orEmpty())
        }

        btnCreate.setOnClickListener {
            val name = etName.text.toString().trim()

            if (name.isEmpty()) {
                etName.error = "Please enter a folder name"
                return@setOnClickListener
            }

            val currentDate = SimpleDateFormat(
                "MMMM dd, yyyy",
                Locale.getDefault()
            ).format(Date())

            lifecycleScope.launch(Dispatchers.IO) {
                val storageDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                val physicalFolder = File(storageDir, name)

                if (!physicalFolder.exists()) {
                    physicalFolder.mkdirs()
                }

                val newFolder = Folder(
                    name = name,
                    dateCreated = currentDate,
                    timestamp = System.currentTimeMillis(),
                    colorHex = selectedColorHex
                )

                AppDatabase
                    .getDatabase(this@CreateFolderActivity)
                    .appDao()
                    .insertFolder(newFolder)

                launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@CreateFolderActivity,
                        "Folder '$name' created",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun setupColorPicker() {
        colorViews.forEach { (view, colorHex) ->
            view.setOnClickListener {
                selectedColorHex = colorHex
                updateFolderPreview()
                updateSelectionUI(view)
            }
        }

        updateSelectionUI(colorViews.first().first)
    }

    private fun updateFolderPreview() {
        val selectedColor = Color.parseColor(selectedColorHex)

        folderPreview.setImageResource(R.drawable.ic_folder_cute)

        ImageViewCompat.setImageTintList(
            folderPreview,
            ColorStateList.valueOf(selectedColor)
        )
    }

    private fun updateSelectionUI(selectedView: View) {
        colorViews.forEach { (view, colorHex) ->
            val isSelected = view === selectedView

            // Hindi na zoomed
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = if (isSelected) 1f else 0.88f

            view.background = buildColorCircle(
                colorHex = colorHex,
                selected = isSelected
            )
        }
    }

    private fun buildColorCircle(
        colorHex: String,
        selected: Boolean
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(colorHex))

            if (selected) {
                setStroke(dp(2), Color.parseColor("#171717"))
            } else {
                setStroke(dp(1), Color.parseColor("#A7A7AF"))
            }
        }
    }

    private fun updateCreateButtonState(text: String) {
        val hasText = text.isNotBlank()

        btnCreate.isEnabled = hasText

        if (hasText) {
            btnCreate.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#FFF79A"))
            btnCreate.setTextColor(Color.parseColor("#171717"))
            btnCreate.strokeColor =
                ColorStateList.valueOf(Color.parseColor("#111111"))
        } else {
            btnCreate.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
            btnCreate.setTextColor(Color.parseColor("#171717"))
            btnCreate.strokeColor =
                ColorStateList.valueOf(Color.parseColor("#111111"))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}