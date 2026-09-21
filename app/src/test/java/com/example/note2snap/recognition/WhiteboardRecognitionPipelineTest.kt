package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.example.note2snap.ccl.Region
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class WhiteboardRecognitionPipelineTest {

    @Test
    fun binaryTreePipelineDoesNotDuplicateDiagramAsText() = runBlocking {

        val bitmap =
            loadBitmap(
                "test_whiteboard_1.png"
            )

        val fakeRecognizer =
            EchoLineRecognizer()

        val pipeline =
            WhiteboardRecognitionPipeline(
                lineRecognizer = fakeRecognizer
            )

        val result =
            pipeline.process(
                bitmap
            )

        println()
        println("===================================")
        println("FULL PIPELINE DUPLICATION TEST")
        println("===================================")

        println(
            "Recognized text lines: " +
                    result.recognizedLines.size
        )

        println(
            "Diagram regions: " +
                    result.diagramRegions.size
        )

        println()

        println("---------- DIAGRAMS ----------")

        result.diagramRegions.forEachIndexed { index, region ->

            println(
                "Diagram ${index + 1}: " +
                        "${region.boundingBox}"
            )
        }

        println()
        println("---------- OCR TEXT ----------")

        result.recognizedLines.forEachIndexed { index, line ->

            println(
                "${index + 1}. " +
                        "${line.text} " +
                        "box=${line.boundingBox}"
            )
        }

        println()
        println("---------- OVERLAP CHECK ----------")

        var duplicateFound = false

        result.recognizedLines.forEach { line ->

            result.diagramRegions.forEach { diagram ->

                val overlap =
                    calculateOverlapRatio(
                        textBox = line.boundingBox,
                        visualBox = diagram.boundingBox
                    )

                if (overlap >= 0.60f) {

                    duplicateFound = true

                    println(
                        "POSSIBLE DUPLICATE:"
                    )

                    println(
                        "Text: ${line.text}"
                    )

                    println(
                        "Text box: ${line.boundingBox}"
                    )

                    println(
                        "Visual box: ${diagram.boundingBox}"
                    )

                    println(
                        "Overlap: $overlap"
                    )
                }
            }
        }

        println()
        println("===================================")

        assertTrue(
            "Binary tree image should contain at least one diagram.",
            result.diagramRegions.isNotEmpty()
        )

        assertTrue(
            "Pipeline should still produce OCR text regions.",
            result.recognizedLines.isNotEmpty()
        )

        assertFalse(
            "Text inside a preserved diagram should not also be sent to OCR.",
            duplicateFound
        )

        pipeline.close()
    }

    @Test
    fun allFourWhiteboardsCompletePipeline() = runBlocking {

        val images =
            listOf(
                "test_whiteboard_1.png",
                "test_whiteboard_2.png",
                "test_whiteboard_3.png",
                "test_whiteboard_4.png"
            )

        images.forEach { imageName ->

            val bitmap =
                loadBitmap(
                    imageName
                )

            val pipeline =
                WhiteboardRecognitionPipeline(
                    lineRecognizer =
                        EchoLineRecognizer()
                )

            val result =
                pipeline.process(
                    bitmap
                )

            println()
            println(
                "==================================="
            )

            println(
                "PIPELINE: $imageName"
            )

            println(
                "Recognized lines: " +
                        result.recognizedLines.size
            )

            println(
                "Visual regions: " +
                        result.diagramRegions.size
            )

            println(
                "Structured blocks: " +
                        result.structuredNote.blocks.size
            )

            println(
                "Title: " +
                        result.structuredNote.title
            )

            println(
                "==================================="
            )

            assertTrue(
                "$imageName should produce either text or visuals.",
                result.recognizedLines.isNotEmpty() ||
                        result.diagramRegions.isNotEmpty()
            )

            pipeline.close()
        }
    }

    private fun loadBitmap(
        resourceName: String
    ): Bitmap {

        val stream =
            javaClass.classLoader
                ?.getResourceAsStream(
                    resourceName
                )
                ?: error(
                    "$resourceName not found"
                )

        return BitmapFactory.decodeStream(
            stream
        )
            ?: error(
                "Failed to decode $resourceName"
            )
    }

    private fun calculateOverlapRatio(
        textBox: Rect,
        visualBox: Rect
    ): Float {

        val intersection =
            Rect()

        if (
            !intersection.setIntersect(
                textBox,
                visualBox
            )
        ) {
            return 0f
        }

        val textArea =
            textBox.width() *
                    textBox.height()

        if (textArea <= 0) {
            return 0f
        }

        val intersectionArea =
            intersection.width() *
                    intersection.height()

        return intersectionArea.toFloat() /
                textArea.toFloat()
    }

    private class EchoLineRecognizer :
        LineRecognizer {

        override suspend fun recognize(
            regions: List<Region>
        ): List<RecognizedLine> {

            return regions.map { region ->

                RecognizedLine(
                    sourceRegionId =
                        region.id,

                    boundingBox =
                        Rect(
                            region.boundingBox
                        ),

                    text =
                        "TEXT_REGION_${region.id}",

                    confidence =
                        1f
                )
            }
        }

        override fun close() {
            // Nothing to close for fake OCR.
        }
    }
}