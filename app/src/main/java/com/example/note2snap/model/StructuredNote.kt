package com.example.note2snap.model

import android.graphics.Rect

enum class BlockType {
    TITLE,
    SECTION_HEADER,
    SUBHEADING,
    BULLET_ITEM,
    NUMBERED_ITEM,
    KEY_DEFINITION,
    MATHEMATICAL,
    REGULAR_TEXT,
    VISUAL
}

data class NoteBlock(
    val rawText: String,
    val type: BlockType,
    val formattedText: String,

    // Used only when this block is an image/diagram.
    val imagePath: String? = null,

    // Original position on the whiteboard.
    val boundingBox: Rect? = null
)

data class StructuredNote(
    val title: String,
    val blocks: List<NoteBlock>
)