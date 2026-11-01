package com.example.note2snap.recognition

internal data class PaddlePrediction(val text: String, val meanSelectedProbability: Float)
internal data class PaddleOutput(val values: FloatArray, val steps: Int, val classes: Int)

/** Separate from the existing CRNN CTC implementation. Probabilities are not calibrated confidence. */
internal class PaddleCtcDecoder(private val characters: List<String>) {
    init { require(characters.size == PaddleModelAssets.CLASSES && characters[0].isEmpty() && characters.last() == " ") }

    fun decode(output: PaddleOutput): PaddlePrediction {
        require(output.classes == characters.size && output.steps > 0 &&
            output.values.size.toLong() == output.steps.toLong() * output.classes)
        val text = StringBuilder()
        var previous = -1
        var sum = 0f
        var count = 0
        for (step in 0 until output.steps) {
            val offset = step * output.classes
            var best = 0
            for (c in 0 until output.classes) {
                require(output.values[offset + c].isFinite()) { "Non-finite Paddle output" }
                if (output.values[offset + c] > output.values[offset + best]) best = c
            }
            if (best != 0 && best != previous) {
                text.append(characters[best])
                sum += output.values[offset + best]
                count++
            }
            previous = best
        }
        return PaddlePrediction(text.toString(), if (count == 0) 0f else sum / count)
    }
}
