package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.ceil

/** Explicit offline research A/B. Rendered controls are NEVER described as photographed handwriting. */
@RunWith(AndroidJUnit4::class)
class PaddleCropRefinementDeviceTest {
    private data class Sample(val id:String,val category:String,val truth:String,
                              val bitmap:Bitmap,val sourceBox:Rect,val kind:String)
    @Test fun compareAllAvailableCrops():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runCropRefinementResearch")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val corpus=args.getString("corpus")?:error("Pass corpus=real or rendered")
        require(corpus in listOf("real","rendered"))
        val root=File(context.getExternalFilesDir(null),"paddle-crop-refinement-research")
        val directory=File(root,corpus+"-"+UUID.randomUUID()).apply{check(mkdirs())}
        val rows=JSONArray()
        val report=JSONObject().put("schemaVersion",1).put("status","started")
            .put("corpus",corpus).put("revision",PaddleCropRefiner.REVISION).put("samples",rows)
            .put("pssScope","Process snapshots, not per-stage peak allocation")
        fun flush(){File(directory,"record.json").writeText(report.toString(2))}
        fun png(name:String,b:Bitmap){File(directory,name).outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))}}
        fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        fun pss()=Debug.MemoryInfo().also{Debug.getMemoryInfo(it)}.totalPss
        val samples=mutableListOf<Sample>()
        if(corpus=="real"){
            val captured=File(args.getString("capturedDirectory")?:error("capturedDirectory required"))
            val old=JSONObject(File(captured,"scan.json").readText())
            for(i in 0..3){
                val line=old.getJSONArray("lines").getJSONObject(i)
                val box=line.getJSONArray("box")
                val upper=i==1||i==3
                samples.add(Sample("line-"+(i+1),if(upper)"uppercase" else "mixed_case",
                    if(upper)"THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG." else "The quick brown fox jumps over the lazy dog.",
                    BitmapFactory.decodeFile(File(captured,line.getString("crop")).absolutePath),
                    Rect(box.getInt(0),box.getInt(1),box.getInt(2),box.getInt(3)),"photographed_handwriting"))
            }
            val title=instrumentation.context.assets.open("paddle_reference/fractional.png").use{BitmapFactory.decodeStream(it)}
            samples.add(Sample("title","mixed_case","Mobile Application Development",title,
                Rect(0,0,title.width,title.height),"photographed_handwriting"))
        }else{
            val definitions=listOf(
                Triple("lowercase","lowercase","the quick brown fox jumps over the lazy dog."),
                Triple("mixed","mixed_case","The Quick brown fox jumps over the lazy dog."),
                Triple("upper","uppercase","THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG."),
                Triple("technical","technical","CoroutineScope SQL HTTP kotlinx.coroutines"),
                Triple("numbers","numbers","0 1 2 3 4 5 6 7 8 9 10.25 -42 2026"),
                Triple("formula","formula","F = ma; x² + y² = r²; O(n log n)"),
                Triple("short","short","I l 1"),
                Triple("faint","faint","Database transactions preserve consistency."),
                Triple("tight","tightly_spaced","minimum width; tightly spaced strokes."),
                Triple("long","long","Concurrent processes share limited resources while database transactions preserve consistency.")
            )
            for((id,category,truth)in definitions){
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                    color=if(id=="faint")Color.rgb(230,230,230) else Color.BLACK
                    textSize=32f
                    if(id=="tight")textScaleX=0.7f
                }
                val width=ceil(paint.measureText(truth)).toInt()+32
                val b=Bitmap.createBitmap(width,120,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
                Canvas(b).drawText(truth,16f,76f,paint)
                samples.add(Sample(id,category,truth,b,Rect(1300,400,1300+width,520),"synthetic_rendered_control"))
            }
        }
        report.put("declaredSampleCount",samples.size)
            .put("modelSha256",PaddleModelAssets.MODEL_SHA256).put("dictionarySha256",PaddleModelAssets.DICTIONARY_SHA256)
        flush()
        val runtime=OnnxPaddleRecognitionRuntime(context)
        val off=PaddleOcrLineRecognizer(runtime,PaddleInputPreprocessor(),PaddleCtcDecoder(PaddleModelAssets.characters(context)),false)
        val on=PaddleOcrLineRecognizer(runtime,PaddleInputPreprocessor(),PaddleCtcDecoder(PaddleModelAssets.characters(context)),true)
        try{
            for(sample in samples){
                val bitmap=sample.bitmap
                val original=bitmap.copy(Bitmap.Config.ARGB_8888,false)
                png(sample.id+"-original.png",bitmap)
                val originalBox=Rect(sample.sourceBox)
                val record=JSONObject().put("id",sample.id).put("category",sample.category)
                    .put("kind",sample.kind).put("groundTruth",sample.truth)
                    .put("sourceBox",JSONArray(listOf(originalBox.left,originalBox.top,originalBox.right,originalBox.bottom)))
                    .put("sourceRegionId",if(sample.id=="title")999 else samples.indexOf(sample)+100)
                    .put("originalWidth",bitmap.width).put("originalHeight",bitmap.height)
                    .put("originalPngSha256",sha(File(directory,sample.id+"-original.png").readBytes()))
                val trials=JSONArray();record.put("trials",trials);rows.put(record);flush()
                // Warm the shared model once, then reverse A/B order on the second iteration.
                off.recognizeLine(bitmap)
                repeat(2){iteration->
                    for(enabled in if(iteration==0)listOf(false,true)else listOf(true,false)){
                        val result=(if(enabled)on else off).recognizeLine(bitmap)
                        val d=result.cropRefinement
                        trials.put(JSONObject().put("enabled",enabled).put("iteration",iteration)
                            .put("rawText",result.prediction.text)
                            .put("preprocessingNs",result.preprocessingNs).put("inferenceNs",result.inferenceNs)
                            .put("decodingNs",result.decodingNs).put("refinementNs",result.refinementNs)
                            .put("pssKiB",pss()).put("applied",d?.applied?:false)
                            .put("reason",d?.reason?:"disabled").put("scratchBytes",d?.scratchBytes?:0)
                            .put("inputHeight",d?.inputHeight?:bitmap.height)
                            .put("inputWidth",PaddleInputPreprocessor.geometry(bitmap.width,d?.inputHeight?:bitmap.height).first)
                            .put("temporaryCropBytes",if(d?.applied==true)bitmap.width.toLong()*d.inputHeight*4 else 0)
                            .put("localInputBox",JSONArray(listOf(0,d?.top?:0,bitmap.width,d?.bottom?:bitmap.height))))
                        if(enabled&&iteration==0&&d!!.applied){
                            val refined=Bitmap.createBitmap(bitmap,0,d.top,bitmap.width,d.inputHeight)
                            try{png(sample.id+"-refined.png",refined)}finally{if(refined!==bitmap)refined.recycle()}
                        }
                        assertTrue(bitmap.sameAs(original));assertEquals(originalBox,sample.sourceBox);assertFalse(bitmap.isRecycled)
                        flush()
                    }
                }
                record.put("pixelsUnchanged",true).put("sourceBoxPreserved",true)
                original.recycle();flush()
            }
            report.put("status","completed");flush()
        }catch(error:Throwable){
            report.put("status","failed").put("failureType",error.javaClass.name);flush();throw error
        }finally{off.close();on.close();samples.forEach{it.bitmap.recycle()}}
    }
}
