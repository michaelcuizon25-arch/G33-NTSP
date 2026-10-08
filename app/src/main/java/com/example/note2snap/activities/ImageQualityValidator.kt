package com.example.note2snap.activities

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// ============================================================================
// PART 1: Still-image quality gate (runs before OCR on a captured/picked photo)
// ============================================================================

enum class ImageQualityIssue {
    BLURRY,
    TOO_DARK,
    TOO_BRIGHT,
    GLARE,
    BOARD_NOT_CLEAR
}

data class ImageQualityResult(
    val isValid: Boolean,
    val issue: ImageQualityIssue? = null,
    val title: String = "",
    val message: String = "",
    val brightness: Double = 0.0,
    val contrast: Double = 0.0,
    val edgeStrength: Double = 0.0
)

object ImageQualityValidator {

    /**
     * Conservative quality gate:
     * severe image-quality problems block OCR.
     *
     * Minor OCR uncertainty is handled downstream by Needs Review.
     */
    fun validate(
        file: File
    ): ImageQualityResult {

        val bitmap =
            decodeSampled(
                file,
                maxDimension = 900
            ) ?: return ImageQualityResult(
                isValid = false,
                issue = ImageQualityIssue.BOARD_NOT_CLEAR,
                title = "Image could not be checked",
                message =
                    "Note2Snap could not read this image. Please capture or choose another photo."
            )

        return try {
            analyze(
                bitmap
            )
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun analyze(
        bitmap: Bitmap
    ): ImageQualityResult {

        val width = bitmap.width
        val height = bitmap.height

        if (width < 120 || height < 120) {
            return invalid(
                ImageQualityIssue.BOARD_NOT_CLEAR,
                "Whiteboard is not clear",
                "The image is too small to scan reliably. Move closer or choose a clearer photo."
            )
        }

        // Sampling keeps validation fast on mobile.
        val step = max(1, minOf(width, height) / 280)

        var count = 0L
        var sum = 0.0
        var sumSquares = 0.0
        var darkCount = 0L
        var brightCount = 0L
        var inkCount = 0L

        var maskedEdgeTotal = 0.0
        var maskedEdgeSamples = 0L

        // 4 x 4 grid used to detect localized specular glare.
        val gridSize = 4
        val cellGlare = LongArray(gridSize * gridSize)
        val cellCount = LongArray(gridSize * gridSize)

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val gray = luminance(pixel)

                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                val saturation = maxC - minC

                count++
                sum += gray
                sumSquares += gray * gray

                if (gray < 40.0) {
                    darkCount++
                }

                if (gray > 245.0) {
                    brightCount++
                }

                // Ink heuristic: reasonably dark and not heavily saturated background surface
                if (gray < 165.0 && saturation < 100) {
                    inkCount++
                }

                val cellX = (x * gridSize / width).coerceIn(0, gridSize - 1)
                val cellY = (y * gridSize / height).coerceIn(0, gridSize - 1)
                val cell = cellY * gridSize + cellX

                cellCount[cell]++

                // Specular Glare check: extremely bright with near-zero saturation (pure white highlight blob)
                if (maxC > 242 && saturation < 28) {
                    cellGlare[cell]++
                }

                // MASKED EDGE STRENGTH:
                // Exclude clipped white background (gray > 240) and extreme shadows (gray < 25).
                // This prevents bright, clean whiteboards from artificially deflating edge strength.
                val isValidForEdge = gray in 25.0..240.0

                if (x + step < width) {
                    val rightPixel = bitmap.getPixel(x + step, y)
                    val rightGray = luminance(rightPixel)
                    if (isValidForEdge && rightGray in 25.0..240.0) {
                        maskedEdgeTotal += abs(gray - rightGray)
                        maskedEdgeSamples++
                    }
                }

                if (y + step < height) {
                    val downPixel = bitmap.getPixel(x, y + step)
                    val downGray = luminance(downPixel)
                    if (isValidForEdge && downGray in 25.0..240.0) {
                        maskedEdgeTotal += abs(gray - downGray)
                        maskedEdgeSamples++
                    }
                }

                x += step
            }
            y += step
        }

        if (count <= 0) {
            return invalid(
                ImageQualityIssue.BOARD_NOT_CLEAR,
                "Whiteboard is not clear",
                "Note2Snap could not inspect this image. Please try another photo."
            )
        }

        val average = sum / count
        val variance = (sumSquares / count) - (average * average)
        val contrast = sqrt(variance.coerceAtLeast(0.0))

        val edgeStrength = if (maskedEdgeSamples > 0) {
            maskedEdgeTotal / maskedEdgeSamples
        } else {
            0.0
        }

        val darkRatio = darkCount.toDouble() / count
        val brightRatio = brightCount.toDouble() / count
        val inkRatio = inkCount.toDouble() / count

        var strongestGlareCell = 0.0
        cellCount.indices.forEach { index ->
            if (cellCount[index] > 0) {
                val ratio = cellGlare[index].toDouble() / cellCount[index]
                if (ratio > strongestGlareCell) {
                    strongestGlareCell = ratio
                }
            }
        }

        // 1. Severe low light check
        if (average < 38.0 || darkRatio > 0.72) {
            return invalid(
                ImageQualityIssue.TOO_DARK,
                "Lighting is too low",
                "Move to a brighter area or turn on the flash, then try again.",
                average,
                contrast,
                edgeStrength
            )
        }

        // 2. Severe overall overexposure check (evaluated before blur!)
        val severelyOverexposed =
            (
                average > 250.0 &&
                contrast < 10.0
            ) ||
            (
                brightRatio > 0.88 &&
                inkRatio < 0.004
            )

        if (severelyOverexposed) {
            return invalid(
                ImageQualityIssue.TOO_BRIGHT,
                "Image is too bright",
                "Most of the board is washed out by light. Reduce direct light or change your camera angle, then try again.",
                average,
                contrast,
                edgeStrength
            )
        }

        // 3. Localized specular glare check
        if (
            strongestGlareCell > 0.72 &&
            brightRatio < 0.70
        ) {
            return invalid(
                ImageQualityIssue.GLARE,
                "Strong glare detected",
                "A bright reflection is covering part of the board. Change your angle or reduce direct light.",
                average,
                contrast,
                edgeStrength
            )
        }

        // 4. Missing board or unreadable content check
        if (
            contrast < 6.0 &&
            inkRatio < 0.0012
        ) {
            return invalid(
                ImageQualityIssue.BOARD_NOT_CLEAR,
                "Whiteboard is not clear",
                "Make sure the board and its writing are visible inside the frame, then try again.",
                average,
                contrast,
                edgeStrength
            )
        }

        // 5. Masked Blur & Dirty Lens check
        if (edgeStrength < 2.6) {
            val isSmudgedLens = contrast in 12.0..28.0
            val message = if (isSmudgedLens) {
                "Clean your camera lens, hold your phone steady, and capture the board again."
            } else {
                "Hold your phone steady and capture the board again."
            }

            return invalid(
                ImageQualityIssue.BLURRY,
                "Image looks blurry",
                message,
                average,
                contrast,
                edgeStrength
            )
        }

        return ImageQualityResult(
            isValid = true,
            brightness = average,
            contrast = contrast,
            edgeStrength = edgeStrength
        )
    }

