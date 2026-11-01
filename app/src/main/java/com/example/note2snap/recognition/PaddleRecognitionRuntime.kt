package com.example.note2snap.recognition

import android.content.Context
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.example.note2snap.diagnostics.PerformanceDiagnostics
import com.example.note2snap.diagnostics.PerfStage
import java.io.Closeable

internal interface PaddleRecognitionRuntime : Closeable { fun run(input: PaddleInput): PaddleOutput }

/** One lazily initialized CPU session per comparison recognizer; serialized against close. */
internal class OnnxPaddleRecognitionRuntime(context: Context) : PaddleRecognitionRuntime {
    private val appContext = context.applicationContext
    private var session: OrtSession? = null
    private var closed = false
    private var initializationFailure: Throwable? = null
    private val environment get() = OrtEnvironment.getEnvironment() // Shared ORT environment; do not close globally.

    private fun session(): OrtSession {
        check(!closed) { "Paddle runtime is closed" }
        session?.let { return it }
        initializationFailure?.let { throw IllegalStateException("Paddle session initialization previously failed", it) }
        return try { PerformanceDiagnostics.measure(PerfStage.PADDLE_INITIALIZATION) {
            val bytes = PaddleModelAssets.verifiedBytes(appContext, PaddleModelAssets.MODEL, PaddleModelAssets.MODEL_SHA256)
            val created = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                environment.createSession(bytes, options)
            }
            try {
                require(created.inputNames == setOf("x") && created.outputNames.size == 1)
                val input = created.inputInfo.getValue("x").info as TensorInfo
                require(input.type == OnnxJavaType.FLOAT && input.shape.contentEquals(longArrayOf(-1, 3, 48, -1)))
                val output = created.outputInfo.values.single().info as TensorInfo
                require(output.type == OnnxJavaType.FLOAT && output.shape.size == 3 && output.shape[2] == 438L)
                session = created
                created
            } catch (error: Throwable) { created.close(); throw error }
        } } catch (error: Throwable) { initializationFailure = error; throw error }
    }

    @Synchronized override fun run(input: PaddleInput): PaddleOutput {
        require(input.width in 320..PaddleInputPreprocessor.MAX_WIDTH)
        val active = session()
        return PerformanceDiagnostics.measure(PerfStage.PADDLE_INFERENCE) {
            OnnxTensor.createTensor(environment, input.buffer.asFloatBuffer(), input.shape).use { tensor ->
                active.run(mapOf("x" to tensor)).use { result ->
                    val output = result[0] as OnnxTensor
                    val shape = output.info.shape
                    require(shape.size == 3 && shape[0] == 1L && shape[1] > 0 && shape[2] == 438L)
                    val values = FloatArray(Math.toIntExact(shape[1] * shape[2]))
                    output.floatBuffer.get(values)
                    PaddleOutput(values, Math.toIntExact(shape[1]), 438)
                }
            }
        }
    }

    @Synchronized override fun close() { if (!closed) { closed = true; session?.close(); session = null } }
}
