package com.example.note2snap.activities

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.TextPaint
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.example.note2snap.R
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Note
import com.example.note2snap.utils.DocxExporter
import com.example.note2snap.utils.DrawingView
import com.example.note2snap.utils.ToolMode
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfViewerActivity : AppCompatActivity() {

    private var currentNoteId: Int = -1
    private var currentNote: Note? = null
    private var currentTitle: String = "Untitled Note"
    private var currentRawContent: String = ""
    private var currentImagePath: String? = null

    private var isEditMode = false
    private var activeTool: ToolMode = ToolMode.NONE
    private var currentPage = 1
    private var totalPages = 1
    private var isTextExpanded = false

    // Rich block editor helpers
    private val toggleContentMarker = "\u2063"
    private val expandedToggleBlocks = mutableSetOf<Int>()
    private var editorWatcherAttached = false
    private var formattingEditorText = false

    private val createPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { writePdfToUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)

        currentNoteId = intent.getIntExtra("NOTE_ID", -1)
        currentTitle = intent.getStringExtra("TITLE") ?: "Untitled Note"
        currentImagePath = sanitizeFilePath(intent.getStringExtra("IMAGE_PATH"))
        val directContent = intent.getStringExtra("CONTENT")

        setupHeaderAndMetadata()
        setupToolRibbon()
        setupBottomActions()
        setupPageNavigation()
        setupExtractedTextExpansion()

        if (!directContent.isNullOrEmpty()) {
            currentRawContent = sanitizeOcrText(directContent)
            renderContent(currentRawContent)
        } else {
            fetchNoteFromDatabase()
        }
    }

    /**
     * Converts file:// URIs or raw file paths into clean filesystem paths.
     */
    private fun sanitizeFilePath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("file://")) {
            Uri.parse(path).path
        } else {
            path
        }
    }

    /**
     * Cleans OCR bullet artifacts (e.g. "oLeading Lines" -> "• Leading Lines")
     * and standardizes line-starting bullet characters.
     */
    private fun sanitizeOcrText(text: String): String {
        return text.lines().joinToString("\n") { line ->
            var trimmed = line.trim()
            if (trimmed.matches(Regex("^[oO0][A-Z].*"))) {
                trimmed = "• " + trimmed.substring(1)
            } else if (trimmed.matches(Regex("^[oO0]\\s+[A-Z].*"))) {
                trimmed = "• " + trimmed.substring(1).trimStart()
            } else if (trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("· ")) {
                trimmed = "• " + trimmed.substring(2)
            }
            trimmed
        }
    }

    private fun setupHeaderAndMetadata() {
        val tvTitle = findViewById<TextView>(R.id.tvPdfTitle)
        tvTitle?.text = currentTitle

        tvTitle?.setOnClickListener {
            showRenameDialog()
        }

        val currentDate = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()).format(Date())
        findViewById<TextView>(R.id.tvPdfDate)?.text = currentDate

        findViewById<View>(R.id.btnPdfBack)?.setOnClickListener { finish() }

        findViewById<View>(R.id.btnPdfMoreOptions)?.setOnClickListener { view ->
            showOptionsMenu(view)
        }
    }

    private fun setupToolRibbon() {
        val drawingView = findViewById<DrawingView>(R.id.drawingView)
        activeTool = ToolMode.NONE
        drawingView?.setTool(ToolMode.NONE)

        findViewById<View>(R.id.btnToolText)?.setOnClickListener {
            showAddBlockSheet()
        }

        findViewById<View>(R.id.btnToolShare)?.setOnClickListener {
            shareDocument()
        }

        findViewById<View>(R.id.btnToolPen)?.setOnClickListener {
            if (activeTool == ToolMode.PEN) {
                activeTool = ToolMode.NONE
                drawingView?.setTool(ToolMode.NONE)
                showToolToast("Pen Off")
            } else {
                activeTool = ToolMode.PEN
                drawingView?.setTool(ToolMode.PEN)
                showColorPickerDialog(isHighlighter = false)
            }
        }

        findViewById<View>(R.id.btnToolHighlighter)?.setOnClickListener {
            if (activeTool == ToolMode.HIGHLIGHTER) {
                activeTool = ToolMode.NONE
                drawingView?.setTool(ToolMode.NONE)
                showToolToast("Highlighter Off")
            } else {
                activeTool = ToolMode.HIGHLIGHTER
                drawingView?.setTool(ToolMode.HIGHLIGHTER)
                showColorPickerDialog(isHighlighter = true)
            }
        }

        findViewById<View>(R.id.btnToolEraser)?.setOnClickListener {
            if (activeTool == ToolMode.ERASER) {
                activeTool = ToolMode.NONE
                drawingView?.setTool(ToolMode.NONE)
                showToolToast("Eraser Off")
            } else {
                activeTool = ToolMode.ERASER
                drawingView?.setTool(ToolMode.ERASER)
                showToolToast("Eraser Active")
            }
        }

        findViewById<View>(R.id.btnToolShapes)?.setOnClickListener { showToolToast("Shape recognition active") }
        findViewById<View>(R.id.btnToolMic)?.setOnClickListener { showToolToast("Voice note recording") }

        findViewById<View>(R.id.btnToolUndo)?.setOnClickListener {
            drawingView?.undo()
            showToolToast("Undo")
        }

        findViewById<View>(R.id.btnToolRedo)?.setOnClickListener {
            drawingView?.redo()
            showToolToast("Redo")
        }
    }


    /**
     * Shows a Note2Snap-styled "Add block" menu when the Text tool is tapped.
     * This keeps the existing text editor and save/export flow intact.
     */
    private fun showAddBlockSheet() {
        val dialog = BottomSheetDialog(this)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(24))
            background = roundedBackground("#FFF9FF", 28f)
        }

        val handle = View(this).apply {
            background = roundedBackground("#D7D8DE", 99f)
        }

        sheet.addView(
            handle,
            LinearLayout.LayoutParams(dp(44), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
        )

        val addBlockLabel = TextView(this).apply {
            text = "+  Add block"
            textSize = 13f
            setTextColor("#5A7FDB".toColorInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = roundedBackground("#FFFFFF", 18f, "#E7E8EE")
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }

        sheet.addView(
            addBlockLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )

        val optionsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = roundedBackground("#FFFFFF", 22f, "#ECECF2")
            elevation = dp(3).toFloat()
        }

        optionsCard.addView(
            createBlockOption(
                icon = "T",
                title = "Heading",
                description = "Add a section title"
            ) {
                dialog.dismiss()
                insertTextBlock(BlockType.HEADING)
            }
        )

        optionsCard.addView(dividerView())

        optionsCard.addView(
            createBlockOption(
                icon = "☷",
                title = "Bullet list",
                description = "Start a list of key points"
            ) {
                dialog.dismiss()
                insertTextBlock(BlockType.BULLET)
            }
        )

        optionsCard.addView(dividerView())

        optionsCard.addView(
            createBlockOption(
                icon = "›",
                title = "Toggle",
                description = "Add a collapsible-style section"
            ) {
                dialog.dismiss()
                insertTextBlock(BlockType.TOGGLE)
            }
        )

        optionsCard.addView(dividerView())

        optionsCard.addView(
            createBlockOption(
                icon = "¶",
                title = "Normal text",
                description = "Add a regular paragraph"
            ) {
                dialog.dismiss()
                insertTextBlock(BlockType.NORMAL)
            }
        )

        sheet.addView(
            optionsCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        dialog.setContentView(sheet)
        dialog.show()
    }

    private enum class BlockType {
        HEADING,
        BULLET,
        TOGGLE,
        NORMAL
    }

    private fun insertTextBlock(type: BlockType) {
        ensureInlineEditMode()

        val editor = findViewById<EditText>(R.id.etInlineEditor) ?: return
        val editable = editor.text ?: return

        val cursor = editor.selectionStart.coerceAtLeast(0)

        val prefix =
            if (cursor > 0 && editable[cursor - 1] != '\n') "\n" else ""

        if (prefix.isNotEmpty()) {
            editable.insert(cursor, prefix)
        }

        val start = cursor + prefix.length

        when (type) {
            BlockType.HEADING -> {
                val headingText = "Heading"
                editable.insert(start, headingText)

                val end = start + headingText.length

                editable.setSpan(
                    StyleSpan(android.graphics.Typeface.BOLD),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                editable.setSpan(
                    RelativeSizeSpan(1.35f),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                editable.setSpan(
                    ForegroundColorSpan("#171717".toColorInt()),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Highlight placeholder so typing replaces "Heading".
                editor.setSelection(start, end)
            }

            BlockType.BULLET -> {
                val bulletText = "• "
                editable.insert(start, bulletText)
                editor.setSelection(start + bulletText.length)
            }

            BlockType.TOGGLE -> {
                val titleText = "▸ Toggle"
                val contentPlaceholder = "Type toggle content"
                val block =
                    "$titleText\n$toggleContentMarker$contentPlaceholder"

                editable.insert(start, block)

                val titleEnd = start + titleText.length

                editable.setSpan(
                    StyleSpan(android.graphics.Typeface.BOLD),
                    start,
                    titleEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                editable.setSpan(
                    ForegroundColorSpan("#5A7FDB".toColorInt()),
                    start,
                    start + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                val contentStart =
                    start +
                            titleText.length +
                            1 +
                            toggleContentMarker.length

                val contentEnd =
                    contentStart +
                            contentPlaceholder.length

                // Highlight placeholder so typing replaces it.
                editor.setSelection(
                    contentStart,
                    contentEnd
                )
            }

            BlockType.NORMAL -> {
                editor.setSelection(start)
            }
        }

        attachEditorAutoFormatting(editor)

        editor.requestFocus()

        val imm =
            getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        imm.showSoftInput(
            editor,
            InputMethodManager.SHOW_IMPLICIT
        )
    }

    private fun ensureInlineEditMode() {
        if (!isEditMode) {
            toggleInlineEditMode()
        }
    }

    /**
     * Automatically continues bullet lists when Enter is pressed.
     * Toggle content lines also keep their invisible marker so they can
     * collapse properly in view mode.
     */
    private fun attachEditorAutoFormatting(editor: EditText) {
        if (editorWatcherAttached) return

        editor.addTextChangedListener(
            object : TextWatcher {

                private var insertedNewlineAt = -1
                private var beforeCount = 0
                private var addedCount = 0

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                    insertedNewlineAt = start
                    beforeCount = count
                    addedCount = after
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    insertedNewlineAt = start
                    beforeCount = before
                    addedCount = count
                }

                override fun afterTextChanged(editable: Editable?) {
                    if (formattingEditorText) return
                    val value = editable ?: return

                    // React only to a single newly inserted newline.
                    if (
                        beforeCount != 0 ||
                        addedCount != 1 ||
                        insertedNewlineAt < 0 ||
                        insertedNewlineAt >= value.length ||
                        value[insertedNewlineAt] != '\n'
                    ) {
                        return
                    }

                    val newlinePos = insertedNewlineAt

                    val previousBreak =
                        if (newlinePos <= 0) {
                            -1
                        } else {
                            value.lastIndexOf(
                                '\n',
                                newlinePos - 1
                            )
                        }

                    val previousLineStart =
                        previousBreak + 1

                    val previousLine =
                        value.substring(
                            previousLineStart,
                            newlinePos
                        )

                    formattingEditorText = true

                    try {
                        when {
                            previousLine == "• " ||
                                    previousLine == "•" -> {
                                // Pressing Enter on an empty bullet exits the list.
                                val deleteEnd =
                                    newlinePos.coerceAtMost(
                                        value.length
                                    )

                                value.delete(
                                    previousLineStart,
                                    deleteEnd
                                )

                                val newCursor =
                                    previousLineStart.coerceAtMost(
                                        value.length
                                    )

                                editor.setSelection(newCursor)
                            }

                            previousLine.startsWith("• ") -> {
                                // Continue the bullet list automatically.
                                val insertAt =
                                    (newlinePos + 1)
                                        .coerceAtMost(
                                            value.length
                                        )

                                value.insert(
                                    insertAt,
                                    "• "
                                )

                                editor.setSelection(
                                    (insertAt + 2)
                                        .coerceAtMost(
                                            value.length
                                        )
                                )
                            }

                            previousLine.startsWith(toggleContentMarker) -> {
                                // Continue multi-line toggle content.
                                val insertAt =
                                    (newlinePos + 1)
                                        .coerceAtMost(
                                            value.length
                                        )

                                value.insert(
                                    insertAt,
                                    toggleContentMarker
                                )

                                editor.setSelection(
                                    (insertAt + toggleContentMarker.length)
                                        .coerceAtMost(
                                            value.length
                                        )
                                )
                            }
                        }
                    } finally {
                        formattingEditorText = false
                    }
                }
            }
        )

        editorWatcherAttached = true
    }

    private fun createBlockOption(
        icon: String,
        title: String,
        description: String,
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground("#FFFFFF", 16f)

            val iconView = TextView(this@PdfViewerActivity).apply {
                text = icon
                gravity = Gravity.CENTER
                textSize = 17f
                setTextColor("#5A7FDB".toColorInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = roundedBackground("#EEF2FF", 14f)
            }

            addView(
                iconView,
                LinearLayout.LayoutParams(dp(42), dp(42))
            )

            val labels = LinearLayout(this@PdfViewerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }

            labels.addView(
                TextView(this@PdfViewerActivity).apply {
                    text = title
                    textSize = 14f
                    setTextColor("#171717".toColorInt())
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            )

            labels.addView(
                TextView(this@PdfViewerActivity).apply {
                    text = description
                    textSize = 11f
                    setTextColor("#777780".toColorInt())
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

            setOnClickListener { onClick() }
        }
    }

    private fun dividerView(): View {
        return View(this).apply {
            setBackgroundColor("#ECECF2".toColorInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                marginStart = dp(62)
                marginEnd = dp(8)
            }
        }
    }

    private fun roundedBackground(
        fillColor: String,
        radiusDp: Float,
        strokeColor: String? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setColor(fillColor.toColorInt())

            if (strokeColor != null) {
                setStroke(
                    dp(1),
                    strokeColor.toColorInt()
                )
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun showColorPickerDialog(isHighlighter: Boolean) {
        val drawingView = findViewById<DrawingView>(R.id.drawingView) ?: return
        val dialog = BottomSheetDialog(this)

        val names = if (isHighlighter) {
            arrayOf("Yellow", "Mint", "Sky", "Pink", "Orange")
        } else {
            arrayOf("Black", "Blue", "Red", "Green", "Purple")
        }

        val colors = if (isHighlighter) {
            intArrayOf(
                "#FFE56B".toColorInt(),
                "#8EE5B5".toColorInt(),
                "#8FD3FF".toColorInt(),
                "#F5A8D0".toColorInt(),
                "#FFB56B".toColorInt()
            )
        } else {
            intArrayOf(
                "#171717".toColorInt(),
                "#5A7FDB".toColorInt(),
                "#D94B62".toColorInt(),
                "#37A66B".toColorInt(),
                "#8A63C7".toColorInt()
            )
        }

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(24))
            background = roundedBackground("#FFF9FF", 28f)
        }

        sheet.addView(
            View(this).apply {
                background = roundedBackground("#D7D4DC", 3f)
            },
            LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            }
        )

        sheet.addView(
            TextView(this).apply {
                text = if (isHighlighter) "Highlighter color" else "Pen color"
                textSize = 21f
                setTextColor("#171717".toColorInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        )

        sheet.addView(
            TextView(this).apply {
                text = if (isHighlighter) {
                    "Choose a soft color for highlighting."
                } else {
                    "Choose your drawing color."
                }
                textSize = 11f
                setTextColor("#777780".toColorInt())
                setPadding(0, dp(4), 0, dp(18))
            }
        )

        val swatchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        colors.forEachIndexed { index, color ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(5), 0, dp(5), 0)

                val swatch = View(this@PdfViewerActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke(dp(2), "#FFFFFF".toColorInt())
                    }
                    elevation = dp(2).toFloat()
                }

                addView(
                    swatch,
                    LinearLayout.LayoutParams(dp(46), dp(46))
                )

                addView(
                    TextView(this@PdfViewerActivity).apply {
                        text = names[index]
                        textSize = 9f
                        gravity = Gravity.CENTER
                        setTextColor("#5F5F68".toColorInt())
                        setPadding(0, dp(7), 0, 0)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )

                setOnClickListener {
                    if (isHighlighter) {
                        drawingView.setHighlighterColor(color)
                        activeTool = ToolMode.HIGHLIGHTER
                    } else {
                        drawingView.setPenColor(color)
                        activeTool = ToolMode.PEN
                    }

                    showToolToast(
                        if (isHighlighter) {
                            "Highlighter: ${names[index]}"
                        } else {
                            "Pen: ${names[index]}"
                        }
                    )
                    dialog.dismiss()
                }
            }

            swatchRow.addView(
                item,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        sheet.addView(swatchRow)

        val cancel = TextView(this).apply {
            text = "Cancel"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor("#777780".toColorInt())
            setPadding(0, dp(18), 0, dp(2))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        sheet.addView(cancel)

        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun setupBottomActions() {
        findViewById<View>(R.id.btnActionSaveNotes)?.setOnClickListener {
            if (isEditMode) {
                toggleInlineEditMode()
            } else {
                saveNoteToDatabase()
            }
        }

        findViewById<View>(R.id.btnActionDownload)?.setOnClickListener {
            val sanitizedFileName = currentTitle.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            createPdfLauncher.launch("$sanitizedFileName.pdf")
        }

        findViewById<View>(R.id.btnActionShare)?.setOnClickListener {
            shareDocument()
        }
    }

    private fun setupPageNavigation() {
        val btnPageUp = findViewById<View>(R.id.btnPageUp)
        val btnPageDown = findViewById<View>(R.id.btnPageDown)
        val scrollView = findViewById<ScrollView>(R.id.scrollViewContent)

        updatePageIndicator()

        scrollView?.setOnScrollChangeListener { v: View, _: Int, scrollY: Int, _: Int, _: Int ->
            val childView = (v as? ScrollView)?.getChildAt(0)
            if (childView != null && v.height > 0) {
                val totalContentHeight = childView.height
                val viewportHeight = v.height

                totalPages = (totalContentHeight / viewportHeight.toFloat()).toInt().coerceAtLeast(1)
                currentPage = ((scrollY / viewportHeight.toFloat()) + 1).toInt().coerceIn(1, totalPages)
                updatePageIndicator()
            }
        }

        btnPageUp?.setOnClickListener {
            if (currentPage > 1) {
                currentPage--
                updatePageIndicator()
                scrollView?.fullScroll(View.FOCUS_UP)
            }
        }

        btnPageDown?.setOnClickListener {
            if (currentPage < totalPages) {
                currentPage++
                updatePageIndicator()
                scrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun updatePageIndicator() {
        val pageText = String.format(Locale.getDefault(), "%d / %d", currentPage, totalPages)
        findViewById<TextView>(R.id.tvPageIndicator)?.text = pageText
    }

    private fun isDarkMode(): Boolean {
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun fetchNoteFromDatabase() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@PdfViewerActivity).appDao()
            val note = if (currentNoteId != -1) {
                db.getNoteById(currentNoteId)
            } else {
                db.getNoteByTitle(currentTitle)
            }

            note?.let {
                currentNote = it
                currentNoteId = it.id
                currentTitle = it.title
                currentRawContent = sanitizeOcrText(it.content)
                currentImagePath = sanitizeFilePath(it.imagePath)

                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.tvPdfTitle)?.text = currentTitle
                    renderContent(currentRawContent)
                }
            }
        }
    }

    private fun setupExtractedTextExpansion() {
        val button = findViewById<TextView>(R.id.btnExpandText)

        button?.setOnClickListener {
            if (isEditMode) return@setOnClickListener

            isTextExpanded = !isTextExpanded
            applyExtractedTextExpansion()
        }
    }

    private fun applyExtractedTextExpansion() {
        val content = findViewById<TextView>(R.id.tvPdfContent)
        val button = findViewById<TextView>(R.id.btnExpandText)

        if (content == null || button == null) return

        if (isEditMode) {
            content.maxLines = Int.MAX_VALUE
            content.ellipsize = null
            button.visibility = View.GONE
            return
        }

        val visibleText = content.text?.toString().orEmpty()
        val needsExpansion =
            visibleText.length > 420 ||
                    visibleText.count { it == '\n' } >= 11

        if (!needsExpansion) {
            isTextExpanded = false
            content.maxLines = Int.MAX_VALUE
            content.ellipsize = null
            button.visibility = View.GONE
            return
        }

        button.visibility = View.VISIBLE

        if (isTextExpanded) {
            content.maxLines = Int.MAX_VALUE
            content.ellipsize = null
            button.text = "Show less"
        } else {
            content.maxLines = 12
            content.ellipsize = android.text.TextUtils.TruncateAt.END
            button.text = "Show more"
        }
    }

    private fun renderContent(rawContent: String) {
        val tvPdfContent = findViewById<TextView>(R.id.tvPdfContent)
        val tvSectionHeader = findViewById<TextView>(R.id.tvSectionHeader)
        val webViewContent = findViewById<WebView>(R.id.webViewContent)
        val scrollViewContent = findViewById<View>(R.id.scrollViewContent)
        val ivScannedImage = findViewById<ImageView>(R.id.ivScannedImage)

        val dark = isDarkMode()
        val cleanedText = sanitizeOcrText(rawContent)
        val hasRichContent = cleanedText.contains("<table", ignoreCase = true) ||
                cleanedText.contains("<img", ignoreCase = true)

        // Render Scanned Photo into ImageView if view exists and file is available
        if (ivScannedImage != null) {
            val validPath = currentImagePath
            if (!validPath.isNullOrEmpty() && File(validPath).exists()) {
                val bitmap = BitmapFactory.decodeFile(validPath)
                ivScannedImage.setImageBitmap(bitmap)
                ivScannedImage.visibility = View.VISIBLE
            } else {
                ivScannedImage.visibility = View.GONE
            }
        }

        if (hasRichContent && webViewContent != null) {
            scrollViewContent?.visibility = View.GONE
            webViewContent.visibility = View.VISIBLE

            val bgColorStr = if (dark) "#121212" else "#FFFFFF"
            val textColorStr = if (dark) "#E0E0E0" else "#000000"
            val headerBgStr = if (dark) "#1F1F1F" else "#F2F2F7"
            val borderColorStr = if (dark) "#333333" else "#CCCCCC"

            val imageHtml = if (!currentImagePath.isNullOrEmpty() && File(currentImagePath!!).exists()) {
                "<img src=\"file://${currentImagePath}\" style=\"max-width:100%; border-radius:8px; margin-bottom:12px;\"/>"
            } else ""

            val styledHtml = """
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        body { font-family: sans-serif; padding: 12px; color: $textColorStr; background-color: $bgColorStr; }
                        table { width: 100%; border-collapse: collapse; margin-top: 10px; margin-bottom: 10px; }
                        img { display: block; max-width: 100%; height: auto; margin: 10px 0; border-radius: 8px; }
                        th { background-color: $headerBgStr; font-weight: bold; text-align: left; padding: 8px; border: 1px solid $borderColorStr; color: $textColorStr; }
                        td { padding: 8px; border: 1px solid $borderColorStr; vertical-align: top; color: $textColorStr; }
                    </style>
                </head>
                <body>
                    $imageHtml
                    $cleanedText
                </body>
                </html>
            """.trimIndent()

            webViewContent.setBackgroundColor(bgColorStr.toColorInt())
            webViewContent.webViewClient = WebViewClient()
            webViewContent.settings.javaScriptEnabled = false
            webViewContent.settings.allowFileAccess = true
            webViewContent.loadDataWithBaseURL(
                "file://${filesDir.absolutePath}/",
                styledHtml,
                "text/html",
                "UTF-8",
                null
            )
        } else {
            webViewContent?.visibility = View.GONE
            scrollViewContent?.visibility = View.VISIBLE

            var detectedSubHeader: String? = null

            for (line in cleanedText.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if ((trimmed.startsWith("Chapter", ignoreCase = true) || trimmed.startsWith("Section", ignoreCase = true))
                    && !trimmed.equals(currentTitle, ignoreCase = true)) {
                    detectedSubHeader = trimmed
                    break
                }
            }

            if (!detectedSubHeader.isNullOrBlank()) {
                tvSectionHeader?.text = detectedSubHeader
                tvSectionHeader?.visibility = View.VISIBLE
            } else {
                tvSectionHeader?.visibility = View.GONE
            }

            val displaySource: Spanned =
                if (rawContent.contains("<", ignoreCase = true)) {
                    HtmlCompat.fromHtml(
                        rawContent,
                        HtmlCompat.FROM_HTML_MODE_COMPACT
                    )
                } else {
                    val htmlFormatted = rawContent
                        .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
                        .replace("\n", "<br/>")

                    HtmlCompat.fromHtml(
                        htmlFormatted,
                        HtmlCompat.FROM_HTML_MODE_COMPACT
                    )
                }

            if (tvPdfContent != null) {
                tvPdfContent.text =
                    buildToggleDisplay(
                        displaySource,
                        dark
                    )

                tvPdfContent.setTextColor(
                    if (dark) {
                        Color.WHITE
                    } else {
                        "#0F172A".toColorInt()
                    }
                )

                tvPdfContent.movementMethod =
                    LinkMovementMethod.getInstance()

                tvPdfContent.highlightColor =
                    Color.TRANSPARENT

                applyExtractedTextExpansion()
            }
        }
    }

    /**
     * Builds the normal reading view.
     *
     * A line beginning with "▸ " is treated as a toggle title.
     * The lines immediately after it that begin with the invisible
     * toggleContentMarker belong to that toggle.
     */
    private fun buildToggleDisplay(
        source: Spanned,
        dark: Boolean
    ): SpannableStringBuilder {

        val sourceText = source.toString()
        val output = SpannableStringBuilder()

        data class SourceLine(
            val start: Int,
            val end: Int,
            val text: String
        )

        val lines = mutableListOf<SourceLine>()

        var lineStart = 0

        while (lineStart <= sourceText.length) {
            val newlineIndex = sourceText.indexOf('\n', lineStart)

            val lineEnd =
                if (newlineIndex == -1) {
                    sourceText.length
                } else {
                    newlineIndex
                }

            lines.add(
                SourceLine(
                    start = lineStart,
                    end = lineEnd,
                    text = sourceText.substring(
                        lineStart,
                        lineEnd
                    )
                )
            )

            if (newlineIndex == -1) break

            lineStart = newlineIndex + 1
        }

        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.text.trimStart()

            if (
                trimmed.startsWith("▸ ") ||
                trimmed.startsWith("▾ ")
            ) {
                val toggleIndex = index

                val expanded =
                    expandedToggleBlocks.contains(
                        toggleIndex
                    )

                val title =
                    trimmed
                        .removePrefix("▸ ")
                        .removePrefix("▾ ")

                val titleStart =
                    output.length

                output.append(
                    if (expanded) "▾ " else "▸ "
                )

                output.append(title)

                val titleEnd = output.length

                output.setSpan(
                    StyleSpan(
                        android.graphics.Typeface.BOLD
                    ),
                    titleStart,
                    titleEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                output.setSpan(
                    ForegroundColorSpan(
                        if (dark) {
                            "#AFC4F6".toColorInt()
                        } else {
                            "#5A7FDB".toColorInt()
                        }
                    ),
                    titleStart,
                    (titleStart + 1)
                        .coerceAtMost(titleEnd),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                output.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(
                            widget: View
                        ) {
                            if (
                                expandedToggleBlocks.contains(
                                    toggleIndex
                                )
                            ) {
                                expandedToggleBlocks.remove(
                                    toggleIndex
                                )
                            } else {
                                expandedToggleBlocks.add(
                                    toggleIndex
                                )
                            }

                            renderContent(
                                currentRawContent
                            )
                        }

                        override fun updateDrawState(
                            ds: TextPaint
                        ) {
                            ds.isUnderlineText = false
                            ds.color =
                                if (dark) {
                                    Color.WHITE
                                } else {
                                    "#171717".toColorInt()
                                }
                        }
                    },
                    titleStart,
                    titleEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                var contentIndex = index + 1

                while (
                    contentIndex < lines.size &&
                    lines[contentIndex].text
                        .startsWith(toggleContentMarker)
                ) {
                    if (expanded) {
                        output.append('\n')
                        output.append("    ")

                        val contentLine =
                            lines[contentIndex]

                        val visibleStart =
                            (contentLine.start +
                                    toggleContentMarker.length)
                                .coerceAtMost(
                                    contentLine.end
                                )

                        if (
                            visibleStart <
                            contentLine.end
                        ) {
                            output.append(
                                source.subSequence(
                                    visibleStart,
                                    contentLine.end
                                )
                            )
                        }
                    }

                    contentIndex++
                }

                index = contentIndex

            } else if (
                line.text.startsWith(
                    toggleContentMarker
                )
            ) {
                // Orphaned toggle content should still be readable.
                val visibleStart =
                    (line.start +
                            toggleContentMarker.length)
                        .coerceAtMost(line.end)

                if (visibleStart < line.end) {
                    output.append(
                        source.subSequence(
                            visibleStart,
                            line.end
                        )
                    )
                }

                index++

            } else {
                output.append(
                    source.subSequence(
                        line.start,
                        line.end
                    )
                )

                index++
            }

            if (
                index < lines.size &&
                (
                        output.isEmpty() ||
                                output.last() != '\n'
                        )
            ) {
                output.append('\n')
            }
        }

        return output
    }

    private fun toggleInlineEditMode() {
        val tvPdfContent = findViewById<TextView>(R.id.tvPdfContent)
        val etInlineEditor = findViewById<EditText>(R.id.etInlineEditor)
        val btnToolText = findViewById<ImageButton>(R.id.btnToolText)
        val drawingView = findViewById<DrawingView>(R.id.drawingView)

        activeTool = ToolMode.NONE
        drawingView?.setTool(ToolMode.NONE)

        if (!isEditMode) {
            val editorContent = createEditorContent(currentRawContent)

            etInlineEditor?.setText(editorContent)
            tvPdfContent?.visibility = View.GONE
            etInlineEditor?.visibility = View.VISIBLE
            findViewById<TextView>(R.id.btnExpandText)?.visibility = View.GONE

            etInlineEditor?.let {
                attachEditorAutoFormatting(it)
            }

            etInlineEditor?.requestFocus()

            if (editorContent.isNotEmpty()) {
                etInlineEditor?.setSelection(editorContent.length)
            }

            val imm =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

            imm.showSoftInput(
                etInlineEditor,
                InputMethodManager.SHOW_IMPLICIT
            )

            btnToolText?.setColorFilter("#16A34A".toColorInt())
            isEditMode = true

            Toast.makeText(
                this,
                "Editing Mode Active",
                Toast.LENGTH_SHORT
            ).show()

        } else {
            val updatedText = etInlineEditor?.text

            currentRawContent =
                if (updatedText is Spanned) {
                    HtmlCompat.toHtml(
                        updatedText,
                        HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE
                    )
                } else {
                    updatedText?.toString()?.replace("\n", "<br/>") ?: ""
                }

            etInlineEditor?.visibility = View.GONE
            tvPdfContent?.visibility = View.VISIBLE

            val imm =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

            imm.hideSoftInputFromWindow(
                etInlineEditor?.windowToken,
                0
            )

            btnToolText?.setColorFilter("#5A7FDB".toColorInt())
            isEditMode = false
            isTextExpanded = false

            drawingView?.clear()

            renderContent(currentRawContent)
            saveNoteToDatabase()
        }
    }

    private fun createEditorContent(rawContent: String): SpannableStringBuilder {
        val source =
            if (rawContent.contains("<", ignoreCase = true)) {
                HtmlCompat.fromHtml(
                    rawContent,
                    HtmlCompat.FROM_HTML_MODE_COMPACT
                )
            } else {
                rawContent
            }

        return SpannableStringBuilder(source)
    }

    private fun shareDocument() {
        DocxExporter.shareAsDocx(
            context = this,
            title = currentTitle,
            content = cleanHtmlAndMarkdown(currentRawContent)
        )
    }

    private fun cleanHtmlAndMarkdown(text: String): String {
        return text.replace(toggleContentMarker, "")
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("</p>"), "\n")
            .replace(Regex("</tr>"), "\n")
            .replace(Regex("</td>"), " | ")
            .replace(Regex("<[^>]*>"), "")
            .replace("**", "")
            .replace(Regex("&nbsp;"), " ")
            .trim()
    }

    private fun showOptionsMenu(anchorView: View) {
        val dialog = BottomSheetDialog(this)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
            background = roundedBackground("#FFF9FF", 28f)
        }

        sheet.addView(
            View(this).apply {
                background = roundedBackground("#D7D4DC", 3f)
            },
            LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            }
        )

        sheet.addView(
            TextView(this).apply {
                text = "Note options"
                textSize = 21f
                setTextColor("#171717".toColorInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(4), 0, dp(4), dp(4))
            }
        )

        sheet.addView(
            TextView(this).apply {
                text = "Edit, organize, or manage this note."
                textSize = 11f
                setTextColor("#777780".toColorInt())
                setPadding(dp(4), 0, dp(4), dp(16))
            }
        )

        val optionsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground("#FFFFFF", 20f, "#ECECF2")
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        fun addOption(
            iconRes: Int,
            title: String,
            description: String,
            destructive: Boolean = false,
            action: () -> Unit
        ) {
            optionsCard.addView(
                createNoteOption(
                    iconRes = iconRes,
                    title = title,
                    description = description,
                    destructive = destructive
                ) {
                    dialog.dismiss()
                    action()
                }
            )
        }

        addOption(
            R.drawable.ic_option_edit,
            "Edit note text",
            "Change the extracted text and blocks."
        ) {
            toggleInlineEditMode()
        }

        addOption(
            R.drawable.ic_option_rename,
            "Rename title",
            "Give this note a new title."
        ) {
            showRenameDialog()
        }

        addOption(
            R.drawable.ic_option_move,
            "Move to folder",
            "Organize this note inside a folder."
        ) {
            showMoveToFolderDialog()
        }

        addOption(
            R.drawable.ic_option_delete,
            "Delete note",
            "Permanently remove this note.",
            destructive = true
        ) {
            showDeleteConfirmationDialog()
        }

        sheet.addView(optionsCard)
        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun createNoteOption(
        iconRes: Int,
        title: String,
        description: String,
        destructive: Boolean = false,
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(11), dp(10), dp(11))
            background = roundedBackground("#FFFFFF", 16f)

            val iconContainer = LinearLayout(this@PdfViewerActivity).apply {
                gravity = Gravity.CENTER
                background = roundedBackground(
                    if (destructive) "#FFF0F3" else "#EEF2FF",
                    14f
                )
            }

            iconContainer.addView(
                ImageView(this@PdfViewerActivity).apply {
                    setImageResource(iconRes)
                },
                LinearLayout.LayoutParams(dp(22), dp(22))
            )

            addView(
                iconContainer,
                LinearLayout.LayoutParams(dp(44), dp(44))
            )

            val labels = LinearLayout(this@PdfViewerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }

            labels.addView(
                TextView(this@PdfViewerActivity).apply {
                    text = title
                    textSize = 13f
                    setTextColor(
                        if (destructive) {
                            "#D94B62".toColorInt()
                        } else {
                            "#171717".toColorInt()
                        }
                    )
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            )

            labels.addView(
                TextView(this@PdfViewerActivity).apply {
                    text = description
                    textSize = 10f
                    setTextColor("#777780".toColorInt())
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

            setOnClickListener { onClick() }
        }
    }

    private fun showMoveToFolderDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db =
                AppDatabase
                    .getDatabase(this@PdfViewerActivity)
                    .appDao()

            val folders = db.getAllFolders().first()

            withContext(Dispatchers.Main) {
                val dialog =
                    BottomSheetDialog(this@PdfViewerActivity)

                val sheet =
                    LinearLayout(this@PdfViewerActivity).apply {
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
                    View(this@PdfViewerActivity).apply {
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
                    TextView(this@PdfViewerActivity).apply {
                        text = "Move to folder"
                        textSize = 21f
                        setTextColor(
                            "#171717".toColorInt()
                        )
                        typeface =
                            android.graphics.Typeface.DEFAULT_BOLD
                    }
                )

                sheet.addView(
                    TextView(this@PdfViewerActivity).apply {
                        text = currentTitle
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
                    LinearLayout(this@PdfViewerActivity).apply {
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

                val currentFolderId =
                    currentNote?.folderId

                listCard.addView(
                    createFolderDestinationRow(
                        title = "Main Screen",
                        subtitle =
                            "Keep this note outside folders",
                        isCurrent =
                            currentFolderId == null
                    ) {
                        moveNoteToFolder(
                            null,
                            "Main Screen"
                        )
                        dialog.dismiss()
                    }
                )

                folders.forEach { folder ->
                    listCard.addView(
                        createFolderDestinationRow(
                            title = folder.name,
                            subtitle =
                                "Move note into this folder",
                            isCurrent =
                                currentFolderId ==
                                        folder.id
                        ) {
                            moveNoteToFolder(
                                folder.id,
                                folder.name
                            )
                            dialog.dismiss()
                        }
                    )
                }

                if (folders.isEmpty()) {
                    listCard.addView(
                        TextView(
                            this@PdfViewerActivity
                        ).apply {
                            text =
                                "No folders yet. Create a folder from the Notes screen."
                            textSize = 11f
                            setTextColor(
                                "#777780".toColorInt()
                            )
                            setPadding(
                                dp(12),
                                dp(14),
                                dp(12),
                                dp(14)
                            )
                        }
                    )
                }

                sheet.addView(listCard)

                dialog.setContentView(sheet)
                dialog.show()
            }
        }
    }

    private fun createFolderDestinationRow(
        title: String,
        subtitle: String,
        isCurrent: Boolean,
        onClick: () -> Unit
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
            isClickable = true
            isFocusable = true

            addView(
                TextView(this@PdfViewerActivity).apply {
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

            val labels =
                LinearLayout(
                    this@PdfViewerActivity
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
                    this@PdfViewerActivity
                ).apply {
                    text = title
                    textSize = 13f
                    typeface =
                        android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(
                        "#171717".toColorInt()
                    )
                }
            )

            labels.addView(
                TextView(
                    this@PdfViewerActivity
                ).apply {
                    text =
                        if (isCurrent) {
                            "Current location"
                        } else {
                            subtitle
                        }
                    textSize = 10f
                    setTextColor(
                        if (isCurrent) {
                            "#5A7FDB".toColorInt()
                        } else {
                            "#777780".toColorInt()
                        }
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

            if (!isCurrent) {
                addView(
                    TextView(
                        this@PdfViewerActivity
                    ).apply {
                        text = "›"
                        textSize = 22f
                        gravity = Gravity.CENTER
                        setTextColor(
                            "#9A9AA3".toColorInt()
                        )
                    },
                    LinearLayout.LayoutParams(
                        dp(28),
                        dp(44)
                    )
                )
            }

            setOnClickListener {
                if (!isCurrent) {
                    onClick()
                }
            }
        }
    }

    private fun moveNoteToFolder(folderId: Int?, folderName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@PdfViewerActivity).appDao()
            val existingNote = currentNote

            if (existingNote != null) {
                db.updateNoteFolder(existingNote.id, folderId)
                currentNote = existingNote.copy(folderId = folderId)
            } else {
                val newNote = Note(
                    folderId = folderId,
                    title = currentTitle,
                    content = currentRawContent,
                    imagePath = currentImagePath ?: "",
                    dateEdited = "Updated"
                )
                val newId = db.insertNote(newNote)
                currentNoteId = newId.toInt()
                fetchNoteFromDatabase()
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@PdfViewerActivity, "Moved to $folderName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(currentTitle)
            setSelection(currentTitle.length)
            setPadding(40, 32, 40, 32)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename Note Title")
            .setView(input)
            .setPositiveButton("Save") { d, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    currentTitle = newTitle
                    findViewById<TextView>(R.id.tvPdfTitle)?.text = newTitle
                    saveNoteToDatabase()
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    private fun showDeleteConfirmationDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Delete Note")
            .setMessage("Are you sure you want to delete this note?")
            .setPositiveButton("Delete") { d, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    currentNote?.let {
                        AppDatabase.getDatabase(this@PdfViewerActivity).appDao().deleteNote(it)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PdfViewerActivity, "Note deleted", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    private fun saveNoteToDatabase() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@PdfViewerActivity).appDao()
            val existingNote = currentNote ?: if (currentNoteId != -1) db.getNoteById(currentNoteId) else null

            if (existingNote != null) {
                val updatedNote = existingNote.copy(
                    title = currentTitle,
                    content = currentRawContent,
                    imagePath = currentImagePath ?: existingNote.imagePath
                )
                db.updateNote(updatedNote)
                currentNote = updatedNote
                currentNoteId = updatedNote.id
            } else {
                val newNote = Note(
                    title = currentTitle,
                    content = currentRawContent,
                    imagePath = currentImagePath ?: "",
                    dateEdited = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date())
                )
                val insertedId = db.insertNote(newNote)
                currentNoteId = insertedId.toInt()
                currentNote = newNote.copy(id = currentNoteId)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@PdfViewerActivity, "Note saved!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun writePdfToUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pdfDocument = PdfDocument()
                val pageWidth = 595
                val pageHeight = 842
                val margin = 40f
                val printableWidth = (pageWidth - (margin * 2)).toInt()

                var pageNumber = 1
                var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                var page = pdfDocument.startPage(pageInfo)
                var canvas = page.canvas

                val titlePaint = TextPaint().apply {
                    textSize = 18f
                    color = Color.BLACK
                    isFakeBoldText = true
                    isAntiAlias = true
                }

                val bodyPaint = TextPaint().apply {
                    textSize = 12f
                    color = Color.BLACK
                    isAntiAlias = true
                }

                var currentY = margin

                // 1. Draw Title
                val titleLayout = createStaticLayout(currentTitle, titlePaint, printableWidth)
                canvas.withTranslation(margin, currentY) {
                    titleLayout.draw(canvas)
                }

                currentY += titleLayout.height + 16f

                // 2. Draw Scanned Photo if available
                val imgPath = currentImagePath
                if (!imgPath.isNullOrEmpty()) {
                    val imgFile = File(imgPath)
                    if (imgFile.exists()) {
                        val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                        if (bitmap != null) {
                            val maxImgHeight = 220f
                            val scale = (printableWidth.toFloat() / bitmap.width).coerceAtMost(maxImgHeight / bitmap.height)
                            val scaledWidth = (bitmap.width * scale).toInt()
                            val scaledHeight = (bitmap.height * scale).toInt()

                            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                            canvas.drawBitmap(scaledBitmap, margin, currentY, null)
                            currentY += scaledHeight + 16f
                        }
                    }
                }

                // 3. Draw Extracted Text
                val cleanContent = cleanHtmlAndMarkdown(currentRawContent)
                val lines = cleanContent.lines()

                for (line in lines) {
                    val lineLayout = createStaticLayout(line, bodyPaint, printableWidth)

                    if (currentY + lineLayout.height > pageHeight - margin) {
                        pdfDocument.finishPage(page)
                        pageNumber++
                        pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        currentY = margin
                    }

                    canvas.withTranslation(margin, currentY) {
                        lineLayout.draw(canvas)
                    }

                    currentY += lineLayout.height + 4f
                }

                pdfDocument.finishPage(page)

                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    pdfDocument.writeTo(outputStream)
                }
                pdfDocument.close()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfViewerActivity, "PDF saved successfully!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PdfViewerActivity, "Failed to save PDF.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun createStaticLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
    }

    private fun showToolToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}