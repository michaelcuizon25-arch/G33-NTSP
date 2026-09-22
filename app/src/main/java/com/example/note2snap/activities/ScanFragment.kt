package com.example.note2snap.activities

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect // CHANGE: Imported Rect for bounding box spatial positioning
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.note2snap.R
import com.example.note2snap.ccl.Region
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.BlockType // CHANGE: Imported BlockType for visual vs text block classification
import com.example.note2snap.model.Note // CHANGE: Imported Note database entity
import com.example.note2snap.model.NoteBlock // CHANGE: Imported NoteBlock model for structured note output
import com.example.note2snap.model.ScanHistory
import com.example.note2snap.recognition.WhiteboardRecognitionPipeline
import com.example.note2snap.utils.WhiteboardRuleEngine // CHANGE: Imported offline spatial rule engine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanFragment : Fragment() {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvInstruction: TextView
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var isTorchOn = false
    private var loadingDialog: AlertDialog? = null
    private var scanStartTime: Long = 0L

    private val selectImageFromGallery = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scanStartTime = System.currentTimeMillis()
            showLoadingDialog("Processing photo...")
            val savedPath = saveImageToInternalStorage(selectedUri)
            if (savedPath != null) {
                val localFileUri = Uri.fromFile(File(savedPath))
                processImageWithOcr(localFileUri, savedPath, "Gallery Note")
            } else {
                hideLoadingDialog()
                Toast.makeText(requireContext(), "Failed to save selected image.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), "Camera permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_scan, container, false)

        viewFinder = view.findViewById(R.id.viewFinder)
        tvInstruction = view.findViewById(R.id.tvInstruction)
        val btnCapture = view.findViewById<Button>(R.id.btnCapture)
        val btnGallery = view.findViewById<View>(R.id.btnGallery)
        val btnFlash = view.findViewById<ImageButton>(R.id.btnFlash)

        checkAndStartCamera()

        btnCapture.setOnClickListener {
            takePhoto()
        }

        btnGallery.setOnClickListener {
            selectImageFromGallery.launch("image/*")
        }

        btnFlash.setOnClickListener {
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                isTorchOn = !isTorchOn
                camera?.cameraControl?.enableTorch(isTorchOn)

                val statusText = if (isTorchOn) "Flashlight ON" else "Flashlight OFF"
                Toast.makeText(requireContext(), statusText, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Flash unavailable on this device", Toast.LENGTH_SHORT).show()
            }
        }

        return view
    }

    fun updateInstruction(message: String) {
        tvInstruction.text = message
    }

    private fun checkAndStartCamera() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Toast.makeText(requireContext(), "Failed to start camera.", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        scanStartTime = System.currentTimeMillis()
        showLoadingDialog("Scanning process...")

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "NOTE2SNAP_$timeStamp.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NOTE2SNAP")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            requireContext().contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    hideLoadingDialog()
                    Toast.makeText(requireContext(), "Photo capture failed.", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    if (savedUri != null) {
                        processImageWithOcr(savedUri, savedUri.toString(), "Scanned Note")
                    } else {
                        hideLoadingDialog()
                    }
                }
            }
        )
    }

    private fun preprocessBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmpGrayscale)
        val paint = Paint()

        val grayscaleMatrix = ColorMatrix().apply {
            setSaturation(0f)
        }

        val contrast = 1.3f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )

        grayscaleMatrix.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(grayscaleMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        return bmpGrayscale
    }

    private fun processImageWithOcr(imageUri: Uri, imagePath: String, fallbackTitle: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(imageUri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap == null) {
                    withContext(Dispatchers.Main) {
                        hideLoadingDialog()
                        Toast.makeText(requireContext(), "Failed to decode image.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val processedBitmap = preprocessBitmap(originalBitmap)

                // Working pipeline with no trained model required:
                // photo -> preprocessing -> CCL -> line crops -> ML Kit OCR -> ordered lines
                val pipelineResult = runCatching {
                    val pipeline = WhiteboardRecognitionPipeline(
                        requireContext()
                    )
                    try {
                        pipeline.process(originalBitmap)
                    } finally {
                        pipeline.close()
                    }
                }.getOrElse { error ->
                    error.printStackTrace()
                    null
                }

                if (
                    pipelineResult != null &&
                    (
                            pipelineResult.recognizedLines.isNotEmpty() ||
                                    pipelineResult.diagramRegions.isNotEmpty()
                            )
                ) {

                    val structuredNote = pipelineResult.structuredNote

                    // CHANGE: Save detected diagram/visual bounding regions into file storage and return as visual blocks
                    val visualBlocks = saveDetectedDiagrams(pipelineResult.diagramRegions)

                    /*
                     * CHANGE: Avoid re-sorting text blocks spatially to preserve multi-column logical reading order.
                     * Only visual blocks are ordered among themselves before placing them at the top of body text.
                     */
                    val orderedVisualBlocks = visualBlocks.sortedWith(
                        compareBy<NoteBlock>(
                            { it.boundingBox?.top ?: Int.MAX_VALUE },
                            { it.boundingBox?.left ?: Int.MAX_VALUE }
                        )
                    )

                    // CHANGE: Merge visual blocks ahead of text blocks while maintaining structuredNote logical sequence
                    val allBlocks = orderedVisualBlocks + structuredNote.blocks

                    // CHANGE: Convert blocks into HTML formatted content string for rendering in PdfViewerActivity
                    val formattedContent = allBlocks
                        .map { renderNoteBlock(it) }
                        .filter { it.isNotBlank() }
                        .joinToString("<br/>")

                    val finalTitle = structuredNote.title
                        .takeIf { it.isNotBlank() && it != "Untitled Scan" }
                        ?: fallbackTitle

                    saveNoteToDatabase(finalTitle, formattedContent, imagePath)
                    saveScanHistory(finalTitle, imagePath)

                    val elapsedTime = System.currentTimeMillis() - scanStartTime
                    delay((5000L - elapsedTime).coerceAtLeast(0L))

                    withContext(Dispatchers.Main) {
                        hideLoadingDialog()

                        startActivity(
                            Intent(requireContext(), PdfViewerActivity::class.java).apply {
                                putExtra("TITLE", finalTitle)
                                putExtra("CONTENT", formattedContent)
                                putExtra("IMAGE_PATH", imagePath)
                            }
                        )
                    }

                    return@launch
                }

                // CHANGE: Safe fallback using ML Kit TextRecognizer when the custom pipeline produces no results
                val inputImage = InputImage.fromBitmap(processedBitmap, 0)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                withContext(Dispatchers.Main) {
                    recognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            // CHANGE: Pass ML Kit 'visionText' object directly to WhiteboardRuleEngine to preserve bounding box spatial coordinates
                            val structuredNote = WhiteboardRuleEngine.process(visionText)
                            val formattedContent = structuredNote.blocks.joinToString("<br/>") { it.formattedText }

                            val extractedTitle = if (structuredNote.title.isNotBlank() && structuredNote.title != "Untitled Scan") {
                                structuredNote.title
                            } else {
                                fallbackTitle
                            }

                            val finalTitle = if (extractedTitle.isNotBlank()) extractedTitle else fallbackTitle

                            lifecycleScope.launch(Dispatchers.IO) {
                                saveNoteToDatabase(finalTitle, formattedContent, imagePath)
                                saveScanHistory(finalTitle, imagePath)

                                val elapsedTime = System.currentTimeMillis() - scanStartTime
                                val remainingDelay = (5000L - elapsedTime).coerceAtLeast(0L)
                                delay(remainingDelay)

                                withContext(Dispatchers.Main) {
                                    hideLoadingDialog()

                                    val intent = Intent(requireContext(), PdfViewerActivity::class.java).apply {
                                        putExtra("TITLE", finalTitle)
                                        putExtra("CONTENT", formattedContent)
                                        putExtra("IMAGE_PATH", imagePath)
                                    }
                                    startActivity(intent)
                                }
                            }
                        }
                        .addOnFailureListener {
                            lifecycleScope.launch(Dispatchers.IO) {
                                saveNoteToDatabase(fallbackTitle, "", imagePath)
                                saveScanHistory(fallbackTitle, imagePath)

                                val elapsedTime = System.currentTimeMillis() - scanStartTime
                                val remainingDelay = (5000L - elapsedTime).coerceAtLeast(0L)
                                delay(remainingDelay)

                                withContext(Dispatchers.Main) {
                                    hideLoadingDialog()
                                    Toast.makeText(requireContext(), "OCR processing failed.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    hideLoadingDialog()
                }
            }
        }
    }

    private fun showLoadingDialog(message: String) {
        if (loadingDialog == null) {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_loading, null)
            dialogView.findViewById<TextView>(R.id.tvLoadingMessage).text = message

            loadingDialog = AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setCancelable(false)
                .create().apply {
                    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                }
        } else {
            loadingDialog?.findViewById<TextView>(R.id.tvLoadingMessage)?.text = message
        }
        loadingDialog?.show()
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun saveImageToInternalStorage(uri: Uri): String? {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val fileName = "gallery_${System.currentTimeMillis()}.jpg"
            val file = File(requireContext().filesDir, fileName)
            val outputStream = FileOutputStream(file)

            inputStream?.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // CHANGE: Added helper function to save diagram regions to PNGs and return NoteBlock items with image paths and bounding boxes
    private fun saveDetectedDiagrams(regions: List<Region>): List<NoteBlock> {
        if (regions.isEmpty()) {
            return emptyList()
        }

        val directory = File(requireContext().filesDir, "recognized_diagrams").apply {
            mkdirs()
        }

        return regions.mapIndexedNotNull { index, region ->
            runCatching {
                val file = File(directory, "diagram_${System.currentTimeMillis()}_$index.png")

                FileOutputStream(file).use { stream ->
                    region.croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }

                NoteBlock(
                    rawText = "",
                    type = BlockType.VISUAL,
                    formattedText = "",
                    imagePath = file.absolutePath,
                    boundingBox = Rect(region.boundingBox)
                )
            }.getOrNull()
        }
    }

    // CHANGE: Added block rendering helper to format visual blocks into <img> HTML elements and text blocks into styled HTML
    private fun renderNoteBlock(block: NoteBlock): String {
        return when (block.type) {
            BlockType.VISUAL -> {
                val path = block.imagePath ?: return ""
                val file = File(path)
                val imageUri = Uri.fromFile(file)

                """
                <p>
                    <img src='$imageUri' alt='Whiteboard diagram' />
                </p>
                """.trimIndent()
            }
            else -> {
                block.formattedText.takeIf { it.isNotBlank() } ?: block.rawText
            }
        }
    }

    private suspend fun saveNoteToDatabase(title: String, content: String, imagePath: String) {
        val currentDate = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date())
        val newNote = Note(
            title = title,
            content = content,
            imagePath = imagePath,
            dateEdited = currentDate
        )
        AppDatabase.getDatabase(requireContext()).appDao().insertNote(newNote)
    }

    private suspend fun saveScanHistory(title: String, imagePath: String) {
        val currentDate = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date())
        val historyItem = ScanHistory(
            title = title,
            date = currentDate,
            imagePath = imagePath
        )
        AppDatabase.getDatabase(requireContext()).appDao().insertScanHistory(historyItem)
    }
}