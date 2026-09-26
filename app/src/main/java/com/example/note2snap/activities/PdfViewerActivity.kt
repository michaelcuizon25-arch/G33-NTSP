package com.example.note2snap.activities

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.toColorInt
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
            currentRawContent = sanitizeOcrText(directContent)
            renderContent(currentRawContent)
            // Save/Sync initially so new scans exist in both Notes & History without duplicating
            saveNoteToDatabase()
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
            toggleInlineEditMode()
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

    private fun showColorPickerDialog(isHighlighter: Boolean) {
        val drawingView = findViewById<DrawingView>(R.id.drawingView) ?: return

        val colorNames = if (isHighlighter) {
            arrayOf("Yellow 🟡", "Green 🟢", "Blue 🔵", "Pink 🩷", "Orange 🟠")
        } else {
            arrayOf("Black ⬛", "Blue 🔵", "Red 🔴", "Green 🟢", "Purple 🟣")
        }

        val colorValues = if (isHighlighter) {
            intArrayOf(
                Color.YELLOW,
                Color.GREEN,
                Color.CYAN,
                Color.MAGENTA,
                "#FFA500".toColorInt()
            )
        } else {
            intArrayOf(
                Color.BLACK,
                Color.BLUE,
                Color.RED,
                Color.GREEN,
                "#800080".toColorInt()
            )
        }

        AlertDialog.Builder(this)
            .setTitle(if (isHighlighter) "Select Highlighter Color" else "Select Pen Color")
            .setItems(colorNames) { _, index ->
                val selectedColor = colorValues[index]
                if (isHighlighter) {
                    drawingView.setHighlighterColor(selectedColor)
                    showToolToast("Highlighter: ${colorNames[index]}")
                } else {
                    drawingView.setPenColor(selectedColor)
                    showToolToast("Pen: ${colorNames[index]}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                currentRawContent = sanitizeOcrText(it.content)
                currentImagePath = sanitizeFilePath(it.imagePath)

                withContext(Dispatchers.Main) {
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

        val cleanedText = sanitizeOcrText(rawContent)
        val hasRichContent = cleanedText.contains("<table", ignoreCase = true) ||
                cleanedText.contains("<img", ignoreCase = true)

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

            val bgColorStr = "#FFFFFF"
            val textColorStr = "#202127"
            val headerBgStr = "#F2F2F7"
            val borderColorStr = "#CCCCCC"

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
            tvPdfContent?.setTextColor("#202127".toColorInt())
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
            val cleanText = cleanHtmlAndMarkdown(currentRawContent)
            etInlineEditor?.setText(cleanText)
            tvPdfContent?.visibility = View.GONE
            etInlineEditor?.visibility = View.VISIBLE

            etInlineEditor?.requestFocus()

            if (cleanText.isNotEmpty()) {
                etInlineEditor?.setSelection(cleanText.length)
            }

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etInlineEditor, InputMethodManager.SHOW_IMPLICIT)

            btnToolText?.setColorFilter("#16A34A".toColorInt())
            isEditMode = true
            Toast.makeText(this, "Editing Mode Active", Toast.LENGTH_SHORT).show()
        } else {
            val updatedText = etInlineEditor?.text?.toString() ?: ""
            currentRawContent = sanitizeOcrText(updatedText.replace("\n", "<br/>"))

            etInlineEditor?.visibility = View.GONE
            tvPdfContent?.visibility = View.VISIBLE

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etInlineEditor?.windowToken, 0)

            btnToolText?.setColorFilter("#3B62C6".toColorInt())
            isEditMode = false

            drawingView?.clear()

            renderContent(currentRawContent)
            saveNoteToDatabase()
        }
    }

    private fun shareDocument() {
        DocxExporter.shareAsDocx(
            context = this,
            title = currentTitle,
            content = cleanHtmlAndMarkdown(currentRawContent)
        )
    }

    private fun cleanHtmlAndMarkdown(text: String): String {
        return text.replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("</p>"), "\n")
            .replace(Regex("</tr>"), "\n")
            .replace(Regex("</td>"), " | ")
            .replace(Regex("<[^>]*>"), "")
            .replace("**", "")
            .replace(Regex("&nbsp;"), " ")
            .trim()
    }

    private fun showOptionsMenu(anchorView: View) {
        val popup = PopupMenu(this, anchorView)

        popup.menu.add(0, 1, 0, "Edit Note Text")
        popup.menu.add(0, 2, 1, "Rename Title")
        popup.menu.add(0, 3, 2, "Move Note to Folder")
        popup.menu.add(0, 4, 3, "Delete Note")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    toggleInlineEditMode()
                    true
                }
                2 -> {
                    showRenameDialog()
                    true
                }
                3 -> {
                    showMoveToFolderDialog()
                    true
                }
                4 -> {
                    showDeleteConfirmationDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
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
                    val db = AppDatabase.getDatabase(this@PdfViewerActivity).appDao()
                    currentNote?.let {
                        db.deleteNote(it)
                        // Also remove from scan history if image path exists
                        if (!it.imagePath.isNullOrEmpty()) {
                            db.deleteScanHistoryByPath(it.imagePath)
                        }
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
                    content = currentRawContent,
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
                    content = currentRawContent,
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