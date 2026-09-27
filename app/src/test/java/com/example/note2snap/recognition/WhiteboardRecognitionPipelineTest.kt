package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.note2snap.ccl.ConnectedComponentLabeler
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
import com.example.note2snap.preprocessing.WhiteboardPreprocessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class WhiteboardRecognitionPipelineTest {

    @Test
    fun saveTextLinesAndNonTextRegions() = runBlocking {

        val images = listOf(
            "board_test_1.png",
            "board_test_2.png",
            "board_test_3.png",
            "board_test_4.jpg",
            "board_test_5.jpg",
            "board_test_6.jpg"
        )

        val preprocessor =
            WhiteboardPreprocessor()

        val labeler =
            ConnectedComponentLabeler()

        images.forEach { imageName ->

            println()
            println("===================================")
            println("TESTING: $imageName")
            println("===================================")

            val originalBitmap =
                loadBitmap(imageName)

            val preprocessed =
                preprocessor.process(
                    originalBitmap
                )

            val regions =
                labeler.label(
                    binary =
                        preprocessed.binarizedBitmap,

                    recognitionBitmap =
                        preprocessed.recognitionBitmap
                )

            val textRegions =
                regions
                    .filter {
                        it.type == RegionType.TEXT
                    }
                    .sortedWith(
                        compareBy(
                            { it.boundingBox.top },
                            { it.boundingBox.left }
                        )
                    )

            val nonTextRegions =
                regions
                    .filter {
                        it.type == RegionType.NON_TEXT
                    }
                    .sortedWith(
                        compareBy(
                            { it.boundingBox.top },
                            { it.boundingBox.left }
                        )
                    )

            println(
                "TOTAL REGIONS: ${regions.size}"
            )

            println(
                "TEXT LINES: ${textRegions.size}"
            )

            println(
                "NON-TEXT REGIONS: ${nonTextRegions.size}"
            )

            println()

            println(
                "---------- TEXT LINES ----------"
            )

            textRegions.forEachIndexed { index, region ->

                println(
                    "LINE ${index + 1} -> " +
                            "${region.boundingBox}"
                )
            }

            println()

            println(
                "---------- NON-TEXT ----------"
            )

            nonTextRegions.forEachIndexed { index, region ->

                println(
                    "NON-TEXT ${index + 1} -> " +
                            "${region.boundingBox}"
                )
            }

            saveDebugOutput(
                imageName =
                    imageName,

                originalBitmap =
                    originalBitmap,

                recognitionBitmap =
                    preprocessed.recognitionBitmap,

                binaryBitmap =
                    preprocessed.binarizedBitmap,

                textRegions =
                    textRegions,

                nonTextRegions =
                    nonTextRegions
            )

            assertTrue(
                "$imageName should produce at least one region.",
                regions.isNotEmpty()
            )
        }
    }

    private fun loadBitmap(
        resourceName: String
    ): Bitmap {

        val currentDirectory =
            File(
                System.getProperty("user.dir")
                    ?: "."
            )

        val resourceFile =
            if (
                currentDirectory.name == "app"
            ) {

                File(
                    currentDirectory,
                    "src/test/resources/$resourceName"
                )

            } else {

                File(
                    currentDirectory,
                    "app/src/test/resources/$resourceName"
                )
            }

        if (!resourceFile.exists()) {

            error(
                "File not found: ${resourceFile.absolutePath}"
            )
        }

        return BitmapFactory.decodeFile(
            resourceFile.absolutePath
        )
            ?: error(
                "Failed to decode $resourceName"
            )
    }

    private fun saveDebugOutput(
        imageName: String,
        originalBitmap: Bitmap,
        recognitionBitmap: Bitmap,
        binaryBitmap: Bitmap,
        textRegions: List<Region>,
        nonTextRegions: List<Region>
    ) {

        val root =
            getOutputDirectory()

        val boardFolder =
            File(
                root,
                imageName.substringBeforeLast(".")
            ).apply {
                mkdirs()
            }

        val textFolder =
            File(
                boardFolder,
                "TEXT_LINES"
            ).apply {
                mkdirs()
            }

        val nonTextFolder =
            File(
                boardFolder,
                "NON_TEXT"
            ).apply {
                mkdirs()
            }

        clearFolder(
            textFolder
        )

        clearFolder(
            nonTextFolder
        )

        saveBitmap(
            bitmap =
                originalBitmap,

            file =
                File(
                    boardFolder,
                    "01_original.png"
                )
        )

        saveBitmap(
            bitmap =
                recognitionBitmap,

            file =
                File(
                    boardFolder,
                    "02_recognition.png"
                )
        )

        saveBitmap(
            bitmap =
                binaryBitmap,

            file =
                File(
                    boardFolder,
                    "03_binary.png"
                )
        )

        textRegions.forEachIndexed { index, region ->

            val lineNumber =
                index + 1

            saveBitmap(
                bitmap =
                    region.croppedBitmap,

                file =
                    File(
                        textFolder,
                        "line_${lineNumber}.png"
                    )
            )
        }

        nonTextRegions.forEachIndexed { index, region ->

            val regionNumber =
                index + 1

            saveBitmap(
                bitmap =
                    region.croppedBitmap,

                file =
                    File(
                        nonTextFolder,
                        "non_text_${regionNumber}.png"
                    )
            )
        }

        saveSummary(
            imageName =
                imageName,

            boardFolder =
                boardFolder,

            textRegions =
                textRegions,

            nonTextRegions =
                nonTextRegions
        )

        println()
        println("DEBUG FILES SAVED TO:")
        println(boardFolder.absolutePath)
        println()
    }

    private fun saveSummary(
        imageName: String,
        boardFolder: File,
        textRegions: List<Region>,
        nonTextRegions: List<Region>
    ) {

        val summaryFile =
            File(
                boardFolder,
                "summary.txt"
            )

        summaryFile.writeText(
            buildString {

                appendLine(
                    "IMAGE: $imageName"
                )

                appendLine()

                appendLine(
                    "TEXT LINES: ${textRegions.size}"
                )

                appendLine(
                    "NON-TEXT REGIONS: ${nonTextRegions.size}"
                )

                appendLine()

                appendLine(
                    "========== TEXT LINES =========="
                )

                textRegions.forEachIndexed { index, region ->

                    appendLine(
                        "LINE ${index + 1}"
                    )

                    appendLine(
                        "ID: ${region.id}"
                    )

                    appendLine(
                        "BOX: ${region.boundingBox}"
                    )

                    appendLine(
                        "WIDTH: ${region.boundingBox.width()}"
                    )

                    appendLine(
                        "HEIGHT: ${region.boundingBox.height()}"
                    )

                    appendLine(
                        "PIXEL AREA: ${region.pixelArea}"
                    )

                    appendLine()
                }

                appendLine(
                    "========== NON-TEXT REGIONS =========="
                )

                nonTextRegions.forEachIndexed { index, region ->

                    appendLine(
                        "NON-TEXT ${index + 1}"
                    )

                    appendLine(
                        "ID: ${region.id}"
                    )

                    appendLine(
                        "BOX: ${region.boundingBox}"
                    )

                    appendLine(
                        "WIDTH: ${region.boundingBox.width()}"
                    )

                    appendLine(
                        "HEIGHT: ${region.boundingBox.height()}"
                    )

                    appendLine(
                        "PIXEL AREA: ${region.pixelArea}"
                    )

                    appendLine()
                }
            }
        )
    }

    private fun saveBitmap(
        bitmap: Bitmap,
        file: File
    ) {

        FileOutputStream(
            file
        ).use { stream ->

            bitmap.compress(
                Bitmap.CompressFormat.PNG,
                100,
                stream
            )
        }
    }

    private fun clearFolder(
        folder: File
    ) {

        folder
            .listFiles()
            ?.forEach { file ->

                if (file.isFile) {
                    file.delete()
                }
            }
    }

    private fun getOutputDirectory(): File {

        val currentDirectory =
            File(
                System.getProperty("user.dir")
                    ?: "."
            )

        val appDirectory =
            if (
                currentDirectory.name == "app"
            ) {

                currentDirectory

            } else {

                File(
                    currentDirectory,
                    "app"
                )
            }

        return File(
            appDirectory,
            "build/note2snap-debug"
        ).apply {
            mkdirs()
        }
    }
}