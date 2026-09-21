package com.example.note2snap.recognition

import android.content.Context
import android.graphics.Bitmap
import com.example.note2snap.ccl.ConnectedComponentLabeler
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
import com.example.note2snap.model.StructuredNote
import com.example.note2snap.preprocessing.WhiteboardPreprocessor
import com.example.note2snap.structuring.PipelineTextStructurer

data class RecognitionPipelineResult(
    val structuredNote: StructuredNote,
    val recognizedLines: List<RecognizedLine>,
    val diagramRegions: List<Region>
)

/**
 * Note2Snap recognition pipeline.
 *
 * Real app:
 * photo
 * -> preprocessing
 * -> CCL
 * -> CRNN OCR
 * -> structuring
 *
 * Tests can inject a fake LineRecognizer
 * without requiring Android Context.
 */
class WhiteboardRecognitionPipeline(
    private val lineRecognizer: LineRecognizer
) {

    /**
     * Real-app constructor.
     *
     * Passing Context automatically enables
     * the CRNN/TFLite recognizer.
     */
    constructor(
        context: Context
    ) : this(
        lineRecognizer =
            CrnnLineRecognizer(
                context.applicationContext
            )
    )

    private val preprocessor =
        WhiteboardPreprocessor()

    private val labeler =
        ConnectedComponentLabeler()

    private val structurer =
        PipelineTextStructurer()

    suspend fun process(
        photo: Bitmap
    ): RecognitionPipelineResult {

        val preprocessed =
            preprocessor.process(
                photo
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
                it.type ==
                        RegionType.TEXT
            }

        val diagramRegions =
            regions.filter {
                it.type ==
                        RegionType.NON_TEXT
            }

        val recognizedLines =
            lineRecognizer.recognize(
                textRegions
            )

        val structuredNote =
            structurer.structure(
                recognizedLines
            )

        return RecognitionPipelineResult(
            structuredNote =
                structuredNote,

            recognizedLines =
                recognizedLines,

            diagramRegions =
                diagramRegions
        )
    }

    fun close() {
        lineRecognizer.close()
    }
}