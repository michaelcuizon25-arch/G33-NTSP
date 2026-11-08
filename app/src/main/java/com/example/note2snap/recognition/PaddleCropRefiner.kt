package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Model-input geometry only. Never replaces a PhysicalLine/Region source bounding box. */
internal data class PaddleCropRefinement(
    val originalWidth: Int, val originalHeight: Int,
    val top: Int = 0, val bottom: Int = originalHeight,
    val reason: String, val scratchBytes: Int = 0
) {
    val applied get() = top != 0 || bottom != originalHeight
    val localBox get() = Rect(0, top, originalWidth, bottom)
    val inputHeight get() = bottom - top
}

/**
 * Experimental exact-background verifier. Photographic texture is uncertain, not empty.
 * A strict RGB majority identifies a flat background. Every non-background pixel,
 * even a one-level faint mark or isolated punctuation pixel, expands protected bounds.
 * This deliberately abstains on many photographed whiteboards, including inset frames.
 */
internal object PaddleCropRefiner {
    const val REVISION = "flat-background-v1"
    private const val MAX_PIXELS = 4_000_000L
    private const val MAX_ROW_WIDTH = 8192

    fun analyze(source: Bitmap): PaddleCropRefinement {
        val w = source.width
        val h = source.height
        fun unchanged(reason: String, scratch: Int = 0) =
            PaddleCropRefinement(w, h, reason = reason, scratchBytes = scratch)
        if (source.isRecycled || w < 3 || h < 8) return unchanged("unsupported_input")
        if (w > MAX_ROW_WIDTH || w.toLong() * h > MAX_PIXELS) return unchanged("analysis_budget")
        try { PaddleInputPreprocessor.geometry(w, h) }
        catch (_: IllegalArgumentException) { return unchanged("original_aspect_ratio_limit") }

        // Boyer-Moore finds a possible exact majority using one reusable pixel row.
        // A separate pass verifies the majority and protects ALL other opaque pixels.
        val row = IntArray(w)
        var candidate = 0
        var balance = 0
        for (y in 0 until h) {
            source.getPixels(row, 0, w, 0, y, w, 1)
            for (pixel in row) {
                if (pixel ushr 24 != 255) return unchanged("transparent_input", w * 4)
                if (balance == 0) { candidate = pixel; balance = 1 }
                else if (pixel == candidate) balance++ else balance--
            }
        }
        var backgroundCount = 0L
        var foregroundCount = 0
        var first = h
        var last = -1
        var strongestContrast = 0
        for (y in 0 until h) {
            source.getPixels(row, 0, w, 0, y, w, 1)
            for (pixel in row) {
                if (pixel == candidate) backgroundCount++
                else {
                    foregroundCount++
                    first = min(first, y); last = max(last, y)
                    strongestContrast = max(max(strongestContrast,
                        abs((pixel and 255) - (candidate and 255))), max(
                        abs(((pixel ushr 8) and 255) - ((candidate ushr 8) and 255)),
                        abs(((pixel ushr 16) and 255) - ((candidate ushr 16) and 255))))
                }
            }
        }
        if (backgroundCount * 100 < w.toLong() * h * 55)
            return unchanged("background_uncertain", w * 4)
        if (foregroundCount < 8 || last - first < 2 || strongestContrast < 16)
            return unchanged("insufficient_handwriting_evidence", w * 4)

        val inkHeight = last - first + 1
        val padding = max(3, ceil(inkHeight * 0.08).toInt())
        var topTrim = max(0, first - padding)
        var bottomTrim = max(0, h - (last + 1 + padding))
        val totalTrim = topTrim + bottomTrim
        if (totalTrim < 2) return unchanged("no_verified_margin", w * 4)
        // Keep at least 80% of the original height (at most 1.25x scale gain).
        // Excess verified whitespace stays as extra safety padding.
        val budget = h / 5
        if (totalTrim > budget) {
            topTrim = (topTrim.toLong() * budget / totalTrim).toInt()
            bottomTrim = budget - topTrim
        }
        if (topTrim + bottomTrim < 2) return unchanged("no_material_trim", w * 4)
        val bottom = h - bottomTrim
        try { PaddleInputPreprocessor.geometry(w, bottom - topTrim) }
        catch (_: IllegalArgumentException) { return unchanged("refined_aspect_ratio_limit", w * 4) }
        return PaddleCropRefinement(w, h, topTrim, bottom, "verified_flat_margin", w * 4)
    }
}
