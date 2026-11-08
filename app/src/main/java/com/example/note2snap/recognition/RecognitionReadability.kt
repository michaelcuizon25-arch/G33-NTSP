package com.example.note2snap.recognition

import android.text.TextUtils
import com.example.note2snap.model.StructuredNote

/** Validate OCR before title extraction can remove it from the body blocks. */
internal object RecognitionReadability {
    fun hasReadableWriting(lines: List<RecognizedLine>): Boolean =
        lines.any { line -> line.text.count { it.isLetterOrDigit() } >= 3 }

    fun htmlContent(note: StructuredNote): String {
        val titleHeader = "<h1>${TextUtils.htmlEncode(note.title)}</h1>"
        if (note.blocks.isEmpty()) return titleHeader
        return titleHeader + "<br/><br/>" + note.blocks.joinToString("<br/><br/>") { it.formattedText }
    }
}
