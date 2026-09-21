package com.example.note2snap.recognition

import android.graphics.Rect

data class RecognizedLine(
    val sourceRegionId: Int,
    val boundingBox: Rect,
    val text: String,
    val confidence: Float
)
