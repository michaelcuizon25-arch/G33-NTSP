package com.example.note2snap.correction

import android.content.Context

/** Loaded only after opt-in; immutable vocabulary is reused across scans. */
internal object BundledTextCorrector {
    @Volatile private var cached: DictionaryTextCorrector? = null

    fun load(context: Context): DictionaryTextCorrector = cached ?: synchronized(this) {
        cached ?: read(context).also { cached = it }
    }

    private fun read(context: Context): DictionaryTextCorrector {
        fun lines(path: String) = context.assets.open(path).bufferedReader().use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }
        val words = lines("correction/academic_vocabulary.txt").toSet()
        val aliases = linkedMapOf<String, String>()
        for (line in lines("correction/reviewed_ocr_aliases.tsv")) {
            val fields = line.split('\t')
            require(fields.size == 2 && aliases.put(fields[0], fields[1]) == null) { "Invalid/duplicate correction alias" }
        }
        return DictionaryTextCorrector(words, aliases)
    }
}
