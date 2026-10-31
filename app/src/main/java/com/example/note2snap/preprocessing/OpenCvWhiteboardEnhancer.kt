package com.example.note2snap.preprocessing

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Geometry-preserving, offline enhancement at WhiteboardPreprocessor's bounded size.
 * CLAHE on luminance retains colored markers; a small bilateral filter and a blend
 * with the original luminance limit noise amplification and preserve thin strokes.
 * No binarization, morphology, inversion, crop or geometric warp is performed here.
 * Perspective is intentionally inactive: the scanner supplies no validated board
 * corners. Guessed contours could discard handwriting and invalidate diagram boxes.
 */
class OpenCvWhiteboardEnhancer : WhiteboardEnhancer {
    override fun enhance(source: Bitmap): WhiteboardEnhancement {
        if (!NativeRuntime.available) return WhiteboardEnhancement(source, false, "native_library_unavailable")
        // CLAHE tile statistics are unsuitable for very small images.
        if (source.width < 16 || source.height < 16) return WhiteboardEnhancement(source, false, "image_too_small")
        val rgba = Mat()
        val rgb = Mat()
        val lab = Mat()
        val filtered = Mat()
        val contrast = Mat()
        val blended = Mat()
        val channels = ArrayList<Mat>(3)
        val clahe = Imgproc.createCLAHE(1.5, Size(8.0, 8.0))
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            Core.split(lab, channels)
            // Lab L is the grayscale/luminance plane. Leave chroma unchanged.
            Imgproc.bilateralFilter(channels[0], filtered, 3, 10.0, 1.5)
            clahe.apply(filtered, contrast)
            Core.addWeighted(channels[0], 0.5, contrast, 0.5, 0.0, blended)
            val luminance = channels[0]
            channels[0] = blended
            try { Core.merge(channels, lab) } finally { channels[0] = luminance }
            Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
            Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
            val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(rgba, result)
            } catch (error: Throwable) {
                result.recycle()
                throw error
            }
            return WhiteboardEnhancement(result, true)
        } finally {
            channels.forEach { it.release() }
            blended.release()
            contrast.release()
            filtered.release()
            lab.release()
            rgb.release()
            rgba.release()
            clahe.collectGarbage()
            clahe.clear()
        }
    }

    private object NativeRuntime {
        // Load the bundled native library once, only when the feature is requested.
        val available: Boolean = try {
            OpenCVLoader.initLocal()
        } catch (_: LinkageError) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
