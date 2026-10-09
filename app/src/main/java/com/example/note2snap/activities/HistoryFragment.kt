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
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toColorInt
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
import org.json.JSONArray

class HistoryFragment : Fragment() {

    private var historyAdapter: HistoryAdapter? = null

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

        val rvHistory =
            view.findViewById<RecyclerView>(
                R.id.rvHistory
            )

        val llEmptyHistory =
            view.findViewById<LinearLayout>(
                R.id.llEmptyHistory
            )

        view.findViewById<View>(
            R.id.btnEmptyHistoryScan
        ).setOnClickListener {
            (
                    activity as?
                            MainActivity
                    )
                ?.openScan()
        }


        rvHistory.layoutManager =
            LinearLayoutManager(
                requireContext()
            )

        historyAdapter =
            HistoryAdapter(
                historyList =
                    emptyList(),

                onItemClick = {
                        item ->

                    lifecycleScope.launch(
                        Dispatchers.IO
                    ) {
                        val dao =
                            AppDatabase
                                .getDatabase(
                                    requireContext()
                                )
                                .appDao()

                        val existingNote =
                            if (
                                item.imagePath
                                    .isNotBlank()
                            ) {
                                dao.getNoteByPath(
                                    item.imagePath
                                )
                            } else {
                                null
                            }

                        val sourcePaths =
                            runCatching {
                                val array =
                                    JSONArray(
                                        item.sourceImagePathsJson
                                    )

                                List(
                                    array.length()
                                ) {
                                        index ->
                                    array.optString(
                                        index
                                    )
                                }
                                    .filter {
                                        it.isNotBlank()
                                    }
                            }.getOrDefault(
                                listOfNotNull(
                                    item.imagePath
                                        .takeIf {
                                            it.isNotBlank()
                                        }
                                )
                            )

                        val pageContents =
                            runCatching {
                                val array =
                                    JSONArray(
                                        item.pageContentsJson
                                    )

                                List(
                                    array.length()
                                ) {
                                        index ->
                                    array.optString(
                                        index
                                    )
                                }
                            }.getOrDefault(
                                emptyList()
                            )

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
                                        item.title
                                    )

                                    putExtra(
                                        "IMAGE_PATH",
                                        item.imagePath
                                    )

                                    if (
                                        existingNote ==
                                        null
                                    ) {
                                        pageContents
                                            .firstOrNull()
                                            ?.let {
                                                putExtra(
                                                    "CONTENT",
                                                    it
                                                )
                                            }

                                        putStringArrayListExtra(
                                            "IMAGE_PATHS",
                                            ArrayList(
                                                sourcePaths
                                            )
                                        )

                                        putStringArrayListExtra(
                                            "PAGE_CONTENTS",
                                            ArrayList(
                                                pageContents
                                            )
                                        )
                                    }
                                }

                            startActivity(
                                intent
                            )
                        }
                    }
                },

                onItemLongClick = { item ->
                    showOptionsDialog(
                        item
                    )
                },

                onSelectionChanged = {
                        count ->
                    updateHistorySelectionBar(
                        count
                    )
                }
            )

        rvHistory.adapter =
            historyAdapter

        view.findViewById<View>(
            R.id.btnHistorySelectAll
        ).setOnClickListener {
            historyAdapter?.selectAll()
        }

        view.findViewById<View>(
            R.id.btnHistoryCancelSelection
        ).setOnClickListener {
            historyAdapter?.clearSelection()
        }

        view.findViewById<View>(
            R.id.btnHistoryDeleteSelected
        ).setOnClickListener {
            showBulkDeleteHistoryDialog()
        }

        AppDatabase
            .getDatabase(
                requireContext()
            )
            .appDao()
            .getAllScanHistory()
            .observe(
                viewLifecycleOwner
            ) { historyList ->

                if (
                    historyList.isNullOrEmpty()
                ) {

                    llEmptyHistory.visibility =
                        View.VISIBLE

                    rvHistory.visibility =
                        View.GONE

                } else {

                    llEmptyHistory.visibility =
                        View.GONE

                    rvHistory.visibility =
                        View.VISIBLE

                    historyAdapter?.updateData(
                        historyList
                    )
                }
            }

        return view
    }

    private fun updateHistorySelectionBar(
        count: Int
    ) {
        val root =
            view ?: return

        val selectionBar =
            root.findViewById<View>(
                R.id.historySelectionBar
            )

        if (
            count > 0
        ) {
            selectionBar?.apply {
                visibility =
                    View.VISIBLE

                elevation =
                    dp(16)
                        .toFloat()

                translationZ =
                    dp(16)
                        .toFloat()

                bringToFront()
            }
        } else {
            selectionBar?.visibility =
                View.GONE
        }

        root.findViewById<TextView>(
            R.id.tvHistorySelectedCount
        )?.text =
            if (
                count == 1
            ) {
                "1 selected"
            } else {
                "$count selected"
            }
    }

    private fun showBulkDeleteHistoryDialog() {
        val selected =
            historyAdapter
                ?.selectedItems()
                .orEmpty()

        if (
            selected.isEmpty()
        ) {
            return
        }

        androidx.appcompat.app.AlertDialog
            .Builder(
                requireContext()
            )
            .setTitle(
                "Delete ${selected.size} history items?"
            )
            .setMessage(
                "This removes only the selected scan history entries. Saved Notes are not deleted."
            )
            .setNegativeButton(
                "Cancel",
                null
            )
            .setPositiveButton(
                "Delete"
            ) {
                    _,
                    _ ->

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
                            item ->
                        dao.deleteScanHistory(
                            item
                        )
                    }

                    withContext(
                        Dispatchers.Main
                    ) {
                        historyAdapter
                            ?.clearSelection()

                        view?.let {
                                anchorView ->
                            com.example.note2snap.utils
                                .Note2SnapNotice
                                .show(
                                    anchor =
                                        anchorView,
                                    title =
                                        "History deleted",
                                    message =
                                        "${selected.size} removed",
                                    symbol =
                                        "×"
                                )
                        }
                    }
                }
            }
            .show()
    }

    private fun showOptionsDialog(
        item: ScanHistory
    ) {
        val dialog =
            BottomSheetDialog(
                requireContext()
            )

        val sheet =
            createHistorySheet(
                title = "History options",
                subtitle = item.title
            )

        val card =
            LinearLayout(
                requireContext()
            ).apply {
                orientation =
                    LinearLayout.VERTICAL

                background =
                    roundedBackground(
                        colorHex(
                            R.color.nts_surface
                        ),
                        20f,
                        colorHex(
                            R.color.nts_blue_line
                        )
                    )

                setPadding(
                    dp(6),
                    dp(6),
                    dp(6),
                    dp(6)
                )
            }

        card.addView(
            createHistoryOptionRow(
                title = "Edit title",
                subtitle =
                    "Rename this scan and linked note",
                icon = "✎"
            ) {
                dialog.dismiss()
                showEditTitleDialog(
                    item
                )
            }
        )

        card.addView(
            createHistoryOptionRow(
                title = "Delete note",
                subtitle =
                    "Remove this scan from history",
                icon = "×",
                destructive = true
            ) {
                dialog.dismiss()
                confirmDeleteNote(
                    item
                )
            }
        )

        sheet.addView(card)
        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun showEditTitleDialog(
        item: ScanHistory
    ) {
        val dialog =
            BottomSheetDialog(
                requireContext()
            )

        val sheet =
            createHistorySheet(
                title = "Rename scan",
                subtitle =
                    "Update the title shown in History and Notes"
            )

        val input =
            EditText(
                requireContext()
            ).apply {
                setText(
                    item.title
                )
                setSelection(
                    item.title.length
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
                gravity = Gravity.CENTER
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
                gravity = Gravity.CENTER
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
                    val newTitle =
                        input.text
                            .toString()
                            .trim()

                    if (
                        newTitle.isEmpty()
                    ) {
                        Toast.makeText(
                            requireContext(),
                            "Title cannot be empty",
                            Toast.LENGTH_SHORT
                        ).show()

                        return@setOnClickListener
                    }

                    lifecycleScope.launch(
                        Dispatchers.IO
                    ) {
                        val dao =
                            AppDatabase
                                .getDatabase(
                                    requireContext()
                                )
                                .appDao()

                        dao.updateScanHistory(
                            item.copy(
                                title =
                                    newTitle
                            )
                        )

                        if (
                            !item.imagePath
                                .isNullOrEmpty()
                        ) {
                            dao.updateNoteTitleByPath(
                                item.imagePath,
                                newTitle
                            )
                        }

                        withContext(
                            Dispatchers.Main
                        ) {
                            Toast.makeText(
                                requireContext(),
                                "Title updated",
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

    private fun confirmDeleteNote(
        item: ScanHistory
    ) {
        val dialog =
            BottomSheetDialog(
                requireContext()
            )

        val sheet =
            createHistorySheet(
                title = "Delete scan?",
                subtitle =
                    "This will remove \"${item.title}\" from History."
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
                gravity = Gravity.CENTER
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
                gravity = Gravity.CENTER
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
                        val dao =
                            AppDatabase
                                .getDatabase(
                                    requireContext()
                                )
                                .appDao()

                        dao.deleteScanHistory(
                            item
                        )

                        if (
                            !item.imagePath
                                .isNullOrEmpty()
                        ) {
                            dao.deleteNoteByPath(
                                item.imagePath
                            )
                        }

                        withContext(
                            Dispatchers.Main
                        ) {
                            Toast.makeText(
                                requireContext(),
                                "History deleted",
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

    private fun createHistorySheet(
        title: String,
        subtitle: String
    ): LinearLayout {
        return LinearLayout(
            requireContext()
        ).apply {
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
                    colorHex(
                        R.color.nts_background
                    ),
                    28f
                )

            addView(
                View(
                    requireContext()
                ).apply {
                    background =
                        roundedBackground(
                            colorHex(
                                R.color.nts_blue_line
                            ),
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
                TextView(
                    requireContext()
                ).apply {
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
                TextView(
                    requireContext()
                ).apply {
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

    private fun createHistoryOptionRow(
        title: String,
        subtitle: String,
        icon: String,
        destructive: Boolean = false,
        action: () -> Unit
    ): View {
        return LinearLayout(
            requireContext()
        ).apply {
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

            isClickable = true
            isFocusable = true

            addView(
                TextView(
                    requireContext()
                ).apply {
                    text = icon
                    textSize = 17f
                    gravity =
                        Gravity.CENTER

                    setTextColor(
                        if (destructive) {
                            "#D94B62".toColorInt()
                        } else {
                            ContextCompat.getColor(
                                requireContext(),
                                R.color.nts_blue
                            )
                        }
                    )

                    background =
                        roundedBackground(
                            colorHex(
                                R.color.nts_surface_blue_soft
                            ),
                            14f
                        )
                },
                LinearLayout.LayoutParams(
                    dp(42),
                    dp(42)
                )
            )

            val labels =
                LinearLayout(
                    requireContext()
                ).apply {
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
                TextView(
                    requireContext()
                ).apply {
                    text = title
                    textSize = 12.5f
                    typeface =
                        ResourcesCompat.getFont(
                            requireContext(),
                            R.font.poppins_medium
                        )
                    setTextColor(
                        if (destructive) {
                            "#D94B62".toColorInt()
                        } else {
                            ContextCompat.getColor(
                                requireContext(),
                                R.color.nts_text
                            )
                        }
                    )
                }
            )

            labels.addView(
                TextView(
                    requireContext()
                ).apply {
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

            setOnClickListener {
                action()
            }
        }
    }

    private fun dp(
        value: Int
    ): Int {
        return (
                value *
                        resources
                            .displayMetrics
                            .density
                ).toInt()
    }

    private fun colorHex(
        colorRes: Int
    ): String {
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
            shape =
                GradientDrawable.RECTANGLE

            cornerRadius =
                radiusDp *
                        resources
                            .displayMetrics
                            .density

            setColor(
                Color.parseColor(
                    fillColor
                )
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
