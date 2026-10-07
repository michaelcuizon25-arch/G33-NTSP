package com.example.note2snap.activities

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import com.google.android.material.card.MaterialCardView
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

    // Batch scans keep the source image and structured result per page.
    // The primary imagePath is still used for Notes/History lookup.
    private val currentImagePaths =
        mutableListOf<String>()

    private val currentPageContents =
        mutableListOf<String>()

    private var currentBatchPageIndex =
        0

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

    // Exact on-screen markup snapshots per source page.
    // These preserve snapped highlights + freehand pen strokes in PDF export.
    private var pendingAnnotationSnapshots:
        List<Bitmap?> =
        emptyList()

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
        currentImagePath =
            sanitizeFilePath(
                intent.getStringExtra(
                    "IMAGE_PATH"
                )
            )

        restoreSourcePathsFromIntent()

        val directContent =
            intent.getStringExtra(
                "CONTENT"
            )

        setupHeaderAndMetadata()
        setupToolRibbon()
        setupBottomActions()
        setupPageNavigation()

        /*
         * IMPORTANT:
         * If this is an already-saved Note, always reload it from Room by NOTE_ID.
         * The database contains the full batch:
         * - sourceImagePathsJson
         * - pageContentsJson
         *
         * Using only CONTENT + IMAGE_PATH would collapse a saved batch back to
         * one page when reopened.
         */
        if (
            currentNoteId !=
            -1
        ) {
            fetchNoteFromDatabase()

        } else if (
            !directContent.isNullOrEmpty()
        ) {
            restorePageContentsFromIntent(
                directContent
            )

            currentBatchPageIndex =
                0

            currentRawContent =
                currentPageContents
                    .firstOrNull()
                    ?: sanitizeOcrText(
                        stripPersistedMarkup(
                            directContent
                        )
                    )

            markupLoaded =
                false

            clearMarkupUiForPageSwitch()

            renderContent(
                currentRawContent
            )

            findViewById<View>(
                R.id.tvPdfContent
            )?.post {
                loadMarkupData()
                maybeShowOcrReviewWarning()
            }

            // New unsaved scan: create/sync its database record once.
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

    private fun restoreSourcePathsFromIntent() {
        currentImagePaths.clear()

        intent
            .getStringArrayListExtra(
                "IMAGE_PATHS"
            )
            .orEmpty()
            .mapNotNull {
                sanitizeFilePath(
                    it
                )
            }
            .filter {
                File(it).exists()
            }
            .distinct()
            .let {
                currentImagePaths.addAll(
                    it
                )
            }

        if (
            currentImagePaths.isEmpty() &&
            !currentImagePath.isNullOrBlank() &&
            File(currentImagePath!!).exists()
        ) {
            currentImagePaths.add(
                currentImagePath!!
            )
        }

        if (
            currentImagePath.isNullOrBlank() &&
            currentImagePaths.isNotEmpty()
        ) {
            currentImagePath =
                currentImagePaths.first()
        }
    }

    private fun restorePageContentsFromIntent(
        fallbackContent: String
    ) {
        currentPageContents.clear()

        intent
            .getStringArrayListExtra(
                "PAGE_CONTENTS"
            )
            .orEmpty()
            .map {
                sanitizeOcrText(
                    stripPersistedMarkup(
                        it
                    )
                )
            }
            .let {
                currentPageContents.addAll(
                    it
                )
            }

        if (
            currentPageContents.isEmpty()
        ) {
            currentPageContents.add(
                sanitizeOcrText(
                    stripPersistedMarkup(
                        fallbackContent
                    )
                )
            )
        }

        normalizeBatchLists()
    }

    private fun restoreBatchDataFromNote(
        note: Note
    ) {
        currentImagePaths.clear()

        if (
            note.sourceImagePathsJson
                .isNotBlank()
        ) {
            runCatching {
                val array =
                    JSONArray(
                        note.sourceImagePathsJson
                    )

                for (
                    index in
                    0 until array.length()
                ) {
                    sanitizeFilePath(
                        array.optString(
                            index
                        )
                    )
                        ?.takeIf {
                            File(it).exists()
                        }
                        ?.let {
                            currentImagePaths.add(
                                it
                            )
                        }
                }
            }
        }

        if (
            currentImagePaths.isEmpty() &&
            note.imagePath.isNotBlank() &&
            File(note.imagePath).exists()
        ) {
            currentImagePaths.add(
                note.imagePath
            )
        }

        currentPageContents.clear()

        if (
            note.pageContentsJson
                .isNotBlank()
        ) {
            runCatching {
                val array =
                    JSONArray(
                        note.pageContentsJson
                    )

                for (
                    index in
                    0 until array.length()
                ) {
                    currentPageContents.add(
                        sanitizeOcrText(
                            stripPersistedMarkup(
                                array.optString(
                                    index
                                )
                            )
                        )
                    )
                }
            }
        }

        if (
            currentPageContents.isEmpty()
        ) {
            currentPageContents.add(
                sanitizeOcrText(
                    stripPersistedMarkup(
                        note.content
                    )
                )
            )
        }

        normalizeBatchLists()
    }

    private fun normalizeBatchLists() {
        currentImagePaths
            .distinct()
            .toList()
            .let {
                currentImagePaths.clear()
                currentImagePaths.addAll(
                    it
                )
            }

        if (
            currentPageContents.isEmpty()
        ) {
            currentPageContents.add(
                ""
            )
        }

        currentBatchPageIndex =
            currentBatchPageIndex.coerceIn(
                0,
                (
                    batchPageCount() -
                        1
                    ).coerceAtLeast(
                        0
                    )
            )
    }

    private fun batchPageCount(): Int {
        return maxOf(
            currentImagePaths.size,
            currentPageContents.size,
            1
        )
    }

    private fun isBatchDocument(): Boolean =
        batchPageCount() > 1

    private fun currentSourcePath(): String? {
        if (
            currentImagePaths.isEmpty()
        ) {
            return currentImagePath
        }

        return currentImagePaths[
            currentBatchPageIndex
                .coerceIn(
                    0,
                    currentImagePaths.lastIndex
                )
        ]
    }

    private fun currentPageContent(): String {
        if (
            currentPageContents.isEmpty()
        ) {
            return currentRawContent
        }

        return currentPageContents[
            currentBatchPageIndex
                .coerceIn(
                    0,
                    currentPageContents.lastIndex
                )
        ]
    }

    private fun syncCurrentPageContentFromRaw() {
        if (
            currentPageContents.isEmpty()
        ) {
            currentPageContents.add(
                currentRawContent
            )
            return
        }

        val index =
            currentBatchPageIndex
                .coerceIn(
                    0,
                    currentPageContents.lastIndex
                )

        currentPageContents[
            index
        ] =
            currentRawContent
    }

    private fun pageContentsJson(): String =
        JSONArray(
            currentPageContents
        ).toString()

    private fun sourcePathsJson(): String =
        JSONArray(
            currentImagePaths
                .ifEmpty {
                    currentImagePath
                        ?.let {
                            listOf(it)
                        }
                        ?: emptyList()
                }
        ).toString()

    private fun combinedBatchContent(): String {
        syncCurrentPageContentFromRaw()

        return currentPageContents
            .joinToString(
                "<br/><br/><hr/><br/><br/>"
            )
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
        currentRawContent =
            sanitizeOcrText(
                updatedText.replace(
                    "\n",
                    "<br/>"
                )
            )

        syncCurrentPageContentFromRaw()

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

            syncCurrentPageContentFromRaw()
            saveMarkupData()

            // PDF export is rebuilt from ALL batch pages as a reviewer,
            // instead of rasterizing only the currently visible page.
            pendingPdfSnapshot =
                null

            // Capture the saved highlight + pen layer for every batch page.
            pendingAnnotationSnapshots =
                buildAllAnnotationSnapshots()

            val sanitizedFileName =
                currentTitle.replace(
                    "[^a-zA-Z0-9._-]".toRegex(),
                    "_"
                )

            createPdfLauncher.launch(
                "$sanitizedFileName.pdf"
            )
        }

        findViewById<View>(R.id.btnActionShare)?.setOnClickListener {
            shareDocument()
        }
    }

    private fun setupPageNavigation() {
        val btnPageUp =
            findViewById<View>(
                R.id.btnPageUp
            )

        val btnPageDown =
            findViewById<View>(
                R.id.btnPageDown
            )

        val scrollView =
            findViewById<ScrollView>(
                R.id.scrollViewContent
            )

        updatePageIndicator()

        /*
         * For batch scans, these controls are real source-page navigation:
         * 1 / 2, 2 / 2, etc. For a normal single-page note, the old
         * scroll-position behavior is preserved.
         */
        scrollView?.setOnScrollChangeListener {
                v: View,
                _: Int,
                scrollY: Int,
                _: Int,
                _: Int ->

            if (
                isBatchDocument()
            ) {
                return@setOnScrollChangeListener
            }

            val childView =
                (v as? ScrollView)
                    ?.getChildAt(
                        0
                    )

            if (
                childView != null &&
                v.height > 0
            ) {
                val totalContentHeight =
                    childView.height

                val viewportHeight =
                    v.height

                totalPages =
                    (
                        totalContentHeight /
                            viewportHeight
                                .toFloat()
                        )
                        .toInt()
                        .coerceAtLeast(
                            1
                        )

                currentPage =
                    (
                        (
                            scrollY /
                                viewportHeight
                                    .toFloat()
                            ) +
                            1
                        )
                        .toInt()
                        .coerceIn(
                            1,
                            totalPages
                        )

                updatePageIndicator()
            }
        }

        btnPageUp?.setOnClickListener {
            if (
                isBatchDocument()
            ) {
                showBatchPage(
                    currentBatchPageIndex -
                        1
                )
            } else if (
                currentPage > 1
            ) {
                currentPage--

                updatePageIndicator()

                scrollView?.fullScroll(
                    View.FOCUS_UP
                )
            }
        }

        btnPageDown?.setOnClickListener {
            if (
                isBatchDocument()
            ) {
                showBatchPage(
                    currentBatchPageIndex +
                        1
                )
            } else if (
                currentPage <
                totalPages
            ) {
                currentPage++

                updatePageIndicator()

                scrollView?.fullScroll(
                    View.FOCUS_DOWN
                )
            }
        }
    }

    private fun showBatchPage(
        targetIndex: Int
    ) {
        if (
            !isBatchDocument()
        ) {
            return
        }

        val safeIndex =
            targetIndex.coerceIn(
                0,
                batchPageCount() - 1
            )

        if (
            safeIndex ==
            currentBatchPageIndex
        ) {
            return
        }

        if (
            isEditMode
        ) {
            /*
             * toggleInlineEditMode() commits the current editor text before
             * leaving text-edit mode.
             */
            toggleInlineEditMode()
        }

        syncCurrentPageContentFromRaw()
        saveMarkupData()

        currentBatchPageIndex =
            safeIndex

        currentRawContent =
            currentPageContent()

        clearMarkupUiForPageSwitch()

        renderContent(
            currentRawContent
        )

        findViewById<View>(
            R.id.scrollViewContent
        )?.let {
            (it as? ScrollView)
                ?.scrollTo(
                    0,
                    0
                )
        }

        findViewById<View>(
            R.id.tvPdfContent
        )?.post {
            loadMarkupData()
        }

        updatePageIndicator()
    }

    private fun clearMarkupUiForPageSwitch() {
        activeTool =
            ToolMode.NONE

        findViewById<DrawingView>(
            R.id.drawingView
        )?.apply {
            setTool(
                ToolMode.NONE
            )

            setVectorStrokes(
                emptyList()
            )
        }

        highlightRanges.clear()
        markupUndoStack.clear()
        markupRedoStack.clear()
        markupLoaded =
            false

        findViewById<EditText>(
            R.id.etInlineEditor
        )?.visibility =
            View.GONE

        findViewById<TextView>(
            R.id.tvPdfContent
        )?.visibility =
            View.VISIBLE

        isEditMode =
            false
    }

    private fun updatePageIndicator() {
        val pageText =
            if (
                isBatchDocument()
            ) {
                String.format(
                    Locale.getDefault(),
                    "%d / %d",
                    currentBatchPageIndex + 1,
                    batchPageCount()
                )
            } else {
                String.format(
                    Locale.getDefault(),
                    "%d / %d",
                    currentPage,
                    totalPages
                )
            }

        findViewById<TextView>(
            R.id.tvPageIndicator
        )?.text =
            pageText

        if (
            isBatchDocument()
        ) {
            findViewById<View>(
                R.id.btnPageUp
            )?.apply {
                isEnabled =
                    currentBatchPageIndex >
                    0

                alpha =
                    if (
                        isEnabled
                    ) {
                        1f
                    } else {
                        0.35f
                    }
            }

            findViewById<View>(
                R.id.btnPageDown
            )?.apply {
                isEnabled =
                    currentBatchPageIndex <
                    batchPageCount() -
                    1

                alpha =
                    if (
                        isEnabled
                    ) {
                        1f
                    } else {
                        0.35f
                    }
            }
        }
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
                currentImagePath =
                    sanitizeFilePath(
                        it.imagePath
                    )

                restoreBatchDataFromNote(
                    it
                )

                currentBatchPageIndex =
                    0

                currentRawContent =
                    currentPageContent()

                withContext(
                    Dispatchers.Main
                ) {
                    markupLoaded =
                        false

                    clearMarkupUiForPageSwitch()

                    findViewById<TextView>(
                        R.id.tvPdfTitle
                    )?.text =
                        currentTitle

                    renderContent(
                        currentRawContent
                    )

                    findViewById<View>(
                        R.id.tvPdfContent
                    )?.post {
                        loadMarkupData()
                    }
                }
            }
        }
    }

    private fun renderContent(
        rawContent: String
    ) {
        val tvPdfContent =
            findViewById<TextView>(
                R.id.tvPdfContent
            )

        val tvSectionHeader =
            findViewById<TextView>(
                R.id.tvSectionHeader
            )

        val webViewContent =
            findViewById<WebView>(
                R.id.webViewContent
            )

        val scrollViewContent =
            findViewById<View>(
                R.id.scrollViewContent
            )

        val ivScannedImage =
            findViewById<ImageView>(
                R.id.ivScannedImage
            )

        val cardScannedImage =
            findViewById<View>(
                R.id.cardScannedImage
            )

        val cleanedContent =
            sanitizeOcrText(
                rawContent
            )

        val textOnly =
            removeDiagramHtml(
                cleanedContent
            )

        val diagramHtml =
            getDiagramHtml(
                cleanedContent
            )

        /*
         * Keep the original viewer layout intact:
         * Original Whiteboard -> Structured Notes -> Detected Diagrams.
         *
         * Do not switch the whole result into one WebView just because a
         * diagram image exists. That was what made the page look like one
         * long non-editable document.
         */
        webViewContent?.visibility =
            View.GONE

        scrollViewContent?.visibility =
            View.VISIBLE

        val sourcePath =
            currentSourcePath()

        if (
            ivScannedImage != null &&
            cardScannedImage != null &&
            !sourcePath.isNullOrBlank() &&
            File(sourcePath).exists()
        ) {
            val bitmap =
                BitmapFactory.decodeFile(
                    sourcePath
                )

            if (
                bitmap != null
            ) {
                ivScannedImage.setImageBitmap(
                    bitmap
                )

                ivScannedImage.visibility =
                    View.VISIBLE

                cardScannedImage.visibility =
                    View.VISIBLE
            } else {
                ivScannedImage.visibility =
                    View.GONE

                cardScannedImage.visibility =
                    View.GONE
            }
        } else {
            ivScannedImage?.visibility =
                View.GONE

            cardScannedImage?.visibility =
                View.GONE
        }

        var detectedSubHeader:
            String? =
            null

        for (
            line in
            cleanHtmlAndMarkdown(
                textOnly
            ).lines()
        ) {
            val trimmed =
                line.trim()

            if (
                trimmed.isEmpty()
            ) {
                continue
            }

            if (
                (
                    trimmed.startsWith(
                        "Chapter",
                        ignoreCase =
                            true
                    ) ||
                    trimmed.startsWith(
                        "Section",
                        ignoreCase =
                            true
                    )
                ) &&
                !trimmed.equals(
                    currentTitle,
                    ignoreCase =
                        true
                )
            ) {
                detectedSubHeader =
                    trimmed

                break
            }
        }

        if (
            !detectedSubHeader
                .isNullOrBlank()
        ) {
            tvSectionHeader?.text =
                detectedSubHeader

            tvSectionHeader?.visibility =
                View.VISIBLE
        } else {
            tvSectionHeader?.visibility =
                View.GONE
        }

        val htmlFormatted =
            textOnly
                .replace(
                    Regex(
                        "\\*\\*(.*?)\\*\\*"
                    ),
                    "<b>$1</b>"
                )
                .replace(
                    "\n",
                    "<br/>"
                )

        tvPdfContent?.text =
            HtmlCompat.fromHtml(
                htmlFormatted,
                HtmlCompat
                    .FROM_HTML_MODE_COMPACT
            )

        tvPdfContent?.setTextColor(
            ContextCompat.getColor(
                this,
                R.color.nts_text
            )
        )

        tvPdfContent?.visibility =
            if (
                isEditMode
            ) {
                View.GONE
            } else {
                View.VISIBLE
            }

        renderDetectedDiagrams(
            diagramHtml
        )

        applySavedHighlightsToDisplayedText()

        findViewById<DrawingView>(
            R.id.drawingView
        )?.post {
            loadMarkupData()
        }

        updatePageIndicator()
    }

    private fun renderDetectedDiagrams(
        diagramHtml: String
    ) {
        val section =
            findViewById<View>(
                R.id.llDetectedVisuals
            )

        val container =
            findViewById<LinearLayout>(
                R.id.llDetectedVisualItems
            )

        container?.removeAllViews()

        if (
            diagramHtml.isBlank() ||
            container == null
        ) {
            section?.visibility =
                View.GONE

            return
        }

        val imagePaths =
            extractDiagramImagePaths(
                diagramHtml
            )

        if (
            imagePaths.isEmpty()
        ) {
            section?.visibility =
                View.GONE

            return
        }

        imagePaths.forEach {
                path ->

            val bitmap =
                BitmapFactory.decodeFile(
                    path
                ) ?: return@forEach

            val card =
                MaterialCardView(
                    this
                ).apply {
                    radius =
                        dp(
                            16
                        ).toFloat()

                    cardElevation =
                        0f

                    strokeWidth =
                        dp(
                            1
                        )

                    strokeColor =
                        ContextCompat.getColor(
                            this@PdfViewerActivity,
                            R.color.nts_blue_line
                        )

                    setCardBackgroundColor(
                        ContextCompat.getColor(
                            this@PdfViewerActivity,
                            R.color.nts_surface_blue_soft
                        )
                    )
                }

            val image =
                ImageView(
                    this
                ).apply {
                    setImageBitmap(
                        bitmap
                    )

                    scaleType =
                        ImageView.ScaleType.FIT_CENTER

                    adjustViewBounds =
                        true

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

                    contentDescription =
                        "Detected whiteboard diagram"
                }

            card.addView(
                image,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )

            container.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin =
                        dp(
                            10
                        )
                }
            )
        }

        section?.visibility =
            if (
                container.childCount >
                0
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun extractDiagramImagePaths(
        diagramHtml: String
    ): List<String> {
        val regex =
            Regex(
                """src\s*=\s*['"]file://([^'"]+)['"]""",
                RegexOption.IGNORE_CASE
            )

        return regex
            .findAll(
                diagramHtml
            )
            .mapNotNull {
                match ->

                match.groupValues
                    .getOrNull(
                        1
                    )
                    ?.takeIf {
                        path ->
                        path.isNotBlank() &&
                            File(path).exists()
                    }
            }
            .distinct()
            .toList()
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
                if (
                    preservedDiagramHtml
                        .isBlank()
                ) {
                    updatedTextHtml
                } else {
                    updatedTextHtml +
                        "<br/><br/>" +
                        preservedDiagramHtml
                }

            syncCurrentPageContentFromRaw()

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

        val path =
            currentSourcePath()
                ?.takeIf {
                    it.isNotBlank()
                }

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
        syncCurrentPageContentFromRaw()
        saveMarkupData()
        super.onPause()
    }

    private fun shareDocument() {
        val shareContent =
            if (
                isBatchDocument()
            ) {
                combinedBatchContent()
            } else {
                currentRawContent
            }

        DocxExporter.shareAsDocx(
            context = this,
            title = currentTitle,
            content = cleanHtmlAndMarkdown(
                shareContent
            )
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
                syncCurrentPageContentFromRaw()

                val newNote = Note(
                    folderId =
                        folderId,
                    title =
                        currentTitle,
                    content =
                        if (
                            isBatchDocument()
                        ) {
                            combinedBatchContent()
                        } else {
                            buildPersistedContent()
                        },
                    imagePath =
                        currentImagePath ?: "",
                    sourceImagePathsJson =
                        sourcePathsJson(),
                    pageContentsJson =
                        pageContentsJson(),
                    dateEdited =
                        "Updated"
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
        syncCurrentPageContentFromRaw()

        val sourcePathsJson =
            sourcePathsJson()

        val pageContentsJson =
            pageContentsJson()

        val contentToStore =
            if (
                isBatchDocument()
            ) {
                combinedBatchContent()
            } else {
                buildPersistedContent()
            }

        lifecycleScope.launch(
            Dispatchers.IO
        ) {
            val db =
                AppDatabase
                    .getDatabase(
                        this@PdfViewerActivity
                    )
                    .appDao()

            val path =
                currentImagePath

            // Search for existing note by ID, or fallback to primary image path.
            val existingNote =
                currentNote
                    ?: (
                        if (
                            currentNoteId !=
                            -1
                        ) {
                            db.getNoteById(
                                currentNoteId
                            )
                        } else {
                            null
                        }
                        )
                    ?: (
                        if (
                            !path.isNullOrEmpty()
                        ) {
                            db.getNoteByPath(
                                path
                            )
                        } else {
                            null
                        }
                        )

            val formattedDate =
                SimpleDateFormat(
                    "MMM d, yyyy",
                    Locale.getDefault()
                ).format(
                    Date()
                )

            if (
                existingNote != null
            ) {
                val updatedNote =
                    existingNote.copy(
                        title =
                            currentTitle,
                        content =
                            contentToStore,
                        imagePath =
                            path
                                ?: existingNote.imagePath,
                        sourceImagePathsJson =
                            sourcePathsJson,
                        pageContentsJson =
                            pageContentsJson
                    )

                db.updateNote(
                    updatedNote
                )

                currentNote =
                    updatedNote

                currentNoteId =
                    updatedNote.id

                val activePath =
                    path
                        ?: existingNote.imagePath

                if (
                    !activePath.isNullOrEmpty()
                ) {
                    val existingHistory =
                        db.getScanHistoryByPath(
                            activePath
                        )

                    if (
                        existingHistory != null
                    ) {
                        db.updateScanHistory(
                            existingHistory.copy(
                                title =
                                    currentTitle,
                                sourceImagePathsJson =
                                    sourcePathsJson,
                                pageContentsJson =
                                    pageContentsJson
                            )
                        )
                    } else {
                        db.insertScanHistory(
                            ScanHistory(
                                title =
                                    currentTitle,
                                imagePath =
                                    activePath,
                                sourceImagePathsJson =
                                    sourcePathsJson,
                                pageContentsJson =
                                    pageContentsJson,
                                timestamp =
                                    System.currentTimeMillis(),
                                date =
                                    formattedDate
                            )
                        )
                    }
                }
            } else {
                val newNote =
                    Note(
                        title =
                            currentTitle,
                        content =
                            contentToStore,
                        imagePath =
                            path ?: "",
                        sourceImagePathsJson =
                            sourcePathsJson,
                        pageContentsJson =
                            pageContentsJson,
                        dateEdited =
                            formattedDate
                    )

                val insertedId =
                    db.insertNote(
                        newNote
                    )

                currentNoteId =
                    insertedId.toInt()

                currentNote =
                    newNote.copy(
                        id =
                            currentNoteId
                    )

                if (
                    !path.isNullOrEmpty()
                ) {
                    val existingHistory =
                        db.getScanHistoryByPath(
                            path
                        )

                    if (
                        existingHistory ==
                        null
                    ) {
                        db.insertScanHistory(
                            ScanHistory(
                                title =
                                    currentTitle,
                                imagePath =
                                    path,
                                sourceImagePathsJson =
                                    sourcePathsJson,
                                pageContentsJson =
                                    pageContentsJson,
                                timestamp =
                                    System.currentTimeMillis(),
                                date =
                                    formattedDate
                            )
                        )
                    } else {
                        db.updateScanHistory(
                            existingHistory.copy(
                                title =
                                    currentTitle,
                                sourceImagePathsJson =
                                    sourcePathsJson,
                                pageContentsJson =
                                    pageContentsJson
                            )
                        )
                    }
                }
            }

            withContext(
                Dispatchers.Main
            ) {
                saveMarkupData()

                Toast.makeText(
                    this@PdfViewerActivity,
                    "Note saved!",
                    Toast.LENGTH_SHORT
                ).show()
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


    private data class PageMarkupForExport(
        val highlights: List<HighlightRange>,
        val strokes: List<DrawingView.VectorStroke>
    )

    private fun markupFileForSourcePath(
        sourcePath: String?
    ): File? {
        val path =
            sourcePath
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        val directory =
            File(
                filesDir,
                "annotations"
            ).apply {
                mkdirs()
            }

        val stableName =
            "image_${path.hashCode().toUInt().toString(16)}_markup.json"

        return File(
            directory,
            stableName
        )
    }

    private fun readMarkupForExport(
        sourcePath: String?
    ): PageMarkupForExport {
        val file =
            markupFileForSourcePath(
                sourcePath
            )

        if (
            file == null ||
            !file.exists()
        ) {
            return PageMarkupForExport(
                highlights =
                    emptyList(),
                strokes =
                    emptyList()
            )
        }

        return runCatching {
            val root =
                JSONObject(
                    file.readText()
                )

            val highlights =
                mutableListOf<HighlightRange>()

            val highlightsJson =
                root.optJSONArray(
                    "highlights"
                )
                    ?: JSONArray()

            for (
                index in
                0 until highlightsJson.length()
            ) {
                val item =
                    highlightsJson
                        .optJSONObject(
                            index
                        )
                        ?: continue

                highlights.add(
                    HighlightRange(
                        start =
                            item.optInt(
                                "start"
                            ),
                        end =
                            item.optInt(
                                "end"
                            ),
                        color =
                            item.optInt(
                                "color"
                            )
                    )
                )
            }

            val strokes =
                mutableListOf<DrawingView.VectorStroke>()

            val strokesJson =
                root.optJSONArray(
                    "strokes"
                )
                    ?: JSONArray()

            for (
                index in
                0 until strokesJson.length()
            ) {
                val strokeObject =
                    strokesJson
                        .optJSONObject(
                            index
                        )
                        ?: continue

                val points =
                    mutableListOf<DrawingView.StrokePoint>()

                val pointsJson =
                    strokeObject
                        .optJSONArray(
                            "points"
                        )
                        ?: JSONArray()

                for (
                    pointIndex in
                    0 until pointsJson.length()
                ) {
                    val pointObject =
                        pointsJson
                            .optJSONObject(
                                pointIndex
                            )
                            ?: continue

                    points.add(
                        DrawingView.StrokePoint(
                            x =
                                pointObject
                                    .optDouble(
                                        "x"
                                    )
                                    .toFloat(),
                            y =
                                pointObject
                                    .optDouble(
                                        "y"
                                    )
                                    .toFloat()
                        )
                    )
                }

                if (
                    points.size >=
                    2
                ) {
                    strokes.add(
                        DrawingView.VectorStroke(
                            points =
                                points,
                            color =
                                strokeObject
                                    .optInt(
                                        "color"
                                    ),
                            width =
                                strokeObject
                                    .optDouble(
                                        "width"
                                    )
                                    .toFloat(),
                            alpha =
                                strokeObject
                                    .optInt(
                                        "alpha",
                                        255
                                    )
                        )
                    )
                }
            }

            PageMarkupForExport(
                highlights =
                    highlights,
                strokes =
                    strokes
            )

        }.getOrElse {
            PageMarkupForExport(
                highlights =
                    emptyList(),
                strokes =
                    emptyList()
            )
        }
    }

    private fun buildAnnotationSnapshotForPage(
        rawPage: String,
        sourcePath: String?
    ): Bitmap? {
        val markup =
            readMarkupForExport(
                sourcePath
            )

        if (
            markup.highlights.isEmpty() &&
            markup.strokes.isEmpty()
        ) {
            return null
        }

        val referenceTextView =
            findViewById<TextView>(
                R.id.tvPdfContent
            )

        val referenceDrawingView =
            findViewById<DrawingView>(
                R.id.drawingView
            )

        val logicalWidth =
            referenceTextView
                ?.width
                ?.takeIf {
                    it > 0
                }
                ?: (
                    resources
                        .displayMetrics
                        .widthPixels -
                    dpExport(
                        64
                    )
                    ).coerceAtLeast(
                        320
                    )

        val styledText =
            buildReviewerStyledText(
                cleanHtmlAndMarkdown(
                    removeDiagramHtml(
                        rawPage
                    )
                )
            )

        val text =
            SpannableStringBuilder(
                styledText
            )

        markup.highlights
            .forEach {
                    range ->

                val start =
                    range.start
                        .coerceIn(
                            0,
                            text.length
                        )

                val end =
                    range.end
                        .coerceIn(
                            start,
                            text.length
                        )

                if (
                    end >
                    start
                ) {
                    text.setSpan(
                        BackgroundColorSpan(
                            range.color
                        ),
                        start,
                        end,
                        Spannable
                            .SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

        val bodyPaint =
            TextPaint().apply {
                textSize =
                    13f *
                    resources
                        .displayMetrics
                        .scaledDensity

                color =
                    Color.BLACK

                typeface =
                    ResourcesCompat.getFont(
                        this@PdfViewerActivity,
                        R.font.poppins_regular
                    )

                isAntiAlias =
                    true
            }

        val layout =
            StaticLayout
                .Builder
                .obtain(
                    text,
                    0,
                    text.length,
                    bodyPaint,
                    logicalWidth
                )
                .setAlignment(
                    Layout.Alignment.ALIGN_NORMAL
                )
                .setIncludePad(
                    false
                )
                .setLineSpacing(
                    2f *
                    resources
                        .displayMetrics
                        .density,
                    1f
                )
                .build()

        val strokeBottom =
            markup.strokes
                .flatMap {
                    it.points
                }
                .maxOfOrNull {
                    it.y
                }
                ?: 0f

        val logicalHeight =
            maxOf(
                layout.height +
                    dpExport(
                        24
                    ),
                strokeBottom
                    .toInt() +
                    dpExport(
                        24
                    ),
                referenceDrawingView
                    ?.height
                    ?: 0,
                dpExport(
                    120
                )
            )

        val bitmap =
            Bitmap.createBitmap(
                logicalWidth,
                logicalHeight,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(
                bitmap
            )

        canvas.drawColor(
            Color.WHITE
        )

        canvas.save()

        canvas.translate(
            0f,
            dpExport(
                10
            ).toFloat()
        )

        layout.draw(
            canvas
        )

        canvas.restore()

        markup.strokes
            .forEach {
                    stroke ->

                if (
                    stroke.points.size <
                    2
                ) {
                    return@forEach
                }

                val paint =
                    Paint(
                        Paint.ANTI_ALIAS_FLAG
                    ).apply {
                        color =
                            stroke.color

                        strokeWidth =
                            stroke.width

                        alpha =
                            stroke.alpha

                        style =
                            Paint.Style.STROKE

                        strokeCap =
                            Paint.Cap.ROUND

                        strokeJoin =
                            Paint.Join.ROUND
                    }

                val path =
                    Path()

                stroke.points
                    .forEachIndexed {
                            index,
                            point ->

                        /*
                         * DrawingView stores pen points NORMALIZED from 0f..1f.
                         * Convert them back to bitmap pixel coordinates before
                         * drawing the exported annotation snapshot.
                         */
                        val px =
                            point.x *
                            bitmap.width

                        val py =
                            point.y *
                            bitmap.height

                        if (
                            index ==
                            0
                        ) {
                            path.moveTo(
                                px,
                                py
                            )
                        } else {
                            path.lineTo(
                                px,
                                py
                            )
                        }
                    }

                canvas.drawPath(
                    path,
                    paint
                )
            }

        return bitmap
    }

    private fun buildAllAnnotationSnapshots():
        List<Bitmap?> {
        saveMarkupData()

        val pages =
            currentPageContents
                .toList()
                .ifEmpty {
                    listOf(
                        currentRawContent
                    )
                }

        val paths =
            currentImagePaths
                .toList()
                .ifEmpty {
                    listOfNotNull(
                        currentImagePath
                    )
                }

        return pages.mapIndexed {
                index,
                page ->

            buildAnnotationSnapshotForPage(
                rawPage =
                    page,
                sourcePath =
                    paths.getOrNull(
                        index
                    )
                        ?: currentImagePath
            )
        }
    }

    private fun dpExport(
        value: Int
    ): Int =
        (
            value *
            resources
                .displayMetrics
                .density
            ).toInt()

    private enum class ReviewerPdfBlockType {
        SECTION,
        SUBHEADING,
        BULLET,
        BODY
    }

    private data class ReviewerPdfBlock(
        val type: ReviewerPdfBlockType,
        val text: String
    )

    private fun reviewerPdfBlocks(
        plainText: String
    ): List<ReviewerPdfBlock> {
        val blocks =
            mutableListOf<ReviewerPdfBlock>()

        plainText
            .replace(
                "\r\n",
                "\n"
            )
            .split("\n")
            .forEach {
                    rawLine ->

                val line =
                    rawLine
                        .trim()

                if (
                    line.isBlank()
                ) {
                    return@forEach
                }

                val normalized =
                    line
                        .replace(
                            Regex(
                                """^[▪■□◦●○]+\s*"""
                            ),
                            "• "
                        )

                val type =
                    when {
                        isReviewerSectionHeader(
                            normalized
                        ) ->
                            ReviewerPdfBlockType.SECTION

                        normalized.startsWith(
                            "•"
                        ) ||
                        normalized.startsWith(
                            "-"
                        ) ->
                            ReviewerPdfBlockType.BULLET

                        isReviewerSubheading(
                            normalized
                        ) ->
                            ReviewerPdfBlockType.SUBHEADING

                        else ->
                            ReviewerPdfBlockType.BODY
                    }

                blocks.add(
                    ReviewerPdfBlock(
                        type =
                            type,
                        text =
                            normalized
                    )
                )
            }

        return blocks
    }

    private fun drawReviewerPdfFooter(
        canvas: Canvas,
        pageNumber: Int,
        pageWidth: Int,
        pageHeight: Int,
        outerMargin: Float,
        accentPaint: Paint,
        footerPaint: Paint
    ) {
        val lineY =
            pageHeight -
                27f

        canvas.drawRect(
            outerMargin,
            lineY,
            pageWidth -
                outerMargin -
                34f,
            lineY +
                1.1f,
            accentPaint
        )

        val pageBox =
            RectF(
                pageWidth -
                    outerMargin -
                    28f,
                lineY -
                    10f,
                pageWidth -
                    outerMargin,
                lineY +
                    14f
            )

        canvas.drawRoundRect(
            pageBox,
            2f,
            2f,
            accentPaint
        )

        val pageText =
            pageNumber
                .toString()

        val textWidth =
            footerPaint
                .measureText(
                    pageText
                )

        canvas.drawText(
            pageText,
            pageBox.centerX() -
                textWidth /
                2f,
            pageBox.centerY() +
                3.7f,
            footerPaint
        )
    }

    private fun reviewerBlockHeight(
        block: ReviewerPdfBlock,
        width: Int,
        bodyPaint: TextPaint,
        headingPaint: TextPaint,
        sectionPaint: TextPaint
    ): Int {
        return when (
            block.type
        ) {
            ReviewerPdfBlockType.SECTION -> {
                val layout =
                    createStaticLayout(
                        block.text.uppercase(
                            Locale.getDefault()
                        ),
                        sectionPaint,
                        (
                            width -
                            16
                        ).coerceAtLeast(
                            1
                        )
                    )

                layout.height +
                    14
            }

            ReviewerPdfBlockType.SUBHEADING -> {
                val layout =
                    createStaticLayout(
                        block.text,
                        headingPaint,
                        (
                            width -
                            16
                        ).coerceAtLeast(
                            1
                        )
                    )

                layout.height +
                    14
            }

            ReviewerPdfBlockType.BULLET -> {
                val clean =
                    block.text
                        .trimStart(
                            '•',
                            '-',
                            ' '
                        )

                val layout =
                    createStaticLayout(
                        clean,
                        bodyPaint,
                        (
                            width -
                            18
                        ).coerceAtLeast(
                            1
                        )
                    )

                layout.height +
                    7
            }

            ReviewerPdfBlockType.BODY -> {
                val layout =
                    createStaticLayout(
                        block.text,
                        bodyPaint,
                        width.coerceAtLeast(
                            1
                        )
                    )

                layout.height +
                    7
            }
        }
    }

    private fun drawReviewerBlock(
        canvas: Canvas,
        block: ReviewerPdfBlock,
        x: Float,
        y: Float,
        width: Int,
        bodyPaint: TextPaint,
        headingPaint: TextPaint,
        sectionPaint: TextPaint,
        sectionFillPaint: Paint,
        subheadingFillPaint: Paint,
        bulletPaint: Paint
    ): Float {
        return when (
            block.type
        ) {
            ReviewerPdfBlockType.SECTION -> {
                val text =
                    block.text.uppercase(
                        Locale.getDefault()
                    )

                val layout =
                    createStaticLayout(
                        text,
                        sectionPaint,
                        (
                            width -
                            16
                        ).coerceAtLeast(
                            1
                        )
                    )

                val height =
                    maxOf(
                        20f,
                        layout.height +
                            7f
                    )

                val rect =
                    RectF(
                        x,
                        y,
                        x +
                            width,
                        y +
                            height
                    )

                canvas.drawRect(
                    rect,
                    sectionFillPaint
                )

                canvas.withTranslation(
                    x +
                        8f,
                    y +
                        (
                            height -
                            layout.height
                        ) /
                        2f
                ) {
                    layout.draw(
                        canvas
                    )
                }

                y +
                    height +
                    6f
            }

            ReviewerPdfBlockType.SUBHEADING -> {
                val layout =
                    createStaticLayout(
                        block.text,
                        headingPaint,
                        (
                            width -
                            16
                        ).coerceAtLeast(
                            1
                        )
                    )

                val height =
                    maxOf(
                        18f,
                        layout.height +
                            6f
                    )

                val rect =
                    RectF(
                        x,
                        y,
                        x +
                            width,
                        y +
                            height
                    )

                canvas.drawRect(
                    rect,
                    subheadingFillPaint
                )

                canvas.withTranslation(
                    x +
                        8f,
                    y +
                        (
                            height -
                            layout.height
                        ) /
                        2f
                ) {
                    layout.draw(
                        canvas
                    )
                }

                y +
                    height +
                    6f
            }

            ReviewerPdfBlockType.BULLET -> {
                val clean =
                    block.text
                        .trimStart(
                            '•',
                            '-',
                            ' '
                        )

                canvas.drawCircle(
                    x +
                        4.5f,
                    y +
                        6.2f,
                    1.5f,
                    bulletPaint
                )

                val layout =
                    createStaticLayout(
                        clean,
                        bodyPaint,
                        (
                            width -
                            18
                        ).coerceAtLeast(
                            1
                        )
                    )

                canvas.withTranslation(
                    x +
                        12f,
                    y
                ) {
                    layout.draw(
                        canvas
                    )
                }

                y +
                    layout.height +
                    6f
            }

            ReviewerPdfBlockType.BODY -> {
                val layout =
                    createStaticLayout(
                        block.text,
                        bodyPaint,
                        width.coerceAtLeast(
                            1
                        )
                    )

                canvas.withTranslation(
                    x,
                    y
                ) {
                    layout.draw(
                        canvas
                    )
                }

                y +
                    layout.height +
                    6f
            }
        }
    }

    private fun writePdfToUri(
        uri: Uri
    ) {
        syncCurrentPageContentFromRaw()

        val exportPages =
            currentPageContents
                .toList()
                .ifEmpty {
                    listOf(
                        currentRawContent
                    )
                }

        lifecycleScope.launch(
            Dispatchers.IO
        ) {
            try {
                val pdfDocument =
                    PdfDocument()

                val pageWidth =
                    595

                val pageHeight =
                    842

                val outerMargin =
                    34f

                val columnGap =
                    18f

                val footerReserve =
                    38f

                val contentWidth =
                    pageWidth -
                        (
                            outerMargin *
                            2f
                        )

                val columnWidth =
                    (
                        contentWidth -
                            columnGap
                        ) /
                        2f

                val titleTypeface =
                    ResourcesCompat.getFont(
                        this@PdfViewerActivity,
                        R.font.poppins_semibold
                    )

                val bodyTypeface =
                    ResourcesCompat.getFont(
                        this@PdfViewerActivity,
                        R.font.poppins_regular
                    )

                val accent =
                    "#244F8F"
                        .toColorInt()

                val lightBlue =
                    "#DCE9FB"
                        .toColorInt()

                val textColor =
                    "#171717"
                        .toColorInt()

                val secondaryText =
                    "#4B5563"
                        .toColorInt()

                val titlePaint =
                    TextPaint().apply {
                        textSize =
                            17.5f

                        color =
                            "#081B57"
                                .toColorInt()

                        typeface =
                            titleTypeface

                        isAntiAlias =
                            true
                    }

                val bodyPaint =
                    TextPaint().apply {
                        textSize =
                            8.2f

                        color =
                            textColor

                        typeface =
                            bodyTypeface

                        isAntiAlias =
                            true
                    }

                val headingPaint =
                    TextPaint().apply {
                        textSize =
                            8.3f

                        color =
                            "#163D79"
                                .toColorInt()

                        typeface =
                            titleTypeface

                        isAntiAlias =
                            true
                    }

                val sectionTextPaint =
                    TextPaint().apply {
                        textSize =
                            7.7f

                        color =
                            Color.WHITE

                        typeface =
                            titleTypeface

                        isAntiAlias =
                            true
                    }

                val metaPaint =
                    TextPaint().apply {
                        textSize =
                            6.8f

                        color =
                            secondaryText

                        typeface =
                            bodyTypeface

                        isAntiAlias =
                            true
                    }

                val sectionFillPaint =
                    Paint(
                        Paint.ANTI_ALIAS_FLAG
                    ).apply {
                        color =
                            accent
                    }

                val subheadingFillPaint =
                    Paint(
                        Paint.ANTI_ALIAS_FLAG
                    ).apply {
                        color =
                            lightBlue
                    }

                val rulePaint =
                    Paint(
                        Paint.ANTI_ALIAS_FLAG
                    ).apply {
                        color =
                            "#D5DCE8"
                                .toColorInt()

                        strokeWidth =
                            0.7f
                    }

                val bulletPaint =
                    Paint(
                        Paint.ANTI_ALIAS_FLAG
                    ).apply {
                        color =
                            Color.BLACK
                    }

                val footerPagePaint =
                    Paint(
                        Paint.ANTI_ALIAS_FLAG
                    ).apply {
                        color =
                            Color.WHITE

                        textSize =
                            8f

                        typeface =
                            Typeface.DEFAULT_BOLD
                    }

                var outputPageNumber =
                    1

                var page:
                    PdfDocument.Page? =
                    null

                var canvas:
                    Canvas? =
                    null

                var currentColumn =
                    0

                var y =
                    outerMargin

                var pageTop =
                    outerMargin

                fun startNewPdfPage(
                    showTitle: Boolean
                ) {
                    page?.let {
                        drawReviewerPdfFooter(
                            canvas =
                                it.canvas,
                            pageNumber =
                                outputPageNumber -
                                    1,
                            pageWidth =
                                pageWidth,
                            pageHeight =
                                pageHeight,
                            outerMargin =
                                outerMargin,
                            accentPaint =
                                sectionFillPaint,
                            footerPaint =
                                footerPagePaint
                        )

                        pdfDocument.finishPage(
                            it
                        )
                    }

                    val pageInfo =
                        PdfDocument
                            .PageInfo
                            .Builder(
                                pageWidth,
                                pageHeight,
                                outputPageNumber
                            )
                            .create()

                    page =
                        pdfDocument.startPage(
                            pageInfo
                        )

                    canvas =
                        page!!.canvas

                    canvas!!.drawColor(
                        Color.WHITE
                    )

                    pageTop =
                        outerMargin

                    if (
                        showTitle
                    ) {
                        val titleLayout =
                            createStaticLayout(
                                currentTitle,
                                titlePaint,
                                contentWidth
                                    .toInt()
                            )

                        canvas!!.withTranslation(
                            outerMargin,
                            pageTop
                        ) {
                            titleLayout.draw(
                                canvas!!
                            )
                        }

                        pageTop +=
                            titleLayout.height +
                                7f

                        canvas!!.drawRect(
                            outerMargin,
                            pageTop,
                            pageWidth -
                                outerMargin,
                            pageTop +
                                2.2f,
                            sectionFillPaint
                        )

                        pageTop +=
                            10f

                        val subtitle =
                            "Note2Snap Reviewer"

                        val subtitleLayout =
                            createStaticLayout(
                                subtitle,
                                metaPaint,
                                contentWidth
                                    .toInt()
                            )

                        canvas!!.withTranslation(
                            outerMargin,
                            pageTop
                        ) {
                            subtitleLayout.draw(
                                canvas!!
                            )
                        }

                        pageTop +=
                            subtitleLayout.height +
                                11f
                    }

                    // Thin divider between columns.
                    canvas!!.drawLine(
                        outerMargin +
                            columnWidth +
                            columnGap /
                            2f,
                        pageTop,
                        outerMargin +
                            columnWidth +
                            columnGap /
                            2f,
                        pageHeight -
                            footerReserve,
                        rulePaint
                    )

                    currentColumn =
                        0

                    y =
                        pageTop

                    outputPageNumber++
                }

                fun moveToNextColumnOrPage() {
                    if (
                        currentColumn ==
                        0
                    ) {
                        currentColumn =
                            1

                        y =
                            pageTop
                    } else {
                        startNewPdfPage(
                            showTitle =
                                false
                        )
                    }
                }

                /*
                 * Draws the exact editor content (text + highlights + pen)
                 * as ONE continuous reviewer content stream.
                 * It can continue into the next column/page without creating
                 * a separate "Highlights & Pen Notes" section.
                 */
                fun drawAnnotatedContent(
                    bitmap: Bitmap
                ) {
                    val scale =
                        columnWidth /
                            bitmap.width
                                .coerceAtLeast(
                                    1
                                )
                                .toFloat()

                    var sourceTop =
                        0f

                    while (
                        sourceTop <
                        bitmap.height
                    ) {
                        var availableHeight =
                            pageHeight -
                                footerReserve -
                                y

                        if (
                            availableHeight <
                            36f
                        ) {
                            moveToNextColumnOrPage()

                            availableHeight =
                                pageHeight -
                                    footerReserve -
                                    y
                        }

                        val sourceHeightThatFits =
                            (
                                availableHeight /
                                    scale
                                )
                                .coerceAtLeast(
                                    1f
                                )

                        val sourceBottom =
                            minOf(
                                bitmap.height
                                    .toFloat(),
                                sourceTop +
                                    sourceHeightThatFits
                            )

                        val destinationHeight =
                            (
                                sourceBottom -
                                    sourceTop
                                ) *
                                scale

                        val x =
                            outerMargin +
                                currentColumn *
                                (
                                    columnWidth +
                                        columnGap
                                )

                        val sourceRect =
                            android.graphics.Rect(
                                0,
                                sourceTop
                                    .toInt()
                                    .coerceAtLeast(
                                        0
                                    ),
                                bitmap.width,
                                sourceBottom
                                    .toInt()
                                    .coerceAtMost(
                                        bitmap.height
                                    )
                            )

                        val destinationRect =
                            RectF(
                                x,
                                y,
                                x +
                                    columnWidth,
                                y +
                                    destinationHeight
                            )

                        canvas!!.drawBitmap(
                            bitmap,
                            sourceRect,
                            destinationRect,
                            Paint(
                                Paint.ANTI_ALIAS_FLAG or
                                    Paint.FILTER_BITMAP_FLAG
                            )
                        )

                        y +=
                            destinationHeight +
                                6f

                        sourceTop =
                            sourceBottom

                        if (
                            sourceTop <
                            bitmap.height
                        ) {
                            moveToNextColumnOrPage()
                        }
                    }
                }

                startNewPdfPage(
                    showTitle =
                        true
                )

                exportPages
                    .forEachIndexed {
                            sourceIndex,
                            rawPage ->

                        val textOnly =
                            cleanHtmlAndMarkdown(
                                removeDiagramHtml(
                                    rawPage
                                )
                            )
                                .trim()

                        val blocks =
                            reviewerPdfBlocks(
                                textOnly
                            )

                        val sourceMeta =
                            if (
                                exportPages.size >
                                1
                            ) {
                                "PAGE ${sourceIndex + 1} OF ${exportPages.size}"
                            } else {
                                null
                            }

                        if (
                            sourceMeta != null
                        ) {
                            val metaLayout =
                                createStaticLayout(
                                    sourceMeta,
                                    metaPaint,
                                    columnWidth
                                        .toInt()
                                )

                            if (
                                y +
                                    metaLayout.height +
                                    12f >
                                pageHeight -
                                    footerReserve
                            ) {
                                moveToNextColumnOrPage()
                            }

                            val x =
                                outerMargin +
                                    currentColumn *
                                    (
                                        columnWidth +
                                            columnGap
                                    )

                            canvas!!.withTranslation(
                                x,
                                y
                            ) {
                                metaLayout.draw(
                                    canvas!!
                                )
                            }

                            y +=
                                metaLayout.height +
                                    7f
                        }

                        val annotationBitmap =
                            pendingAnnotationSnapshots
                                .getOrNull(
                                    sourceIndex
                                )

                        if (
                            annotationBitmap !=
                            null
                        ) {
                            /*
                             * ONE version only:
                             * structured text + highlight + pen together.
                             * Do not print the clean text again underneath.
                             */
                            drawAnnotatedContent(
                                annotationBitmap
                            )

                        } else {
                            for (
                                block in
                                blocks
                            ) {
                                val required =
                                    reviewerBlockHeight(
                                        block =
                                            block,
                                        width =
                                            columnWidth
                                                .toInt(),
                                        bodyPaint =
                                            bodyPaint,
                                        headingPaint =
                                            headingPaint,
                                        sectionPaint =
                                            sectionTextPaint
                                    )

                                if (
                                    y +
                                        required >
                                    pageHeight -
                                        footerReserve
                                ) {
                                    moveToNextColumnOrPage()
                                }

                                val x =
                                    outerMargin +
                                        currentColumn *
                                        (
                                            columnWidth +
                                                columnGap
                                        )

                                y =
                                    drawReviewerBlock(
                                        canvas =
                                            canvas!!,
                                        block =
                                            block,
                                        x =
                                            x,
                                        y =
                                            y,
                                        width =
                                            columnWidth
                                                .toInt(),
                                        bodyPaint =
                                            bodyPaint,
                                        headingPaint =
                                            headingPaint,
                                        sectionPaint =
                                            sectionTextPaint,
                                        sectionFillPaint =
                                            sectionFillPaint,
                                        subheadingFillPaint =
                                            subheadingFillPaint,
                                        bulletPaint =
                                            bulletPaint
                                    )
                            }
                        }

                        val diagramPaths =
                            extractDiagramImagePaths(
                                getDiagramHtml(
                                    rawPage
                                )
                            )

                        if (
                            diagramPaths
                                .isNotEmpty()
                        ) {
                            val diagramHeader =
                                ReviewerPdfBlock(
                                    type =
                                        ReviewerPdfBlockType.SUBHEADING,
                                    text =
                                        "Detected Diagram"
                                )

                            val headerHeight =
                                reviewerBlockHeight(
                                    block =
                                        diagramHeader,
                                    width =
                                        columnWidth
                                            .toInt(),
                                    bodyPaint =
                                        bodyPaint,
                                    headingPaint =
                                        headingPaint,
                                    sectionPaint =
                                        sectionTextPaint
                                )

                            if (
                                y +
                                    headerHeight +
                                    80f >
                                pageHeight -
                                    footerReserve
                            ) {
                                moveToNextColumnOrPage()
                            }

                            val x =
                                outerMargin +
                                    currentColumn *
                                    (
                                        columnWidth +
                                            columnGap
                                    )

                            y =
                                drawReviewerBlock(
                                    canvas =
                                        canvas!!,
                                    block =
                                        diagramHeader,
                                    x =
                                        x,
                                    y =
                                        y,
                                    width =
                                        columnWidth
                                            .toInt(),
                                    bodyPaint =
                                        bodyPaint,
                                    headingPaint =
                                        headingPaint,
                                    sectionPaint =
                                        sectionTextPaint,
                                    sectionFillPaint =
                                        sectionFillPaint,
                                    subheadingFillPaint =
                                        subheadingFillPaint,
                                    bulletPaint =
                                        bulletPaint
                                )

                            diagramPaths
                                .take(
                                    3
                                )
                                .forEach {
                                        imagePath ->

                                    val bitmap =
                                        BitmapFactory
                                            .decodeFile(
                                                imagePath
                                            )
                                            ?: return@forEach

                                    val maxWidth =
                                        columnWidth

                                    val maxHeight =
                                        150f

                                    val scale =
                                        minOf(
                                            maxWidth /
                                                bitmap.width,
                                            maxHeight /
                                                bitmap.height
                                        )

                                    val drawWidth =
                                        bitmap.width *
                                            scale

                                    val drawHeight =
                                        bitmap.height *
                                            scale

                                    if (
                                        y +
                                            drawHeight +
                                            8f >
                                        pageHeight -
                                            footerReserve
                                    ) {
                                        moveToNextColumnOrPage()
                                    }

                                    val imageX =
                                        outerMargin +
                                            currentColumn *
                                            (
                                                columnWidth +
                                                    columnGap
                                            )

                                    val destination =
                                        RectF(
                                            imageX,
                                            y,
                                            imageX +
                                                drawWidth,
                                            y +
                                                drawHeight
                                        )

                                    canvas!!.drawBitmap(
                                        bitmap,
                                        null,
                                        destination,
                                        Paint(
                                            Paint.ANTI_ALIAS_FLAG or
                                                Paint.FILTER_BITMAP_FLAG
                                        )
                                    )

                                    y +=
                                        drawHeight +
                                            10f
                                }
                        }

                        // Small breathing space between scanned source pages.
                        y +=
                            6f
                    }

                page?.let {
                    drawReviewerPdfFooter(
                        canvas =
                            it.canvas,
                        pageNumber =
                            outputPageNumber -
                                1,
                        pageWidth =
                            pageWidth,
                        pageHeight =
                            pageHeight,
                        outerMargin =
                            outerMargin,
                        accentPaint =
                            sectionFillPaint,
                        footerPaint =
                            footerPagePaint
                    )

                    pdfDocument.finishPage(
                        it
                    )
                }

                val pdfBytes =
                    ByteArrayOutputStream()
                        .use {
                                memory ->

                            pdfDocument.writeTo(
                                memory
                            )

                            memory.toByteArray()
                        }

                pdfDocument.close()

                val outputStream =
                    contentResolver
                        .openOutputStream(
                            uri,
                            "w"
                        )
                        ?: throw IllegalStateException(
                            "Unable to open PDF output."
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
                    Toast.makeText(
                        this@PdfViewerActivity,
                        "Reviewer PDF saved!",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (
                error: Exception
            ) {
                error.printStackTrace()

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

    private fun findLastLineThatFits(
        layout: StaticLayout,
        availableHeight: Int
    ): Int {
        var lastLine =
            -1

        for (
            line in
            0 until layout.lineCount
        ) {
            if (
                layout.getLineBottom(
                    line
                ) <=
                availableHeight
            ) {
                lastLine =
                    line
            } else {
                break
            }
        }

        return lastLine
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