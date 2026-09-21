package com.example.note2snap.ccl

import android.graphics.Bitmap
import android.graphics.Rect

enum class RegionType { TEXT, NON_TEXT }

data class Region(
    val id: Int,
    val boundingBox: Rect,
    val type: RegionType,
    val pixelArea: Int,
    val croppedBitmap: Bitmap
)
