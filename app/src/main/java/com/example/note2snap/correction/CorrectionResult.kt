package com.example.note2snap.correction

enum class CorrectionEvidenceSource { REVIEWED_OCR_ALIAS, DICTIONARY_EDIT_DISTANCE }

/** Edit distance is supporting evidence, never a calibrated probability. */
data class CorrectionEvidence(
    val source: CorrectionEvidenceSource,
    val editDistance: Int,
    val reason: String
)

/** Offsets address the original string in UTF-16 code units; end is exclusive. */
data class SuggestedCorrection(
    val start: Int,
    val endExclusive: Int,
    val original: String,
    val replacement: String,
    val applied: Boolean,
    val evidence: CorrectionEvidence
)

data class CorrectionResult(
    val originalText: String,
    val correctedText: String,
    val suggestedCorrections: List<SuggestedCorrection> = emptyList(),
    val skippedReason: String? = null
)
