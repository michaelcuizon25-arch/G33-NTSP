package com.example.note2snap.activities

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.text.Html
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
import androidx.lifecycle.lifecycleScope
import com.example.note2snap.R
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Note
import com.example.note2snap.utils.DocxExporter
import com.example.note2snap.utils.DrawingView
import com.example.note2snap.utils.ToolMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfViewerActivity : AppCompatActivity() {

    private var currentNote: Note? = null
    private var currentTitle: String = "Untitled Note"
    private var currentRawContent: String = ""

    private var isEditMode = false
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

        currentTitle = intent.getStringExtra("TITLE") ?: "Untitled Note"
        val directContent = intent.getStringExtra("CONTENT")

        setupHeaderAndMetadata()
        setupToolRibbon()
        setupBottomActions()
        setupPageNavigation()

        if (!directContent.isNullOrEmpty()) {
            currentRawContent = directContent
            renderContent(directContent)
            fetchNoteFromDatabase()
        } else {
            fetchNoteFromDatabase()
        }
    }

    private fun setupHeaderAndMetadata() {
        findViewById<TextView>(R.id.tvPdfTitle)?.text = currentTitle

        val currentDate = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()).format(Date())
        findViewById<TextView>(R.id.tvPdfDate)?.text = currentDate

        findViewById<ImageButton>(R.id.btnPdfBack)?.setOnClickListener { finish() }

        findViewById<ImageView>(R.id.btnPdfMoreOptions)?.setOnClickListener { view ->
            showOptionsMenu(view)
        }
    }

    private fun setupToolRibbon() {
        val drawingView = findViewById<DrawingView>(R.id.drawingView)

        // Pen inactive by default for normal page touch scrolling
        drawingView?.setTool(ToolMode.NONE)

        // Text Edit Tool Button
        findViewById<TextView>(R.id.btnToolText)?.setOnClickListener {
            toggleInlineEditMode()
        }

        // Top Toolbar Share Button
        findViewById<ImageButton>(R.id.btnToolShare)?.setOnClickListener {
            shareDocument()
        }

        // Tool Selectors
        val toolPen = findViewById<TextView>(R.id.btnToolPen)
        val toolHighlighter = findViewById<TextView>(R.id.btnToolHighlighter)
        val toolEraser = findViewById<TextView>(R.id.btnToolEraser)
        val toolShapes = findViewById<TextView>(R.id.btnToolShapes)
        val toolMic = findViewById<TextView>(R.id.btnToolMic)

        // Undo & Redo Tool Buttons
        val toolUndo = findViewById<View>(R.id.btnToolUndo)
        val toolRedo = findViewById<View>(R.id.btnToolRedo)

        toolPen?.setOnClickListener {
            drawingView?.setTool(ToolMode.PEN)
            showColorPickerDialog(isHighlighter = false)
        }

        toolHighlighter?.setOnClickListener {
            drawingView?.setTool(ToolMode.HIGHLIGHTER)
            showColorPickerDialog(isHighlighter = true)
        }

        toolEraser?.setOnClickListener {
            drawingView?.setTool(ToolMode.ERASER)
            showToolToast("Eraser Active")
        }

        toolShapes?.setOnClickListener { showToolToast("Shape recognition active") }
        toolMic?.setOnClickListener { showToolToast("Voice note recording") }

        toolUndo?.setOnClickListener {
            drawingView?.undo()
            showToolToast("Undo")
        }

        toolRedo?.setOnClickListener {
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
        val btnPageUp = findViewById<TextView>(R.id.btnPageUp)
        val btnPageDown = findViewById<TextView>(R.id.btnPageDown)

        updatePageIndicator()

        btnPageUp?.setOnClickListener {
            if (currentPage > 1) {
                currentPage--
                updatePageIndicator()
                findViewById<ScrollView>(R.id.scrollViewContent)?.fullScroll(View.FOCUS_UP)
            }
        }

        btnPageDown?.setOnClickListener {
            if (currentPage < totalPages) {
                currentPage++
                updatePageIndicator()
                findViewById<ScrollView>(R.id.scrollViewContent)?.fullScroll(View.FOCUS_DOWN)
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
            val note = AppDatabase.getDatabase(this@PdfViewerActivity).appDao().getNoteByTitle(currentTitle)
            note?.let {
                currentNote = it
                currentRawContent = it.content
                withContext(Dispatchers.Main) {
                    renderContent(it.content)
                }
            }
        }
    }

    private fun renderContent(rawContent: String) {
        val tvPdfContent = findViewById<TextView>(R.id.tvPdfContent)
        val tvSectionHeader = findViewById<TextView>(R.id.tvSectionHeader)
        val webViewContent = findViewById<WebView>(R.id.webViewContent)
        val scrollViewContent = findViewById<View>(R.id.scrollViewContent)

        val dark = isDarkMode()
        val hasRichContent = rawContent.contains("<table", ignoreCase = true) ||
                rawContent.contains("<img", ignoreCase = true)

        if (hasRichContent && webViewContent != null) {
            scrollViewContent?.visibility = View.GONE
            webViewContent.visibility = View.VISIBLE

            val bgColorStr = if (dark) "#121212" else "#FFFFFF"
            val textColorStr = if (dark) "#E0E0E0" else "#000000"
            val headerBgStr = if (dark) "#1F1F1F" else "#F2F2F7"
            val borderColorStr = if (dark) "#333333" else "#CCCCCC"

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
                    $rawContent
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

            val lines = rawContent.lines()
            val contentBuilder = StringBuilder()
            var detectedHeader = currentTitle

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith("1.") || trimmed.startsWith("Chapter") || trimmed.startsWith("Section")) {
                    detectedHeader = trimmed
                } else {
                    contentBuilder.append(trimmed).append("\n")
                }
            }

            tvSectionHeader?.text = detectedHeader

            val bodyText = contentBuilder.ifEmpty { rawContent }.toString()
            val htmlFormatted = bodyText
                .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
                .replace("\n", "<br/>")

            tvPdfContent?.text = Html.fromHtml(htmlFormatted, Html.FROM_HTML_MODE_COMPACT)
            tvPdfContent?.setTextColor(if (dark) Color.WHITE else "#0F172A".toColorInt())
        }
    }

    private fun toggleInlineEditMode() {
        val tvPdfContent = findViewById<TextView>(R.id.tvPdfContent)
        val etInlineEditor = findViewById<EditText>(R.id.etInlineEditor)
        val btnToolText = findViewById<TextView>(R.id.btnToolText)

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

            btnToolText?.setTextColor("#16A34A".toColorInt())
            isEditMode = true
            Toast.makeText(this, "Editing Mode Active", Toast.LENGTH_SHORT).show()
        } else {
            val updatedText = etInlineEditor?.text?.toString() ?: ""
            currentRawContent = updatedText.replace("\n", "<br/>")

            etInlineEditor?.visibility = View.GONE
            tvPdfContent?.visibility = View.VISIBLE

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etInlineEditor?.windowToken, 0)

            btnToolText?.setTextColor("#2563EB".toColorInt())
            isEditMode = false

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
                    .setTitle("Move '${currentTitle}' to Folder")
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
                    imagePath = intent.getStringExtra("IMAGE_PATH") ?: "",
                    dateEdited = "Updated"
                )
                db.insertNote(newNote)
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
            val existingNote = currentNote

            if (existingNote != null) {
                val updatedNote = existingNote.copy(title = currentTitle, content = currentRawContent)
                db.updateNote(updatedNote)
                currentNote = updatedNote
            } else {
                val newNote = Note(
                    title = currentTitle,
                    content = currentRawContent,
                    imagePath = intent.getStringExtra("IMAGE_PATH") ?: "",
                    dateEdited = "Updated"
                )
                db.insertNote(newNote)
                currentNote = newNote
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

                val titleLayout = createStaticLayout(currentTitle, titlePaint, printableWidth)
                canvas.withTranslation(margin, currentY) {
                    titleLayout.draw(canvas)
                }

                currentY += titleLayout.height + 20f

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