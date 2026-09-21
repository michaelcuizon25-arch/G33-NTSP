package com.example.note2snap.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DocxExporter {

    fun shareAsDocx(context: Context, title: String, content: String) {
        val docxFile = createDocxFile(context, title, content)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            docxFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Share Note via DOCX"))
    }

    private fun createDocxFile(context: Context, title: String, content: String): File {
        val safeFileName = title.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val file = File(context.cacheDir, "$safeFileName.docx")

        fun String.xmlEscape() = this.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val paragraphXml = content.lines().joinToString("") { line ->
            "<w:p><w:r><w:t>${line.xmlEscape()}</w:t></w:r></w:p>"
        }

        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    <w:p>
                        <w:r><w:rPr><w:b/><w:sz w:val="36"/></w:rPr><w:t>${title.xmlEscape()}</w:t></w:r>
                    </w:p>
                    $paragraphXml
                </w:body>
            </w:document>
        """.trimIndent()

        val contentTypesXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
        """.trimIndent()

        val relsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
        """.trimIndent()

        val wordRelsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>
        """.trimIndent()

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            fun addEntry(name: String, contentStr: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contentStr.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            addEntry("[Content_Types].xml", contentTypesXml)
            addEntry("_rels/.rels", relsXml)
            addEntry("word/document.xml", documentXml)
            addEntry("word/_rels/document.xml.rels", wordRelsXml)
        }

        return file
    }
}