package com.example.note2snap.activities

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.adapter.NotesAdapter
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Note
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SaveFolderActivity : AppCompatActivity() {

    private var folderId: Int = -1
    private var folderName: String = "Folder"

    private lateinit var notesAdapter: NotesAdapter
    private lateinit var rvFolderNotes: RecyclerView
    private lateinit var tvEmptyFolder: TextView
    private lateinit var tvFolderCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_save_folder)

        folderId = intent.getIntExtra("FOLDER_ID", -1)
        folderName = intent.getStringExtra("FOLDER_NAME") ?: "Folder"

        findViewById<TextView>(R.id.tvFolderTitle)?.text = folderName
        findViewById<ImageButton>(R.id.btnFolderBack)?.setOnClickListener {
            finish()
        }

        rvFolderNotes = findViewById(R.id.rvFolderNotes)
        tvEmptyFolder = findViewById(R.id.tvEmptyFolder)
        tvFolderCount = findViewById(R.id.tvFolderCount)

        setupNotesList()
        observeFolderNotes()
    }

    private fun setupNotesList() {
        notesAdapter = NotesAdapter(
            notes = emptyList(),
            onItemClick = { note ->
                val intent =
                    Intent(this, PdfViewerActivity::class.java).apply {
                        putExtra("NOTE_ID", note.id)
                        putExtra("TITLE", note.title)
                        putExtra("CONTENT", note.content)
                        putExtra("IMAGE_PATH", note.imagePath)
                    }
                startActivity(intent)
            },
            onMoveClick = { note ->
                showMoveNoteDialog(note)
            },
            onDeleteClick = { note ->
                lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase
                        .getDatabase(this@SaveFolderActivity)
                        .appDao()
                        .deleteNote(note)
                }
            },
            onToggleStarClick = { note ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val updated =
                        note.copy(
                            isStarred = !note.isStarred
                        )

                    AppDatabase
                        .getDatabase(this@SaveFolderActivity)
                        .appDao()
                        .insertNote(updated)
                }
            }
        )

        rvFolderNotes.layoutManager =
            LinearLayoutManager(this)

        rvFolderNotes.adapter = notesAdapter
    }

    private fun observeFolderNotes() {
        lifecycleScope.launch {
            AppDatabase
                .getDatabase(this@SaveFolderActivity)
                .appDao()
                .getAllNotes()
                .collectLatest { allNotes ->

                    val folderNotes =
                        allNotes.filter {
                            it.folderId == folderId
                        }

                    notesAdapter.updateNotes(folderNotes)

                    tvFolderCount.text =
                        when (folderNotes.size) {
                            0 -> "No notes yet"
                            1 -> "1 note"
                            else -> "${folderNotes.size} notes"
                        }

                    tvEmptyFolder.visibility =
                        if (folderNotes.isEmpty()) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }

                    rvFolderNotes.visibility =
                        if (folderNotes.isEmpty()) {
                            View.GONE
                        } else {
                            View.VISIBLE
                        }
                }
        }
    }

    private fun showMoveNoteDialog(note: Note) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao =
                AppDatabase
                    .getDatabase(this@SaveFolderActivity)
                    .appDao()

            val folders = dao.getAllFolders().first()

            launch(Dispatchers.Main) {
                val dialog =
                    BottomSheetDialog(this@SaveFolderActivity)

                val sheet =
                    LinearLayout(this@SaveFolderActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(
                            dp(18),
                            dp(12),
                            dp(18),
                            dp(24)
                        )
                        background =
                            roundedBackground(
                                "#FFF9FF",
                                28f
                            )
                    }

                sheet.addView(
                    View(this@SaveFolderActivity).apply {
                        background =
                            roundedBackground(
                                "#D7D4DC",
                                3f
                            )
                    },
                    LinearLayout.LayoutParams(
                        dp(42),
                        dp(4)
                    ).apply {
                        gravity =
                            Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(18)
                    }
                )

                sheet.addView(
                    TextView(this@SaveFolderActivity).apply {
                        text = "Move note"
                        textSize = 21f
                        typeface =
                            android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(
                            "#171717".toColorInt()
                        )
                    }
                )

                sheet.addView(
                    TextView(this@SaveFolderActivity).apply {
                        text = note.title
                        textSize = 11f
                        setTextColor(
                            "#777780".toColorInt()
                        )
                        setPadding(
                            0,
                            dp(4),
                            0,
                            dp(14)
                        )
                    }
                )

                val listCard =
                    LinearLayout(
                        this@SaveFolderActivity
                    ).apply {
                        orientation =
                            LinearLayout.VERTICAL
                        background =
                            roundedBackground(
                                "#FFFFFF",
                                20f,
                                "#ECECF2"
                            )
                        setPadding(
                            dp(6),
                            dp(6),
                            dp(6),
                            dp(6)
                        )
                    }

                listCard.addView(
                    createDestinationRow(
                        "Main Screen",
                        note.folderId == null
                    ) {
                        moveNote(
                            note,
                            null,
                            "Main Screen"
                        )
                        dialog.dismiss()
                    }
                )

                folders.forEach { folder ->
                    listCard.addView(
                        createDestinationRow(
                            folder.name,
                            note.folderId ==
                                    folder.id
                        ) {
                            moveNote(
                                note,
                                folder.id,
                                folder.name
                            )
                            dialog.dismiss()
                        }
                    )
                }

                sheet.addView(listCard)

                dialog.setContentView(sheet)
                dialog.show()
            }
        }
    }

    private fun moveNote(
        note: Note,
        destinationFolderId: Int?,
        destinationName: String
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase
                .getDatabase(this@SaveFolderActivity)
                .appDao()
                .updateNoteFolder(
                    note.id,
                    destinationFolderId
                )

            launch(Dispatchers.Main) {
                Toast.makeText(
                    this@SaveFolderActivity,
                    "Moved to $destinationName",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun createDestinationRow(
        name: String,
        isCurrent: Boolean,
        action: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(10),
                dp(11),
                dp(10),
                dp(11)
            )
            background =
                roundedBackground(
                    if (isCurrent) {
                        "#EEF2FF"
                    } else {
                        "#FFFFFF"
                    },
                    16f
                )

            addView(
                TextView(this@SaveFolderActivity).apply {
                    text =
                        if (isCurrent) "✓" else "▣"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(
                        if (isCurrent) {
                            "#5A7FDB".toColorInt()
                        } else {
                            "#171717".toColorInt()
                        }
                    )
                    background =
                        roundedBackground(
                            if (isCurrent) {
                                "#DCE6FF"
                            } else {
                                "#F4F4F7"
                            },
                            14f
                        )
                },
                LinearLayout.LayoutParams(
                    dp(44),
                    dp(44)
                )
            )

            val label =
                TextView(this@SaveFolderActivity).apply {
                    text =
                        if (isCurrent) {
                            "$name\nCurrent location"
                        } else {
                            name
                        }
                    textSize = 13f
                    setTextColor(
                        "#171717".toColorInt()
                    )
                    setPadding(
                        dp(12),
                        0,
                        0,
                        0
                    )
                }

            addView(
                label,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!isCurrent) action()
            }
        }
    }

    private fun dp(value: Int): Int {
        return (
                value *
                        resources.displayMetrics.density
                ).toInt()
    }

    private fun roundedBackground(
        fillColor: String,
        radiusDp: Float,
        strokeColor: String? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius =
                radiusDp *
                        resources.displayMetrics.density
            setColor(Color.parseColor(fillColor))

            if (strokeColor != null) {
                setStroke(
                    dp(1),
                    Color.parseColor(strokeColor)
                )
            }
        }
    }

}
