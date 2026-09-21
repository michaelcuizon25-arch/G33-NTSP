package com.example.note2snap.ccl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.roundToInt
import com.example.note2snap.preprocessing.WhiteboardPreprocessor

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ConnectedComponentDebugTest {

    @Test
    fun testWhiteboardRegions() {

        val testImages =
            listOf(
                "test_whiteboard_1.png",
                "test_whiteboard_2.png",
                "test_whiteboard_3.png",
                "test_whiteboard_4.png"
            )

        println()
        println("===================================")
        println("NOTE2SNAP MULTI-IMAGE REGION TEST")
        println("===================================")
        println("Images to test: ${testImages.size}")

        testImages.forEachIndexed { index, imageName ->

            println()
            println()
            println("###################################")
            println("TEST IMAGE ${index + 1}: $imageName")
            println("###################################")

            testSingleImage(
                imageName = imageName
            )
        }

        println()
        println()
        println("===================================")
        println("ALL WHITEBOARD TESTS FINISHED")
        println("===================================")
    }

    private fun testSingleImage(
        imageName: String
    ) {

        val inputStream =
            javaClass.classLoader
                ?.getResourceAsStream(
                    imageName
                )
                ?: error(
                    "$imageName not found"
                )

        val originalBitmap: Bitmap =
            BitmapFactory.decodeStream(
                inputStream
            )
                ?: error(
                    "Failed to decode $imageName"
                )

        println()
        println("-----------------------------------")
        println("IMAGE: $imageName")
        println("-----------------------------------")

        println(
            "Image size: " +
                    "${originalBitmap.width} x " +
                    "${originalBitmap.height}"
        )

        val preprocessor =
            WhiteboardPreprocessor()

        val preprocessingResult =
            preprocessor.process(
                originalBitmap
            )

        val binaryBitmap =
            preprocessingResult.binarizedBitmap

        val recognitionBitmap =
            preprocessingResult.recognitionBitmap

        val labeler =
            ConnectedComponentLabeler()

        val regions =
            labeler.label(
                binary = binaryBitmap,
                recognitionBitmap = originalBitmap
            )

        println()
        println(
            "TOTAL REGIONS: ${regions.size}"
        )

        println("-----------------------------------")

        var textCount = 0
        var visualCount = 0

        regions.forEachIndexed { index, region ->

            when (region.type) {

                RegionType.TEXT ->
                    textCount++

                RegionType.NON_TEXT ->
                    visualCount++
            }

            println(
                """
                Region ${index + 1}
                Type: ${region.type}
                Box:
                    left   = ${region.boundingBox.left}
                    top    = ${region.boundingBox.top}
                    right  = ${region.boundingBox.right}
                    bottom = ${region.boundingBox.bottom}
                Size:
                    ${region.boundingBox.width()} x ${region.boundingBox.height()}
                -------------------------------
                """.trimIndent()
            )
        }

        println()
        println(
            "TEXT REGIONS   : $textCount"
        )

        println(
            "VISUAL REGIONS : $visualCount"
        )

        println(
            "TOTAL          : ${regions.size}"
        )

        println("-----------------------------------")

        val imageFolderName =
            imageName
                .substringBeforeLast(".")
                .replace(
                    Regex("[^A-Za-z0-9_-]"),
                    "_"
                )

        val outputFolder =
            File(
                "build/note2snap-debug/$imageFolderName"
            )

        if (!outputFolder.exists()) {
            outputFolder.mkdirs()
        }

        saveDebugRegions(
            regions = regions,
            outputFolder = outputFolder
        )

        saveAnnotatedImage(
            originalBitmap = recognitionBitmap,
            regions = regions,
            outputFolder = outputFolder
        )

        saveBinaryImage(
            binaryBitmap = binaryBitmap,
            outputFolder = outputFolder
        )

        println()
        println(
            "Finished testing: $imageName"
        )

        println(
            "Output folder:"
        )

        println(
            outputFolder.absolutePath
        )
    }


    private fun saveDebugRegions(
        regions: List<Region>,
        outputFolder: File
    ) {

        val cropsFolder =
            File(
                outputFolder,
                "crops"
            )

        if (!cropsFolder.exists()) {
            cropsFolder.mkdirs()
        }

        regions.forEachIndexed { index, region ->

            val prefix =
                when (region.type) {

                    RegionType.TEXT ->
                        "TEXT"

                    RegionType.NON_TEXT ->
                        "VISUAL"
                }

            val outputFile =
                File(
                    cropsFolder,
                    "${prefix}_${index + 1}.png"
                )

            outputFile
                .outputStream()
                .use { stream ->

                    region.croppedBitmap.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        stream
                    )
                }
        }

        println()
        println(
            "Individual crops saved to:"
        )

        println(
            cropsFolder.absolutePath
        )
    }

    private fun saveAnnotatedImage(
        originalBitmap: Bitmap,
        regions: List<Region>,
        outputFolder: File
    ) {

        val annotatedBitmap =
            originalBitmap.copy(
                Bitmap.Config.ARGB_8888,
                true
            )

        val canvas =
            Canvas(
                annotatedBitmap
            )

        // RED = TEXT
        val textBoxPaint =
            Paint().apply {

                style =
                    Paint.Style.STROKE

                strokeWidth =
                    8f

                color =
                    Color.RED
            }

        // BLUE = VISUAL / DIAGRAM
        val visualBoxPaint =
            Paint().apply {

                style =
                    Paint.Style.STROKE

                strokeWidth =
                    12f

                color =
                    Color.BLUE
            }

        val labelPaint =
            Paint().apply {

                style =
                    Paint.Style.FILL

                textSize =
                    42f

                strokeWidth =
                    2f
            }

        println()
        println(
            "========== FINAL REGIONS =========="
        )

        regions.forEachIndexed { index, region ->

            val box =
                region.boundingBox

            println(
                "${index + 1}. " +
                        "${region.type} " +
                        "ID=${region.id} " +
                        "box=${box.width()}x${box.height()} " +
                        "coords=(${box.left},${box.top})-" +
                        "(${box.right},${box.bottom}) " +
                        "area=${region.pixelArea}"
            )
        }

        println(
            "==================================="
        )

        regions.forEachIndexed { index, region ->

            val box =
                region.boundingBox

            val boxPaint =
                when (region.type) {

                    RegionType.TEXT ->
                        textBoxPaint

                    RegionType.NON_TEXT ->
                        visualBoxPaint
                }

            canvas.drawRect(
                box.left.toFloat(),
                box.top.toFloat(),
                box.right.toFloat(),
                box.bottom.toFloat(),
                boxPaint
            )

            val label =
                when (region.type) {

                    RegionType.TEXT ->
                        "TEXT ${index + 1}"

                    RegionType.NON_TEXT ->
                        "VISUAL ${index + 1}"
                }

            labelPaint.color =
                when (region.type) {

                    RegionType.TEXT ->
                        Color.RED

                    RegionType.NON_TEXT ->
                        Color.BLUE
                }

            val labelY =
                if (box.top > 55) {

                    box.top.toFloat() -
                            10f

                } else {

                    box.bottom.toFloat() +
                            50f
                }

            canvas.drawText(
                label,
                box.left.toFloat(),
                labelY,
                labelPaint
            )
        }

        val outputFile =
            File(
                outputFolder,
                "debug_regions.png"
            )

        outputFile
            .outputStream()
            .use { stream ->

                annotatedBitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    stream
                )
            }

        println()
        println(
            "Annotated debug image saved to:"
        )

        println(
            outputFile.absolutePath
        )

        println()
        println("LEGEND:")
        println("RED  = TEXT")
        println("BLUE = VISUAL")
    }

    private fun saveBinaryImage(
        binaryBitmap: Bitmap,
        outputFolder: File
    ) {

        val outputFile =
            File(
                outputFolder,
                "binary.png"
            )

        outputFile
            .outputStream()
            .use { stream ->

                binaryBitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    stream
                )
            }

        println()
        println(
            "Binary image saved to:"
        )

        println(
            outputFile.absolutePath
        )
    }
}