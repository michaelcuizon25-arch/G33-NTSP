package com.example.note2snap.ccl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import com.example.note2snap.preprocessing.WhiteboardPreprocessor
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameTrackFilterTest {
    private val capturedRoot = File("../evaluation/next-primary-run/90bc382b-9eb9-4da4-993b-bfd729ed6038")
    private fun mask(binary: Bitmap): BooleanArray {
        val pixels=IntArray(binary.width*binary.height)
        binary.getPixels(pixels,0,binary.width,0,0,binary.width,binary.height)
        return BooleanArray(pixels.size) { Color.red(pixels[it])>127 }
    }

    @Test fun capturedFrameFragmentsHaveIndependentImageSupport() {
        org.junit.Assume.assumeTrue("Captured research fixture is not checked out",File(capturedRoot,"scan.json").isFile && File(capturedRoot,"recognition.png").isFile)
        val image = BitmapFactory.decodeFile(File(capturedRoot,"recognition.png").absolutePath)
        val original = image.copy(Bitmap.Config.ARGB_8888,false)
        val prepared = WhiteboardPreprocessor().process(image)
        val foreground = mask(prepared.binarizedBitmap)
        val initialMask = foreground.copyOf()
        val report = JSONObject(File(capturedRoot,"scan.json").readText())
        val events = report.getJSONArray("filterDecisions")
        val boxes = (0 until events.length()).map { events.getJSONObject(it) }
            .filter { it.getString("stage") == "raw_component" && it.getBoolean("accepted") }
            .map { it.getJSONArray("box").let { a -> Rect(a.getInt(0),a.getInt(1),a.getInt(2),a.getInt(3)) } }
        val start = System.nanoTime()
        val detector = FrameTrackFilter.detect(image,boxes,32,foreground)
        val fragments = listOf(Rect(111,879,121,1051),Rect(112,1064,116,1085),
            Rect(112,1101,116,1120),Rect(111,1180,115,1198))
        val checks=mutableListOf<String>()
        val decisions = fragments.map { detector?.isFrameComponent(it) { text -> checks.add(text) } }
        val elapsed=System.nanoTime()-start
        val output = File("../evaluation/frame-filter-validation/detector-probe.json")
        output.parentFile!!.mkdirs()
        output.writeText(JSONObject().put("tracks", detector?.tracks.toString())
            .put("detectAndVerifyNs",elapsed).put("fragments",decisions.toString()).put("checks",JSONArray(checks)).toString(2))
        assertNotNull("Captured metal frame should have independent groove/edge support",detector)
        assertEquals(listOf(true,true,true,true),decisions)
        assertTrue(boxes.filter { it.left > 250 }.none { detector!!.isFrameComponent(it) })
        assertArrayEquals(initialMask,foreground)
        assertTrue(image.sameAs(original))
        image.recycle();original.recycle()
    }

    @Test fun actualCclRetainsExactlyFourCompleteOrderedTextLines() {
        org.junit.Assume.assumeTrue("Captured research fixture is not checked out",File(capturedRoot,"recognition.png").isFile)
        val source=BitmapFactory.decodeFile(File(capturedRoot,"recognition.png").absolutePath)
        val prepared=WhiteboardPreprocessor().process(source)
        val rejected=mutableListOf<Rect>()
        val allRaw=mutableListOf<Rect>()
        val start=System.nanoTime()
        val regions=ConnectedComponentLabeler().label(prepared.binarizedBitmap,prepared.recognitionBitmap) { stage,box,accepted,_ ->
            if(stage=="inset_frame_rejected") rejected.add(Rect(box))
            if(stage=="raw_component" && accepted) allRaw.add(Rect(box))
        }.filter { it.type==RegionType.TEXT }.sortedBy { it.boundingBox.top }
        val report=JSONObject().put("cclNs",System.nanoTime()-start)
            .put("textBoxes",JSONArray(regions.map { JSONArray(listOf(it.boundingBox.left,it.boundingBox.top,it.boundingBox.right,it.boundingBox.bottom)) }))
            .put("rejected",JSONArray(rejected.map { JSONArray(listOf(it.left,it.top,it.right,it.bottom)) }))
        val imageFrame=FrameTrackFilter.detect(prepared.recognitionBitmap,allRaw,32,mask(prepared.binarizedBitmap))
        val checks=mutableListOf<String>()
        allRaw.filter{it.left<130 && it.right>100 && it.width()<=16}.forEach{imageFrame?.isFrameComponent(it){text->checks.add(text)}}
        report.put("tracks",imageFrame?.tracks.toString()).put("checks",JSONArray(checks))
        File("../evaluation/frame-filter-validation/after-ccl.json").writeText(report.toString(2))
        assertEquals(4,regions.size)
        assertTrue(rejected.contains(Rect(112,1064,116,1085)))
        assertTrue(rejected.contains(Rect(112,1101,116,1120)))
        assertTrue(rejected.contains(Rect(111,1180,115,1198)))
        // Every actual sentence's ink remains inside its original global coordinate system.
        for((index,region) in regions.withIndex()) {
            assertTrue(region.boundingBox.left<=280)
            assertTrue(region.boundingBox.right>=listOf(1140,1305,1290,1375)[index])
            assertTrue(region.boundingBox.top<=listOf(515,610,715,810)[index])
            assertTrue(region.boundingBox.bottom>=listOf(575,655,790,865)[index])
        }
        source.recycle()
    }

    private fun fixture(groove: Boolean=true): Bitmap = Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(230,230,230))
        for(y in 50 until 950) for(x in 90 until 150) setPixel(x,y,Color.rgb(185,188,195))
        if(groove) for(y in 50 until 950) for(x in 120 until 124) setPixel(x,y,Color.rgb(130,133,140))
        for(y in 50 until 950) for(x in 150 until 800) setPixel(x,y,Color.rgb(150,185,170))
    }
    private fun fragments()=(100..850 step 80).map { Rect(90,it,93,it+28) }

    @Test fun disconnectedFrameTrackCanBeVerifiedWithoutOneConnectedLongStroke() {
        val image=fixture()
        val boxes=fragments()
        val foreground=BooleanArray(800*1000)
        boxes.forEach { b -> for(y in b.top until b.bottom) for(x in b.left until b.right) foreground[y*800+x]=true }
        val detector=FrameTrackFilter.detect(image,boxes,28,foreground)
        assertNotNull(detector)
        assertTrue(boxes.all { detector!!.isFrameComponent(it) })
        image.recycle()
    }

    @Test fun genuineNarrowGlyphsNearFrameAndPhotoEdgesAreRetainedIncludingFaintInk() {
        for(value in listOf(40,177)) {
            val image=fixture()
            val boxes=fragments()
            val foreground=BooleanArray(800*1000)
            boxes.forEach { b -> for(y in b.top until b.bottom) for(x in b.left until b.right) foreground[y*800+x]=true }
            val glyphs=mutableListOf<Rect>()
            // Interior, on-frame, touching a frame edge, and near the photo edge.
            for(x in listOf(4,89,105,155,350,790)) for((index,kind) in listOf("I","l","1").withIndex()) {
                val y=400+index*90
                val right=x+if(kind=="I") 2 else 6
                val box=Rect(x,y,right,y+28)
                glyphs.add(box)
                for(py in y until y+28) for(px in x until right) {
                    val ink=px<x+2 || (kind=="l"&&py>=y+26) || (kind=="1"&&py<y+3)
                    if(ink) {image.setPixel(px,py,Color.rgb(value,value,value+7));foreground[py*800+px]=true}
                }
            }
            val detector=FrameTrackFilter.detect(image,boxes,28,foreground)
            assertNotNull(detector)
            val bad=glyphs.filter { detector!!.isFrameComponent(it) }
            assertTrue("Real I/l/1 must survive: value=$value rejected=$bad",bad.isEmpty())
            image.recycle()
        }
    }

    @Test fun tallGrayHandwrittenStrokeAndTwoEdgeStripAreAmbiguousAndPreserved() {
        val image=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        for(y in 40 until 960) for(x in 90 until 106) image.setPixel(x,y,Color.rgb(180,180,180))
        val boxes=fragments().map { Rect(90,it.top,106,it.bottom) } + Rect(90,400,106,600)
        assertNull(FrameTrackFilter.detect(image,boxes,32,BooleanArray(800*1000){true}))
        image.recycle()
        val plain=fixture(groove=false)
        assertNull(FrameTrackFilter.detect(plain,fragments(),28,BooleanArray(800*1000){true}))
        plain.recycle()
    }

    @Test fun isolatedSimilarStrokesAndPlainWritingDoNotCreateAFrameTrack() {
        val image=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        val boxes=listOf(Rect(100,100,103,130),Rect(100,300,103,330),Rect(100,500,103,530),Rect(100,700,103,730))
        boxes.forEach {b->for(y in b.top until b.bottom)for(x in b.left until b.right)image.setPixel(x,y,Color.BLACK)}
        assertNull(FrameTrackFilter.detect(image,boxes,30,BooleanArray(800*1000){true}))
        image.recycle()
    }
}
