package com.example.note2snap.activities

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.adapters.RecentScanAdapter
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Note
import com.example.note2snap.model.ScanHistory
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeFragment : Fragment() {

    private val recentScansList = mutableListOf<ScanHistory>()
    private var latestHistory = listOf<ScanHistory>()
    private var latestNotes = listOf<Note>()

    private lateinit var recentScanAdapter: RecentScanAdapter

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

        val view = inflater.inflate(
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

        recentScanAdapter = RecentScanAdapter(
            scans = recentScansList,
            onItemClick = { scan -> openRecentScan(scan) },
            onStarClick = { scan -> handleRecentStar(scan) },
            onMoreClick = { scan -> showRecentOptions(scan) }
        )

        rvRecentNotes?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = recentScanAdapter
            isNestedScrollingEnabled = false
        }

        observeSavedNotes()
        observeRecentScans()
        return view
    }

    private fun observeSavedNotes() {
        val safeContext = context ?: return
        val dao = AppDatabase.getDatabase(safeContext).appDao()

        lifecycleScope.launch {
            dao.getAllNotes().collectLatest { notes ->
                latestNotes = notes
                val totalNotesCount = notes.size

                tvStatTotal?.text = totalNotesCount.toString()

                val sevenDaysAgo = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
                val savedThisWeek = notes.count { it.timestamp >= sevenDaysAgo }

                tvStatWeek?.text = savedThisWeek.toString()

                val badge = getEvolvedNoteBadge(totalNotesCount)
                tvStatStreakEmoji?.text = badge.first
                tvStatStreak?.text = badge.second

                refreshRecentCards()
            }
        }
    }

    private fun observeRecentScans() {
        val safeContext = context ?: return
        AppDatabase.getDatabase(safeContext)
            .appDao()
            .getAllScanHistory()
            .observe(viewLifecycleOwner) { history ->

                latestHistory = history.orEmpty()
                val sevenDaysAgo = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
                val scansThisWeek = latestHistory.count { it.timestamp >= sevenDaysAgo }

                tvGreetingSubtitle?.text = if (scansThisWeek > 0) {
                    "You scanned $scansThisWeek this week!"
                } else {
                    "Ready to scan your notes today?"
                }

                refreshRecentCards()
            }
    }

    private fun refreshRecentCards() {
        if (!::recentScanAdapter.isInitialized) return

        val recent = latestHistory.sortedByDescending { it.timestamp }.take(3)

        val savedPaths = latestNotes.mapNotNull {
            it.imagePath?.takeIf { path -> path.isNotBlank() }
        }.toSet()

        val starredPaths = latestNotes.filter { it.isStarred }.mapNotNull {
            it.imagePath?.takeIf { path -> path.isNotBlank() }
        }.toSet()

        recentScanAdapter.updateData(
            newScans = recent,
            newSavedPaths = savedPaths,
            newStarredPaths = starredPaths
        )

        if (recent.isEmpty()) {
            rvRecentNotes?.visibility = View.GONE
            tvEmptyRecent?.visibility = View.VISIBLE
        } else {
            rvRecentNotes?.visibility = View.VISIBLE
            tvEmptyRecent?.visibility = View.GONE
        }
    }

    private fun handleRecentStar(scan: ScanHistory) {
        val safeContext = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(safeContext).appDao()
            val path = scan.imagePath

            val note = if (!path.isNullOrBlank()) {
                dao.getNoteByPath(path)
            } else null

            if (note == null) {
                withContext(Dispatchers.Main) {
                    val currentCtx = context ?: return@withContext
                    Toast.makeText(
                        currentCtx,
                        "Save this scan to Notes first.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }

            dao.updateNote(note.copy(isStarred = !note.isStarred))
        }
    }

    private fun showRecentOptions(scan: ScanHistory) {
        val safeContext = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(safeContext).appDao()

            val existingNote = if (!scan.imagePath.isNullOrBlank()) {
                dao.getNoteByPath(scan.imagePath)
            } else null

            withContext(Dispatchers.Main) {
                val currentCtx = context ?: return@withContext
                val dialog = BottomSheetDialog(currentCtx)
                val sheet = createRecentSheet(currentCtx, scan.title)

                val card = LinearLayout(currentCtx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundedBackground(
                        currentCtx,
                        colorHex(currentCtx, R.color.nts_surface),
                        20f,
                        colorHex(currentCtx, R.color.nts_blue_line)
                    )
                    setPadding(dp(currentCtx, 6), dp(currentCtx, 6), dp(currentCtx, 6), dp(currentCtx, 6))
                }

                card.addView(
                    createRecentOptionRow(
                        currentCtx,
                        title = if (existingNote == null) "Open scan" else "Open note",
                        subtitle = "View this captured note",
                        icon = "↗"
                    ) {
                        dialog.dismiss()
                        openRecentScan(scan)
                    }
                )

                card.addView(
                    createRecentOptionRow(
                        currentCtx,
                        title = if (existingNote == null) "Save to Notes" else if (existingNote.isStarred) "Unfavorite" else "Favorite",
                        subtitle = if (existingNote == null) "Keep this scan in Notes" else if (existingNote.isStarred) "Remove from favorites" else "Keep this note easy to find",
                        icon = if (existingNote == null) "+" else "★"
                    ) {
                        dialog.dismiss()
                        if (existingNote == null) {
                            openRecentScan(scan)
                        } else {
                            lifecycleScope.launch(Dispatchers.IO) {
                                dao.updateNote(existingNote.copy(isStarred = !existingNote.isStarred))
                            }
                        }
                    }
                )

                card.addView(
                    createRecentOptionRow(
                        currentCtx,
                        title = "Delete from History",
                        subtitle = "Remove this item from recent scans",
                        icon = "×",
                        destructive = true
                    ) {
                        dialog.dismiss()
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (!scan.imagePath.isNullOrBlank()) {
                                dao.deleteScanHistoryByPath(scan.imagePath)
                            } else {
                                dao.deleteScanHistory(scan)
                            }
                        }
                    }
                )

                sheet.addView(card)
                dialog.setContentView(sheet)
                dialog.show()
            }
        }
    }

    private fun createRecentSheet(ctx: Context, subtitle: String): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18), dp(ctx, 12), dp(ctx, 18), dp(ctx, 24))
            background = roundedBackground(ctx, colorHex(ctx, R.color.nts_background), 28f)

            addView(
                View(ctx).apply {
                    background = roundedBackground(ctx, colorHex(ctx, R.color.nts_blue_line), 99f)
                },
                LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(ctx, 16)
                }
            )

            addView(
                TextView(ctx).apply {
                    text = "Recent scan"
                    textSize = 20f
                    typeface = ResourcesCompat.getFont(ctx, R.font.apple_garamond_bold)
                    setTextColor(ContextCompat.getColor(ctx, R.color.nts_text))
                }
            )

            addView(
                TextView(ctx).apply {
                    text = subtitle
                    textSize = 10.5f
                    typeface = ResourcesCompat.getFont(ctx, R.font.poppins_regular)
                    setTextColor(ContextCompat.getColor(ctx, R.color.nts_text_secondary))
                    setPadding(0, dp(ctx, 3), 0, dp(ctx, 14))
                }
            )
        }
    }

    private fun createRecentOptionRow(
        ctx: Context,
        title: String,
        subtitle: String,
        icon: String,
        destructive: Boolean = false,
        action: () -> Unit
    ): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
            isClickable = true
            isFocusable = true

            addView(
                TextView(ctx).apply {
                    text = icon
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(
                        if (destructive) "#D94B62".toColorInt()
                        else ContextCompat.getColor(ctx, R.color.nts_blue)
                    )
                    background = roundedBackground(ctx, colorHex(ctx, R.color.nts_surface_blue_soft), 14f)
                },
                LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 42))
            )

            val labels = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 12), 0, 0, 0)
            }

            labels.addView(
                TextView(ctx).apply {
                    text = title
                    textSize = 12.5f
                    typeface = ResourcesCompat.getFont(ctx, R.font.poppins_medium)
                    setTextColor(
                        if (destructive) "#D94B62".toColorInt()
                        else ContextCompat.getColor(ctx, R.color.nts_text)
                    )
                }
            )

            labels.addView(
                TextView(ctx).apply {
                    text = subtitle
                    textSize = 9.5f
                    typeface = ResourcesCompat.getFont(ctx, R.font.poppins_regular)
                    setTextColor(ContextCompat.getColor(ctx, R.color.nts_text_secondary))
                }
            )

            addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { action() }
        }
    }

    private fun dp(ctx: Context, value: Int): Int {
        return (value * ctx.resources.displayMetrics.density).toInt()
    }

    private fun colorHex(ctx: Context, colorRes: Int): String {
        val color = ContextCompat.getColor(ctx, colorRes)
        return String.format("#%06X", 0xFFFFFF and color)
    }

    private fun roundedBackground(
        ctx: Context,
        fillColor: String,
        radiusDp: Float,
        strokeColor: String? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * ctx.resources.displayMetrics.density
            setColor(Color.parseColor(fillColor))
            if (strokeColor != null) {
                setStroke(dp(ctx, 1), Color.parseColor(strokeColor))
            }
        }
    }

    private fun openRecentScan(scan: ScanHistory) {
        val safeContext = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(safeContext).appDao()

            val existingNote = if (!scan.imagePath.isNullOrBlank()) {
                dao.getNoteByPath(scan.imagePath)
            } else null

            withContext(Dispatchers.Main) {
                val currentCtx = context ?: return@withContext
                val intent = Intent(currentCtx, PdfViewerActivity::class.java).apply {
                    putExtra("NOTE_ID", existingNote?.id ?: -1)
                    putExtra("TITLE", scan.title)
                    if (existingNote != null) {
                        putExtra("CONTENT", existingNote.content)
                    }
                    putExtra("IMAGE_PATH", scan.imagePath)
                }
                startActivity(intent)
            }
        }
    }

    private fun updateHeaderAndDate() {
        val calendar = Calendar.getInstance()

        tvDate?.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(calendar.time)

        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        tvGreeting?.text = when (hour) {
            in 5..11 -> "Good morning! Ready to study?"
            in 12..17 -> "Good afternoon! Ready to study?"
            in 18..21 -> "Good evening! Ready to study?"
            else -> "Late night study session?"
        }
    }

    private fun getEvolvedNoteBadge(noteCount: Int): Pair<String, String> {
        return when (noteCount) {
            0 -> "❄️" to "0 Notes"
            in 1..2 -> "🌱" to "$noteCount Notes"
            in 3..5 -> "🔥" to "$noteCount Notes"
            in 6..10 -> "⚡" to "$noteCount Notes"
            in 11..25 -> "🚀" to "$noteCount Notes"
            in 26..50 -> "💎" to "$noteCount Notes"
            else -> "👑" to "$noteCount Notes"
        }
    }
}