package com.example.note2snap.activities

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.adapter.FolderAdapter
import com.example.note2snap.adapter.NotesAdapter
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Folder
import com.example.note2snap.model.Note
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class SortType { NAME, TIME, SIZE, TYPE }
enum class FilterType { ALL, NOTES, FOLDERS }

class NotesFragment : Fragment() {

    private val masterNotesList = mutableListOf<Note>()
    private val masterFolderList = mutableListOf<Folder>()

    private var currentFilteredNotes = listOf<Note>()
    private var currentFilteredFolders = listOf<Folder>()

    private lateinit var notesAdapter: NotesAdapter
    private lateinit var folderAdapter: FolderAdapter

    private lateinit var rvNotes: RecyclerView
    private var rvFolders: RecyclerView? = null
    private var tvFoldersLabel: TextView? = null
    private var tvNotesLabel: TextView? = null

    private var tvResultCount: TextView? = null
    private var tvNotFound: TextView? = null
    private var btnSort: ImageView? = null

    private var chipAll: TextView? = null
    private var chipNotes: TextView? = null
    private var chipFolders: TextView? = null

    private var currentSort = SortType.NAME
    private var currentFilter = FilterType.ALL
    private var searchQuery = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_notes, container, false)

        rvNotes = view.findViewById(R.id.rvNotes)
        rvFolders = view.findViewById(R.id.rvFolders)
        tvFoldersLabel = view.findViewById(R.id.tvFoldersLabel)
        tvNotesLabel = view.findViewById(R.id.tvNotesLabel)

        chipAll = view.findViewById(R.id.chipAll)
        chipNotes = view.findViewById(R.id.chipNotes)
        chipFolders = view.findViewById(R.id.chipFolders)

        val fabAdd = view.findViewById<FloatingActionButton>(R.id.fabAddNotes)
        val etSearch = view.findViewById<EditText>(R.id.etSearchNotes)

        tvResultCount = view.findViewById(R.id.tvResultCount)
        tvNotFound = view.findViewById(R.id.tvNotFound)
        btnSort = view.findViewById(R.id.btnSort)

        tvNotFound?.setText(R.string.no_file_found)

        // Initialize adapters
        notesAdapter = NotesAdapter(
            notes = emptyList(),
            onItemClick = { note ->
                val intent = Intent(context, PdfViewerActivity::class.java).apply {
                    putExtra("TITLE", note.title)
                    putExtra("CONTENT", note.content)
                    putExtra("IMAGE_PATH", note.imagePath)
                }
                startActivity(intent)
            },
            onMoveClick = { note -> showMoveNoteDialog(note) },
            onDeleteClick = { note -> deleteNote(note) },
            onToggleStarClick = { note -> toggleStarNote(note) }
        )
        rvNotes.layoutManager = LinearLayoutManager(context)
        rvNotes.adapter = notesAdapter

        folderAdapter = FolderAdapter(
            folderList = emptyList(),
            onItemClick = { folder -> handleFolderClick(folder) },
            onEditClick = { folder -> showEditFolderDialog(folder) },
            onDeleteClick = { folder -> showDeleteFolderDialog(folder) }
        )
        rvFolders?.layoutManager = LinearLayoutManager(context)
        rvFolders?.adapter = folderAdapter

        fabAdd?.setOnClickListener { showBottomSheetMenu() }

        setupFilterListeners()

        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s.toString().trim()
                applySearchAndSort()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSort?.setOnClickListener { showSortBottomSheet() }

        observeDatabaseData()

        return view
    }

    private fun handleFolderClick(folder: Folder) {
        val intent = Intent(context, SaveFolderActivity::class.java).apply {
            putExtra("FOLDER_ID", folder.id)
            putExtra("FOLDER_NAME", folder.name)
        }
        startActivity(intent)
    }

    private fun showMoveNoteDialog(note: Note) {
        val dialog = BottomSheetDialog(requireContext())

        val sheet = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
            background = roundedBackground("#FFF9FF", 28f)
        }

        sheet.addView(
            View(requireContext()).apply {
                background = roundedBackground("#D7D4DC", 3f)
            },
            LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            }
        )

        sheet.addView(
            TextView(requireContext()).apply {
                text = "Move note"
                textSize = 21f
                setTextColor("#171717".toColorInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        )

        sheet.addView(
            TextView(requireContext()).apply {
                text = note.title
                textSize = 11f
                setTextColor("#777780".toColorInt())
                setPadding(0, dp(4), 0, dp(14))
            }
        )

        val listCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground("#FFFFFF", 20f, "#ECECF2")
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        // Main screen / no folder option
        listCard.addView(
            createMoveFolderRow(
                title = "Main Screen",
                subtitle = "Keep this note outside folders",
                isCurrent = note.folderId == null
            ) {
                lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase.getDatabase(requireContext())
                        .appDao()
                        .updateNoteFolder(note.id, null)

                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Moved to Main Screen",
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    }
                }
            }
        )

        if (masterFolderList.isNotEmpty()) {
            masterFolderList.forEach { folder ->
                listCard.addView(
                    createMoveFolderRow(
                        title = folder.name,
                        subtitle = "Move note into this folder",
                        isCurrent = note.folderId == folder.id
                    ) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            AppDatabase.getDatabase(requireContext())
                                .appDao()
                                .updateNoteFolder(note.id, folder.id)

                            launch(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    getString(
                                        R.string.moved_to_folder,
                                        folder.name
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                                dialog.dismiss()
                            }
                        }
                    }
                )
            }
        } else {
            listCard.addView(
                TextView(requireContext()).apply {
                    text = "No folders yet. Create one using the + button in Notes."
                    textSize = 11f
                    setTextColor("#777780".toColorInt())
                    setPadding(dp(12), dp(14), dp(12), dp(14))
                }
            )
        }

        sheet.addView(listCard)
        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun createMoveFolderRow(
        title: String,
        subtitle: String,
        isCurrent: Boolean,
        onClick: () -> Unit
    ): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(11), dp(10), dp(11))
            background = roundedBackground(
                if (isCurrent) "#EEF2FF" else "#FFFFFF",
                16f
            )
            isClickable = true
            isFocusable = true

            val icon = TextView(requireContext()).apply {
                text = if (isCurrent) "✓" else "▣"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(
                    if (isCurrent) {
                        "#5A7FDB".toColorInt()
                    } else {
                        "#171717".toColorInt()
                    }
                )
                background = roundedBackground(
                    if (isCurrent) "#DCE6FF" else "#F4F4F7",
                    14f
                )
            }

            addView(
                icon,
                LinearLayout.LayoutParams(dp(44), dp(44))
            )

            val labels = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }

            labels.addView(
                TextView(requireContext()).apply {
                    text = title
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor("#171717".toColorInt())
                }
            )

            labels.addView(
                TextView(requireContext()).apply {
                    text = if (isCurrent) "Current location" else subtitle
                    textSize = 10f
                    setTextColor(
                        if (isCurrent) {
                            "#5A7FDB".toColorInt()
                        } else {
                            "#777780".toColorInt()
                        }
                    )
                    setPadding(0, dp(2), 0, 0)
                }
            )

            addView(
                labels,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            if (!isCurrent) {
                addView(
                    TextView(requireContext()).apply {
                        text = "›"
                        textSize = 22f
                        gravity = Gravity.CENTER
                        setTextColor("#9A9AA3".toColorInt())
                    },
                    LinearLayout.LayoutParams(dp(28), dp(44))
                )
            }

            setOnClickListener {
                if (!isCurrent) onClick()
            }
        }
    }

    private fun setupFilterListeners() {
        chipAll?.setOnClickListener {
            currentFilter = FilterType.ALL
            updateFilterTabUI()
            applySearchAndSort()
        }

        chipNotes?.setOnClickListener {
            currentFilter = FilterType.NOTES
            updateFilterTabUI()
            applySearchAndSort()
        }

        chipFolders?.setOnClickListener {
            currentFilter = FilterType.FOLDERS
            updateFilterTabUI()
            applySearchAndSort()
        }
    }

    private fun updateFilterTabUI() {
        val context = context ?: return
        val activeBg = ContextCompat.getDrawable(context, R.drawable.bg_chip_selected)
        val inactiveBg = ContextCompat.getDrawable(context, R.drawable.bg_chip_unselected)
        val activeTextColor = ContextCompat.getColor(context, android.R.color.white)

        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        val inactiveTextColor = ContextCompat.getColor(context, typedValue.resourceId)

        chipAll?.background = if (currentFilter == FilterType.ALL) activeBg else inactiveBg
        chipAll?.setTextColor(if (currentFilter == FilterType.ALL) activeTextColor else inactiveTextColor)

        chipNotes?.background = if (currentFilter == FilterType.NOTES) activeBg else inactiveBg
        chipNotes?.setTextColor(if (currentFilter == FilterType.NOTES) activeTextColor else inactiveTextColor)

        chipFolders?.background = if (currentFilter == FilterType.FOLDERS) activeBg else inactiveBg
        chipFolders?.setTextColor(if (currentFilter == FilterType.FOLDERS) activeTextColor else inactiveTextColor)
    }

    private fun observeDatabaseData() {
        val dao = AppDatabase.getDatabase(requireContext()).appDao()

        lifecycleScope.launch {
            dao.getAllFolders().collectLatest { fetchedFolders ->
                masterFolderList.clear()
                masterFolderList.addAll(fetchedFolders)
                applySearchAndSort()
            }
        }

        lifecycleScope.launch {
            dao.getAllNotes().collectLatest { fetchedNotes ->
                masterNotesList.clear()
                masterNotesList.addAll(fetchedNotes)
                applySearchAndSort()
            }
        }
    }

    private fun applySearchAndSort() {
        // 1. FILTER & SORT FOLDERS
        var filteredFolders = if (searchQuery.isEmpty()) {
            masterFolderList
        } else {
            masterFolderList.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

        filteredFolders = when (currentSort) {
            SortType.NAME -> filteredFolders.sortedBy { it.name.lowercase() }
            SortType.TIME -> filteredFolders.sortedByDescending { it.timestamp }
            SortType.SIZE -> filteredFolders.sortedBy { it.name.lowercase() }
            SortType.TYPE -> filteredFolders.sortedBy { it.name.lowercase() }
        }

        currentFilteredFolders = filteredFolders
        folderAdapter.updateFolders(currentFilteredFolders)

        // 2. FILTER & SORT ROOT NOTES (Unassigned notes on main screen)
        var filteredNotes = masterNotesList.filter { it.folderId == null }

        if (searchQuery.isNotEmpty()) {
            filteredNotes = filteredNotes.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }

        filteredNotes = when (currentSort) {
            SortType.NAME -> filteredNotes.sortedBy { it.title.lowercase() }
            SortType.TIME -> filteredNotes.sortedByDescending { it.timestamp }
            SortType.SIZE -> filteredNotes.sortedByDescending { it.fileSizeBytes }
            SortType.TYPE -> filteredNotes.sortedBy { it.fileType.lowercase() }
        }

        currentFilteredNotes = filteredNotes
        notesAdapter.updateNotes(currentFilteredNotes)

        // 3. TOGGLE VISIBILITY
        val showFoldersSection = (currentFilter == FilterType.ALL || currentFilter == FilterType.FOLDERS) && currentFilteredFolders.isNotEmpty()
        val showNotesSection = (currentFilter == FilterType.ALL || currentFilter == FilterType.NOTES) && currentFilteredNotes.isNotEmpty()

        rvFolders?.visibility = if (showFoldersSection) View.VISIBLE else View.GONE
        tvFoldersLabel?.visibility = if (showFoldersSection) View.VISIBLE else View.GONE

        rvNotes.visibility = if (showNotesSection) View.VISIBLE else View.GONE
        tvNotesLabel?.visibility = if (showNotesSection) View.VISIBLE else View.GONE

        val visibleItemCount = (if (showFoldersSection) currentFilteredFolders.size else 0) + (if (showNotesSection) currentFilteredNotes.size else 0)

        if (visibleItemCount == 0) {
            tvNotFound?.setText(R.string.no_file_found)
            tvNotFound?.visibility = View.VISIBLE
            tvResultCount?.visibility = View.GONE
        } else {
            tvNotFound?.visibility = View.GONE
            if (searchQuery.isNotEmpty()) {
                tvResultCount?.visibility = View.VISIBLE
                tvResultCount?.text = getString(R.string.found_items_count, visibleItemCount)
            } else {
                tvResultCount?.visibility = View.GONE
            }
        }
    }

    private fun showSortBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(R.layout.dialog_sort_by)

        dialog.findViewById<TextView>(R.id.tvSortName)?.setOnClickListener {
            currentSort = SortType.NAME
            applySearchAndSort()
            dialog.dismiss()
        }

        dialog.findViewById<TextView>(R.id.tvSortTime)?.setOnClickListener {
            currentSort = SortType.TIME
            applySearchAndSort()
            dialog.dismiss()
        }

        dialog.findViewById<TextView>(R.id.tvSortSize)?.setOnClickListener {
            currentSort = SortType.SIZE
            applySearchAndSort()
            dialog.dismiss()
        }

        dialog.findViewById<TextView>(R.id.tvSortType)?.setOnClickListener {
            currentSort = SortType.TYPE
            applySearchAndSort()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showEditFolderDialog(folder: Folder) {
        val input = EditText(requireContext()).apply {
            setText(folder.name)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setSelection(folder.name.length)
            setPadding(40, 32, 40, 32)
        }

        val dialog = AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle(R.string.edit_folder_name)
            .setView(input)
            .setPositiveButton(R.string.save) { d, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val updatedFolder = folder.copy(name = newName)
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppDatabase.getDatabase(requireContext()).appDao().insertFolder(updatedFolder)
                        launch(Dispatchers.Main) {
                            Toast.makeText(context, R.string.folder_updated, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
    }

    private fun showDeleteFolderDialog(folder: Folder) {
        val dialog = AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle(R.string.delete_folder)
            .setMessage(getString(R.string.delete_folder_confirm, folder.name))
            .setPositiveButton(R.string.delete) { d, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase.getDatabase(requireContext()).appDao().deleteFolder(folder)
                    launch(Dispatchers.Main) {
                        Toast.makeText(context, R.string.folder_deleted, Toast.LENGTH_SHORT).show()
                    }
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
    }

    private fun deleteNote(note: Note) {
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.getDatabase(requireContext()).appDao().deleteNote(note)
            launch(Dispatchers.Main) {
                Toast.makeText(context, R.string.note_deleted, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleStarNote(note: Note) {
        lifecycleScope.launch(Dispatchers.IO) {
            val updatedNote = note.copy(isStarred = !note.isStarred)
            AppDatabase.getDatabase(requireContext()).appDao().insertNote(updatedNote)
        }
    }

    private fun showBottomSheetMenu() {
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(R.layout.dialog_add_options)

        dialog.findViewById<LinearLayout>(R.id.llOptionFolder)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(context, CreateFolderActivity::class.java))
        }

        dialog.findViewById<LinearLayout>(R.id.llOptionNote)?.setOnClickListener {
            dialog.dismiss()
            (activity as? MainActivity)?.loadFragment(ScanFragment())
        }

        dialog.show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun roundedBackground(
        fillColor: String,
        radiusDp: Float,
        strokeColor: String? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
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