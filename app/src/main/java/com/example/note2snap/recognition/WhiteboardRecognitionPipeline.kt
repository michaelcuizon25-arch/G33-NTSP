package com.example.note2snap.recognition

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.note2snap.BuildConfig
import com.example.note2snap.correction.OfflineTextCorrector
import com.example.note2snap.ccl.ConnectedComponentLabeler
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
import com.example.note2snap.model.StructuredNote
import com.example.note2snap.preprocessing.WhiteboardPreprocessor
import com.example.note2snap.structuring.PipelineTextStructurer

data class RecognitionPipelineResult(
    val structuredNote: StructuredNote,
    val recognizedLines: List<RecognizedLine>,
    val diagramRegions: List<Region>,
    // recognizedLines remains the original engine output for existing callers.
    val correctedLines: List<RecognizedLine> = recognizedLines,
    val textCorrections: List<LineTextCorrection> = emptyList()
)

/**
 * Note2Snap recognition pipeline.
 *
 * Real app:
 * photo
 * -> preprocessing
 * -> CCL
 * -> CRNN OCR / CTC decoding
 * -> optional offline text correction
 * -> structuring
 *
 * Tests can inject a fake LineRecognizer
 * without requiring Android Context.
 */
class WhiteboardRecognitionPipeline(
    private val lineRecognizer: LineRecognizer,
    private val textCorrector: OfflineTextCorrector? = null
) {

    /**
     * Real-app constructor.
     *
     * Passing Context automatically enables
     * the CRNN/TFLite recognizer.
     */
    @JvmOverloads
    constructor(context: Context, preprocessingMode: CrnnPreprocessingMode = CrnnPreprocessingMode.LEGACY,
                enableTextCorrection: Boolean = BuildConfig.ENABLE_OFFLINE_TEXT_CORRECTION) :
        this(context, preprocessingMode, enableTextCorrection, { appContext, mode -> CrnnLineRecognizer(appContext, mode) })

    internal constructor(context: Context, preprocessingMode: CrnnPreprocessingMode = CrnnPreprocessingMode.LEGACY,
                         enableTextCorrection: Boolean = BuildConfig.ENABLE_OFFLINE_TEXT_CORRECTION,
                         recognizerFactory: (Context, CrnnPreprocessingMode) -> LineRecognizer) :
        this(recognizerFactory(context.applicationContext, preprocessingMode),
            configuredTextCorrector(context, enableTextCorrection))

    private val preprocessor =
        WhiteboardPreprocessor()

    private val labeler =
        ConnectedComponentLabeler()

    private val structurer =
        PipelineTextStructurer()

    suspend fun process(
        photo: Bitmap
    ): RecognitionPipelineResult {

        Log.i(RECOGNITION_LOG_TAG, "Scan source=${photo.width}x${photo.height}")

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

        Log.i(RECOGNITION_LOG_TAG, "Preprocessing recognition=${preprocessed.recognitionBitmap.width}x${preprocessed.recognitionBitmap.height} " +
            "binary=${preprocessed.binarizedBitmap.width}x${preprocessed.binarizedBitmap.height}")

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

        Log.i(RECOGNITION_LOG_TAG, "CCL totalRegions=${regions.size} textRegions=${textRegions.size} nonTextRegions=${diagramRegions.size}")

        (lineRecognizer as? CrnnLineRecognizer)?.diagnostics?.recordSource(photo, preprocessed, regions)

        val recognizedLines =
            lineRecognizer.recognize(
                textRegions
            )

        val corrections = textCorrector?.let { correctRecognizedLines(recognizedLines, it) }.orEmpty()
        val correctedLines = if (textCorrector == null) recognizedLines else recognizedLines.mapIndexed { index, line ->
            val text = corrections[index].result.correctedText
            if (text == line.text) line else line.copy(text = text)
        }
        (lineRecognizer as? CrnnLineRecognizer)?.diagnostics?.recordCorrections(textCorrector != null, corrections)
        Log.i(RECOGNITION_LOG_TAG, "Offline correction enabled=${textCorrector != null} " +
            "changedLines=${corrections.count { it.result.originalText != it.result.correctedText }}")

        val structuredNote =
            structurer.structure(
                correctedLines
            )

        (lineRecognizer as? CrnnLineRecognizer)?.diagnostics?.recordStructured(structuredNote)
        Log.i(RECOGNITION_LOG_TAG, "Structuring recognizedLines=${recognizedLines.size} blocks=${structuredNote.blocks.size} " +
            "readable=${RecognitionReadability.hasReadableWriting(recognizedLines)} title=${org.json.JSONObject.quote(structuredNote.title)}")

        return RecognitionPipelineResult(
            structuredNote =
                structuredNote,

            recognizedLines =
                recognizedLines,

            diagramRegions =
                diagramRegions,
            correctedLines = correctedLines,
            textCorrections = corrections
        )
    }

    fun close() {
        lineRecognizer.close()
    }
}
