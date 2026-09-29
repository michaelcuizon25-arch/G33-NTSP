package com.example.note2snap.activities

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.ContextCompat
import android.text.style.BackgroundColorSpan
import android.text.style.LineBackgroundSpan
import androidx.core.graphics.withTranslation
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.example.note2snap.R
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Note
import com.example.note2snap.model.ScanHistory
import com.example.note2snap.utils.DocxExporter
import com.example.note2snap.utils.DrawingView
import com.example.note2snap.utils.ToolMode
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
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
    private var preservedDiagramHtml: String = ""
    private var activeTool: ToolMode = ToolMode.NONE
    private var currentPage = 1
    private var totalPages = 1
    private var isTextExpanded = false

    private data class HighlightRange(
        val start: Int,
        val end: Int,
        val color: Int
    )

    private val highlightRanges = mutableListOf<HighlightRange>()

    private data class MarkupState(
        val strokes: List<DrawingView.VectorStroke>,
        val highlights: List<HighlightRange>,
        val content: String,
        val wasEditMode: Boolean,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private val markupUndoStack = mutableListOf<MarkupState>()
    private val markupRedoStack = mutableListOf<MarkupState>()
    private var restoringMarkupState = false
    private var suppressTextHistory = false

    private var markupLoaded = false

    private data class PdfMarkupSnapshot(
        val bitmap: Bitmap
    )

    private var pendingPdfSnapshot: PdfMarkupSnapshot? = null

    private val markupMarkerPrefix = "<!--N2S_MARKUP_BASE64:"
    private val markupMarkerSuffix = "-->"

    // Rich text block helpers
    private val toggleContentMarker = "\u2063"
    private var editorWatcherAttached = false
    private var formattingEditorText = false

    private class ReviewerHeaderBackgroundSpan(
        private val backgroundColor: Int
    ) : LineBackgroundSpan {

        override fun drawBackground(
            canvas: Canvas,
            paint: Paint,
            left: Int,
            right: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            lineNumber: Int
        ) {
            val oldColor = paint.color
            val oldStyle = paint.style

            paint.color = backgroundColor
            paint.style = Paint.Style.FILL

            canvas.drawRect(
                left.toFloat(),
                top.toFloat(),
                right.toFloat(),
                bottom.toFloat(),
                paint
            )

            paint.color = oldColor
            paint.style = oldStyle
        }
    }

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

        if (!directContent.isNullOrEmpty()) {
            markupLoaded = false
            val restored =
                restoreMarkupFromPersistedContent(
                    directContent
                )

            currentRawContent =
                sanitizeOcrText(
                    stripPersistedMarkup(
                        directContent
                    )
                )

            if (!restored) {
                markupLoaded = false
            }

            renderContent(currentRawContent)

            findViewById<View>(
                R.id.tvPdfContent
            )?.post {
                maybeShowOcrReviewWarning()
            }

            // Save/Sync initially so new scans exist in both Notes & History without duplicating
            saveNoteToDatabase()
        } else {
            fetchNoteFromDatabase()
        }
    }

    private fun maybeShowOcrReviewWarning() {
        val count =
            intent.getIntExtra(
                "OCR_REVIEW_COUNT",
                0
            )

        val reviewLines =
            intent.getStringArrayListExtra(
                "OCR_REVIEW_LINES"
            ).orEmpty()

        if (
            count <= 0 &&
            reviewLines.isEmpty()
        ) {
            return
        }

        val dialog =
            BottomSheetDialog(this)

        val sheet =
            LinearLayout(this).apply {
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
            }

        sheet.addView(
            View(this).apply {
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

        sheet.addView(
            TextView(this).apply {
                text =
                    "Some text needs review"

                textSize =
                    22f

                typeface =
                    ResourcesCompat.getFont(
                        this@PdfViewerActivity,
                        R.font.apple_garamond_bold
                    )

                setTextColor(
                    ContextCompat.getColor(
                        this@PdfViewerActivity,
                        R.color.nts_text
                    )
                )
            }
        )

        sheet.addView(
            TextView(this).apply {
                text =
                    if (count == 1) {
                        "1 OCR line may be inaccurate."
                    } else {
                        "$count OCR lines may be inaccurate."
                    }

                textSize =
                    10.5f

                typeface =
                    ResourcesCompat.getFont(
                        this@PdfViewerActivity,
                        R.font.poppins_regular
                    )

                setTextColor(
                    ContextCompat.getColor(
                        this@PdfViewerActivity,
                        R.color.nts_text_secondary
                    )
                )

                setPadding(
                    0,
                    dp(3),
                    0,
                    dp(12)
                )
            }
        )

        if (reviewLines.isNotEmpty()) {
            val previewCard =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.VERTICAL

                    background =
                        roundedBackground(
                            colorHex(
                                R.color.nts_surface
                            ),
                            18f,
                            colorHex(
                                R.color.nts_blue_line
                            )
                        )

                    setPadding(
                        dp(12),
                        dp(10),
                        dp(12),
                        dp(10)
                    )
                }

            reviewLines
                .take(4)
                .forEach { line ->
                    previewCard.addView(
                        TextView(this).apply {
                            text =
                                "• $line"

                            textSize =
                                10.5f

                            typeface =
                                ResourcesCompat.getFont(
                                    this@PdfViewerActivity,
                                    R.font.poppins_regular
                                )

                            setTextColor(
                                ContextCompat.getColor(
                                    this@PdfViewerActivity,
                                    R.color.nts_text
                                )
                            )

                            setPadding(
                                0,
                                dp(4),
                                0,
                                dp(4)
                            )
                        }
                    )
                }

            sheet.addView(
                previewCard
            )
        }

        val hint =
            TextView(this).apply {
                text =
                    "Review the extracted text and edit anything that does not match the original whiteboard."

                textSize =
                    9.5f

                typeface =
                    ResourcesCompat.getFont(
                        this@PdfViewerActivity,
                        R.font.poppins_regular
                    )

                setTextColor(
                    ContextCompat.getColor(
                        this@PdfViewerActivity,
                        R.color.nts_text_secondary
                    )
                )

                setPadding(
                    0,
                    dp(12),
                    0,
                    dp(12)
                )
            }

        sheet.addView(
            hint
        )

        val reviewButton =
            TextView(this).apply {
                text =
                    "Review now"

                gravity =
                    Gravity.CENTER

                textSize =
                    11.5f

                typeface =
                    ResourcesCompat.getFont(
                        this@PdfViewerActivity,
                        R.font.poppins_semibold
                    )

                setTextColor(
                    ContextCompat.getColor(
                        this@PdfViewerActivity,
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
                    dp(16),
                    dp(11),
                    dp(16),
                    dp(11)
                )

                setOnClickListener {
                    dialog.dismiss()

                    if (!isEditMode) {
                        toggleInlineEditMode()
                    }
                }
            }

        sheet.addView(
            reviewButton
        )

        dialog.setContentView(
            sheet
        )

        dialog.show()
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

    private fun leaveTextEditModeForDrawing() {
        if (!isEditMode) return

        val tvPdfContent = findViewById<TextView>(R.id.tvPdfContent)
        val etInlineEditor = findViewById<EditText>(R.id.etInlineEditor)
        val btnToolText = findViewById<ImageButton>(R.id.btnToolText)

        val updatedText = etInlineEditor?.text?.toString() ?: ""
        currentRawContent = sanitizeOcrText(updatedText.replace("\n", "<br/>"))

        etInlineEditor?.visibility = View.GONE
        tvPdfContent?.visibility = View.VISIBLE

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etInlineEditor?.windowToken, 0)

        btnToolText?.setColorFilter(ContextCompat.getColor(this, R.color.nts_blue))
        isEditMode = false

        renderContent(currentRawContent)
        saveNoteToDatabase()
    }

    private fun setupToolRibbon() {
        val drawingView = findViewById<DrawingView>(R.id.drawingView)

        activeTool = ToolMode.NONE
        drawingView?.setTool(ToolMode.NONE)

        // Pen and eraser gestures tell us BEFORE they mutate the drawing.
        drawingView?.setBeforeMarkupChangeListener {
            pushMarkupUndoState()
        }

        // A snapped text highlight lives in PdfViewerActivity, so capture
        // history immediately before adding it.
        drawingView?.setHighlighterStrokeListener { startX, startY, endX, endY, color ->
            pushMarkupUndoState()

            snapHighlighterToText(
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                color = color
            )
        }

        // The eraser now removes both freehand strokes and snapped highlights.
        drawingView?.setEraserTouchListener { x, y ->
            eraseHighlightAt(
                x = x,
                y = y
            )
        }

        drawingView?.setMarkupChangedListener {
            if (!restoringMarkupState) {
                saveMarkupData()
            }
        }

        findViewById<View>(R.id.btnToolText)?.setOnClickListener {
            activeTool = ToolMode.NONE
            drawingView?.setTool(ToolMode.NONE)
            showAddBlockSheet()
        }

        findViewById<View>(R.id.btnToolPen)?.setOnClickListener {
            if (activeTool == ToolMode.PEN) {
                activeTool = ToolMode.NONE
                drawingView?.setTool(ToolMode.NONE)
                showToolToast("Pen Off")
            } else {
                leaveTextEditModeForDrawing()

                activeTool = ToolMode.PEN
                drawingView?.bringToFront()
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
                leaveTextEditModeForDrawing()

                activeTool = ToolMode.HIGHLIGHTER
                drawingView?.bringToFront()
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
                leaveTextEditModeForDrawing()

                activeTool = ToolMode.ERASER
                drawingView?.bringToFront()
                drawingView?.setTool(ToolMode.ERASER)
                showToolToast("Eraser Active")
            }
        }

        findViewById<View>(R.id.btnToolUndo)?.setOnClickListener {
            undoMarkup()
        }

        findViewById<View>(R.id.btnToolRedo)?.setOnClickListener {
            redoMarkup()
        }
    }

    private fun copyStroke(
        stroke: DrawingView.VectorStroke
    ): DrawingView.VectorStroke {
        return DrawingView.VectorStroke(
            points = stroke.points.map {
                DrawingView.StrokePoint(
                    x = it.x,
                    y = it.y
                )
            },
            color = stroke.color,
            width = stroke.width,
            alpha = stroke.alpha
        )
    }

    private fun currentContentForHistory(): String {
        if (!isEditMode) {
            return currentRawContent
        }

        val editor =
            findViewById<EditText>(
                R.id.etInlineEditor
            )

        val value =
            editor?.text
                ?: return currentRawContent

        return if (value is Spanned) {
            HtmlCompat.toHtml(
                value,
                HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE
            )
        } else {
            value.toString()
                .replace("\n", "<br/>")
        }
    }

    private fun captureMarkupState(): MarkupState {
        val drawingView =
            findViewById<DrawingView>(
                R.id.drawingView
            )

        val editor =
            findViewById<EditText>(
                R.id.etInlineEditor
            )

        return MarkupState(
            strokes =
                drawingView
                    ?.getVectorStrokes()
                    .orEmpty()
                    .map(::copyStroke),
            highlights =
                highlightRanges.map {
                    it.copy()
                },
            content =
                currentContentForHistory(),
            wasEditMode =
                isEditMode,
            selectionStart =
                if (isEditMode) {
                    editor?.selectionStart
                        ?.coerceAtLeast(0)
                        ?: 0
                } else {
                    0
                },
            selectionEnd =
                if (isEditMode) {
                    editor?.selectionEnd
                        ?.coerceAtLeast(0)
                        ?: 0
                } else {
                    0
                }
        )
    }

    private fun pushMarkupUndoState() {
        if (restoringMarkupState) return

        markupUndoStack.add(
            captureMarkupState()
        )

        // Keep memory usage bounded even during long study sessions.
        if (markupUndoStack.size > 60) {
            markupUndoStack.removeAt(0)
        }

        markupRedoStack.clear()
    }

    private fun restoreMarkupState(
        state: MarkupState
    ) {
        restoringMarkupState = true
        suppressTextHistory = true

        try {
            findViewById<DrawingView>(
                R.id.drawingView
            )?.setVectorStrokes(
                state.strokes.map(::copyStroke)
            )

            highlightRanges.clear()
            highlightRanges.addAll(
                state.highlights.map {
                    it.copy()
                }
            )

            currentRawContent =
                state.content

            val textView =
                findViewById<TextView>(
                    R.id.tvPdfContent
                )

            val editor =
                findViewById<EditText>(
                    R.id.etInlineEditor
                )

            isEditMode =
                state.wasEditMode

            if (state.wasEditMode) {
                val editorContent =
                    createEditorContent(
                        currentRawContent
                    )

                editor?.setText(
                    editorContent
                )

                textView?.visibility =
                    View.GONE

                editor?.visibility =
                    View.VISIBLE

                val length =
                    editor?.text?.length ?: 0

                val start =
                    state.selectionStart
                        .coerceIn(
                            0,
                            length
                        )

                val end =
                    state.selectionEnd
                        .coerceIn(
                            0,
                            length
                        )

                editor?.setSelection(
                    minOf(start, end),
                    maxOf(start, end)
                )

                attachEditorAutoFormatting(
                    editor ?: return
                )
            } else {
                editor?.visibility =
                    View.GONE

                textView?.visibility =
                    View.VISIBLE

                renderContent(
                    currentRawContent
                )
            }

            saveMarkupData()
        } finally {
            suppressTextHistory = false
            restoringMarkupState = false
        }
    }

    private fun undoMarkup() {
        if (markupUndoStack.isEmpty()) {
            showToolToast("Nothing to undo")
            return
        }

        markupRedoStack.add(
            captureMarkupState()
        )

        val previous =
            markupUndoStack.removeAt(
                markupUndoStack.lastIndex
            )

        restoreMarkupState(previous)
    }

    private fun redoMarkup() {
        if (markupRedoStack.isEmpty()) {
            showToolToast("Nothing to redo")
            return
        }

        markupUndoStack.add(
            captureMarkupState()
        )

        val next =
            markupRedoStack.removeAt(
                markupRedoStack.lastIndex
            )

        restoreMarkupState(next)
    }

    private fun eraseHighlightAt(
        x: Float,
        y: Float
    ) {
        if (highlightRanges.isEmpty()) return

        val textView =
            findViewById<TextView>(
                R.id.tvPdfContent
            ) ?: return

        val layout =
            textView.layout
                ?: return

        if (
            textView.height <= 0 ||
            layout.lineCount <= 0
        ) {
            return
        }

        // DrawingView and TextView share the same content area.
        val safeY =
            y.toInt().coerceIn(
                0,
                (textView.height - 1)
                    .coerceAtLeast(0)
            )

        val line =
            layout.getLineForVertical(
                safeY
            )

        val radius =
            dp(18).toFloat()

        val leftOffset =
            layout.getOffsetForHorizontal(
                line,
                (x - radius)
                    .coerceAtLeast(0f)
            )

        val rightOffset =
            layout.getOffsetForHorizontal(
                line,
                x + radius
            )

        val minOffset =
            minOf(
                leftOffset,
                rightOffset
            )

        val maxOffset =
            maxOf(
                leftOffset,
                rightOffset
            )

        val removed =
            highlightRanges.removeAll { range ->
                range.start <= maxOffset &&
                        range.end >= minOffset
            }

        if (removed) {
            applySavedHighlightsToDisplayedText()
            saveMarkupData()
        }
    }

    private fun showAddBlockSheet() {
        val dialog = BottomSheetDialog(this)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(24))
            background = roundedBackground(colorHex(R.color.nts_background), 28f)
        }

        val handle = View(this).apply {
            background = roundedBackground(colorHex(R.color.nts_blue_line), 99f)
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
            setTextColor(ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_blue))
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = roundedBackground(colorHex(R.color.nts_surface), 18f, colorHex(R.color.nts_blue_line))
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
            background = roundedBackground(colorHex(R.color.nts_surface), 22f, colorHex(R.color.nts_blue_line))
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

        // Treat the whole block insertion as one Undo step.
        pushMarkupUndoState()
        suppressTextHistory = true

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
                    ForegroundColorSpan(ContextCompat.getColor(this, R.color.nts_text)),
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
                    ForegroundColorSpan(ContextCompat.getColor(this, R.color.nts_blue)),
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

        suppressTextHistory = false

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
                    if (
                        !formattingEditorText &&
                        !restoringMarkupState &&
                        !suppressTextHistory
                    ) {
                        pushMarkupUndoState()
                    }

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
            background = roundedBackground(colorHex(R.color.nts_surface), 16f)

            val iconView = TextView(this@PdfViewerActivity).apply {
                text = icon
                gravity = Gravity.CENTER
                textSize = 17f
                setTextColor(ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_blue))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = roundedBackground(colorHex(R.color.nts_blue_soft), 14f)
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
                    setTextColor(ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_text))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            )

            labels.addView(
                TextView(this@PdfViewerActivity).apply {
                    text = description
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_text_secondary))
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
            setBackgroundColor(ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_blue_line))
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
        val drawingView = findViewById<DrawingView>(R.id.drawingView)
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
            background = roundedBackground(colorHex(R.color.nts_background), 28f)
        }

        sheet.addView(
            View(this).apply {
                background = roundedBackground(colorHex(R.color.nts_blue_line), 3f)
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
                setTextColor(ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_text))
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
                setTextColor(ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_text_secondary))
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
                        setStroke(dp(2), ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_outline))
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
                        setTextColor(ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_text_secondary))
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
            setTextColor(ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_text_secondary))
            setPadding(0, dp(18), 0, dp(2))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        sheet.addView(cancel)

        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun snapHighlighterToText(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        color: Int
    ) {
        val textView = findViewById<TextView>(R.id.tvPdfContent) ?: return
        val layout = textView.layout ?: return
        val displayedText = textView.text ?: return

        if (displayedText.isEmpty() || layout.lineCount == 0) return

        // DrawingView and TextView occupy the same padded FrameLayout area,
        // so their local coordinates already match. Do NOT subtract
        // textView.left/top here.
        val middleY = ((startY + endY) / 2f)
            .toInt()
            .coerceIn(0, (textView.height - 1).coerceAtLeast(0))

        val line = layout.getLineForVertical(middleY)
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line)

        val minX = minOf(startX, endX).coerceAtLeast(0f)
        val maxX = maxOf(startX, endX).coerceAtLeast(minX)

        var start = layout.getOffsetForHorizontal(line, minX)
            .coerceIn(lineStart, lineEnd)

        var end = layout.getOffsetForHorizontal(line, maxX)
            .coerceIn(lineStart, lineEnd)

        if (start > end) {
            val temp = start
            start = end
            end = temp
        }

        // A tiny swipe should still select the word directly underneath it.
        if (start == end) {
            end = (end + 1).coerceAtMost(lineEnd)
        }

        // Trim whitespace first.
        while (start < end && displayedText[start].isWhitespace()) start++
        while (end > start && displayedText[end - 1].isWhitespace()) end--

        if (end <= start) return

        // Snap only to the touched word boundaries INSIDE this same visual line.
        // This prevents a one-word swipe from pulling neighboring lines/phrases.
        while (
            start > lineStart &&
            !displayedText[start - 1].isWhitespace() &&
            displayedText[start - 1] != '\n'
        ) {
            start--
        }

        while (
            end < lineEnd &&
            end < displayedText.length &&
            !displayedText[end].isWhitespace() &&
            displayedText[end] != '\n'
        ) {
            end++
        }

        if (end <= start) return

        // Replace an exact duplicate instead of stacking multiple spans.
        highlightRanges.removeAll {
            it.start == start && it.end == end
        }

        highlightRanges.add(
            HighlightRange(
                start = start,
                end = end,
                color = color
            )
        )

        applySavedHighlightsToDisplayedText()
        saveMarkupData()
    }

    private fun applySavedHighlightsToDisplayedText() {
        val textView =
            findViewById<TextView>(
                R.id.tvPdfContent
            ) ?: return

        val plain =
            textView.text.toString()

        val styled =
            buildReviewerStyledText(
                plain
            )

        textView.text = styled
    }

    private fun buildReviewerStyledText(
        plainText: String
    ): SpannableStringBuilder {
        val styled =
            SpannableStringBuilder(
                plainText
            )

        val blue =
            "#244F8F".toColorInt()

        var lineStart = 0

        val lines =
            plainText.split("\n")

        for ((index, line) in lines.withIndex()) {
            val trimmed =
                line.trim()

            val lineEnd =
                lineStart + line.length

            if (
                trimmed.isNotEmpty() &&
                isReviewerSectionHeader(
                    trimmed
                )
            ) {
                styled.setSpan(
                    ReviewerHeaderBackgroundSpan(
                        blue
                    ),
                    lineStart,
                    lineEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                styled.setSpan(
                    ForegroundColorSpan(
                        Color.WHITE
                    ),
                    lineStart,
                    lineEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                styled.setSpan(
                    StyleSpan(
                        Typeface.BOLD
                    ),
                    lineStart,
                    lineEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                styled.setSpan(
                    RelativeSizeSpan(
                        0.93f
                    ),
                    lineStart,
                    lineEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else if (
                trimmed.isNotEmpty() &&
                isReviewerSubheading(
                    trimmed
                )
            ) {
                styled.setSpan(
                    StyleSpan(
                        Typeface.BOLD
                    ),
                    lineStart,
                    lineEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            if (index < lines.lastIndex) {
                lineStart =
                    lineEnd + 1
            }
        }

        for (range in highlightRanges) {
            val safeStart =
                range.start.coerceIn(
                    0,
                    styled.length
                )

            val safeEnd =
                range.end.coerceIn(
                    safeStart,
                    styled.length
                )

            if (safeEnd > safeStart) {
                styled.setSpan(
                    BackgroundColorSpan(
                        range.color
                    ),
                    safeStart,
                    safeEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        return styled
    }

    private fun isReviewerSectionHeader(
        text: String
    ): Boolean {
        if (text.length > 72) {
            return false
        }

        if (
            text.matches(
                Regex(
                    "^\\\\d+(?:\\\\.\\\\d+)*[.)]?\\\\s+.+"
                )
            )
        ) {
            return true
        }

        val lower =
            text.lowercase(
                Locale.getDefault()
            )

        val prefixes =
            listOf(
                "introduction",
                "advantages",
                "disadvantages",
                "brief history",
                "what ",
                "why ",
                "where ",
                "when ",
                "how ",
                "chapter ",
                "section "
            )

        return prefixes.any {
            lower.startsWith(it)
        } ||
                (
                        text.endsWith("?") &&
                                text.length <= 55
                        )
    }

    private fun isReviewerSubheading(
        text: String
    ): Boolean {
        if (text.length > 52) {
            return false
        }

        if (
            text.startsWith("•") ||
            text.startsWith("-")
        ) {
            return false
        }

        return text.endsWith(":") ||
                text.matches(
                    Regex(
                        "^[A-Z][A-Za-z0-9 /&()-]{2,40}$"
                    )
                )
    }

    private fun createPdfTextWithHighlights(): CharSequence {
        return buildReviewerStyledText(
            cleanHtmlAndMarkdown(
                currentRawContent
            )
        )
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
            if (isEditMode) {
                toggleInlineEditMode()
            }

            saveMarkupData()
            pendingPdfSnapshot = buildPdfMarkupSnapshot()

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

    private fun colorHex(colorRes: Int): String {
        val color = ContextCompat.getColor(this, colorRes)
        return String.format("#%06X", 0xFFFFFF and color)
    }

    private fun fetchNoteFromDatabase() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@PdfViewerActivity).appDao()

            // Fetch by ID first, then by ImagePath, then by Title
            val note = if (currentNoteId != -1) {
                db.getNoteById(currentNoteId)
            } else if (!currentImagePath.isNullOrEmpty()) {
                db.getNoteByPath(currentImagePath!!)
            } else {
                db.getNoteByTitle(currentTitle)
            }

            note?.let {
                currentNote = it
                currentNoteId = it.id
                currentTitle = it.title
                currentImagePath = sanitizeFilePath(it.imagePath)

                withContext(Dispatchers.Main) {
                    markupLoaded = false

                    val restored =
                        restoreMarkupFromPersistedContent(
                            it.content
                        )

                    currentRawContent =
                        sanitizeOcrText(
                            stripPersistedMarkup(
                                it.content
                            )
                        )

                    if (!restored) {
                        markupLoaded = false
                    }

                    findViewById<TextView>(R.id.tvPdfTitle)?.text = currentTitle
                    renderContent(currentRawContent)
                }
            }
        }
    }

    private fun renderContent(rawContent: String) {
        val tvPdfContent = findViewById<TextView>(R.id.tvPdfContent)
        val tvSectionHeader = findViewById<TextView>(R.id.tvSectionHeader)
        val webViewContent = findViewById<WebView>(R.id.webViewContent)
        val scrollViewContent = findViewById<View>(R.id.scrollViewContent)
        val ivScannedImage = findViewById<ImageView>(R.id.ivScannedImage)
        val cardScannedImage = findViewById<View>(R.id.cardScannedImage)

        val cleanedText = sanitizeOcrText(rawContent)
        val hasRichContent = cleanedText.contains("<table", ignoreCase = true) ||
                cleanedText.contains("<img", ignoreCase = true)

        if (ivScannedImage != null) {
            val validPath = currentImagePath
            if (!validPath.isNullOrEmpty() && File(validPath).exists()) {
                val bitmap = BitmapFactory.decodeFile(validPath)
                ivScannedImage.setImageBitmap(bitmap)
                ivScannedImage.visibility = View.VISIBLE
                cardScannedImage?.visibility = View.VISIBLE
            } else {
                ivScannedImage.visibility = View.GONE
                cardScannedImage?.visibility = View.GONE
            }
        }

        if (hasRichContent && webViewContent != null) {
            scrollViewContent?.visibility = View.GONE
            webViewContent.visibility = View.VISIBLE

            val bgColorStr = colorHex(R.color.pdf_web_bg)
            val textColorStr = colorHex(R.color.nts_text)
            val headerBgStr = colorHex(R.color.nts_surface_blue)
            val borderColorStr = colorHex(R.color.nts_blue_line)

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

            val htmlFormatted = cleanedText
                .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
                .replace("\n", "<br/>")

            tvPdfContent?.text = HtmlCompat.fromHtml(htmlFormatted, HtmlCompat.FROM_HTML_MODE_COMPACT)
            tvPdfContent?.setTextColor(ContextCompat.getColor(this, R.color.nts_text))

            applySavedHighlightsToDisplayedText()

            findViewById<DrawingView>(R.id.drawingView)?.post {
                loadMarkupData()
            }
        }
    }

    private fun toggleInlineEditMode() {
        val tvPdfContent = findViewById<TextView>(R.id.tvPdfContent)
        val etInlineEditor = findViewById<EditText>(R.id.etInlineEditor)
        val btnToolText = findViewById<ImageButton>(R.id.btnToolText)
        val drawingView = findViewById<DrawingView>(R.id.drawingView)

        activeTool = ToolMode.NONE
        drawingView?.setTool(ToolMode.NONE)

        if (!isEditMode) {
            preservedDiagramHtml =
                getDiagramHtml(
                    currentRawContent
                )

            val editableContent =
                removeDiagramHtml(
                    currentRawContent
                )

            val editorContent =
                createEditorContent(
                    editableContent
                )

            findViewById<WebView>(
                R.id.webViewContent
            )?.visibility = View.GONE

            findViewById<View>(
                R.id.scrollViewContent
            )?.visibility = View.VISIBLE

            etInlineEditor?.setText(editorContent)
            tvPdfContent?.visibility = View.GONE
            etInlineEditor?.visibility = View.VISIBLE
            etInlineEditor?.isEnabled = true
            etInlineEditor?.isFocusableInTouchMode = true
            etInlineEditor?.isFocusable = true
            etInlineEditor?.isClickable = true
            etInlineEditor?.bringToFront()

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

            val updatedTextHtml =
                if (updatedText is Spanned) {
                    HtmlCompat.toHtml(
                        updatedText,
                        HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE
                    )
                } else {
                    updatedText?.toString()?.replace("\n", "<br/>") ?: ""
                }

            currentRawContent =
                if (preservedDiagramHtml.isBlank()) {
                    updatedTextHtml
                } else {
                    updatedTextHtml +
                            "<br/><br/>" +
                            preservedDiagramHtml
                }

            etInlineEditor?.visibility = View.GONE
            tvPdfContent?.visibility = View.VISIBLE

            val imm =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

            imm.hideSoftInputFromWindow(
                etInlineEditor?.windowToken,
                0
            )

            btnToolText?.setColorFilter(ContextCompat.getColor(this, R.color.nts_blue))
            isEditMode = false
            isTextExpanded = false

            renderContent(currentRawContent)
            saveNoteToDatabase()
        }
    }

    private fun getDiagramHtml(
        content: String
    ): String {
        val start =
            content.indexOf(
                "<b>Detected Diagram</b>",
                ignoreCase = true
            )

        if (start < 0) {
            return ""
        }

        return content
            .substring(start)
            .trim()
    }

    private fun removeDiagramHtml(
        content: String
    ): String {
        val start =
            content.indexOf(
                "<b>Detected Diagram</b>",
                ignoreCase = true
            )

        if (start < 0) {
            return content
        }

        return content
            .substring(
                0,
                start
            )
            .trim()
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

        val editorText = SpannableStringBuilder(source)

        for (range in highlightRanges) {
            val safeStart = range.start.coerceIn(0, editorText.length)
            val safeEnd = range.end.coerceIn(safeStart, editorText.length)

            if (safeEnd > safeStart) {
                editorText.setSpan(
                    BackgroundColorSpan(range.color),
                    safeStart,
                    safeEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        return editorText
    }

    private fun buildMarkupJson(): JSONObject {
        val drawingView = findViewById<DrawingView>(R.id.drawingView)

        val root = JSONObject()

        val highlightsJson = JSONArray()
        for (range in highlightRanges) {
            highlightsJson.put(
                JSONObject().apply {
                    put("start", range.start)
                    put("end", range.end)
                    put("color", range.color)
                }
            )
        }
        root.put("highlights", highlightsJson)

        val strokesJson = JSONArray()
        for (stroke in drawingView?.getVectorStrokes().orEmpty()) {
            val pointsJson = JSONArray()

            for (point in stroke.points) {
                pointsJson.put(
                    JSONObject().apply {
                        put("x", point.x)
                        put("y", point.y)
                    }
                )
            }

            strokesJson.put(
                JSONObject().apply {
                    put("color", stroke.color)
                    put("width", stroke.width)
                    put("alpha", stroke.alpha)
                    put("points", pointsJson)
                }
            )
        }

        root.put("strokes", strokesJson)

        return root
    }

    private fun buildPersistedContent(): String {
        val cleanContent = stripPersistedMarkup(currentRawContent)

        val encoded = Base64.encodeToString(
            buildMarkupJson().toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        return cleanContent +
                "\n" +
                markupMarkerPrefix +
                encoded +
                markupMarkerSuffix
    }

    private fun stripPersistedMarkup(content: String): String {
        val start = content.indexOf(markupMarkerPrefix)

        if (start == -1) {
            return content
        }

        return content.substring(0, start).trimEnd()
    }

    private fun restoreMarkupFromPersistedContent(content: String): Boolean {
        val start = content.indexOf(markupMarkerPrefix)

        if (start == -1) {
            return false
        }

        val encodedStart =
            start + markupMarkerPrefix.length

        val end =
            content.indexOf(
                markupMarkerSuffix,
                encodedStart
            )

        if (end == -1) {
            return false
        }

        return try {
            val encoded =
                content.substring(
                    encodedStart,
                    end
                )

            val decoded =
                String(
                    Base64.decode(
                        encoded,
                        Base64.DEFAULT
                    ),
                    Charsets.UTF_8
                )

            restoreMarkupFromJson(
                JSONObject(decoded)
            )

            true
        } catch (_: Exception) {
            false
        }
    }

    private fun restoreMarkupFromJson(
        root: JSONObject
    ) {
        highlightRanges.clear()

        val highlightsJson =
            root.optJSONArray("highlights")
                ?: JSONArray()

        for (index in 0 until highlightsJson.length()) {
            val item =
                highlightsJson.optJSONObject(index)
                    ?: continue

            highlightRanges.add(
                HighlightRange(
                    start = item.optInt("start"),
                    end = item.optInt("end"),
                    color = item.optInt("color")
                )
            )
        }

        val strokes =
            mutableListOf<DrawingView.VectorStroke>()

        val strokesJson =
            root.optJSONArray("strokes")
                ?: JSONArray()

        for (index in 0 until strokesJson.length()) {
            val strokeObject =
                strokesJson.optJSONObject(index)
                    ?: continue

            val points =
                mutableListOf<DrawingView.StrokePoint>()

            val pointsJson =
                strokeObject.optJSONArray("points")
                    ?: JSONArray()

            for (pointIndex in 0 until pointsJson.length()) {
                val pointObject =
                    pointsJson.optJSONObject(pointIndex)
                        ?: continue

                points.add(
                    DrawingView.StrokePoint(
                        x = pointObject.optDouble("x").toFloat(),
                        y = pointObject.optDouble("y").toFloat()
                    )
                )
            }

            if (points.size >= 2) {
                strokes.add(
                    DrawingView.VectorStroke(
                        points = points,
                        color = strokeObject.optInt("color"),
                        width = strokeObject.optDouble("width").toFloat(),
                        alpha = strokeObject.optInt("alpha", 255)
                    )
                )
            }
        }

        findViewById<DrawingView>(R.id.drawingView)
            ?.setVectorStrokes(strokes)

        markupLoaded = true
    }

    private fun markupFile(): File? {
        val directory = File(filesDir, "annotations").apply {
            mkdirs()
        }

        val path = currentImagePath?.takeIf { it.isNotBlank() }

        val stableName =
            if (path != null) {
                "image_${path.hashCode().toUInt().toString(16)}_markup.json"
            } else if (currentNoteId > 0) {
                "note_${currentNoteId}_markup.json"
            } else {
                return null
            }

        return File(directory, stableName)
    }

    private fun saveMarkupData() {
        val file = markupFile() ?: return

        try {
            file.writeText(
                buildMarkupJson().toString()
            )
        } catch (_: Exception) {
            // The database copy is the primary persistence path.
        }
    }

    private fun loadMarkupData() {
        if (markupLoaded) {
            applySavedHighlightsToDisplayedText()
            return
        }

        val file = markupFile()

        if (file == null || !file.exists()) {
            markupLoaded = true
            applySavedHighlightsToDisplayedText()
            return
        }

        try {
            restoreMarkupFromJson(
                JSONObject(file.readText())
            )
        } catch (_: Exception) {
            markupLoaded = true
        }

        applySavedHighlightsToDisplayedText()
    }

    override fun onPause() {
        saveMarkupData()
        super.onPause()
    }

    private fun shareDocument() {
        DocxExporter.shareAsDocx(
            context = this,
            title = currentTitle,
            content = cleanHtmlAndMarkdown(currentRawContent)
        )
    }

    private fun cleanHtmlAndMarkdown(text: String): String {
        return stripPersistedMarkup(text)
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
            setPadding(dp(18), dp(14), dp(18), dp(24))
            background = roundedBackground(colorHex(R.color.nts_background), 28f)
        }

        val handle = View(this).apply {
            background = roundedBackground(colorHex(R.color.nts_blue_line), 99f)
        }

        sheet.addView(
            handle,
            LinearLayout.LayoutParams(dp(44), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
        )

        val title = TextView(this).apply {
            text = "More options"
            textSize = 20f
            setTextColor(ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_text))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, dp(3))
        }

        val subtitle = TextView(this).apply {
            text = "Manage and organize this note"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_text_secondary))
            setPadding(dp(4), 0, 0, dp(14))
        }

        sheet.addView(title)
        sheet.addView(subtitle)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = roundedBackground(colorHex(R.color.nts_surface), 22f, colorHex(R.color.nts_blue_line))
            elevation = dp(3).toFloat()
        }

        card.addView(
            createMoreOption(
                iconRes = R.drawable.ic_option_edit,
                title = "Edit note text",
                subtitle = "Modify extracted and structured content",
                accent = "#5A7FDB"
            ) {
                dialog.dismiss()
                toggleInlineEditMode()
            }
        )

        card.addView(dividerView())

        card.addView(
            createMoreOption(
                iconRes = R.drawable.ic_option_rename,
                title = "Rename title",
                subtitle = "Change the name of this note",
                accent = "#5A7FDB"
            ) {
                dialog.dismiss()
                showRenameDialog()
            }
        )

        card.addView(dividerView())

        card.addView(
            createMoreOption(
                iconRes = R.drawable.ic_option_move,
                title = "Move to folder",
                subtitle = "Organize this note inside a folder",
                accent = "#5A7FDB"
            ) {
                dialog.dismiss()
                showMoveToFolderDialog()
            }
        )

        card.addView(dividerView())

        card.addView(
            createMoreOption(
                iconRes = R.drawable.ic_option_delete,
                title = "Delete note",
                subtitle = "Permanently remove this note",
                accent = "#D94A4A"
            ) {
                dialog.dismiss()
                showDeleteConfirmationDialog()
            }
        )

        sheet.addView(card)

        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun createMoreOption(
        iconRes: Int,
        title: String,
        subtitle: String,
        accent: String,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(11), dp(10), dp(11))
            background = roundedBackground(colorHex(R.color.nts_surface), 16f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val iconWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = roundedBackground(
                if (accent == "#D94A4A") "#FFF0F0" else colorHex(R.color.nts_blue_soft),
                16f
            )
        }

        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(accent.toColorInt())
            contentDescription = title
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        iconWrap.addView(
            icon,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        val textWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(
                if (accent == "#D94A4A") "#C53E3E".toColorInt()
                else ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_text)
            )
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val subtitleView = TextView(this).apply {
            text = subtitle
            textSize = 10.5f
            setTextColor(ContextCompat.getColor(this@PdfViewerActivity, R.color.nts_text_secondary))
            setPadding(0, dp(2), 0, 0)
        }

        textWrap.addView(titleView)
        textWrap.addView(subtitleView)

        row.addView(iconWrap)
        row.addView(
            textWrap,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        return row
    }

    private fun showMoveToFolderDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@PdfViewerActivity).appDao()
            val folders = db.getAllFolders().first()

            withContext(Dispatchers.Main) {
                val folderOptions = mutableListOf("Main Screen (No Folder)")
                folderOptions.addAll(folders.map { it.name })

                AlertDialog.Builder(this@PdfViewerActivity)
                    .setTitle("Move '$currentTitle' to Folder")
                    .setItems(folderOptions.toTypedArray()) { d, index ->
                        if (index == 0) {
                            moveNoteToFolder(null, "Main Screen")
                        } else {
                            val selectedFolder = folders[index - 1]
                            moveNoteToFolder(selectedFolder.id, selectedFolder.name)
                        }
                        d.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
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
                    content = buildPersistedContent(),
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
                    val db = AppDatabase.getDatabase(this@PdfViewerActivity).appDao()
                    currentNote?.let {
                        db.deleteNote(it)
                        // Also remove from scan history if image path exists
                        if (!it.imagePath.isNullOrEmpty()) {
                            db.deleteScanHistoryByPath(it.imagePath)
                        }
                        markupFile()?.delete()
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
            val path = currentImagePath

            // Search for existing note by ID, or fallback to image path to prevent duplicates
            val existingNote = currentNote
                ?: (if (currentNoteId != -1) db.getNoteById(currentNoteId) else null)
                ?: (if (!path.isNullOrEmpty()) db.getNoteByPath(path) else null)

            val formattedDate = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date())

            if (existingNote != null) {
                val updatedNote = existingNote.copy(
                    title = currentTitle,
                    content = buildPersistedContent(),
                    imagePath = path ?: existingNote.imagePath
                )
                db.updateNote(updatedNote)
                currentNote = updatedNote
                currentNoteId = updatedNote.id

                // Sync scan history title if path exists
                val activePath = path ?: existingNote.imagePath
                if (!activePath.isNullOrEmpty()) {
                    db.updateScanHistoryTitleByPath(activePath, currentTitle)
                }
            } else {
                // Insert new note
                val newNote = Note(
                    title = currentTitle,
                    content = buildPersistedContent(),
                    imagePath = path ?: "",
                    dateEdited = formattedDate
                )
                val insertedId = db.insertNote(newNote)
                currentNoteId = insertedId.toInt()
                currentNote = newNote.copy(id = currentNoteId)

                // Sync / Insert into ScanHistory table
                if (!path.isNullOrEmpty()) {
                    val existingHistory = db.getScanHistoryByPath(path)
                    if (existingHistory == null) {
                        val history = ScanHistory(
                            title = currentTitle,
                            imagePath = path,
                            timestamp = System.currentTimeMillis(),
                            date = formattedDate
                        )
                        db.insertScanHistory(history)
                    } else {
                        db.updateScanHistoryTitleByPath(path, currentTitle)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                saveMarkupData()
                Toast.makeText(this@PdfViewerActivity, "Note saved!", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun buildPdfMarkupSnapshot(): PdfMarkupSnapshot? {
        val textView =
            findViewById<TextView>(
                R.id.tvPdfContent
            ) ?: return null

        // tvPdfContent + DrawingView are inside the same FrameLayout.
        // Render that exact editor surface so the PDF matches what the user
        // actually sees instead of rebuilding text and drawing coordinates
        // separately.
        val editorSurface =
            textView.parent as? View
                ?: return null

        if (
            editorSurface.width <= 0 ||
            editorSurface.height <= 0
        ) {
            return null
        }

        val bitmap =
            Bitmap.createBitmap(
                editorSurface.width,
                editorSurface.height,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(bitmap)

        // PDF export always uses a white page. In dark mode the on-screen
        // TextView uses a very light text color, which becomes almost invisible
        // when drawn onto white. Temporarily switch only the default body text
        // to black while rendering, then immediately restore the UI color.
        val originalTextColor =
            textView.currentTextColor

        try {
            textView.setTextColor(
                Color.BLACK
            )

            canvas.drawColor(
                Color.WHITE
            )

            editorSurface.draw(
                canvas
            )
        } finally {
            textView.setTextColor(
                originalTextColor
            )
        }

        return PdfMarkupSnapshot(
            bitmap = bitmap
        )
    }

    private fun writePdfToUri(uri: Uri) {
        val snapshot =
            pendingPdfSnapshot

        if (snapshot == null) {
            Toast.makeText(
                this,
                "Unable to prepare PDF layout.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        lifecycleScope.launch(
            Dispatchers.IO
        ) {
            try {
                val pdfDocument =
                    PdfDocument()

                val pageWidth = 595
                val pageHeight = 842
                val outerMargin = 28f

                val titleTypeface =
                    ResourcesCompat.getFont(
                        this@PdfViewerActivity,
                        R.font.poppins_semibold
                    )

                val titlePaint =
                    TextPaint().apply {
                        textSize = 14f
                        color = Color.BLACK
                        typeface = titleTypeface
                        isAntiAlias = true
                    }

                val contentWidth =
                    pageWidth -
                            (outerMargin * 2f)

                val bitmap =
                    snapshot.bitmap

                val scale =
                    contentWidth /
                            bitmap.width
                                .coerceAtLeast(1)
                                .toFloat()

                var sourceTop = 0f
                var pageNumber = 1
                var firstPage = true

                while (
                    sourceTop <
                    bitmap.height
                        .toFloat()
                ) {
                    val pageInfo =
                        PdfDocument.PageInfo.Builder(
                            pageWidth,
                            pageHeight,
                            pageNumber
                        ).create()

                    val page =
                        pdfDocument.startPage(
                            pageInfo
                        )

                    val canvas =
                        page.canvas

                    // Keep PDF background clean and predictable.
                    canvas.drawColor(
                        Color.WHITE
                    )

                    var bodyTop =
                        outerMargin

                    if (firstPage) {
                        val titleLayout =
                            createStaticLayout(
                                currentTitle,
                                titlePaint,
                                contentWidth.toInt()
                            )

                        canvas.withTranslation(
                            outerMargin,
                            bodyTop
                        ) {
                            titleLayout.draw(
                                canvas
                            )
                        }

                        bodyTop +=
                            titleLayout.height +
                                    14f
                    }

                    val availableHeight =
                        pageHeight -
                                outerMargin -
                                bodyTop

                    val sourceHeight =
                        (availableHeight / scale)
                            .coerceAtLeast(1f)

                    val sourceBottom =
                        minOf(
                            sourceTop +
                                    sourceHeight,
                            bitmap.height
                                .toFloat()
                        )

                    val srcRect =
                        android.graphics.Rect(
                            0,
                            sourceTop
                                .toInt()
                                .coerceAtLeast(0),
                            bitmap.width,
                            kotlin.math.ceil(
                                sourceBottom
                            )
                                .toInt()
                                .coerceAtMost(
                                    bitmap.height
                                )
                        )

                    val renderedHeight =
                        srcRect.height() *
                                scale

                    val dstRect =
                        android.graphics.RectF(
                            outerMargin,
                            bodyTop,
                            outerMargin +
                                    contentWidth,
                            bodyTop +
                                    renderedHeight
                        )

                    canvas.drawBitmap(
                        bitmap,
                        srcRect,
                        dstRect,
                        Paint(
                            Paint.ANTI_ALIAS_FLAG or
                                    Paint.FILTER_BITMAP_FLAG
                        )
                    )

                    pdfDocument.finishPage(
                        page
                    )

                    sourceTop =
                        sourceBottom

                    firstPage = false
                    pageNumber++
                }

                val pdfBytes =
                    ByteArrayOutputStream().use {
                            memoryStream ->
                        pdfDocument.writeTo(
                            memoryStream
                        )
                        memoryStream
                            .toByteArray()
                    }

                pdfDocument.close()

                if (
                    pdfBytes.size < 5 ||
                    pdfBytes[0].toInt()
                        .toChar() != '%' ||
                    pdfBytes[1].toInt()
                        .toChar() != 'P' ||
                    pdfBytes[2].toInt()
                        .toChar() != 'D' ||
                    pdfBytes[3].toInt()
                        .toChar() != 'F'
                ) {
                    throw IllegalStateException(
                        "Generated file is not a valid PDF."
                    )
                }

                val outputStream =
                    contentResolver
                        .openOutputStream(
                            uri,
                            "w"
                        )
                        ?: throw IllegalStateException(
                            "Unable to open the selected PDF file."
                        )

                outputStream.use {
                    it.write(
                        pdfBytes
                    )
                    it.flush()
                }

                withContext(
                    Dispatchers.Main
                ) {
                    pendingPdfSnapshot = null

                    Toast.makeText(
                        this@PdfViewerActivity,
                        "PDF saved successfully!",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()

                withContext(
                    Dispatchers.Main
                ) {
                    Toast.makeText(
                        this@PdfViewerActivity,
                        "Failed to save PDF.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun createStaticLayout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
    }

    private fun showToolToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}