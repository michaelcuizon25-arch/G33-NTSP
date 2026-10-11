
package com.example.note2snap.debug

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.note2snap.ccl.ConnectedComponentLabeler
import com.example.note2snap.ccl.RegionType
import com.example.note2snap.preprocessing.WhiteboardPreprocessor
import java.io.File

object CclRegionDebug {

    fun run(context: Context, photo: Bitmap) {

        val directory = File(
            context.cacheDir,
            "ccl_debug"
        ).apply {
            mkdirs()
        }

        val preprocessed =
            WhiteboardPreprocessor().process(photo)

        saveBitmap(
            preprocessed.binarizedBitmap,
            File(directory, "binary.png")
        )

        val regions =
            ConnectedComponentLabeler().label(
                binary = preprocessed.binarizedBitmap,
                recognitionBitmap =
                    preprocessed.recognitionBitmap
            )

        regions.forEachIndexed { index, region ->

            val prefix =
                if (region.type == RegionType.TEXT) {
                    "text"
                } else {
                    "diagram"
                }

            val file = File(
                directory,
                "${prefix}_${index}.png"
            )

            saveBitmap(region.croppedBitmap, file)

            Log.d(
                "CCL_DEBUG",
                "${region.type}: ${file.absolutePath} " +
                        "box=${region.boundingBox}"
            )
        }

        Log.d(
            "CCL_DEBUG",
            "Finished: ${regions.count { it.type == RegionType.TEXT }} text regions, " +
                    "${regions.count { it.type == RegionType.NON_TEXT }} diagram regions"
        )
    }

    private fun saveBitmap(
        bitmap: Bitmap,
        file: File
    ) {
        file.outputStream().use {
            check(
                bitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    it
                )
            )
        }
    }
}
