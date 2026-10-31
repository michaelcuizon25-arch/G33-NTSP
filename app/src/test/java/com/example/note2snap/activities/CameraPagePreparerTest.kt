package com.example.note2snap.activities

import android.graphics.Rect
import com.example.note2snap.preprocessing.GuideCropGeometry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class CameraPagePreparerTest {
    @Test fun fillCenterGuideBoundsMatchExistingGeometryAndClampEdges() {
        assertEquals(Rect(400,300,3600,2700),GuideCropGeometry(1000,750,100,75,800,600).bounds(4000,3000))
        assertEquals(Rect(400,500,3600,2500),GuideCropGeometry(1000,600,100,50,800,500).bounds(4000,3000))
        assertEquals(Rect(0,0,4000,3000),GuideCropGeometry(1000,750,-20,-20,1100,850).bounds(4000,3000))
    }
    @Test fun preparationUsesWorkerAndValidatesExactPreparedFileOnce() = runBlocking {
        val caller=Thread.currentThread()
        val calls=mutableListOf<String>()
        val expected=ImageQualityResult(false,ImageQualityIssue.BLURRY,"same warning","same message")
        val preparer=CameraPagePreparer({ _, _ ->
            assertNotSame(caller,Thread.currentThread());calls.add("crop");"/prepared.jpg"
        }, { path ->
            assertNotSame(caller,Thread.currentThread());calls.add(path);expected
        })
        val result=preparer.prepare(File("/original.jpg"),null)
        assertSame(expected,result.quality)
        assertEquals("/prepared.jpg",result.path)
        assertEquals(listOf("crop","/prepared.jpg"),calls)
    }
}
