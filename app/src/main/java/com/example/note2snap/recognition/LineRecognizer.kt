package com.example.note2snap.recognition

import com.example.note2snap.ccl.Region

interface LineRecognizer {

    suspend fun recognize(
        regions: List<Region>
    ): List<RecognizedLine>

    fun close()
}