
package com.paddle.ocr.postprocess

object CTCDecoder {
    private const val BLANK_IDX = 0

    fun decode(
        output: FloatArray,
        shape: LongArray,
        characterList: List<String>
    ): List<Pair<String, Float>> {
        val batchSize = shape[0].toInt()
        val timeSteps = shape[1].toInt()
        val numClasses = shape[2].toInt()

        val results = ArrayList<Pair<String, Float>>(batchSize)

        for (b in 0 until batchSize) {
            val baseOffset = b * timeSteps * numClasses

            val text = StringBuilder()
            var previousIndex = -1
            var confidenceSum = 0.0
            var characterCount = 0

            for (t in 0 until timeSteps) {
                val offset = baseOffset + t * numClasses

                var maxIndex = 0
                var maxValue = output[offset]

                for (c in 1 until numClasses) {
                    val value = output[offset + c]

                    if (value > maxValue) {
                        maxValue = value
                        maxIndex = c
                    }
                }

                if (maxIndex != BLANK_IDX &&
                    maxIndex != previousIndex
                ) {
                    val characterIndex = maxIndex - 1

                    if (characterIndex in characterList.indices) {
                        text.append(characterList[characterIndex])
                        confidenceSum += maxValue
                        characterCount++
                    }
                }

                previousIndex = maxIndex
            }

            val confidence = if (characterCount > 0) {
                (confidenceSum / characterCount).toFloat()
            } else {
                0f
            }

            results.add(text.toString() to confidence)
        }

        return results
    }
}
