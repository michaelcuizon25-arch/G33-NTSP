package com.example.note2snap.correction

import java.util.Locale

/**
 * Conservative, deterministic English OCR correction. Only explicitly reviewed
 * aliases can be applied. Generic dictionary neighbours remain suggestions.
 * Valid words, numbers, formulas, identifiers and short/all-caps tokens are kept.
 */
class DictionaryTextCorrector(
    vocabulary: Set<String>,
    reviewedAliases: Map<String, String> = emptyMap(),
    protectedTerms: Set<String> = emptySet()
) : OfflineTextCorrector {
    private val words = vocabulary.toSet()
    private val aliases = reviewedAliases.toMap()
    private val protected = protectedTerms.map { it.lowercase(Locale.ROOT) }.toSet()
    private val byLength = words.groupBy { it.length }

    init {
        require(words.all { WORD.matches(it) && it == it.lowercase(Locale.ROOT) && it.length <= MAX_WORD })
        require(aliases.all { (from, to) -> from.length in MIN_WORD..MAX_WORD && WORD.matches(from) &&
            from == from.lowercase(Locale.ROOT) && from != to && to in words && from !in words }) {
            "Reviewed aliases must map nonwords to existing vocabulary entries"
        }
    }

    override fun correct(text: String): CorrectionResult {
        if (text.length > MAX_TEXT) return CorrectionResult(text, text, skippedReason = "text_limit")
        val suggestions = mutableListOf<SuggestedCorrection>()
        var chunksSeen = 0
        for (line in LINES.findAll(text)) {
            // Do not reinterpret lexical tokens within equations or code expressions.
            if (FORMULA.containsMatchIn(line.value)) continue
            for (chunk in CHUNKS.findAll(line.value)) {
                if (++chunksSeen > MAX_CHUNKS) return CorrectionResult(text, text, skippedReason = "token_limit")
                val token = TOKEN.matchEntire(chunk.value)?.groups?.get(1) ?: continue
                val original = token.value
                if (original.length !in MIN_WORD..MAX_WORD) continue
                val lower = original.lowercase(Locale.ROOT)
                if (lower in words || lower in protected) continue
                val titleCase = original[0].isUpperCase() && original.drop(1).all { it.isLowerCase() }
                if (original != lower && !titleCase) continue // Abbreviations and mixed-case identifiers.
                var nextIndex = chunk.range.last + 1
                while (nextIndex < line.value.length && line.value[nextIndex].isWhitespace()) nextIndex++
                val next = line.value.getOrNull(nextIndex)
                if (chunk.value == original && (next == '(' || next == '[')) continue // Function calls and indexed identifiers.
                val start = line.range.first + chunk.range.first + token.range.first
                val reviewed = aliases[lower]
                if (reviewed != null && reviewed !in protected) {
                    val replacement = restoreCase(reviewed, titleCase)
                    // Title-case tokens may be proper names, even at sentence starts.
                    val apply = !titleCase
                    suggestions += SuggestedCorrection(start, start + original.length, original, replacement, apply,
                        CorrectionEvidence(CorrectionEvidenceSource.REVIEWED_OCR_ALIAS,
                            editDistance(lower, reviewed), if (apply) "bundled reviewed OCR alias" else "possible proper name; review required"))
                    continue
                }
                val maxDistance = if (lower.length >= 8) 2 else 1
                val neighbours = (lower.length - maxDistance..lower.length + maxDistance)
                    .flatMap { byLength[it].orEmpty() }
                    .filter { it !in protected }
                    .map { it to editDistance(lower, it) }
                    .filter { it.second <= maxDistance }
                    .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
                    .take(3)
                for ((candidate, distance) in neighbours) {
                    suggestions += SuggestedCorrection(start, start + original.length, original,
                        restoreCase(candidate, titleCase), false,
                        CorrectionEvidence(CorrectionEvidenceSource.DICTIONARY_EDIT_DISTANCE, distance,
                            "dictionary similarity only; review required"))
                }
            }
        }
        // Rebuild using original offsets, preserving all untouched whitespace and punctuation.
        val corrected = buildString(text.length) {
            var cursor = 0
            for (change in suggestions.filter { it.applied }) {
                append(text, cursor, change.start)
                append(change.replacement)
                cursor = change.endExclusive
            }
            append(text, cursor, text.length)
        }
        return CorrectionResult(text, corrected, suggestions)
    }

    private fun restoreCase(word: String, titleCase: Boolean): String =
        if (titleCase) word.replaceFirstChar { it.uppercaseChar() } else word

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
                previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = current
        }
        return previous[b.length]
    }

    private companion object {
        const val MIN_WORD = 5
        const val MAX_WORD = 32
        const val MAX_TEXT = 32_768
        const val MAX_CHUNKS = 2_048
        val WORD = Regex("[a-z]+")
        val LINES = Regex("[^\\r\\n]+")
        val CHUNKS = Regex("\\S+")
        val TOKEN = Regex("""^[“"'(\[{]*([A-Za-z]+)[,;:!?.“”"'’)\]}]*$""")
        val FORMULA = Regex("""[=^<>≤≥∑√∫]|\w\s*[+*/]\s*\w""")
    }
}
