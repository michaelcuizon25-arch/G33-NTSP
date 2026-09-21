package com.example.note2snap.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.adapter.NotesAdapter
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Note
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeFragment : Fragment() {

    private val recentNotesList = mutableListOf<Note>()
    private lateinit var recentNotesAdapter: NotesAdapter

    private var tvGreeting: TextView? = null
    private var tvGreetingSubtitle: TextView? = null
    private var tvDate: TextView? = null
    private var tvStatTotal: TextView? = null
    private var tvStatWeek: TextView? = null
    private var tvStatStreak: TextView? = null
    private var tvEmptyRecent: TextView? = null
    private var rvRecentNotes: RecyclerView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        tvGreeting = view.findViewById(R.id.tvGreeting)
        tvGreetingSubtitle = view.findViewById(R.id.tvGreetingSubtitle)
        tvDate = view.findViewById(R.id.tvDate)
        tvStatTotal = view.findViewById(R.id.tvStatTotal)
        tvStatWeek = view.findViewById(R.id.tvStatWeek)
        tvStatStreak = view.findViewById(R.id.tvStatStreak)
        tvEmptyRecent = view.findViewById(R.id.tvEmptyRecent)
        rvRecentNotes = view.findViewById(R.id.rvRecentNotes)

        val cardScan = view.findViewById<CardView>(R.id.cardScan)
        val cardNotes = view.findViewById<CardView>(R.id.cardNotes)
        val cardFolders = view.findViewById<CardView>(R.id.cardFolders)

        updateHeaderAndDate()

        // Tab switches synchronized with BottomNavigationView selection
        cardScan?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(R.id.nav_scan)
        }

        cardNotes?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(R.id.nav_notes)
        }

        cardFolders?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(R.id.nav_notes)
        }

        // Horizontal Recent Notes RecyclerView setup
        recentNotesAdapter = NotesAdapter(recentNotesList) { note ->
            val intent = Intent(context, PdfViewerActivity::class.java)
            intent.putExtra("TITLE", note.title)
            intent.putExtra("IMAGE_PATH", note.imagePath)
            startActivity(intent)
        }
        rvRecentNotes?.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvRecentNotes?.adapter = recentNotesAdapter

        observeDatabaseData()

        return view
    }

    private fun updateHeaderAndDate() {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        tvDate?.text = dateFormat.format(calendar.time)

        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        tvGreeting?.text = when (hour) {
            in 5..11 -> "Good morning! Ready to study?"
            in 12..17 -> "Good afternoon! Ready to study?"
            in 18..21 -> "Good evening! Ready to study?"
            else -> "Late night study session? 🦉"
        }
    }

    private fun observeDatabaseData() {
        val dao = AppDatabase.getDatabase(requireContext()).appDao()

        lifecycleScope.launch {
            dao.getAllNotes().collectLatest { allNotes ->
                val totalNotesCount = allNotes.size

                // Total created notes counter
                tvStatTotal?.text = totalNotesCount.toString()

                // Scans created in the last 7 days
                val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                val thisWeekCount = allNotes.count { it.timestamp >= sevenDaysAgo }
                tvStatWeek?.text = thisWeekCount.toString()

                // Dynamic evolved badge based on total created notes count
                tvStatStreak?.text = getEvolvedNoteBadge(totalNotesCount)

                tvGreetingSubtitle?.text = if (thisWeekCount > 0) {
                    "You scanned $thisWeekCount notes this week!"
                } else {
                    "Ready to scan your notes today?"
                }

                // Recent notes list (Top 5 latest notes)
                val sortedRecent = allNotes.sortedByDescending { it.timestamp }.take(5)
                recentNotesList.clear()
                recentNotesList.addAll(sortedRecent)
                recentNotesAdapter.notifyDataSetChanged()

                if (recentNotesList.isEmpty()) {
                    rvRecentNotes?.visibility = View.GONE
                    tvEmptyRecent?.visibility = View.VISIBLE
                } else {
                    rvRecentNotes?.visibility = View.VISIBLE
                    tvEmptyRecent?.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Evolves the streak icon badge tier based on total notes created.
     */
    private fun getEvolvedNoteBadge(noteCount: Int): String {
        return when {
            noteCount == 0 -> "❄️ 0 Notes"
            noteCount in 1..2 -> "🌱 $noteCount Notes"
            noteCount in 3..5 -> "🔥 $noteCount Notes"
            noteCount in 6..10 -> "⚡ $noteCount Notes"
            noteCount in 11..25 -> "🚀 $noteCount Notes"
            noteCount in 26..50 -> "💎 $noteCount Notes"
            else -> "👑 $noteCount Notes"
        }
    }
}