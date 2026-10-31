package com.example.note2snap.preprocessing

import android.graphics.Bitmap

/** Implementations must leave the source untouched and preserve its coordinate space. */
fun interface WhiteboardEnhancer {
    fun enhance(source: Bitmap): WhiteboardEnhancement
}

data class WhiteboardEnhancement(val bitmap: Bitmap, val applied: Boolean, val reason: String? = null)
