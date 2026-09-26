package com.example.note2snap.activities

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.note2snap.R
import com.example.note2snap.adapter.HistoryAdapter
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.ScanHistory
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryFragment : Fragment() {

    private lateinit var rvHistory: RecyclerView
    private lateinit var llEmptyHistory: LinearLayout
    private var tvHistoryCount: TextView? = null
    private var btnClearHistory: TextView? = null

    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view =
            inflater.inflate(
                R.layout.fragment_history,
                container,
                false
            )

        rvHistory =
            view.findViewById(R.id.rvHistory)

        llEmptyHistory =
            view.findViewById(R.id.llEmptyHistory)

        tvHistoryCount =
            view.findViewById(R.id.tvHistoryCount)

        btnClearHistory =
            view.findViewById(R.id.btnClearHistory)

        setupRecyclerView()
        observeHistory()

        btnClearHistory?.setOnClickListener {
            confirmClearHistory()
        }

        return view
    }

    private fun setupRecyclerView() {
        historyAdapter =
            HistoryAdapter(
                historyList = emptyList(),
                onItemClick = { item ->
                    openHistoryItem(item)
                },
                onMoreClick = { item ->
                    showOptionsBottomSheet(item)
                }
            )

        rvHistory.layoutManager =
            LinearLayoutManager(requireContext())

        rvHistory.adapter = historyAdapter
    }

    private fun observeHistory() {
        AppDatabase
            .getDatabase(requireContext())
            .appDao()
            .getAllScanHistory()
            .observe(viewLifecycleOwner) { historyList ->

                val items =
                    historyList ?: emptyList()

                historyAdapter.updateItems(items)

                tvHistoryCount?.text =
                    when (items.size) {
                        0 -> "No scans yet"
                        1 -> "1 recent scan"
                        else -> "${items.size} recent scans"
                    }

                if (items.isEmpty()) {
                    llEmptyHistory.visibility =
                        View.VISIBLE
                    rvHistory.visibility =
                        View.GONE
                    btnClearHistory?.visibility =
                        View.GONE
                } else {
                    llEmptyHistory.visibility =
                        View.GONE
                    rvHistory.visibility =
                        View.VISIBLE
                    btnClearHistory?.visibility =
                        View.VISIBLE
                }
            }
    }

    private fun openHistoryItem(
        item: ScanHistory
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao =
                AppDatabase
                    .getDatabase(requireContext())
                    .appDao()

            val matchingNote =
                dao.getNoteByTitle(item.title)

            withContext(Dispatchers.Main) {
                val intent =
                    Intent(
                        requireContext(),
                        PdfViewerActivity::class.java
                    ).apply {

                        putExtra(
                            "TITLE",
                            matchingNote?.title
                                ?: item.title
                        )

                        putExtra(
                            "IMAGE_PATH",
                            matchingNote?.imagePath
                                ?.takeIf { it.isNotBlank() }
                                ?: item.imagePath
                        )

                        if (matchingNote != null) {
                            putExtra(
                                "NOTE_ID",
                                matchingNote.id
                            )
                            putExtra(
                                "CONTENT",
                                matchingNote.content
                            )
                        }
                    }

                startActivity(intent)
            }
        }
    }

    private fun showOptionsBottomSheet(
        item: ScanHistory
    ) {
        val dialog =
            BottomSheetDialog(requireContext())

        val sheet =
            LinearLayout(requireContext()).apply {
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
                        "#FFF9FF",
                        28f
                    )
            }

        sheet.addView(
            View(requireContext()).apply {
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
            TextView(requireContext()).apply {
                text = "History options"
                textSize = 21f
                typeface =
                    android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(
                    "#171717".toColorInt()
                )
            }
        )

        sheet.addView(
            TextView(requireContext()).apply {
                text = item.title
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

        val card =
            LinearLayout(requireContext()).apply {
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

        card.addView(
            createOptionRow(
                iconRes =
                    R.drawable.ic_option_edit,
                title = "Rename scan",
                subtitle =
                    "Change the history title."
            ) {
                dialog.dismiss()
                showEditTitleDialog(item)
            }
        )

        card.addView(
            createOptionRow(
                iconRes =
                    R.drawable.ic_option_delete,
                title = "Delete from history",
                subtitle =
                    "Remove this scan from history only.",
                destructive = true
            ) {
                dialog.dismiss()
                confirmDeleteHistory(item)
            }
        )

        sheet.addView(card)

        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun createOptionRow(
        iconRes: Int,
        title: String,
        subtitle: String,
        destructive: Boolean = false,
        action: () -> Unit
    ): View {
        return LinearLayout(requireContext()).apply {
            orientation =
                LinearLayout.HORIZONTAL
            gravity =
                Gravity.CENTER_VERTICAL

            setPadding(
                dp(10),
                dp(11),
                dp(10),
                dp(11)
            )

            background =
                roundedBackground(
                    "#FFFFFF",
                    16f
                )

            isClickable = true
            isFocusable = true

            val iconBox =
                LinearLayout(requireContext()).apply {
                    gravity = Gravity.CENTER
                    background =
                        roundedBackground(
                            if (destructive) {
                                "#FFF0F3"
                            } else {
                                "#EEF2FF"
                            },
                            14f
                        )
                }

            iconBox.addView(
                android.widget.ImageView(
                    requireContext()
                ).apply {
                    setImageResource(iconRes)
                },
                LinearLayout.LayoutParams(
                    dp(22),
                    dp(22)
                )
            )

            addView(
                iconBox,
                LinearLayout.LayoutParams(
                    dp(44),
                    dp(44)
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
                    textSize = 13f
                    typeface =
                        android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(
                        if (destructive) {
                            "#D94B62".toColorInt()
                        } else {
                            "#171717".toColorInt()
                        }
                    )
                }
            )

            labels.addView(
                TextView(requireContext()).apply {
                    text = subtitle
                    textSize = 10f
                    setTextColor(
                        "#777780".toColorInt()
                    )
                    setPadding(
                        0,
                        dp(2),
                        0,
                        0
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

            setOnClickListener {
                action()
            }
        }
    }

    private fun showEditTitleDialog(
        item: ScanHistory
    ) {
        val input =
            EditText(requireContext()).apply {
                setText(item.title)
                setSelection(item.title.length)
                setPadding(
                    dp(18),
                    dp(14),
                    dp(18),
                    dp(14)
                )
            }

        val dialog =
            AlertDialog.Builder(requireContext())
                .setTitle("Rename scan")
                .setView(input)
                .setPositiveButton(
                    "Save",
                    null
                )
                .setNegativeButton(
                    "Cancel",
                    null
                )
                .create()

        dialog.setOnShowListener {
            dialog
                .getButton(
                    AlertDialog.BUTTON_POSITIVE
                )
                .setOnClickListener {

                    val newTitle =
                        input.text
                            .toString()
                            .trim()

                    if (newTitle.isEmpty()) {
                        input.error =
                            "Title cannot be empty"
                        return@setOnClickListener
                    }

                    lifecycleScope.launch(
                        Dispatchers.IO
                    ) {
                        AppDatabase
                            .getDatabase(
                                requireContext()
                            )
                            .appDao()
                            .updateScanHistory(
                                item.copy(
                                    title = newTitle
                                )
                            )

                        withContext(
                            Dispatchers.Main
                        ) {
                            Toast.makeText(
                                requireContext(),
                                "History renamed",
                                Toast.LENGTH_SHORT
                            ).show()

                            dialog.dismiss()
                        }
                    }
                }
        }

        dialog.show()
    }

    private fun confirmDeleteHistory(
        item: ScanHistory
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete from history?")
            .setMessage(
                "This removes \"${item.title}\" from History. " +
                        "Your saved note will not be deleted."
            )
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(
                    Dispatchers.IO
                ) {
                    AppDatabase
                        .getDatabase(
                            requireContext()
                        )
                        .appDao()
                        .deleteScanHistory(item)
                }
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear history?")
            .setMessage(
                "All scan history will be removed. " +
                        "Your saved notes will stay untouched."
            )
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch(
                    Dispatchers.IO
                ) {
                    AppDatabase
                        .getDatabase(
                            requireContext()
                        )
                        .appDao()
                        .clearHistory()

                    withContext(
                        Dispatchers.Main
                    ) {
                        Toast.makeText(
                            requireContext(),
                            "History cleared",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun dp(
        value: Int
    ): Int {
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
            shape =
                GradientDrawable.RECTANGLE

            cornerRadius =
                radiusDp *
                        resources.displayMetrics.density

            setColor(
                Color.parseColor(fillColor)
            )

            if (strokeColor != null) {
                setStroke(
                    dp(1),
                    Color.parseColor(
                        strokeColor
                    )
                )
            }
        }
    }
}
