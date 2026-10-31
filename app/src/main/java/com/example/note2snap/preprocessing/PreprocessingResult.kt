package com.example.note2snap.preprocessing

import android.graphics.Bitmap

data class PreprocessingResult(
    val binarizedBitmap: Bitmap,
    val recognitionBitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
    // Same bounded coordinate space as recognitionBitmap; never mutated by enhancement.
    val originalRecognitionBitmap: Bitmap = recognitionBitmap,
    val enhancementRequested: Boolean = false,
    val enhancementApplied: Boolean = false,
    val enhancementReason: String? = null,
    val enhancementElapsedMs: Long = 0
)
