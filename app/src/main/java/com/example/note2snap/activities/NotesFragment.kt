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
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
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
    private var btnViewMode: ImageView? = null

    private var currentViewMode =
        NotesAdapter.DisplayMode.LIST

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
        btnViewMode = view.findViewById(R.id.btnViewMode)

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
        btnViewMode?.setOnClickListener { showViewModeBottomSheet() }

        applyViewMode()
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
        val dialog =
            BottomSheetDialog(requireContext())

        val sheet =
            createSheetContainer(
                title = "Move note",
                subtitle = note.title
            )

        val listCard =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    roundedBackground(
                        colorHex(R.color.nts_surface),
                        20f,
                        colorHex(R.color.nts_blue_line)
                    )
                setPadding(
                    dp(6),
                    dp(6),
                    dp(6),
                    dp(6)
                )
            }

        listCard.addView(
            createMoveFolderRow(
                title = "Main Screen",
                subtitle = "Keep this note outside folders",
                isCurrent = note.folderId == null
            ) {
                lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase
                        .getDatabase(requireContext())
                        .appDao()
                        .updateNoteFolder(
                            note.id,
                            null
                        )

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

        masterFolderList.forEach { folder ->
            listCard.addView(
                createMoveFolderRow(
                    title = folder.name,
                    subtitle = "Move note into this folder",
                    isCurrent =
                        note.folderId ==
                                folder.id
                ) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppDatabase
                            .getDatabase(requireContext())
                            .appDao()
                            .updateNoteFolder(
                                note.id,
                                folder.id
                            )

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
            setPadding(
                dp(10),
                dp(10),
                dp(10),
                dp(10)
            )

            background =
                roundedBackground(
                    if (isCurrent) {
                        colorHex(R.color.nts_blue_soft)
                    } else {
                        colorHex(R.color.nts_surface)
                    },
                    16f
                )

            val icon =
                TextView(requireContext()).apply {
                    text =
                        if (isCurrent) "✓" else "▣"
                    textSize = 17f
                    gravity = Gravity.CENTER
                    setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            if (isCurrent) {
                                R.color.nts_blue
                            } else {
                                R.color.nts_text
                            }
                        )
                    )
                    background =
                        roundedBackground(
                            if (isCurrent) {
                                colorHex(R.color.nts_blue_line)
                            } else {
                                colorHex(R.color.nts_surface_blue_soft)
                            },
                            14f
                        )
                }

            addView(
                icon,
                LinearLayout.LayoutParams(
                    dp(42),
                    dp(42)
                )
            )

            val labels =
                LinearLayout(requireContext()).apply {
                    orientation =
                        LinearLayout.VERTICAL
                    setPadding(
                        dp(12),
                        0,
                        0,
                        0
                    )
                }

            labels.addView(
                TextView(requireContext()).apply {
                    text = title
                    textSize = 12.5f
                    typeface =
                        ResourcesCompat.getFont(
                            requireContext(),
                            R.font.poppins_medium
                        )
                    setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            R.color.nts_text
                        )
                    )
                }
            )

            labels.addView(
                TextView(requireContext()).apply {
                    text =
                        if (isCurrent) {
                            "Current location"
                        } else {
                            subtitle
                        }
                    textSize = 9.5f
                    typeface =
                        ResourcesCompat.getFont(
                            requireContext(),
                            R.font.poppins_regular
                        )
                    setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            if (isCurrent) {
                                R.color.nts_blue
                            } else {
                                R.color.nts_text_secondary
                            }
                        )
                    )
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

            isClickable = !isCurrent
            isFocusable = !isCurrent

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

        // Starred notes always stay at the top.
        // The selected sort is still applied inside the starred and non-starred groups.
        filteredNotes = when (currentSort) {
            SortType.NAME ->
                filteredNotes.sortedWith(
                    compareByDescending<Note> { it.isStarred }
                        .thenBy { it.title.lowercase() }
                )

            SortType.TIME ->
                filteredNotes.sortedWith(
                    compareByDescending<Note> { it.isStarred }
                        .thenByDescending { it.timestamp }
                )

            SortType.SIZE ->
                filteredNotes.sortedWith(
                    compareByDescending<Note> { it.isStarred }
                        .thenByDescending { it.fileSizeBytes }
                )

            SortType.TYPE ->
                filteredNotes.sortedWith(
                    compareByDescending<Note> { it.isStarred }
                        .thenBy { it.fileType.lowercase() }
                )
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
        val dialog =
            BottomSheetDialog(requireContext())

        val sheet =
            createSheetContainer(
                title = "Sort notes",
                subtitle = "Choose how notes and folders are arranged"
            )

        val card =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    roundedBackground(
                        colorHex(R.color.nts_surface),
                        20f,
                        colorHex(R.color.nts_blue_line)
                    )
                setPadding(
                    dp(6),
                    dp(6),
                    dp(6),
                    dp(6)
                )
            }

        fun addSortRow(
            label: String,
            subtitle: String,
            type: SortType
        ) {
            card.addView(
                createChoiceRow(
                    title = label,
                    subtitle = subtitle,
                    selected =
                        currentSort == type
                ) {
                    currentSort = type
                    applySearchAndSort()
                    dialog.dismiss()
                }
            )
        }

        addSortRow(
            "Name",
            "Alphabetical A–Z",
            SortType.NAME
        )
        addSortRow(
            "Time",
            "Newest notes first",
            SortType.TIME
        )
        addSortRow(
            "Size",
            "Largest files first",
            SortType.SIZE
        )
        addSortRow(
            "Type",
            "Group by file type",
            SortType.TYPE
        )

        sheet.addView(card)
        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun showViewModeBottomSheet() {
        val dialog =
            BottomSheetDialog(requireContext())

        val sheet =
            createSheetContainer(
                title = "View notes",
                subtitle = "Change how your notes are displayed"
            )

        val card =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    roundedBackground(
                        colorHex(R.color.nts_surface),
                        20f,
                        colorHex(R.color.nts_blue_line)
                    )
                setPadding(
                    dp(6),
                    dp(6),
                    dp(6),
                    dp(6)
                )
            }

        fun addModeRow(
            title: String,
            subtitle: String,
            mode: NotesAdapter.DisplayMode
        ) {
            card.addView(
                createChoiceRow(
                    title = title,
                    subtitle = subtitle,
                    selected =
                        currentViewMode == mode
                ) {
                    currentViewMode = mode
                    applyViewMode()
                    dialog.dismiss()
                }
            )
        }

        addModeRow(
            "List",
            "Full-width note cards",
            NotesAdapter.DisplayMode.LIST
        )
        addModeRow(
            "Grid",
            "Two-column overview",
            NotesAdapter.DisplayMode.GRID
        )
        addModeRow(
            "Compact",
            "Smaller rows for faster browsing",
            NotesAdapter.DisplayMode.COMPACT
        )

        sheet.addView(card)
        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun applyViewMode() {
        notesAdapter.setDisplayMode(
            currentViewMode
        )

        rvNotes.layoutManager =
            when (currentViewMode) {
                NotesAdapter.DisplayMode.GRID ->
                    GridLayoutManager(
                        requireContext(),
                        2
                    )

                NotesAdapter.DisplayMode.LIST,
                NotesAdapter.DisplayMode.COMPACT ->
                    LinearLayoutManager(
                        requireContext()
                    )
            }

        btnViewMode?.contentDescription =
            when (currentViewMode) {
                NotesAdapter.DisplayMode.LIST ->
                    "List view"
                NotesAdapter.DisplayMode.GRID ->
                    "Grid view"
                NotesAdapter.DisplayMode.COMPACT ->
                    "Compact view"
            }
    }

    private fun createChoiceRow(
        title: String,
        subtitle: String,
        selected: Boolean,
        action: () -> Unit
    ): View {
        return LinearLayout(requireContext()).apply {
            orientation =
                LinearLayout.HORIZONTAL
            gravity =
                Gravity.CENTER_VERTICAL
            setPadding(
                dp(10),
                dp(10),
                dp(10),
                dp(10)
            )
            background =
                roundedBackground(
                    if (selected) {
                        colorHex(R.color.nts_blue_soft)
                    } else {
                        colorHex(R.color.nts_surface)
                    },
                    15f
                )

            addView(
                TextView(requireContext()).apply {
                    text =
                        if (selected) "✓" else "○"
                    gravity = Gravity.CENTER
                    textSize = 16f
                    setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            if (selected) {
                                R.color.nts_blue
                            } else {
                                R.color.nts_text_secondary
                            }
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    dp(34),
                    dp(34)
                )
            )

            val labels =
                LinearLayout(requireContext()).apply {
                    orientation =
                        LinearLayout.VERTICAL
                    setPadding(
                        dp(10),
                        0,
                        0,
                        0
                    )
                }

            labels.addView(
                TextView(requireContext()).apply {
                    text = title
                    textSize = 12.5f
                    typeface =
                        ResourcesCompat.getFont(
                            requireContext(),
                            R.font.poppins_medium
                        )
                    setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            R.color.nts_text
                        )
                    )
                }
            )

            labels.addView(
                TextView(requireContext()).apply {
                    text = subtitle
                    textSize = 9.5f
                    typeface =
                        ResourcesCompat.getFont(
                            requireContext(),
                            R.font.poppins_regular
                        )
                    setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            R.color.nts_text_secondary
                        )
                    )
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

            isClickable = true
            isFocusable = true
            setOnClickListener {
                action()
            }
        }
    }

    private fun createSheetContainer(
        title: String,
        subtitle: String
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation =
                LinearLayout.VERTICAL
            setPadding(
                dp(18),
                dp(12),
                dp(18),
                dp(24)
            )
            background =
                roundedBackground(
                    colorHex(R.color.nts_background),
                    28f
                )

            addView(
                View(requireContext()).apply {
                    background =
                        roundedBackground(
                            colorHex(R.color.nts_blue_line),
                            99f
                        )
                },
                LinearLayout.LayoutParams(
                    dp(42),
                    dp(4)
                ).apply {
                    gravity =
                        Gravity.CENTER_HORIZONTAL
                    bottomMargin =
                        dp(16)
                }
            )

            addView(
                TextView(requireContext()).apply {
                    text = title
                    textSize = 20f
                    typeface =
                        ResourcesCompat.getFont(
                            requireContext(),
                            R.font.apple_garamond_bold
                        )
                    setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            R.color.nts_text
                        )
                    )
                }
            )

            addView(
                TextView(requireContext()).apply {
                    text = subtitle
                    textSize = 10.5f
                    typeface =
                        ResourcesCompat.getFont(
                            requireContext(),
                            R.font.poppins_regular
                        )
                    setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            R.color.nts_text_secondary
                        )
                    )
                    setPadding(
                        0,
                        dp(3),
                        0,
                        dp(14)
                    )
                }
            )
        }
    }

    private fun showEditFolderDialog(
        folder: Folder
    ) {
        val dialog =
            BottomSheetDialog(
                requireContext()
            )

        val sheet =
            createSheetContainer(
                title = "Rename folder",
                subtitle = "Choose a new name for ${folder.name}"
            )

        val input =
            EditText(
                requireContext()
            ).apply {
                setText(folder.name)
                setSelection(
                    folder.name.length
                )
                textSize = 12f
                typeface =
                    ResourcesCompat.getFont(
                        requireContext(),
                        R.font.poppins_regular
                    )

                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.nts_text
                    )
                )

                setHintTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.nts_text_secondary
                    )
                )

                isSingleLine = true

                background =
                    roundedBackground(
                        colorHex(
                            R.color.nts_surface
                        ),
                        16f,
                        colorHex(
                            R.color.nts_blue_line
                        )
                    )

                setPadding(
                    dp(14),
                    dp(12),
                    dp(14),
                    dp(12)
                )
            }

        sheet.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val actions =
            LinearLayout(
                requireContext()
            ).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.END
                setPadding(
                    0,
                    dp(14),
                    0,
                    0
                )
            }

        val cancel =
            TextView(
                requireContext()
            ).apply {
                text = "Cancel"
                textSize = 11.5f
                typeface =
                    ResourcesCompat.getFont(
                        requireContext(),
                        R.font.poppins_medium
                    )
                gravity =
                    Gravity.CENTER

                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.nts_text_secondary
                    )
                )

                setPadding(
                    dp(16),
                    dp(10),
                    dp(16),
                    dp(10)
                )

                setOnClickListener {
                    dialog.dismiss()
                }
            }

        val save =
            TextView(
                requireContext()
            ).apply {
                text = "Save"
                textSize = 11.5f
                typeface =
                    ResourcesCompat.getFont(
                        requireContext(),
                        R.font.poppins_medium
                    )
                gravity =
                    Gravity.CENTER

                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.nts_text
                    )
                )

                background =
                    roundedBackground(
                        colorHex(
                            R.color.nts_yellow
                        ),
                        16f,
                        colorHex(
                            R.color.nts_outline
                        )
                    )

                setPadding(
                    dp(18),
                    dp(10),
                    dp(18),
                    dp(10)
                )

                setOnClickListener {
                    val newName =
                        input.text
                            .toString()
                            .trim()

                    if (newName.isEmpty()) {
                        Toast.makeText(
                            context,
                            "Folder name cannot be empty",
                            Toast.LENGTH_SHORT
                        ).show()

                        return@setOnClickListener
                    }

                    val updatedFolder =
                        folder.copy(
                            name = newName
                        )

                    lifecycleScope.launch(
                        Dispatchers.IO
                    ) {
                        AppDatabase
                            .getDatabase(
                                requireContext()
                            )
                            .appDao()
                            .insertFolder(
                                updatedFolder
                            )

                        launch(
                            Dispatchers.Main
                        ) {
                            Toast.makeText(
                                context,
                                R.string.folder_updated,
                                Toast.LENGTH_SHORT
                            ).show()

                            dialog.dismiss()
                        }
                    }
                }
            }

        actions.addView(cancel)
        actions.addView(save)
        sheet.addView(actions)

        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun showDeleteFolderDialog(
        folder: Folder
    ) {
        val dialog =
            BottomSheetDialog(
                requireContext()
            )

        val sheet =
            createSheetContainer(
                title = "Delete folder?",
                subtitle =
                    "Delete \"${folder.name}\"? Notes inside it will remain available."
            )

        val actions =
            LinearLayout(
                requireContext()
            ).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.END
            }

        val cancel =
            TextView(
                requireContext()
            ).apply {
                text = "Cancel"
                textSize = 11.5f
                typeface =
                    ResourcesCompat.getFont(
                        requireContext(),
                        R.font.poppins_medium
                    )
                gravity =
                    Gravity.CENTER

                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.nts_text_secondary
                    )
                )

                setPadding(
                    dp(16),
                    dp(10),
                    dp(16),
                    dp(10)
                )

                setOnClickListener {
                    dialog.dismiss()
                }
            }

        val delete =
            TextView(
                requireContext()
            ).apply {
                text = "Delete"
                textSize = 11.5f
                typeface =
                    ResourcesCompat.getFont(
                        requireContext(),
                        R.font.poppins_medium
                    )
                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.WHITE
                )

                background =
                    roundedBackground(
                        "#D94B62",
                        16f
                    )

                setPadding(
                    dp(18),
                    dp(10),
                    dp(18),
                    dp(10)
                )

                setOnClickListener {
                    lifecycleScope.launch(
                        Dispatchers.IO
                    ) {
                        AppDatabase
                            .getDatabase(
                                requireContext()
                            )
                            .appDao()
                            .deleteFolder(folder)

                        launch(
                            Dispatchers.Main
                        ) {
                            Toast.makeText(
                                context,
                                R.string.folder_deleted,
                                Toast.LENGTH_SHORT
                            ).show()

                            dialog.dismiss()
                        }
                    }
                }
            }

        actions.addView(cancel)
        actions.addView(delete)
        sheet.addView(actions)

        dialog.setContentView(sheet)
        dialog.show()
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
            (activity as? MainActivity)?.openScan()
        }

        dialog.show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun colorHex(colorRes: Int): String {
        val color =
            ContextCompat.getColor(
                requireContext(),
                colorRes
            )

        return String.format(
            "#%06X",
            0xFFFFFF and color
        )
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