package com.example.note2snap.recognition

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal const val RECOGNITION_LOG_TAG = "NOTE2SNAP_PIPELINE"

internal object CrnnModelAssets {
    const val MODEL = "ocr/note2snap_crnn_v2_epoch03_flex.tflite"
    const val CHARSET = "ocr/note2snap_crnn_v2_epoch03_charlist.txt"
}

/** Test seam around the unchanged TFLite invocation; not a replacement recognizer. */
internal interface CrnnInferenceRuntime {
    val inputShape: IntArray
    val outputShape: IntArray
    val inputType: String
    val outputType: String
    fun run(input: ByteBuffer): Array<Array<FloatArray>>
    fun close()
}

/** Minimal Java-runtime boundary for the dynamic-output caching regression test. */
internal interface CrnnInterpreterAccess {
    fun inputShape(): IntArray
    fun inputType(): String
    fun outputShape(): IntArray
    fun outputType(): String
    fun run(input: ByteBuffer, output: Array<Array<FloatArray>>)
    fun close()
}

private class AndroidCrnnInterpreterAccess(private val interpreter: Interpreter) : CrnnInterpreterAccess {
    override fun inputShape() = interpreter.getInputTensor(0).shape()
    override fun inputType() = interpreter.getInputTensor(0).dataType().toString()
    override fun outputShape() = interpreter.getOutputTensor(0).shape()
    override fun outputType() = interpreter.getOutputTensor(0).dataType().toString()
    override fun run(input: ByteBuffer, output: Array<Array<FloatArray>>) = interpreter.run(input, output)
    override fun close() = interpreter.close()
}

internal class TensorFlowLiteCrnnRuntime internal constructor(private val interpreter: CrnnInterpreterAccess) : CrnnInferenceRuntime {
    constructor(context: Context) : this(AndroidCrnnInterpreterAccess(Interpreter(loadCrnnModel(context))))
    private var invoked = false
    override val inputShape get() = interpreter.inputShape()
    // TFLite 2.16.1 caches TensorImpl.shapeCopy on first getOutputTensor(). Its
    // ByteBuffer path does not resize/allocate, so a pre-invoke dynamic shape may
    // never be refreshed before copyTo checks the Java output shape. Neither
    // output shape NOR dtype may inspect the output tensor before invocation.
    override val outputShape get() = if (invoked) interpreter.outputShape() else intArrayOf()
    override val inputType get() = interpreter.inputType()
    override val outputType get() = if (invoked) interpreter.outputType() else "UNRESOLVED_BEFORE_INFERENCE"

    init {
        Log.i(RECOGNITION_LOG_TAG, "CRNN runtime input=${inputShape.contentToString()} $inputType " +
            "output(before inference)=${outputShape.contentToString()} $outputType")
    }

    override fun run(input: ByteBuffer): Array<Array<FloatArray>> {
        Log.d(RECOGNITION_LOG_TAG, "CRNN invoke bytes=${input.remaining()} position=${input.position()} " +
            "order=${input.order()} input=${inputShape.contentToString()} output(before)=${outputShape.contentToString()}")
        // Keep the established output contract and decoder unchanged. Shape/runtime
        // exceptions must reach the caller rather than masquerading as empty text.
        val output = Array(1) { Array(128) { FloatArray(80) } }
        interpreter.run(input, output)
        invoked = true
        Log.d(RECOGNITION_LOG_TAG, "CRNN invoke completed output=${outputShape.contentToString()}")
        return output
    }

    override fun close() = interpreter.close()

}

private fun loadCrnnModel(context: Context): ByteBuffer =
    context.assets.openFd(CrnnModelAssets.MODEL).use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset, descriptor.declaredLength)
        }
    }

internal fun loadCrnnCharset(context: Context): List<String> =
    context.assets.open(CrnnModelAssets.CHARSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        .removePrefix("\uFEFF").trimEnd('\r', '\n').map { it.toString() }

class CrnnRecognitionFailure internal constructor(
    val failedLines: Int,
    val physicalLines: Int,
    cause: Exception
) : IllegalStateException("CRNN failed on $failedLines of $physicalLines physical lines; no text was recovered", cause)
