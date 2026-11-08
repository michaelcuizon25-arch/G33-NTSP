package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaddleCropRefinerTest {
    private fun line(w: Int = 240, h: Int = 100): Bitmap {
        val b = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.WHITE)
        for (x in 20 until w-20 step 12) for(y in 30 until 70)
            for(dx in 0..2) b.setPixel(x+dx,y,Color.BLACK)
        return b
    }
    private fun assertProtectedPixelsRetained(b: Bitmap, d: PaddleCropRefinement) {
        for(y in 0 until b.height) for(x in 0 until b.width)
            if(b.getPixel(x,y)!=Color.WHITE) assertTrue("Lost pixel ($x,$y)",y>=d.top && y<d.bottom)
        assertEquals(Rect(0,d.top,b.width,d.bottom),d.localBox)
        assertTrue(d.inputHeight * 5 >= b.height * 4)
    }

    @Test fun removesOnlyFlatMarginsWithPaddingAndBoundedScale() {
        val b=line(); val original=b.copy(Bitmap.Config.ARGB_8888,false)
        val d=PaddleCropRefiner.analyze(b)
        assertTrue(d.applied)
        assertEquals("verified_flat_margin",d.reason)
        assertEquals(10,d.top); assertEquals(90,d.bottom)
        assertEquals(b.width*4,d.scratchBytes)
        assertProtectedPixelsRetained(b,d)
        assertTrue(b.sameAs(original))
        b.recycle();original.recycle()
    }

    @Test fun faintMarksPunctuationAscendersAndDescendersRemainEvenAtOneRgbLevel() {
        for(contrast in listOf(1,2,4,8,15,100)) {
            val b=line()
            val faint=Color.rgb(255-contrast,255-contrast,255-contrast)
            b.setPixel(90,3,faint) // detached dot / faint ascender
            b.setPixel(150,96,faint) // descender / punctuation
            val d=PaddleCropRefiner.analyze(b)
            assertProtectedPixelsRetained(b,d)
            assertEquals(0,d.top);assertEquals(b.height,d.bottom)
            b.recycle()
        }
    }

    @Test fun narrowILOneAndShortOrLongStrokeGroupsRetainEveryPixel() {
        for(width in listOf(48,240,1800)) {
            val b=Bitmap.createBitmap(width,100,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
            for(x in listOf(width/4,width/2,3*width/4)) for(y in 25 until 75) b.setPixel(x,y,Color.BLACK)
            // A numeral serif and tiny punctuation must extend the same protected envelope.
            b.setPixel(width/2+1,25,Color.BLACK); b.setPixel(width-3,80,Color.BLACK)
            assertProtectedPixelsRetained(b,PaddleCropRefiner.analyze(b))
            b.recycle()
        }
    }

    @Test fun ambiguousTexturedTransparentAndFrameTouchingMarginsAbstain() {
        val textured=line()
        for(y in 0 until textured.height) for(x in 0 until textured.width step 2)
            if(textured.getPixel(x,y)==Color.WHITE) textured.setPixel(x,y,Color.rgb(254,254,254))
        assertFalse(PaddleCropRefiner.analyze(textured).applied)
        textured.recycle()
        val framed=line()
        for(y in 0 until framed.height) framed.setPixel(1,y,Color.GRAY)
        assertFalse(PaddleCropRefiner.analyze(framed).applied)
        framed.recycle()
        val transparent=line()
        transparent.setHasAlpha(true); transparent.setPixel(0,0,Color.TRANSPARENT)
        assertEquals("transparent_input",PaddleCropRefiner.analyze(transparent).reason)
        transparent.recycle()
    }

    @Test fun blankAndAllFaintInputsAreNotAssumedToBeHandwriting() {
        val blank=Bitmap.createBitmap(240,100,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
        assertFalse(PaddleCropRefiner.analyze(blank).applied)
        for(y in 30 until 70) blank.setPixel(100,y,Color.rgb(250,250,250))
        assertEquals("insufficient_handwriting_evidence",PaddleCropRefiner.analyze(blank).reason)
        blank.recycle()
    }

    @Test fun longLineRefinementCannotExceedPaddleWidthEnvelope() {
        val b=line(8000,130)
        val d=PaddleCropRefiner.analyze(b)
        assertEquals("refined_aspect_ratio_limit",d.reason)
        assertFalse(d.applied)
        b.recycle()
    }

    @Test fun researchExportsKeepOriginalCropAndSourceGeometry() {
        val context=org.robolectric.RuntimeEnvironment.getApplication()
        val root=java.io.File(context.cacheDir,"refinement-"+java.util.UUID.randomUUID())
        val b=line()
        val sourceBox=Rect(1500,400,1740,500)
        val writer=PrimaryOcrDiagnostics(context,enabled=true,root=root)
        val d=PaddleCropRefiner.analyze(b)
        try {
            writer.begin(b,false)
            writer.line(1,91,PhysicalLine(b,sourceBox),null)
            writer.refinement(1,b,sourceBox,d,123L)
            val report=org.json.JSONObject(java.io.File(writer.directory,"scan.json").readText())
            val row=report.getJSONArray("lines").getJSONObject(0)
            assertEquals("[1500,400,1740,500]",row.getJSONArray("box").toString())
            val detail=row.getJSONObject("cropRefinement")
            assertEquals("[1500,400,1740,500]",detail.getJSONArray("originalSourceBox").toString())
            assertEquals("[1500,410,1740,490]",detail.getJSONArray("modelInputSourceBox").toString())
            assertTrue(detail.getBoolean("sourceBoxPreserved"))
            val original=android.graphics.BitmapFactory.decodeFile(java.io.File(writer.directory,"line-1.png").absolutePath)
            val refined=android.graphics.BitmapFactory.decodeFile(java.io.File(writer.directory,"line-1-paddle-refined.png").absolutePath)
            assertTrue(original.sameAs(b));assertEquals(80,refined.height)
            original.recycle();refined.recycle()
        } finally{b.recycle();root.deleteRecursively()}
    }

    private val chars=List(438){ when(it){0->"";1->"x";437->" ";else->"?"} }
    private fun engine(refine: Boolean, blank: Boolean=false, seen: (Bitmap)->Unit): PaddleOcrLineRecognizer {
        val runtime=object:PaddleRecognitionRuntime {
            override fun run(input:PaddleInput)=PaddleOutput(FloatArray(438).apply{this[if(blank)0 else 1]=1f},1,438)
            override fun close(){}
        }
        return PaddleOcrLineRecognizer(runtime,PaddleInputPreprocessor{b,w->seen(b);ByteArray(w*48*3)},
            PaddleCtcDecoder(chars),refine)
    }

    @Test fun disabledPathKeepsInputIdentityAndEnabledPathNeverRecyclesCallerBitmap(): Unit=runBlocking {
        val b=line();val original=b.copy(Bitmap.Config.ARGB_8888,false)
        val off=engine(false){assertSame(b,it)}
        val on=engine(true){assertTrue(it.height<b.height);assertEquals(b.width,it.width)}
        try {
            val baseline=off.recognizeLine(b);val refined=on.recognizeLine(b)
            assertNull(baseline.cropRefinement);assertEquals(0L,baseline.refinementNs)
            assertTrue(refined.cropRefinement!!.applied)
            assertEquals(baseline.prediction,refined.prediction)
            assertFalse(b.isRecycled);assertTrue(b.sameAs(original))
        } finally{off.close();on.close();b.recycle();original.recycle()}
    }

    @Test fun sourceIdsAndDistantColumnBoundingBoxesRemainOriginal(): Unit=runBlocking {
        val b=line()
        val regions=listOf(Region(21,Rect(100,200,340,300),RegionType.TEXT,1000,b),
            Region(99,Rect(1400,200,1640,300),RegionType.TEXT,1000,b))
        val expected=regions.map{r->r.id to PhysicalLineSegmenter().splitIntoPhysicalLines(r).single().boundingBox}
        val primary=PaddlePrimaryLineRecognizer({engine(true){}},{error("Usable Paddle must not start CRNN")})
        try {
            val actual=primary.recognize(regions)
            assertEquals(expected,actual.map{it.sourceRegionId to it.boundingBox})
            assertTrue(actual[1].boundingBox.left-actual[0].boundingBox.left==1300)
            assertFalse(b.isRecycled)
        } finally{primary.close();b.recycle()}
    }

    @Test fun unusableRefinedPredictionUsesOriginalPhysicalCropForCrnn(): Unit=runBlocking {
        val b=line()
        val region=Region(7,Rect(900,300,1140,400),RegionType.TEXT,1000,b)
        val physical=PhysicalLineSegmenter().splitIntoPhysicalLines(region).single()
        val expectedBox=Rect(physical.boundingBox)
        val expectedHeight=physical.bitmap.height
        var paddleHeight=0;var fallbacks=0
        val primary=PaddlePrimaryLineRecognizer({engine(true,true){paddleHeight=it.height}},{
            object:LineRecognizer{
                override suspend fun recognize(regions:List<Region>):List<RecognizedLine>{
                    fallbacks++
                    val original=regions.single()
                    assertEquals(expectedHeight,original.croppedBitmap.height)
                    assertEquals(expectedBox,original.boundingBox)
                    return listOf(RecognizedLine(original.id,original.boundingBox,"fallback",0.5f))
                }
                override fun close(){}
            }
        })
        try {
            assertEquals("fallback",primary.recognize(listOf(region)).single().text)
            assertEquals(1,fallbacks);assertTrue(paddleHeight<expectedHeight)
        } finally{primary.close();if(physical.bitmap!==b)physical.bitmap.recycle();b.recycle()}
    }
}
