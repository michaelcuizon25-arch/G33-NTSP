package com.example.note2snap.recognition

import android.content.Context
import java.security.MessageDigest

/** Pinned development export, verified before creating a native session. Never downloads assets. */
internal object PaddleModelAssets {
    const val MODEL = "ocr/paddle/en_PP-OCRv5_mobile_rec.onnx"
    const val DICTIONARY = "ocr/paddle/ppocrv5_en_dict.txt"
    const val CLASSES = 438
    const val MODEL_SHA256 = "70b2450eed39599af6b996c27a2f1a0ef30eeb49f9f66dd3e74f28f652befc89"
    const val DICTIONARY_SHA256 = "e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6"

    fun verifiedBytes(context: Context, name: String, sha256: String): ByteArray =
        context.assets.open(name).use { it.readBytes() }.also {
            require(MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } == sha256) {
                "Paddle asset integrity check failed: $name"
            }
        }

    fun characters(context: Context): List<String> {
        val lines = verifiedBytes(context, DICTIONARY, DICTIONARY_SHA256).toString(Charsets.UTF_8)
            .lineSequence().toList().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        require(lines.size == 436 && lines.none { it.isEmpty() } && lines.distinct().size == lines.size)
        return listOf("") + lines + " "
    }
}
