package com.example.note2snap.recognition

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Runs only on Android: native OpenCV pixels and bundled ORT CPU output against pinned host references. */
@RunWith(AndroidJUnit4::class)
class PaddleRecognitionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val assets get() = instrumentation.context.assets
    private fun floats(name: String): FloatArray {
        val bytes = assets.open("paddle_reference/$name.f32.gz").use { input -> GZIPInputStream(input).use { it.readBytes() } }
        return FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }

    /** Isolates actual APK dictionary loading from native model initialization. */
    @Test fun dictionaryIntegrityAndCharacterOrderMatchOfficialAsset() {
        val context = instrumentation.targetContext
        val bytes = PaddleModelAssets.verifiedBytes(context, PaddleModelAssets.DICTIONARY,
            PaddleModelAssets.DICTIONARY_SHA256)
        assertEquals(1416, bytes.size)
        assertFalse("Dictionary must retain official LF line endings", bytes.contains(13.toByte()))
        assertFalse("Dictionary must not acquire a UTF-8 BOM",
            bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        val characters = PaddleModelAssets.characters(context)
        assertEquals(438, characters.size)
        assertEquals("", characters.first())
        assertEquals("0", characters[1])
        assertEquals("☺", characters[436])
        assertEquals(" ", characters.last())
    }

    @Test fun nativePreprocessingInferenceAndRepeatedCloseMatchOfficialReferences(): Unit = runBlocking {
        val context = instrumentation.targetContext
        val manifest = assets.open("paddle_reference/manifest.json").use { JSONObject(it.bufferedReader().readText()) }
        val decoder = PaddleCtcDecoder(PaddleModelAssets.characters(context))
        val runtime = OnnxPaddleRecognitionRuntime(context)
        try {
            val fixtures = manifest.getJSONArray("fixtures")
            repeat(3) {
                for (i in 0 until fixtures.length()) {
                    val fixture = fixtures.getJSONObject(i); val name = fixture.getString("name")
                    val bitmap = assets.open("paddle_reference/$name.png").use { BitmapFactory.decodeStream(it) }
                    try {
                        val input = PaddleInputPreprocessor().prepare(bitmap)
                        val expected = floats(name)
                        val actual = FloatArray(input.buffer.remaining() / 4).also { input.buffer.asFloatBuffer().get(it) }
                        assertArrayEquals("OpenCV/preprocessing mismatch: $name", expected, actual, 1e-6f)
                        val output = runtime.run(input)
                        assertArrayEquals("ORT output mismatch: $name", floats("$name-output"), output.values, 1e-3f)
                        assertEquals(fixture.getString("text"), decoder.decode(output).text)
                    } finally { bitmap.recycle() }
                }
            }
        } finally { runtime.close(); runtime.close() }
    }
}
