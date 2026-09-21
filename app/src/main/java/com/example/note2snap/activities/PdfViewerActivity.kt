package com.example.note2snap.activities

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.InputType
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
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
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.example.note2snap.R
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.Note
import com.example.note2snap.utils.DocxExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfViewerActivity : AppCompatActivity() {

    private var currentNote: Note? = null
    private var currentTitle: String = "Untitled Note"
    private var currentRawContent: String = ""

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

        findViewById<TextView>(R.id.tvPdfTitle).apply {
            text = currentTitle
        }

        if (!directContent.isNullOrEmpty()) {
            currentRawContent = directContent
            renderContent(directContent)
            fetchNoteFromDatabase()
        } else {
            fetchNoteFromDatabase()
        }

        findViewById<ImageButton>(R.id.btnPdfBack)?.setOnClickListener { finish() }

        findViewById<ImageView>(R.id.btnPdfMoreOptions)?.setOnClickListener { view ->
            showOptionsMenu(view)
        }

        findViewById<LinearLayout>(R.id.btnActionSaveNotes)?.setOnClickListener {
            showEditContentDialog()
        }

        findViewById<LinearLayout>(R.id.btnActionDownload)?.setOnClickListener {
            val sanitizedFileName = currentTitle.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            createPdfLauncher.launch("$sanitizedFileName.pdf")
        }

        findViewById<LinearLayout>(R.id.btnActionShare)?.setOnClickListener {
            shareDocument()
        }
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
        val webViewContent = findViewById<WebView>(R.id.webViewContent)
        val scrollViewContent = findViewById<View>(R.id.scrollViewContent)

        val dark = isDarkMode()
        val hasRichContent = rawContent.contains("<table", ignoreCase = true) ||
                rawContent.contains("<img", ignoreCase = true)

        if (hasRichContent && webViewContent != null) {
            scrollViewContent?.visibility = View.GONE
            tvPdfContent?.visibility = View.GONE
            webViewContent.visibility = View.VISIBLE

            val bgColor = if (dark) "#121212" else "#FFFFFF"
            val textColor = if (dark) "#E0E0E0" else "#000000"
            val headerBg = if (dark) "#1F1F1F" else "#F2F2F7"
            val borderColor = if (dark) "#333333" else "#CCCCCC"

            val styledHtml = """
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        body { font-family: sans-serif; padding: 12px; color: $textColor; background-color: $bgColor; }
                        table { width: 100%; border-collapse: collapse; margin-top: 10px; margin-bottom: 10px; }
                        img { display: block; max-width: 100%; height: auto; margin: 10px 0; border-radius: 8px; }
                        th { background-color: $headerBg; font-weight: bold; text-align: left; padding: 8px; border: 1px solid $borderColor; color: $textColor; }
                        td { padding: 8px; border: 1px solid $borderColor; vertical-align: top; color: $textColor; }
                    </style>
                </head>
                <body>
                    $rawContent
                </body>
                </html>
            """.trimIndent()

            webViewContent.setBackgroundColor(Color.parseColor(bgColor))
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
            tvPdfContent?.visibility = View.VISIBLE

            val htmlFormatted = rawContent
                .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
                .replace("\n", "<br/>")

            tvPdfContent?.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(htmlFormatted, Html.FROM_HTML_MODE_COMPACT)
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(htmlFormatted)
            }

            tvPdfContent?.setTextColor(if (dark) Color.WHITE else Color.BLACK)
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

        popup.menu.add(0, 1, 0, "Edit Content")
        popup.menu.add(0, 2, 1, "Rename Note")
        popup.menu.add(0, 3, 2, "Delete Note")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    showEditContentDialog()
                    true
                }
                2 -> {
                    showRenameDialog()
                    true
                }
                3 -> {
                    showDeleteConfirmationDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(currentTitle)
            setSelection(currentTitle.length)
            setPadding(40, 32, 40, 32)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename Note")
            .setView(input)
            .setPositiveButton("Save") { d, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    currentTitle = newTitle
                    findViewById<TextView>(R.id.tvPdfTitle).text = newTitle
                    saveNoteToDatabase()
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    private fun showEditContentDialog() {
        // Strip complex HTML tags for user-friendly editing in plain text
        val editableContent = cleanHtmlAndMarkdown(currentRawContent)

        val input = EditText(this).apply {
            setText(editableContent)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(40, 32, 40, 32)
            minLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }

        val scrollContainer = ScrollView(this).apply {
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Note Content")
            .setView(scrollContainer)
            .setPositiveButton("Save") { d, _ ->
                val newText = input.text.toString()
                currentRawContent = newText.replace("\n", "<br/>")
                renderContent(currentRawContent)
                saveNoteToDatabase()
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
                canvas.save()
                canvas.translate(margin, currentY)
                titleLayout.draw(canvas)
                canvas.restore()

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

                    canvas.save()
                    canvas.translate(margin, currentY)
                    lineLayout.draw(canvas)
                    canvas.restore()

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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false)
        }
    }
}
