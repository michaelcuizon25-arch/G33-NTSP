package com.example.note2snap.recognition

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    val inputShapeSignature: IntArray get() = inputShape
    val outputShapeSignature: IntArray get() = outputShape
    val cachedOutputShape: IntArray get() = outputShape
    val inputType: String
    val outputType: String
    fun run(input: ByteBuffer): Array<Array<FloatArray>>
    fun close()
}

/** Minimal Java-runtime boundary for the dynamic-output caching regression test. */
internal interface CrnnInterpreterAccess {
    fun inputShape(): IntArray
    fun inputType(): String
    fun inputShapeSignature(): IntArray
    fun outputShape(): IntArray
    fun outputType(): String
    fun outputShapeSignature(): IntArray
    fun outputNumBytes(): Int
    fun invoke(input: ByteBuffer)
    fun outputBuffer(): ByteBuffer
    fun close()
}

private class AndroidCrnnInterpreterAccess(private val interpreter: Interpreter) : CrnnInterpreterAccess {
    override fun inputShape() = interpreter.getInputTensor(0).shape()
    override fun inputType() = interpreter.getInputTensor(0).dataType().toString()
    override fun inputShapeSignature() = interpreter.getInputTensor(0).shapeSignature()
    override fun outputShape() = interpreter.getOutputTensor(0).shape()
    override fun outputType() = interpreter.getOutputTensor(0).dataType().toString()
    override fun outputShapeSignature() = interpreter.getOutputTensor(0).shapeSignature()
    override fun outputNumBytes() = interpreter.getOutputTensor(0).numBytes()
    override fun invoke(input: ByteBuffer) {
        // Empty output map is supported by TFLite 2.16.1. Invoke the graph without
        // TensorImpl.copyTo(Java array), which compares against cached shapeCopy.
        interpreter.runForMultipleInputsOutputs(arrayOf(input), emptyMap<Int, Any>())
    }
    override fun outputBuffer() = interpreter.getOutputTensor(0).asReadOnlyBuffer()
    override fun close() = interpreter.close()
}

internal class TensorFlowLiteCrnnRuntime internal constructor(private val interpreter: CrnnInterpreterAccess) : CrnnInferenceRuntime {
    constructor(context: Context) : this(AndroidCrnnInterpreterAccess(Interpreter(loadCrnnModel(context))))
    private var resolvedOutputShape = intArrayOf()
    private var reportedOutputShape = intArrayOf()
    private var resolvedOutputSignature = intArrayOf()
    private var resolvedOutputType = "UNRESOLVED_BEFORE_INFERENCE"
    override val inputShape get() = interpreter.inputShape()
    // TFLite 2.16.1 caches TensorImpl.shapeCopy on first getOutputTensor(). Its
    // ByteBuffer path does not resize/allocate, so a pre-invoke dynamic shape may
    // never be refreshed before copyTo checks the Java output shape. We avoid
    // Java array copying entirely and derive the resolved sequence from native
    // bytes and the fixed batch/class contract, retaining the full native output.
    override val outputShape get() = resolvedOutputShape.copyOf()
    override val cachedOutputShape get() = reportedOutputShape.copyOf()
    override val inputShapeSignature get() = interpreter.inputShapeSignature()
    override val outputShapeSignature get() = resolvedOutputSignature.copyOf()
    override val inputType get() = interpreter.inputType()
    override val outputType get() = resolvedOutputType

    init {
        Log.i(RECOGNITION_LOG_TAG, "CRNN runtime input=${inputShape.contentToString()} signature=${inputShapeSignature.contentToString()} $inputType " +
            "output(before inference)=${outputShape.contentToString()} $outputType")
    }

    override fun run(input: ByteBuffer): Array<Array<FloatArray>> {
        Log.d(RECOGNITION_LOG_TAG, "CRNN invoke bytes=${input.remaining()} position=${input.position()} " +
            "order=${input.order()} input=${inputShape.contentToString()} output(before)=${outputShape.contentToString()}")
        val shape = inputShape
        require(shape.contentEquals(intArrayOf(1, 512, 32)) && inputType == "FLOAT32") {
            "Unexpected Epoch 3 input contract: ${shape.contentToString()} $inputType"
        }
        require(input.remaining() == shape.fold(4) { bytes, dimension -> bytes * dimension }) {
            "CRNN input byte count ${input.remaining()} does not match ${shape.contentToString()}"
        }
        interpreter.invoke(input)
        val cachedShape = interpreter.outputShape()
        val signature = interpreter.outputShapeSignature()
        val type = interpreter.outputType()
        val nativeBytes = interpreter.outputNumBytes()
        val buffer = interpreter.outputBuffer().duplicate().order(ByteOrder.nativeOrder())
        Log.i(RECOGNITION_LOG_TAG, "CRNN native output cachedShape=${cachedShape.contentToString()} " +
            "signature=${signature.contentToString()} type=$type nativeBytes=$nativeBytes bufferBytes=${buffer.remaining()}")
        val output = readCrnnLogits(buffer, nativeBytes, cachedShape, signature, type, shape)
        reportedOutputShape = cachedShape.copyOf()
        resolvedOutputShape = intArrayOf(output.size, output[0].size, output[0][0].size)
        resolvedOutputSignature = signature.copyOf()
        resolvedOutputType = type
        Log.i(RECOGNITION_LOG_TAG, "CRNN invoke completed cachedTensorShape=${cachedShape.contentToString()} " +
            "signature=${signature.contentToString()} nativeBytes=$nativeBytes resolvedOutput=${outputShape.contentToString()}")
        return output
    }

    override fun close() = interpreter.close()

}

/** Read every native FLOAT32 logit, independent of TensorImpl's cached time axis. */
internal fun readCrnnLogits(buffer: ByteBuffer, nativeBytes: Int, cachedShape: IntArray,
                           signature: IntArray, type: String, inputShape: IntArray): Array<Array<FloatArray>> {
    require(type == "FLOAT32") { "Unexpected CRNN output type $type" }
    require(signature.size == 3 && signature[2] == 80 && cachedShape.size == 3 && cachedShape[2] == 80) {
        "Unexpected CRNN output layout: cached=${cachedShape.contentToString()} signature=${signature.contentToString()}"
    }
    val batch = inputShape[0]
    val classes = signature[2]
    require(batch > 0 && (signature[0] == -1 || signature[0] == batch) && cachedShape[0] == batch) {
        "Unexpected CRNN output batch contract"
    }
    require(nativeBytes > 0 && nativeBytes % (batch * classes * 4) == 0 && buffer.remaining() == nativeBytes) {
        "Invalid CRNN native output bytes=$nativeBytes bufferBytes=${buffer.remaining()}"
    }
    val steps = nativeBytes / (batch * classes * 4)
    // This fixed model has width/4 CTC steps (not a truncated one-step output).
    // Derive length from native bytes, then verify the architecture's contract.
    val expectedSteps = inputShape[1] / 4
    require(steps == expectedSteps && (signature[1] == -1 || signature[1] == steps)) {
        "Native CRNN output resolved to $steps steps, expected $expectedSteps for input width ${inputShape[1]}; " +
            "cached=${cachedShape.contentToString()} bytes=$nativeBytes"
    }
    val floats = buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
    return Array(batch) { Array(steps) { FloatArray(classes).also { floats.get(it) } } }
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
