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
import androidx.viewpager2.widget.ViewPager2
import com.example.note2snap.R
import com.example.note2snap.adapters.RecentActivityAdapter
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Note
import com.example.note2snap.adapter.StackNoteTransformer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class HomeFragment : Fragment() {

    private val recentNotesList = mutableListOf<Note>()
    private lateinit var recentNotesAdapter: RecentActivityAdapter

    private var tvGreeting: TextView? = null
    private var tvGreetingSubtitle: TextView? = null
    private var tvDate: TextView? = null
    private var tvStatTotal: TextView? = null
    private var tvStatWeek: TextView? = null
    private var tvStatStreak: TextView? = null
    private var tvEmptyRecent: TextView? = null
    private var vpRecentNotes: ViewPager2? = null

    private var autoSwipeJob: Job? = null
    private val swipeInterval = 3.5.seconds

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
        vpRecentNotes = view.findViewById(R.id.vpRecentNotes)

        val cardScan = view.findViewById<CardView>(R.id.cardScan)
        val cardNotes = view.findViewById<CardView>(R.id.cardNotes)
        val cardFolders = view.findViewById<CardView>(R.id.cardFolders)

        updateHeaderAndDate()

        cardScan?.setOnClickListener {
            (activity as? MainActivity)?.openScan()
        }
        cardNotes?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(R.id.nav_notes)
        }
        cardFolders?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(R.id.nav_notes)
        }

        recentNotesAdapter = RecentActivityAdapter(
            notes = recentNotesList,
            onItemClick = { note ->
                val intent = Intent(context, PdfViewerActivity::class.java).apply {
                    putExtra("TITLE", note.title)
                    putExtra("CONTENT", note.content)
                    putExtra("IMAGE_PATH", note.imagePath)
                }
                startActivity(intent)
            }
        )

        vpRecentNotes?.apply {
            adapter = recentNotesAdapter

            // 1. Keep depth cards pre-rendered offscreen
            offscreenPageLimit = 3

            // 2. Attach 3D stack depth transformer
            setPageTransformer(
                StackNoteTransformer(
                    maxVisibleItems = 3,
                    scaleOffset = 0.08f,
                    verticalOffsetDp = 20f
                )
            )

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    super.onPageScrollStateChanged(state)
                    if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                        stopAutoSwipe()
                    } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                        startAutoSwipe()
                    }
                }
            })
        }

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

                tvStatTotal?.text = totalNotesCount.toString()

                val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                val thisWeekCount = allNotes.count { it.timestamp >= sevenDaysAgo }
                tvStatWeek?.text = thisWeekCount.toString()

                tvStatStreak?.text = getEvolvedNoteBadge(totalNotesCount)

                tvGreetingSubtitle?.text = if (thisWeekCount > 0) {
                    "You scanned $thisWeekCount notes this week!"
                } else {
                    "Ready to scan your notes today?"
                }

                val sortedRecent = allNotes.sortedByDescending { it.timestamp }.take(5)
                recentNotesAdapter.updateNotes(sortedRecent)

                if (sortedRecent.isEmpty()) {
                    vpRecentNotes?.visibility = View.GONE
                    tvEmptyRecent?.visibility = View.VISIBLE
                    stopAutoSwipe()
                } else {
                    vpRecentNotes?.visibility = View.VISIBLE
                    tvEmptyRecent?.visibility = View.GONE

                    if (vpRecentNotes?.currentItem == 0) {
                        val middleIndex = (Int.MAX_VALUE / 2) - ((Int.MAX_VALUE / 2) % sortedRecent.size)
                        vpRecentNotes?.setCurrentItem(middleIndex, false)
                    }

                    startAutoSwipe()
                }
            }
        }
    }

    private fun startAutoSwipe() {
        if (recentNotesList.size <= 1) return

        stopAutoSwipe()

        autoSwipeJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(swipeInterval)
                vpRecentNotes?.let { vp ->
                    if (recentNotesList.size > 1) {
                        vp.setCurrentItem(vp.currentItem + 1, true)
                    }
                }
            }
        }
    }

    private fun stopAutoSwipe() {
        autoSwipeJob?.cancel()
        autoSwipeJob = null
    }

    override fun onResume() {
        super.onResume()
        startAutoSwipe()
    }

    override fun onPause() {
        super.onPause()
        stopAutoSwipe()
    }

    private fun getEvolvedNoteBadge(noteCount: Int): String {
        return when (noteCount) {
            0 -> "❄️ 0 Notes"
            in 1..2 -> "🌱 $noteCount Notes"
            in 3..5 -> "🔥 $noteCount Notes"
            in 6..10 -> "⚡ $noteCount Notes"
            in 11..25 -> "🚀 $noteCount Notes"
            in 26..50 -> "💎 $noteCount Notes"
            else -> "👑 $noteCount Notes"
        }
    }
}