package com.example.note2snap.correction

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

/** Scores observed CRNN text separately from synthetic algorithm checks. */
class CorrectionEvaluationTest {
    private data class Sample(val group: String, val truth: String, val raw: String)
    private val corrector = bundledCorrectorForTest()

    @Test fun compareCapturedCrnnTitleOutputsAndSyntheticControls() {
        val real = javaClass.classLoader!!.getResourceAsStream("correction/crnn_title_outputs.tsv")!!
            .bufferedReader().use { it.readLines() }.filter { !it.startsWith("#") && it.isNotEmpty() }.map {
                val fields = it.split('\t', limit = 5)
                Sample("CRNN/${fields[1]}/OpenCV=${fields[2]}", fields[3], fields[4])
            }
        assertEquals(16, real.size)
        val synthetic = listOf(
            Sample("synthetic/ocr-errors", "computer algorithm", "cornputer algorithrn"),
            Sample("synthetic/academic-errors", "definition recognition", "defintion recognitlon"),
            Sample("synthetic/cs-errors", "function recursion", "functlon recurslon"),
            Sample("synthetic/valid-words", "form from modem modern", "form from modem modern"),
            Sample("synthetic/names", "Cornputer Ada CRNN", "Cornputer Ada CRNN"),
            Sample("synthetic/formulas", "x = functlon(y) + 2", "x = functlon(y) + 2"),
            // Intentionally valid unmarked domain term, not an OCR mistake: a false positive.
            Sample("synthetic/negative-domain-control", "functon", "functon")
        )
        val report = StringBuilder("Scope: captured Stage 1 host outputs from one title; synthetic controls are not model accuracy.\n")
        var realChanges = 0
        var worsened = 0
        for ((group, samples) in (real + synthetic).groupBy { it.group }) {
            var chars = 0; var words = 0; var rawChars = 0; var correctedChars = 0; var rawWords = 0; var correctedWords = 0
            for (sample in samples) {
                val corrected = corrector.correct(sample.raw).correctedText
                val before = distance(sample.truth.toList(), sample.raw.toList())
                val after = distance(sample.truth.toList(), corrected.toList())
                if (sample.group.startsWith("CRNN/") && corrected != sample.raw) realChanges++
                if (after > before) worsened++
                chars += sample.truth.length
                words += tokens(sample.truth).size
                rawChars += before; correctedChars += after
                rawWords += distance(tokens(sample.truth), tokens(sample.raw))
                correctedWords += distance(tokens(sample.truth), tokens(corrected))
                report.appendLine("$group truth=${sample.truth} raw=${sample.raw} corrected=$corrected worsened=${after > before}")
            }
            report.appendLine(String.format(Locale.ROOT,
                "%s n=%d chars=%d words=%d CER %.6f -> %.6f WER %.6f -> %.6f", group, samples.size, chars, words,
                rawChars.toDouble()/chars, correctedChars.toDouble()/chars, rawWords.toDouble()/words, correctedWords.toDouble()/words))
        }
        assertEquals("Protected title/mixed case predictions should not be silently rewritten", 0, realChanges)
        assertEquals("The synthetic domain-term negative control must remain visible in evaluation", 1, worsened)
        val sentence = "the cornputer runs an algorithrn with recurslon and a functlon. ".repeat(20)
        repeat(5) { corrector.correct(sentence) }
        val timings = LongArray(30) {
            val start = System.nanoTime(); corrector.correct(sentence); System.nanoTime() - start
        }.sorted()
        report.appendLine("Host-only warmed ${tokens(sentence).size}-token text: medianNs=${timings[15]} p95Ns=${timings[28]}; not Android latency or peak memory")
        val output = File("build/offline-correction-evaluation.txt")
        output.parentFile.mkdirs(); output.writeText(report.toString())
        println(report)
    }

    private fun tokens(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    private fun <T> distance(a: List<T>, b: List<T>): Int {
        var previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.size + 1); current[0] = i + 1
            for (j in b.indices) current[j+1] = minOf(current[j]+1, previous[j+1]+1, previous[j] + if(a[i]==b[j]) 0 else 1)
            previous = current
        }
        return previous[b.size]
    }
}
