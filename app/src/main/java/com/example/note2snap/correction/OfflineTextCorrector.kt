package com.example.note2snap.correction

/** Text only: no OCR engine, images, geometry, note rules or network access. */
fun interface OfflineTextCorrector {
    fun correct(text: String): CorrectionResult
}
