package com.example.note2snap.recognition

import android.graphics.BitmapFactory
import com.example.note2snap.ccl.ConnectedComponentLabeler
import com.example.note2snap.ccl.RegionType
import com.example.note2snap.preprocessing.WhiteboardPreprocessor
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MlKitRecognitionPipelineTest {

    @Test
    fun testRecognitionFromWhiteboardImage() = runBlocking {

        val inputStream =
            javaClass.classLoader
                ?.getResourceAsStream("test_whiteboard_1.png")
                ?: error("test_whiteboard_1.png not found")

        val originalBitmap =
            BitmapFactory.decodeStream(inputStream)

        val preprocessor =
            WhiteboardPreprocessor()

        val labeler =
            ConnectedComponentLabeler()

        val recognizer =
            MlKitLineRecognizer()

        try {

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
                regions.filter {
                    it.type == RegionType.TEXT
                }

            val visualRegions =
                regions.filter {
                    it.type == RegionType.NON_TEXT
                }

            println()
            println("===================================")
            println("ML KIT RECOGNITION TEST")
            println("===================================")

            println(
                "TEXT REGIONS   : ${textRegions.size}"
            )

            println(
                "VISUAL REGIONS : ${visualRegions.size}"
            )

            println("-----------------------------------")

            val recognizedLines =
                recognizer.recognize(
                    textRegions
                )

            recognizedLines
                .sortedWith(
                    compareBy(
                        { it.boundingBox.top },
                        { it.boundingBox.left }
                    )
                )
                .forEachIndexed { index, line ->

                    println(
                        "${index + 1}. ${line.text}"
                    )

                    println(
                        "   Box: " +
                                "${line.boundingBox.left}," +
                                "${line.boundingBox.top}," +
                                "${line.boundingBox.right}," +
                                "${line.boundingBox.bottom}"
                    )

                    println(
                        "   Confidence: ${line.confidence}"
                    )

                    println()
                }

            println("-----------------------------------")

            println(
                "RECOGNIZED LINES: ${recognizedLines.size}"
            )

            println("===================================")

        } finally {

            recognizer.close()
        }
    }
}