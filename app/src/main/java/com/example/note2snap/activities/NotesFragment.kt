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
import kotlinx.coroutines.withContext

enum class SortType { NAME, TIME, SIZE, TYPE }
enum class FilterType { ALL, NOTES, FOLDERS, ARCHIVED }

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
    private var emptyStateContainer: View? = null
    private var tvEmptyNotesSubtitle: TextView? = null
    private var btnSort: ImageView? = null
    private var btnViewMode: ImageView? = null

    private var currentViewMode =
        NotesAdapter.DisplayMode.LIST

    private var chipAll: TextView? = null
    private var chipNotes: TextView? = null
    private var chipFolders: TextView? = null
    private var chipArchived: TextView? = null

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
        chipArchived = view.findViewById(R.id.chipArchived)

        val fabAdd = view.findViewById<FloatingActionButton>(R.id.fabAddNotes)
        val etSearch = view.findViewById<EditText>(R.id.etSearchNotes)

        tvResultCount = view.findViewById(R.id.tvResultCount)
        tvNotFound = view.findViewById(R.id.tvNotFound)

        emptyStateContainer =
            view.findViewById(
                R.id.emptyStateContainer
            )

        tvEmptyNotesSubtitle =
            view.findViewById(
                R.id.tvEmptyNotesSubtitle
            )
        btnSort = view.findViewById(R.id.btnSort)
        btnViewMode = view.findViewById(R.id.btnViewMode)

        tvNotFound?.setText(R.string.no_file_found)

        // Initialize adapters
        notesAdapter = NotesAdapter(
            notes = emptyList(),
            onItemClick = { note ->
                val intent =
                    Intent(
                        context,
                        PdfViewerActivity::class.java
                    ).apply {
                        /*
                         * NOTE_ID is required so PdfViewer reloads the full
                         * saved batch (all source pages + per-page content)
                         * from Room instead of reopening only page 1.
                         */
                        putExtra(
                            "NOTE_ID",
                            note.id
                        )

                        putExtra(
                            "TITLE",
                            note.title
                        )

                        putExtra(
                            "CONTENT",
                            note.content
                        )

                        putExtra(
                            "IMAGE_PATH",
                            note.imagePath
                        )
                    }
                startActivity(intent)
            },
            onMoveClick = { note -> showMoveNoteDialog(note) },
            onDeleteClick = { note -> deleteNote(note) },
            onToggleStarClick = { note -> toggleStarNote(note) },
            selectionEnabled =
                true,
            onSelectionChanged = {
                    count ->
                updateNotesSelectionBar(
                    count
                )
            }
        )
        rvNotes.layoutManager = LinearLayoutManager(context)
        rvNotes.adapter = notesAdapter

        view.findViewById<View>(
            R.id.btnNotesSelectAll
        ).setOnClickListener {
            notesAdapter.selectAll()
        }

        view.findViewById<View>(
            R.id.btnNotesCancelSelection
        ).setOnClickListener {
            notesAdapter.clearSelection()
        }

        view.findViewById<View>(
            R.id.btnNotesDeleteSelected
        ).setOnClickListener {
            showBulkDeleteNotesDialog()
        }

        folderAdapter = FolderAdapter(
            folderList = emptyList(),
            onItemClick = { folder -> handleFolderClick(folder) },
            onEditClick = { folder -> showEditFolderDialog(folder) },
            onArchiveClick = { folder -> toggleFolderArchive(folder) },
            onDeleteClick = { folder -> showDeleteFolderDialog(folder) }
        )

        rvFolders?.layoutManager =
            GridLayoutManager(
                context,
                2
            )

        rvFolders?.adapter =
            folderAdapter

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

        btnSort?.setImageResource(
            R.drawable.ic_sort_sliders
        )
        btnViewMode?.setImageResource(
            R.drawable.ic_view_list_clean
        )

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

    private fun showNotesNotice(
        title: String,
        message: String
    ) {
        view?.let {
            com.example.note2snap.utils
                .Note2SnapNotice
                .show(
                    anchor =
                        it,
                    title =
                        title,
                    message =
                        message,
                    symbol =
                        if (
                            title.contains(
                                "delete",
                                true
                            )
                        ) {
                            "×"
                        } else {
                            "✓"
                        }
                )
        }
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
                        showNotesNotice(
                            "Note moved",
                            "Main Screen"
                        )
                        dialog.dismiss()
                    }
                }
            }
        )

        masterFolderList
            .filter {
                folder ->
                folder.id !in getArchivedFolderIds()
            }
            .forEach {
                folder ->
            listCard.addView(
                createMoveFolderRow(
                    title = folder.name,
                    subtitle = "Move note into this folder",
                    isCurrent =
                        note.folderId ==
                                folder.id,
                    folderColor =
                        folder.colorHex
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
        folderColor: String? = null,
        onClick: () -> Unit
    ): View {
        return LinearLayout(
            requireContext()
        ).apply {
            orientation =
                LinearLayout.HORIZONTAL

            gravity =
                Gravity.CENTER_VERTICAL

            setPadding(
                dp(
                    10
                ),
                dp(
                    10
                ),
                dp(
                    10
                ),
                dp(
                    10
                )
            )

            background =
                roundedBackground(
                    if (
                        isCurrent
                    ) {
                        colorHex(
                            R.color.nts_blue_soft
                        )
                    } else {
                        colorHex(
                            R.color.nts_surface
                        )
                    },
                    16f
                )

            isClickable =
                true

            isFocusable =
                true

            setOnClickListener {
                onClick()
            }

            val icon =
                ImageView(
                    requireContext()
                ).apply {
                    setImageResource(
                        if (
                            folderColor !=
                            null
                        ) {
                            R.drawable.ic_folder_cute
                        } else {
                            R.drawable.ic_note_custom
                        }
                    )

                    val tint =
                        if (
                            folderColor !=
                            null
                        ) {
                            runCatching {
                                Color.parseColor(
                                    folderColor
                                )
                            }.getOrDefault(
                                ContextCompat.getColor(
                                    requireContext(),
                                    R.color.nts_blue
                                )
                            )
                        } else {
                            ContextCompat.getColor(
                                requireContext(),
                                R.color.nts_blue
                            )
                        }

                    androidx.core.widget
                        .ImageViewCompat
                        .setImageTintList(
                            this,
                            android.content.res
                                .ColorStateList
                                .valueOf(
                                    tint
                                )
                        )

                    setPadding(
                        dp(
                            8
                        ),
                        dp(
                            8
                        ),
                        dp(
                            8
                        ),
                        dp(
                            8
                        )
                    )

                    background =
                        roundedBackground(
                            colorHex(
                                R.color.nts_surface_blue_soft
                            ),
                            14f
                        )
                }

            addView(
                icon,
                LinearLayout.LayoutParams(
                    dp(
                        44
                    ),
                    dp(
                        44
                    )
                )
            )

            val textWrap =
                LinearLayout(
                    requireContext()
                ).apply {
                    orientation =
                        LinearLayout.VERTICAL

                    setPadding(
                        dp(
                            12
                        ),
                        0,
                        0,
                        0
                    )
                }

            textWrap.addView(
                TextView(
                    requireContext()
                ).apply {
                    text =
                        if (
                            isCurrent
                        ) {
                            "$title  •  Current"
                        } else {
                            title
                        }

                    textSize =
                        12f

                    typeface =
                        ResourcesCompat.getFont(
                            requireContext(),
                            R.font.poppins_semibold
                        )

                    setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            R.color.nts_text
                        )
                    )
                }
            )

            textWrap.addView(
                TextView(
                    requireContext()
                ).apply {
                    text =
                        subtitle

                    textSize =
                        9.5f

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
                textWrap,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams
                        .WRAP_CONTENT,
                    1f
                )
            )
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

        chipArchived?.setOnClickListener {
            currentFilter =
                FilterType.ARCHIVED

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

        chipArchived?.background =
            if (
                currentFilter ==
                    FilterType.ARCHIVED
            ) {
                activeBg
            } else {
                inactiveBg
            }

        chipArchived?.setTextColor(
            if (
                currentFilter ==
                    FilterType.ARCHIVED
            ) {
                activeTextColor
            } else {
                inactiveTextColor
            }
        )
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
        val archivedFolderIds =
            getArchivedFolderIds()

        val folderSource =
            if (
                currentFilter ==
                    FilterType.ARCHIVED
            ) {
                masterFolderList.filter {
                    folder ->
                    folder.id in archivedFolderIds
                }
            } else {
                masterFolderList.filter {
                    folder ->
                    folder.id !in archivedFolderIds
                }
            }

        var filteredFolders =
            if (
                searchQuery.isEmpty()
            ) {
                folderSource
            } else {
                folderSource.filter {
                    folder ->
                    folder.name.contains(
                        searchQuery,
                        ignoreCase = true
                    )
                }
            }

        filteredFolders = when (currentSort) {
            SortType.NAME -> filteredFolders.sortedBy { it.name.lowercase() }
            SortType.TIME -> filteredFolders.sortedByDescending { it.timestamp }
            SortType.SIZE -> filteredFolders.sortedBy { it.name.lowercase() }
            SortType.TYPE -> filteredFolders.sortedBy { it.name.lowercase() }
        }

        currentFilteredFolders =
            filteredFolders

        val folderCounts =
            masterNotesList
                .mapNotNull {
                    note ->
                    note.folderId
                }
                .groupingBy {
                    it
                }
                .eachCount()

        folderAdapter.updateFolderNoteCounts(
            folderCounts
        )

        folderAdapter.updateArchivedFolderIds(
            archivedFolderIds
        )

        folderAdapter.updateFolders(
            currentFilteredFolders
        )

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
        val showFoldersSection =
            (
                currentFilter ==
                    FilterType.ALL ||
                currentFilter ==
                    FilterType.FOLDERS ||
                currentFilter ==
                    FilterType.ARCHIVED
            ) &&
            currentFilteredFolders
                .isNotEmpty()

        val showNotesSection =
            (
                currentFilter ==
                    FilterType.ALL ||
                currentFilter ==
                    FilterType.NOTES
            ) &&
            currentFilter !=
                FilterType.ARCHIVED &&
            currentFilteredNotes
                .isNotEmpty()

        rvFolders?.visibility =
            if (
                showFoldersSection
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        tvFoldersLabel?.visibility =
            if (
                showFoldersSection
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        tvFoldersLabel?.text =
            if (
                currentFilter ==
                    FilterType.ARCHIVED
            ) {
                "Archived folders"
            } else {
                "Folders"
            }

        rvNotes.visibility =
            if (
                showNotesSection
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        tvNotesLabel?.visibility =
            if (
                showNotesSection
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        val visibleItemCount =
            (
                if (
                    showFoldersSection
                ) {
                    currentFilteredFolders.size
                } else {
                    0
                }
            ) +
            (
                if (
                    showNotesSection
                ) {
                    currentFilteredNotes.size
                } else {
                    0
                }
            )

        val hasAnySavedNote =
            masterNotesList
                .isNotEmpty()

        val hasAnyActiveFolder =
            masterFolderList
                .any {
                    it.id !in archivedFolderIds
                }

        val isSearching =
            searchQuery
                .isNotEmpty()

        val showSearchEmpty =
            isSearching &&
            visibleItemCount == 0

        val showNotesEmpty =
            !isSearching &&
            currentFilter ==
                FilterType.NOTES &&
            currentFilteredNotes
                .isEmpty()

        val showFoldersEmpty =
            !isSearching &&
            currentFilter ==
                FilterType.FOLDERS &&
            currentFilteredFolders
                .isEmpty()

        val showArchivedEmpty =
            !isSearching &&
            currentFilter ==
                FilterType.ARCHIVED &&
            currentFilteredFolders
                .isEmpty()

        val showAllCompletelyEmpty =
            !isSearching &&
            currentFilter ==
                FilterType.ALL &&
            !hasAnySavedNote &&
            !hasAnyActiveFolder

        val showAllNoNotes =
            !isSearching &&
            currentFilter ==
                FilterType.ALL &&
            currentFilteredNotes
                .isEmpty() &&
            hasAnyActiveFolder

        val showEmptyState =
            showSearchEmpty ||
            showNotesEmpty ||
            showFoldersEmpty ||
            showArchivedEmpty ||
            showAllCompletelyEmpty ||
            showAllNoNotes

        emptyStateContainer?.visibility =
            if (
                showEmptyState
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        when {
            showSearchEmpty -> {
                tvNotFound?.text =
                    "No matches found"

                tvEmptyNotesSubtitle?.text =
                    "Try another note or folder name."
            }

            showArchivedEmpty -> {
                tvNotFound?.text =
                    "No archived folders"

                tvEmptyNotesSubtitle?.text =
                    "Archived folders will appear here."
            }

            showFoldersEmpty -> {
                tvNotFound?.text =
                    "No folders yet"

                tvEmptyNotesSubtitle?.text =
                    "Create a folder to group your saved notes."
            }

            showNotesEmpty -> {
                tvNotFound?.text =
                    "No saved notes yet"

                tvEmptyNotesSubtitle?.text =
                    "Scans stay in History until you tap Save."
            }

            showAllNoNotes -> {
                tvNotFound?.text =
                    "No saved notes yet"

                tvEmptyNotesSubtitle?.text =
                    "Your folders are ready. Save a scan to add notes here."
            }

            showAllCompletelyEmpty -> {
                tvNotFound?.text =
                    "Nothing here yet"

                tvEmptyNotesSubtitle?.text =
                    "Scan something or create a folder to get started."
            }
        }

        tvResultCount?.visibility =
            if (
                isSearching &&
                visibleItemCount >
                0
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        if (
            tvResultCount?.visibility ==
            View.VISIBLE
        ) {
            tvResultCount?.text =
                getString(
                    R.string.found_items_count,
                    visibleItemCount
                )
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
                        currentSort == type,
                    iconRes =
                        when (type) {
                            SortType.NAME -> R.drawable.ic_sort_name
                            SortType.TIME -> R.drawable.ic_sort_time
                            SortType.SIZE -> R.drawable.ic_sort_size
                            SortType.TYPE -> R.drawable.ic_sort_type
                        }
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
                        currentViewMode == mode,
                    iconRes =
                        when (mode) {
                            NotesAdapter.DisplayMode.LIST -> R.drawable.ic_view_list_clean
                            NotesAdapter.DisplayMode.GRID -> R.drawable.ic_view_grid_clean
                            NotesAdapter.DisplayMode.COMPACT -> R.drawable.ic_view_compact_clean
                        }
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

        btnViewMode?.setImageResource(
            when (currentViewMode) {
                NotesAdapter.DisplayMode.LIST -> R.drawable.ic_view_list_clean
                NotesAdapter.DisplayMode.GRID -> R.drawable.ic_view_grid_clean
                NotesAdapter.DisplayMode.COMPACT -> R.drawable.ic_view_compact_clean
            }
        )

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
        iconRes: Int,
        action: () -> Unit
    ): View {
        return LinearLayout(requireContext()).apply {
            orientation =
                LinearLayout.HORIZONTAL
            gravity =
                Gravity.CENTER_VERTICAL
            setPadding(
                dp(10),
                dp(8),
                dp(10),
                dp(8)
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

            val iconCard =
                com.google.android.material.card.MaterialCardView(
                    requireContext()
                ).apply {
                    radius = dp(11).toFloat()
                    cardElevation = 0f
                    setCardBackgroundColor(
                        ContextCompat.getColor(
                            requireContext(),
                            if (selected) {
                                R.color.nts_surface_blue_soft
                            } else {
                                R.color.nts_background
                            }
                        )
                    )
                    strokeWidth = dp(1)
                    strokeColor =
                        ContextCompat.getColor(
                            requireContext(),
                            R.color.nts_blue_line
                        )

                    alpha =
                        if (
                            selected
                        ) {
                            1f
                        } else {
                            0.72f
                        }
                }

            val rowIcon =
                ImageView(
                    requireContext()
                ).apply {
                    setImageResource(
                        iconRes
                    )
                    setPadding(
                        dp(8),
                        dp(8),
                        dp(8),
                        dp(8)
                    )
                    androidx.core.widget.ImageViewCompat
                        .setImageTintList(
                            this,
                            android.content.res.ColorStateList.valueOf(
                                ContextCompat.getColor(
                                    requireContext(),
                                    if (selected) {
                                        R.color.nts_blue
                                    } else {
                                        R.color.nts_text_secondary
                                    }
                                )
                            )
                        )
                }

            iconCard.addView(
                rowIcon,
                ViewGroup.LayoutParams(
                    dp(38),
                    dp(38)
                )
            )

            addView(
                iconCard,
                LinearLayout.LayoutParams(
                    dp(38),
                    dp(38)
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

    private fun getArchivedFolderIds(): Set<Int> {
        return requireContext()
            .getSharedPreferences(
                "Note2SnapFolders",
                android.content.Context.MODE_PRIVATE
            )
            .getStringSet(
                "ARCHIVED_FOLDER_IDS",
                emptySet()
            )
            .orEmpty()
            .mapNotNull {
                value ->
                value.toIntOrNull()
            }
            .toSet()
    }

    private fun saveArchivedFolderIds(
        ids: Set<Int>
    ) {
        requireContext()
            .getSharedPreferences(
                "Note2SnapFolders",
                android.content.Context.MODE_PRIVATE
            )
            .edit()
            .putStringSet(
                "ARCHIVED_FOLDER_IDS",
                ids.map {
                    it.toString()
                }.toSet()
            )
            .apply()
    }

    private fun toggleFolderArchive(
        folder: Folder
    ) {
        val archived =
            getArchivedFolderIds()
                .toMutableSet()

        val restoring =
            folder.id in archived

        if (
            restoring
        ) {
            archived.remove(
                folder.id
            )
        } else {
            archived.add(
                folder.id
            )
        }

        saveArchivedFolderIds(
            archived
        )

        applySearchAndSort()

        showNotesNotice(
            if (
                restoring
            ) {
                "Folder restored"
            } else {
                "Folder archived"
            },
            folder.name
        )
    }

    private fun showEditFolderDialog(
        folder: Folder
    ) {
        val dialog =
            BottomSheetDialog(
                requireContext()
            )

        val sheet =
            layoutInflater.inflate(
                R.layout.bottom_sheet_rename,
                null,
                false
            )

        sheet.findViewById<TextView>(
            R.id.tvRenameSheetTitle
        ).text =
            "Rename folder"

        sheet.findViewById<TextView>(
            R.id.tvRenameSheetSubtitle
        ).text =
            "Keep folder names short and easy to scan."

        val input =
            sheet.findViewById<EditText>(
                R.id.etRenameValue
            )

        input.setText(folder.name)
        input.setSelection(folder.name.length)

        sheet.findViewById<View>(
            R.id.btnRenameCancel
        ).setOnClickListener {
            dialog.dismiss()
        }

        sheet.findViewById<View>(
            R.id.btnRenameSave
        ).setOnClickListener {
            val newName =
                input.text
                    .toString()
                    .trim()

            if (newName.isEmpty()) {
                input.error =
                    "Enter a folder name"
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                AppDatabase
                    .getDatabase(requireContext())
                    .appDao()
                    .insertFolder(
                        folder.copy(
                            name = newName
                        )
                    )

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                }
            }
        }

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

    private fun updateNotesSelectionBar(
        count: Int
    ) {
        val root =
            view ?: return

        root.findViewById<View>(
            R.id.notesSelectionBar
        )?.visibility =
            if (
                count > 0
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }

        root.findViewById<View>(
            R.id.fabAddNotes
        )?.visibility =
            if (count > 0) {
                View.GONE
            } else {
                View.VISIBLE
            }

        root.findViewById<TextView>(
            R.id.tvNotesSelectedCount
        )?.text =
            if (
                count == 1
            ) {
                "1 selected"
            } else {
                "$count selected"
            }
    }

    private fun showBulkDeleteNotesDialog() {

        val selected =
            notesAdapter.selectedNotes()

        if (
            selected.isEmpty()
        ) {
            return
        }

        val dialog =
            BottomSheetDialog(
                requireContext()
            )

        val sheet =
            createSheetContainer(
                title =
                    if (
                        selected.size == 1
                    ) {
                        "Delete this note?"
                    } else {
                        "Delete ${selected.size} notes?"
                    },

                subtitle =
                    "Their scan history will stay available."
            )

        val warningCard =
            LinearLayout(
                requireContext()
            ).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(12),
                    dp(12),
                    dp(12),
                    dp(12)
                )

                background =
                    roundedBackground(
                        "#FFF3F4",
                        18f,
                        "#F4C7CD"
                    )
            }

        warningCard.addView(
            ImageView(
                requireContext()
            ).apply {

                setImageResource(
                    R.drawable.ic_option_delete
                )

                androidx.core.widget.ImageViewCompat
                    .setImageTintList(
                        this,
                        android.content.res.ColorStateList
                            .valueOf(
                                Color.parseColor(
                                    "#C44F5E"
                                )
                            )
                    )
            },
            LinearLayout.LayoutParams(
                dp(24),
                dp(24)
            )
        )

        warningCard.addView(
            TextView(
                requireContext()
            ).apply {

                text =
                    "This removes only the saved note${if (selected.size > 1) "s" else ""} from Notes."

                textSize =
                    10f

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
                    dp(10),
                    0,
                    0,
                    0
                )
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        sheet.addView(
            warningCard
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

                text =
                    "Cancel"

                gravity =
                    Gravity.CENTER

                textSize =
                    11.5f

                typeface =
                    ResourcesCompat.getFont(
                        requireContext(),
                        R.font.poppins_medium
                    )

                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.nts_text_secondary
                    )
                )

                setPadding(
                    dp(18),
                    dp(11),
                    dp(18),
                    dp(11)
                )

                setOnClickListener {
                    dialog.dismiss()
                }
            }

        val delete =
            TextView(
                requireContext()
            ).apply {

                text =
                    "Delete"

                gravity =
                    Gravity.CENTER

                textSize =
                    11.5f

                typeface =
                    ResourcesCompat.getFont(
                        requireContext(),
                        R.font.poppins_semibold
                    )

                setTextColor(
                    Color.WHITE
                )

                background =
                    roundedBackground(
                        "#C94F5D",
                        16f
                    )

                setPadding(
                    dp(20),
                    dp(11),
                    dp(20),
                    dp(11)
                )

                setOnClickListener {

                    lifecycleScope.launch(
                        Dispatchers.IO
                    ) {

                        val dao =
                            AppDatabase
                                .getDatabase(
                                    requireContext()
                                )
                                .appDao()

                        selected.forEach {
                                note ->

                            dao.deleteNote(
                                note
                            )
                        }

                        withContext(
                            Dispatchers.Main
                        ) {

                            notesAdapter
                                .clearSelection()

                            dialog.dismiss()

                            showNotesNotice(
                                "Notes deleted",
                                "${selected.size} removed"
                            )
                        }
                    }
                }
            }

        actions.addView(
            cancel
        )

        actions.addView(
            delete
        )

        sheet.addView(
            actions
        )

        dialog.setContentView(
            sheet
        )

        dialog.show()
    }

    private fun deleteNote(note: Note) {
        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.getDatabase(requireContext()).appDao().deleteNote(note)
            launch(Dispatchers.Main) {
                showNotesNotice(
                    "Note deleted",
                    "Removed from Notes"
                )
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