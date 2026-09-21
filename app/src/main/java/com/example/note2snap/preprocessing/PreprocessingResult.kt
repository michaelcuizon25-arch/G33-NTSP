package com.example.note2snap.preprocessing

import android.graphics.Bitmap

data class PreprocessingResult(
    val binarizedBitmap: Bitmap,
    val recognitionBitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int
)