    private fun invalid(
        issue: ImageQualityIssue,
        title: String,
        message: String,
        brightness: Double = 0.0,
        contrast: Double = 0.0,
        edgeStrength: Double = 0.0
    ): ImageQualityResult {
        return ImageQualityResult(
            isValid = false,
            issue = issue,
            title = title,
            message = message,
            brightness = brightness,
            contrast = contrast,
            edgeStrength = edgeStrength
        )
    }

    private fun luminance(pixel: Int): Double {
        return Color.red(pixel) * 0.299 +
                Color.green(pixel) * 0.587 +
                Color.blue(pixel) * 0.114
    }

    private fun decodeSampled(
        file: File,
        maxDimension: Int
    ): Bitmap? {

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(file.absolutePath, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }
}

// ============================================================================
// PART 2: Live camera preview checks (runs while the user is aiming the camera)
// ============================================================================

/**
 * Hints shown to the user WHILE they are aiming the camera at the board
 * (before they press capture).
 *
 * [framesRequired] is how many consecutive analyzed frames (~4 per second)
 * the problem must persist before the hint appears. This avoids flicker while
 * autofocus is hunting or the user is still moving the phone.
 */
enum class LiveCameraHint(
    val title: String,
    val message: String,
    val framesRequired: Int
) {
    LENS_DIRTY(
        title = "Camera lens may be dirty",
        message = "Wipe the lens with a soft, clean cloth.",
        framesRequired = 8
    ),
    BLURRY(
        title = "Image looks blurry",
        message = "Hold steady, move slightly back, and tap the board to focus.",
        framesRequired = 8
    ),
    TOO_DARK(
        title = "Lighting is too low",
        message = "Move to a brighter area or turn on the flash.",
        framesRequired = 6
    ),
    TOO_BRIGHT(
        title = "Image is too bright",
        message = "Reduce direct light or change your camera angle.",
        framesRequired = 6
    ),
    GLARE(
        title = "Strong glare detected",
        message = "Change your angle or reduce direct light on the board.",
        framesRequired = 7
    )
}

/**
 * Lightweight live quality check on the camera preview.
 *
 * Works only on the Y (luminance) plane of the YUV frame, so it is cheap
 * enough to run a few times per second on the camera executor.
 *
 * [onHintChanged] is called ONLY when the visible hint changes
 * (null = no problem / hide the hint). It is called on the analyzer thread,
 * so the caller must switch to the main thread before touching views.
 *
 * Note: software cannot be 100% sure a persistently soft image is caused by a
 * dirty lens rather than focus, so the hint wording is "may be dirty".
 * Tune the constants below on real devices.
 */
class LiveCameraQualityAnalyzer(
    private val onHintChanged: (LiveCameraHint?) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastAnalyzedAt = 0L
    private var previousSignature: IntArray? = null

    private var candidate: LiveCameraHint? = null
    private var candidateStreak = 0
    private var goodStreak = 0
    private var shownHint: LiveCameraHint? = null

    private class Metrics(
        val average: Double,
        val contrast: Double,
        val edgeStrength: Double,
        val darkRatio: Double,
        val brightRatio: Double,
        val strongestGlareCell: Double,
        val motion: Double
    )

    override fun analyze(image: ImageProxy) {
        try {
            if (resetRequested) {
                resetRequested = false
                performReset()
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalyzedAt < ANALYSIS_INTERVAL_MS) return
            lastAnalyzedAt = now

            val metrics = measure(image) ?: return

            // Phone is moving: blur is expected, so don't judge blur/lens now.
            val moving = metrics.motion > MOTION_LIMIT
            val verdict = classify(metrics, moving) ?: run {
                // Either fine, or moving with nothing else wrong.
                if (!moving) registerGood()
                return
            }

            registerBad(verdict)
        } catch (error: Exception) {
            Log.w(TAG, "Live quality analysis failed", error)
        } finally {
            image.close()
        }
    }

    private fun classify(m: Metrics, moving: Boolean): LiveCameraHint? {
        if (m.average < DARK_AVERAGE || m.darkRatio > DARK_RATIO) {
            return LiveCameraHint.TOO_DARK
        }

        if (m.average > BRIGHT_AVERAGE || m.brightRatio > BRIGHT_RATIO) {
            return LiveCameraHint.TOO_BRIGHT
        }

        if (m.strongestGlareCell > GLARE_CELL_RATIO && m.brightRatio < GLARE_MAX_GLOBAL_BRIGHT) {
            return LiveCameraHint.GLARE
        }

        // Flat scene (blank wall / empty board): nothing to judge sharpness on.
        if (m.contrast < MIN_CONTRAST_TO_JUDGE) return null

        if (m.edgeStrength < BLUR_EDGE_STRENGTH) {
            if (moving) return null

            // Steady phone + persistently soft + low-contrast haze => smudged lens.
            return if (m.contrast in LENS_CONTRAST_MIN..LENS_CONTRAST_MAX) {
                LiveCameraHint.LENS_DIRTY
            } else {
                LiveCameraHint.BLURRY
            }
        }

        return null
    }

    private fun registerBad(hint: LiveCameraHint) {
        goodStreak = 0

        if (candidate == hint) {
            candidateStreak++
        } else {
            candidate = hint
            candidateStreak = 1
        }

        // BLURRY can escalate to LENS_DIRTY and vice-versa; show once persistent.
        if (candidateStreak >= hint.framesRequired && shownHint != hint) {
            shownHint = hint
            onHintChanged(hint)
        }
    }

    private fun registerGood() {
        candidate = null
        candidateStreak = 0
        goodStreak++

        if (goodStreak >= GOOD_FRAMES_TO_CLEAR && shownHint != null) {
            shownHint = null
            onHintChanged(null)
        }
    }

    @Volatile
    private var resetRequested = false

    /**
     * Safe to call from any thread (e.g. the main thread when a scan starts).
     * State is cleared on the next analyzed frame so a still-present problem
     * can be shown again afterwards.
     */
    fun reset() {
        resetRequested = true
    }

    private fun performReset() {
        candidate = null
        candidateStreak = 0
        goodStreak = 0
        previousSignature = null
        shownHint = null
    }

    private fun measure(image: ImageProxy): Metrics? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height

        if (width < 64 || height < 64) return null

        // Central 70% of the frame (where the board should be).
        val x0 = (width * 0.15).toInt()
        val x1 = (width * 0.85).toInt()
        val y0 = (height * 0.15).toInt()
        val y1 = (height * 0.85).toInt()
        val regionW = x1 - x0
        val regionH = y1 - y0

        fun lumaAt(x: Int, y: Int): Int {
            val index = y * rowStride + x * pixelStride
            return if (index in 0 until buffer.limit()) buffer.get(index).toInt() and 0xFF else 0
        }

        val gridSize = 4
        val cellGlare = LongArray(gridSize * gridSize)
        val cellCount = LongArray(gridSize * gridSize)

        // 8 x 8 mean-luma signature used to estimate motion between frames.
        val sigSize = 8
        val sigSum = LongArray(sigSize * sigSize)
        val sigCount = IntArray(sigSize * sigSize)

        var count = 0L
        var sum = 0.0
        var sumSquares = 0.0
        var darkCount = 0L
        var brightCount = 0L
        var edgeTotal = 0.0
        var edgeSamples = 0L

        var y = y0
        while (y < y1 - SAMPLE_STEP) {
            var x = x0
            while (x < x1 - SAMPLE_STEP) {
                val gray = lumaAt(x, y)

                count++
                sum += gray
                sumSquares += gray.toDouble() * gray

                if (gray < 40) darkCount++
                if (gray > 245) brightCount++

                val cx = ((x - x0) * gridSize / regionW).coerceIn(0, gridSize - 1)
                val cy = ((y - y0) * gridSize / regionH).coerceIn(0, gridSize - 1)
                val cell = cy * gridSize + cx
                cellCount[cell]++
                if (gray > 245) cellGlare[cell]++

                val sx = ((x - x0) * sigSize / regionW).coerceIn(0, sigSize - 1)
                val sy = ((y - y0) * sigSize / regionH).coerceIn(0, sigSize - 1)
                val sig = sy * sigSize + sx
                sigSum[sig] += gray.toLong()
                sigCount[sig]++

                // Masked edge strength (same idea as ImageQualityValidator):
                // ignore clipped white / deep shadow so clean boards aren't penalized.
                if (gray in 25..240) {
                    val right = lumaAt(x + SAMPLE_STEP, y)
                    if (right in 25..240) {
                        edgeTotal += abs(gray - right)
                        edgeSamples++
                    }
                    val down = lumaAt(x, y + SAMPLE_STEP)
                    if (down in 25..240) {
                        edgeTotal += abs(gray - down)
                        edgeSamples++
                    }
                }

                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }

        if (count <= 0) return null

        val average = sum / count
        val variance = (sumSquares / count) - (average * average)
        val contrast = sqrt(variance.coerceAtLeast(0.0))
        val edgeStrength = if (edgeSamples > 0) edgeTotal / edgeSamples else 0.0

        var strongestGlare = 0.0
        for (i in cellCount.indices) {
            if (cellCount[i] > 0) {
                val ratio = cellGlare[i].toDouble() / cellCount[i]
                if (ratio > strongestGlare) strongestGlare = ratio
            }
        }

        val signature = IntArray(sigSize * sigSize) { i ->
            if (sigCount[i] > 0) (sigSum[i] / sigCount[i]).toInt() else 0
        }

        val previous = previousSignature
        val motion = if (previous != null && previous.size == signature.size) {
            var diff = 0.0
            for (i in signature.indices) diff += abs(signature[i] - previous[i])
            diff / signature.size
        } else {
            0.0
        }
        previousSignature = signature

        return Metrics(
            average = average,
            contrast = contrast,
            edgeStrength = edgeStrength,
            darkRatio = darkCount.toDouble() / count,
            brightRatio = brightCount.toDouble() / count,
            strongestGlareCell = strongestGlare,
            motion = motion
        )
    }

    companion object {
        private const val TAG = "LiveQuality"

        private const val ANALYSIS_INTERVAL_MS = 250L
        private const val SAMPLE_STEP = 3
        private const val GOOD_FRAMES_TO_CLEAR = 3

        // Mirrors ImageQualityValidator thresholds so live hints and the
        // capture-time gate agree with each other.
        private const val DARK_AVERAGE = 42.0
        private const val DARK_RATIO = 0.68
        private const val BRIGHT_AVERAGE = 248.0
        private const val BRIGHT_RATIO = 0.78
        private const val BLUR_EDGE_STRENGTH = 3.0
        private const val MIN_CONTRAST_TO_JUDGE = 7.0
        private const val LENS_CONTRAST_MIN = 10.0
        private const val LENS_CONTRAST_MAX = 28.0

        // Live-only: stricter glare so a bright clean whiteboard doesn't trigger it.
        private const val GLARE_CELL_RATIO = 0.62
        private const val GLARE_MAX_GLOBAL_BRIGHT = 0.25

        // Mean per-cell luma change between frames above which we consider
        // the phone to be moving.
        private const val MOTION_LIMIT = 6.0
    }
}