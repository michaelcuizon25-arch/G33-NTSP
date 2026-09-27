package com.example.note2snap.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.adapters.RecentScanAdapter
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Note
import com.example.note2snap.model.ScanHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeFragment : Fragment() {

    private val recentScansList =
        mutableListOf<ScanHistory>()

    private var latestHistory =
        listOf<ScanHistory>()

    private var latestNotes =
        listOf<Note>()

    private lateinit var recentScanAdapter:
            RecentScanAdapter

    private var tvGreeting: TextView? = null
    private var tvGreetingSubtitle: TextView? = null
    private var tvDate: TextView? = null
    private var tvStatTotal: TextView? = null
    private var tvStatWeek: TextView? = null
    private var tvStatStreak: TextView? = null
    private var tvStatStreakEmoji: TextView? = null
    private var tvEmptyRecent: TextView? = null
    private var rvRecentNotes: RecyclerView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        val view =
            inflater.inflate(
                R.layout.fragment_home,
                container,
                false
            )

        tvGreeting = view.findViewById(R.id.tvGreeting)
        tvGreetingSubtitle = view.findViewById(R.id.tvGreetingSubtitle)
        tvDate = view.findViewById(R.id.tvDate)
        tvStatTotal = view.findViewById(R.id.tvStatTotal)
        tvStatWeek = view.findViewById(R.id.tvStatWeek)
        tvStatStreak = view.findViewById(R.id.tvStatStreak)
        tvStatStreakEmoji = view.findViewById(R.id.tvStatStreakEmoji)
        tvEmptyRecent = view.findViewById(R.id.tvEmptyRecent)
        rvRecentNotes = view.findViewById(R.id.rvRecentNotes)

        val cardScan =
            view.findViewById<CardView>(R.id.cardScan)

        val cardNotes =
            view.findViewById<CardView>(R.id.cardNotes)

        val cardFolders =
            view.findViewById<CardView>(R.id.cardFolders)

        updateHeaderAndDate()

        cardScan?.setOnClickListener {
            (activity as? MainActivity)?.openScan()
        }

        cardNotes?.setOnClickListener {
            (activity as? MainActivity)
                ?.selectTab(R.id.nav_notes)
        }

        cardFolders?.setOnClickListener {
            (activity as? MainActivity)
                ?.selectTab(R.id.nav_notes)
        }

        recentScanAdapter =
            RecentScanAdapter(
                scans = recentScansList,

                onItemClick = { scan ->
                    openRecentScan(scan)
                },

                onStarClick = { scan ->
                    handleRecentStar(scan)
                },

                onMoreClick = { scan ->
                    showRecentOptions(scan)
                }
            )

        rvRecentNotes?.apply {
            layoutManager =
                LinearLayoutManager(requireContext())

            adapter = recentScanAdapter
            isNestedScrollingEnabled = false
        }

        observeSavedNotes()
        observeRecentScans()

        return view
    }

    private fun observeSavedNotes() {
        val dao =
            AppDatabase
                .getDatabase(requireContext())
                .appDao()

        lifecycleScope.launch {
            dao.getAllNotes()
                .collectLatest { notes ->

                    latestNotes = notes

                    val totalNotesCount = notes.size

                    tvStatTotal?.text =
                        totalNotesCount.toString()

                    val sevenDaysAgo =
                        System.currentTimeMillis() -
                                7L * 24L * 60L * 60L * 1000L

                    val savedThisWeek =
                        notes.count {
                            it.timestamp >= sevenDaysAgo
                        }

                    tvStatWeek?.text =
                        savedThisWeek.toString()

                    val badge =
                        getEvolvedNoteBadge(
                            totalNotesCount
                        )

                    tvStatStreakEmoji?.text =
                        badge.first

                    tvStatStreak?.text =
                        badge.second

                    refreshRecentCards()
                }
        }
    }

    private fun observeRecentScans() {
        AppDatabase
            .getDatabase(requireContext())
            .appDao()
            .getAllScanHistory()
            .observe(viewLifecycleOwner) { history ->

                latestHistory =
                    history.orEmpty()

                val sevenDaysAgo =
                    System.currentTimeMillis() -
                            7L * 24L * 60L * 60L * 1000L

                val scansThisWeek =
                    latestHistory.count {
                        it.timestamp >= sevenDaysAgo
                    }

                tvGreetingSubtitle?.text =
                    if (scansThisWeek > 0) {
                        "You scanned $scansThisWeek this week!"
                    } else {
                        "Ready to scan your notes today?"
                    }

                refreshRecentCards()
            }
    }

    private fun refreshRecentCards() {
        if (!::recentScanAdapter.isInitialized) return

        val recent =
            latestHistory
                .sortedByDescending {
                    it.timestamp
                }
                .take(3)

        val savedPaths =
            latestNotes
                .mapNotNull {
                    it.imagePath
                        ?.takeIf { path ->
                            path.isNotBlank()
                        }
                }
                .toSet()

        val starredPaths =
            latestNotes
                .filter {
                    it.isStarred
                }
                .mapNotNull {
                    it.imagePath
                        ?.takeIf { path ->
                            path.isNotBlank()
                        }
                }
                .toSet()

        recentScanAdapter.updateData(
            newScans = recent,
            newSavedPaths = savedPaths,
            newStarredPaths = starredPaths
        )

        if (recent.isEmpty()) {
            rvRecentNotes?.visibility =
                View.GONE

            tvEmptyRecent?.visibility =
                View.VISIBLE
        } else {
            rvRecentNotes?.visibility =
                View.VISIBLE

            tvEmptyRecent?.visibility =
                View.GONE
        }
    }

    private fun handleRecentStar(
        scan: ScanHistory
    ) {
        lifecycleScope.launch(
            Dispatchers.IO
        ) {
            val dao =
                AppDatabase
                    .getDatabase(requireContext())
                    .appDao()

            val path =
                scan.imagePath

            val note =
                if (!path.isNullOrBlank()) {
                    dao.getNoteByPath(path)
                } else {
                    null
                }

            if (note == null) {
                withContext(
                    Dispatchers.Main
                ) {
                    Toast.makeText(
                        requireContext(),
                        "Save this scan to Notes first.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            dao.updateNote(
                note.copy(
                    isStarred =
                        !note.isStarred
                )
            )
        }
    }

    private fun showRecentOptions(
        scan: ScanHistory
    ) {
        lifecycleScope.launch(
            Dispatchers.IO
        ) {
            val dao =
                AppDatabase
                    .getDatabase(requireContext())
                    .appDao()

            val existingNote =
                if (
                    !scan.imagePath
                        .isNullOrBlank()
                ) {
                    dao.getNoteByPath(
                        scan.imagePath
                    )
                } else {
                    null
                }

            withContext(
                Dispatchers.Main
            ) {
                val options =
                    if (existingNote == null) {
                        arrayOf(
                            "Open scan",
                            "Save to Notes",
                            "Delete from History"
                        )
                    } else {
                        arrayOf(
                            "Open note",
                            if (existingNote.isStarred)
                                "Unfavorite"
                            else
                                "Favorite",
                            "Delete from History"
                        )
                    }

                AlertDialog
                    .Builder(requireContext())
                    .setTitle(scan.title)
                    .setItems(options) { dialog, which ->

                        when (which) {
                            0 ->
                                openRecentScan(scan)

                            1 -> {
                                if (existingNote == null) {
                                    openRecentScan(scan)
                                } else {
                                    lifecycleScope.launch(
                                        Dispatchers.IO
                                    ) {
                                        dao.updateNote(
                                            existingNote.copy(
                                                isStarred =
                                                    !existingNote.isStarred
                                            )
                                        )
                                    }
                                }
                            }

                            2 -> {
                                lifecycleScope.launch(
                                    Dispatchers.IO
                                ) {
                                    if (
                                        !scan.imagePath
                                            .isNullOrBlank()
                                    ) {
                                        dao.deleteScanHistoryByPath(
                                            scan.imagePath
                                        )
                                    } else {
                                        dao.deleteScanHistory(
                                            scan
                                        )
                                    }
                                }
                            }
                        }

                        dialog.dismiss()
                    }
                    .show()
            }
        }
    }

    private fun openRecentScan(
        scan: ScanHistory
    ) {
        lifecycleScope.launch(
            Dispatchers.IO
        ) {
            val dao =
                AppDatabase
                    .getDatabase(requireContext())
                    .appDao()

            val existingNote =
                if (
                    !scan.imagePath
                        .isNullOrBlank()
                ) {
                    dao.getNoteByPath(
                        scan.imagePath
                    )
                } else {
                    null
                }

            withContext(
                Dispatchers.Main
            ) {
                val intent =
                    Intent(
                        requireContext(),
                        PdfViewerActivity::class.java
                    ).apply {

                        putExtra(
                            "NOTE_ID",
                            existingNote?.id
                                ?: -1
                        )

                        putExtra(
                            "TITLE",
                            scan.title
                        )

                        if (existingNote != null) {
                            putExtra(
                                "CONTENT",
                                existingNote.content
                            )
                        }

                        putExtra(
                            "IMAGE_PATH",
                            scan.imagePath
                        )
                    }

                startActivity(intent)
            }
        }
    }

    private fun updateHeaderAndDate() {
        val calendar =
            Calendar.getInstance()

        tvDate?.text =
            SimpleDateFormat(
                "EEEE, MMMM d",
                Locale.getDefault()
            ).format(calendar.time)

        val hour =
            calendar.get(
                Calendar.HOUR_OF_DAY
            )

        tvGreeting?.text =
            when (hour) {
                in 5..11 ->
                    "Good morning! Ready to study?"

                in 12..17 ->
                    "Good afternoon! Ready to study?"

                in 18..21 ->
                    "Good evening! Ready to study?"

                else ->
                    "Late night study session?"
            }
    }

    private fun getEvolvedNoteBadge(
        noteCount: Int
    ): Pair<String, String> {
        return when (noteCount) {
            0 ->
                "❄️" to "0 Notes"

            in 1..2 ->
                "🌱" to "$noteCount Notes"

            in 3..5 ->
                "🔥" to "$noteCount Notes"

            in 6..10 ->
                "⚡" to "$noteCount Notes"

            in 11..25 ->
                "🚀" to "$noteCount Notes"

            in 26..50 ->
                "💎" to "$noteCount Notes"

            else ->
                "👑" to "$noteCount Notes"
        }
    }
}
