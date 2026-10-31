package com.example.note2snap.correction

import org.junit.Assert.*
import org.junit.Test
import java.io.File

internal fun bundledCorrectorForTest(): DictionaryTextCorrector {
    fun lines(name: String) = File("src/main/assets/correction/$name").readLines()
        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val aliases = lines("reviewed_ocr_aliases.tsv").associate { line ->
        val fields = line.split('\t')
        require(fields.size == 2)
        fields[0] to fields[1]
    }
    return DictionaryTextCorrector(lines("academic_vocabulary.txt").toSet(), aliases)
}

class DictionaryTextCorrectorTest {
    private val corrector = bundledCorrectorForTest()

    @Test fun commonOcrSubstitutionsUseExplicitReviewedEvidence() {
        val result = corrector.correct("cornputer algorithrn recognitlon handwritlng")
        assertEquals("computer algorithm recognition handwriting", result.correctedText)
        assertEquals("cornputer algorithrn recognitlon handwritlng", result.originalText)
        assertTrue(result.suggestedCorrections.all { it.applied && it.evidence.source == CorrectionEvidenceSource.REVIEWED_OCR_ALIAS })
        assertEquals(2, result.suggestedCorrections.first().evidence.editDistance) // rn -> m, not a probability.
    }

    @Test fun academicMisspellingsAreCorrectedWithoutCompletingSentences() {
        assertEquals("definition implementation segmentation", corrector.correct("defintion implementatlon segmentatlon").correctedText)
    }

    @Test fun csTermsAreCorrected() {
        assertEquals("function recursion polymorphism encapsulation variable",
            corrector.correct("functlon recurslon polymorphisrn encapsulatlon varlable").correctedText)
    }

    @Test fun knownWordsWinIncludingConfusableValidWords() {
        val text = "form from modem modern public pubic algorithm computer recognition"
        val result = corrector.correct(text)
        assertEquals(text, result.correctedText)
        assertTrue(result.suggestedCorrections.isEmpty())
    }

    @Test fun properNamesAndAbbreviationsRemainUnchanged() {
        val text = "Michael Ada Lovelace Dijkstra Java Kotlin OOP CRNN CTC API SQL Algorithrn Cornputer"
        val result = corrector.correct(text)
        assertEquals(text, result.correctedText)
        assertTrue(result.suggestedCorrections.any { it.original == "Algorithrn" && it.replacement == "Algorithm" && !it.applied })
        assertTrue(result.suggestedCorrections.none { it.applied })
    }

    @Test fun caseAndPunctuationArePreserved() {
        assertEquals("\"algorithm,\" (computer); recognition!",
            corrector.correct("\"algorithrn,\" (cornputer); recognitlon!").correctedText)
        assertEquals("ALGORITHRN aLgorithrn", corrector.correct("ALGORITHRN aLgorithrn").correctedText)
    }

    @Test fun numbersFormulasCodeUrlsAndSymbolsAreProtected() {
        val text = "2026 3.14 alg0rithm x=recurslon y = recurslon + 1\n" +
            "recurslon(x) recurslon (x) varlable[2] snake_case foo.bar a/b\n" +
            "https://cornputer.test user@cornputer.test C++ rn 0O1l • ∑"
        assertEquals(text, corrector.correct(text).correctedText)
    }

    @Test fun multipleLinesAndParagraphFormattingAreByteForBytePreservedOutsideChanges() {
        val raw = "• algorithrn\r\n\t\"cornputer\"  \n\nfunctlon: recurslon.\n"
        val expected = "• algorithm\r\n\t\"computer\"  \n\nfunction: recursion.\n"
        assertEquals(expected, corrector.correct(raw).correctedText)
    }

    @Test fun emptyWhitespaceAndIncompleteOutputsDoNotGainContent() {
        for (text in listOf("", " \t\r\n", "algori develo...", "? ...", "comput")) {
            assertEquals(text, corrector.correct(text).correctedText)
        }
    }

    @Test fun genericSimilarityAndAmbiguousNeighboursAreSuggestionsOnly() {
        val custom = DictionaryTextCorrector(setOf("plane", "plant", "parent"))
        val result = custom.correct("plank parant")
        assertEquals("plank parant", result.correctedText)
        assertEquals(setOf("plane", "plant"), result.suggestedCorrections.filter { it.original == "plank" }.map { it.replacement }.toSet())
        assertTrue(result.suggestedCorrections.all { !it.applied })
    }

    @Test fun explicitProtectedTermsOverrideReviewedAliases() {
        val custom = DictionaryTextCorrector(setOf("computer"), mapOf("cornputer" to "computer"), setOf("cornputer"))
        assertEquals("cornputer", custom.correct("cornputer").correctedText)
    }

    @Test fun originalOffsetsUseUtf16AndRemainValidWhenWordsChangeLength() {
        val input = "😀\talgorithrn cornputer"
        val result = corrector.correct(input)
        assertEquals("😀\talgorithm computer", result.correctedText)
        assertEquals(3, result.suggestedCorrections.first().start)
        for (change in result.suggestedCorrections) {
            assertEquals(change.original, input.substring(change.start, change.endExclusive))
        }
    }

    @Test fun correctedOutputIsIdempotent() {
        val once = corrector.correct("algorithrn cornputer").correctedText
        val twice = corrector.correct(once)
        assertEquals(once, twice.correctedText)
        assertTrue(twice.suggestedCorrections.isEmpty())
    }

    @Test fun longInputsAreReturnedIntactInsteadOfTruncated() {
        val text = "algorithrn ".repeat(4_000)
        val result = corrector.correct(text)
        assertEquals(text, result.correctedText)
        assertEquals("text_limit", result.skippedReason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun reviewedAliasesCannotOverwriteKnownValidWords() {
        DictionaryTextCorrector(setOf("form", "from"), mapOf("form" to "from"))
    }

    @Test fun unmarkedDomainTokenDemonstratesKnownFalsePositiveRisk() {
        // Synthetic negative control: 'functon' is intentionally a domain name here,
        // but its plain lowercase spelling supplies no syntactic protection.
        val raw = "functon"
        assertEquals("function", corrector.correct(raw).correctedText)
        // This is why automatic correction stays opt-in and raw text is retained.
    }
}
