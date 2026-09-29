package com.example.note2snap.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.note2snap.R
import com.example.note2snap.ccl.ConnectedComponentLabeler
import com.example.note2snap.ccl.RegionType
import com.example.note2snap.data.AppDatabase
import com.example.note2snap.model.ScanHistory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanFragment : Fragment() {

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var isFlashOn = false
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var viewFinder: PreviewView
    private lateinit var viewFrame: View
    private lateinit var progressBar: ProgressBar

    private var analyzingOverlay: View? = null
    private var analyzingStartedAt: Long = 0L
    private var analysisSequenceStarted = false

    private var controlPanelView: View? = null
    private var backButtonContainerView: View? = null
    private var flashControlView: View? = null


    // Gallery Picker Contract
    private val selectImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processImageUri(it, cropToGuide = false) }
    }

    // Camera Permission Contract
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            context?.let {
                Toast.makeText(it, "Camera permission is required to scan documents.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_scan, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewFinder = view.findViewById(R.id.viewFinder)
        viewFrame = view.findViewById(R.id.viewFrame)
        progressBar = view.findViewById(R.id.progressBarScan)

        analyzingOverlay = view.findViewById(R.id.analyzingOverlay)

        val backButtonContainer = view.findViewById<View>(R.id.backButtonContainer)
        val btnBack = view.findViewById<View>(R.id.btnBack)
        val flashControl = view.findViewById<View>(R.id.flashControl)
        val btnFlash = view.findViewById<ImageView>(R.id.btnFlash)
        val controlPanel = view.findViewById<View>(R.id.controlPanel)

        controlPanelView = controlPanel
        backButtonContainerView = backButtonContainer
        flashControlView = flashControl

        val btnCapture = view.findViewById<View>(R.id.btnCapture)
        val btnGallery = view.findViewById<View>(R.id.btnGallery)
        val tvCancel = view.findViewById<View>(R.id.tvCancel)

        // Elevate parent containers above Camera PreviewView in the Z-axis
        backButtonContainer?.bringToFront()
        flashControl?.bringToFront()
        controlPanel?.bringToFront()
        analyzingOverlay?.bringToFront()
        progressBar.bringToFront()

        cameraExecutor = Executors.newSingleThreadExecutor()

        checkCameraPermissionAndStart()

        btnCapture?.setOnClickListener { takePhoto() }
        btnGallery?.setOnClickListener { selectImageLauncher.launch("image/*") }

        // Flash click listeners
        val flashToggleListener = View.OnClickListener { toggleFlash() }
        flashControl?.setOnClickListener(flashToggleListener)
        btnFlash?.setOnClickListener(flashToggleListener)

        // Navigation back listeners pointing directly to Home
        backButtonContainer?.setOnClickListener { navigateToHome() }
        btnBack?.setOnClickListener { navigateToHome() }
        tvCancel?.setOnClickListener { navigateToHome() }

        // Device System Back Gesture / Back Button Handler
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    navigateToHome()
                }
            }
        )
    }

    private fun navigateToHome() {
        (activity as? MainActivity)?.selectTab(R.id.nav_home)
    }

    private fun checkCameraPermissionAndStart() {
        val safeContext = context ?: return
        if (ContextCompat.checkSelfPermission(safeContext, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val safeContext = context ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(safeContext)

        cameraProviderFuture.addListener({
            if (!isAdded || viewLifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener

            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(if (isFlashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                if (!cameraProvider.hasCamera(cameraSelector)) {
                    Log.w("ScanFragment", "No back camera found on this device")
                    return@addListener
                }

                cameraProvider.unbindAll()

                // Bind camera instance to class variable
                val boundCamera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                camera = boundCamera

                // Restore torch state if flash was previously toggled on
                if (isFlashOn && boundCamera.cameraInfo.hasFlashUnit()) {
                    boundCamera.cameraControl.enableTorch(true)
                }

                setupCameraGestures(safeContext, viewFinder, boundCamera)

            } catch (exc: Exception) {
                Log.e("ScanFragment", "Camera binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(safeContext))
    }

    private fun toggleFlash() {
        val currentCamera = camera
        if (currentCamera == null) {
            context?.let { Toast.makeText(it, "Camera not ready", Toast.LENGTH_SHORT).show() }
            return
        }

        if (!currentCamera.cameraInfo.hasFlashUnit()) {
            context?.let { Toast.makeText(it, "Flash unavailable on this device", Toast.LENGTH_SHORT).show() }
            return
        }

        isFlashOn = !isFlashOn

        // 1. Enable flashlight on camera preview (Torch)
        currentCamera.cameraControl.enableTorch(isFlashOn)

        // 2. Set capture mode flash state
        imageCapture?.flashMode = if (isFlashOn) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }

        // 3. Update UI indicator
        updateFlashUI()
    }

    private fun updateFlashUI() {
        val tvFlashState = view?.findViewById<TextView>(R.id.tvFlashState)
        val btnFlash = view?.findViewById<ImageView>(R.id.btnFlash)

        if (isFlashOn) {
            tvFlashState?.text = "On"
            btnFlash?.setColorFilter("#16A34A".toColorInt()) // Active Green
        } else {
            tvFlashState?.text = "Off"
            btnFlash?.setColorFilter("#5A7FDB".toColorInt()) // Default Accent Blue
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCameraGestures(context: Context, previewView: PreviewView, camera: Camera) {
        val scaleGestureDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val currentZoomRatio = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    val delta = detector.scaleFactor
                    camera.cameraControl.setZoomRatio(currentZoomRatio * delta)
                    return true
                }
            }
        )

        previewView.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)

            if (event.action == MotionEvent.ACTION_UP && !scaleGestureDetector.isInProgress) {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point).build()
                camera.cameraControl.startFocusAndMetering(action)
                view.performClick()
            }
            true
        }
    }

    private fun takePhoto() {
        val safeContext = context ?: return
        val capture = imageCapture ?: run {
            Toast.makeText(safeContext, "Camera not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        val cacheDir = safeContext.externalCacheDir ?: safeContext.cacheDir
        val photoFile = File(
            cacheDir,
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(safeContext),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    if (!isAdded) return
                    setLoading(false)
                    Log.e("ScanFragment", "Photo capture failed: ${exc.message}", exc)
                    Toast.makeText(context, "Failed to capture image", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (!isAdded) return
                    processImageUri(
                        rawUri = Uri.fromFile(photoFile),
                        rawFilePath = photoFile.absolutePath,
                        cropToGuide = true
                    )
                }
            }
        )
    }

    private fun processImageUri(
        rawUri: Uri,
        rawFilePath: String? = null,
        cropToGuide: Boolean = false
    ) {
        val safeContext = context ?: return
        setLoading(true)

        try {
            val filePath = rawFilePath ?: if (rawUri.scheme == "content") {
                copyUriToCache(safeContext, rawUri)
            } else {
                rawUri.path
            }

            if (filePath == null) {
                setLoading(false)
                Toast.makeText(safeContext, "Failed to load image file", Toast.LENGTH_SHORT).show()
                return
            }

            val imageFile = File(filePath)

            val sourceFilePath =
                if (cropToGuide) {
                    cropImageToVisibleGuideFrame(
                        imageFile = imageFile
                    ) ?: filePath
                } else {
                    filePath
                }

            val sourceFile =
                File(sourceFilePath)

            showActualAnalysisState(
                AnalysisStage.DETECTING_TEXT
            )

            val image =
                InputImage.fromFilePath(
                    safeContext,
                    Uri.fromFile(sourceFile)
                )

            val recognizer =
                TextRecognition.getClient(
                    TextRecognizerOptions.DEFAULT_OPTIONS
                )

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!isAdded) return@addOnSuccessListener
                    showActualAnalysisState(
                        AnalysisStage.CHECKING_RECOGNITION
                    )

                    val extractedText = visionText.text
                    val finalText =
                        extractedText.ifBlank {
                            "[No text detected]"
                        }

                    val ocrIssues =
                        findOcrIssues(
                            visionText
                        )

                    lifecycleScope.launch(
                        Dispatchers.IO
                    ) {
                        val diagramHtml =
                            detectDiagramHtml(
                                sourceFilePath
                            )

                        withContext(
                            Dispatchers.Main
                        ) {
                            if (!isAdded) {
                                return@withContext
                            }

                            showActualAnalysisState(
                                AnalysisStage.STRUCTURING_NOTES
                            )

                            val finalContent =
                                if (diagramHtml.isBlank()) {
                                    finalText
                                } else {
                                    finalText +
                                            "<br/><br/><b>Detected Diagram</b><br/>" +
                                            diagramHtml
                                }

                            finishAnalyzingAndNavigate(
                                content = finalContent,
                                imagePath = sourceFilePath,
                                ocrIssues = ocrIssues
                            )
                        }
                    }
                }
                .addOnFailureListener { e ->
                    if (!isAdded) return@addOnFailureListener
                    setLoading(false)
                    Log.e("ScanFragment", "Text recognition failed", e)
                    Toast.makeText(context, "OCR failed to read text", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            if (isAdded) setLoading(false)
            Log.e("ScanFragment", "Error processing image", e)
            Toast.makeText(safeContext, "Error loading image", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun detectDiagramHtml(
        imagePath: String
    ): String {

        val source =
            decodeBitmapForDiagramDetection(
                imagePath
            ) ?: return ""

        val binary =
            createBinaryForDiagramDetection(
                source
            )

        return try {
            val regions =
                ConnectedComponentLabeler()
                    .label(
                        binary,
                        source
                    )
                    .filter {
                        it.type ==
                                RegionType.NON_TEXT
                    }
                    .filter { region ->
                        val box = region.boundingBox
                        val boxArea = box.width().toFloat() * box.height().toFloat()
                        val imageArea = source.width.toFloat() * source.height.toFloat()
                        val areaRatio = if (imageArea > 0f) boxArea / imageArea else 0f

                        box.width() >= 35 &&
                                box.height() >= 30 &&
                                areaRatio in 0.004f..0.60f
                    }
                    .sortedByDescending {
                        it.boundingBox.width() * it.boundingBox.height()
                    }
                    .take(3)

            if (regions.isEmpty()) {
                ""
            } else {
                val directory =
                    File(
                        requireContext().filesDir,
                        "recognized_diagrams"
                    ).apply {
                        mkdirs()
                    }

                regions.mapIndexedNotNull { index, region ->
                    runCatching {
                        val file =
                            File(
                                directory,
                                "diagram_${System.currentTimeMillis()}_$index.png"
                            )

                        FileOutputStream(file).use { stream ->
                            region.croppedBitmap.compress(
                                Bitmap.CompressFormat.PNG,
                                100,
                                stream
                            )
                        }

                        "<p><img src='file://${file.absolutePath}' alt='Detected whiteboard diagram'/></p>"
                    }.getOrNull()
                }.joinToString("<br/>")
            }

        } catch (error: OutOfMemoryError) {
            Log.e(
                "ScanFragment",
                "Diagram detection ran out of memory; continuing with text only.",
                error
            )
            ""

        } catch (error: Exception) {
            Log.w(
                "ScanFragment",
                "Diagram detection failed; continuing with text only.",
                error
            )
            ""

        } finally {
            if (!binary.isRecycled) {
                binary.recycle()
            }

            if (!source.isRecycled) {
                source.recycle()
            }
        }
    }

    private fun decodeBitmapForDiagramDetection(
        imagePath: String
    ): Bitmap? {

        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

        BitmapFactory.decodeFile(
            imagePath,
            bounds
        )

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val maxDimension = 1200
        var sampleSize = 1

        while (
            bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }

        return BitmapFactory.decodeFile(
            imagePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun createBinaryForDiagramDetection(
        source: Bitmap
    ): Bitmap {

        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)

        source.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val grayValues = IntArray(pixels.size)
        var graySum = 0L

        pixels.indices.forEach { index ->
            val pixel = pixels[index]
            val gray =
                (
                        Color.red(pixel) * 0.299f +
                                Color.green(pixel) * 0.587f +
                                Color.blue(pixel) * 0.114f
                        ).toInt().coerceIn(0, 255)

            grayValues[index] = gray
            graySum += gray.toLong()
        }

        val averageGray =
            if (grayValues.isNotEmpty()) {
                (graySum / grayValues.size).toInt()
            } else {
                160
            }

        val threshold =
            (averageGray - 28).coerceIn(80, 210)

        val binaryPixels = IntArray(pixels.size)

        grayValues.indices.forEach { index ->
            binaryPixels[index] =
                if (grayValues[index] < threshold) {
                    Color.WHITE
                } else {
                    Color.BLACK
                }
        }

        return Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        ).apply {
            setPixels(
                binaryPixels,
                0,
                width,
                0,
                0,
                width,
                height
            )
        }
    }

    private fun findOcrIssues(
        visionText: com.google.mlkit.vision.text.Text
    ): List<String> {

        if (visionText.text.isBlank()) {
            return listOf(
                "No text was recognized."
            )
        }

        val issues =
            mutableListOf<String>()

        visionText.textBlocks
            .flatMap { it.lines }
            .forEach { line ->

                val text =
                    line.text.trim()

                if (text.isBlank()) {
                    return@forEach
                }

                val compact =
                    text.filterNot {
                        it.isWhitespace()
                    }

                val alphaNumericCount =
                    compact.count {
                        it.isLetterOrDigit()
                    }

                val suspiciousCount =
                    compact.count {
                        !it.isLetterOrDigit() &&
                                it !in ".,:;!?()[]{}'\"/-+%&@#₱$"
                    }

                val suspiciousRatio =
                    if (compact.isNotEmpty()) {
                        suspiciousCount.toFloat() /
                                compact.length.toFloat()
                    } else {
                        0f
                    }

                val repeatedNoise =
                    Regex(
                        """([^\p{L}\p{N}\s])\1{2,}"""
                    ).containsMatchIn(
                        text
                    )

                val noReadableCharacters =
                    alphaNumericCount == 0 &&
                            compact.length >= 2

                val likelyGarbled =
                    compact.length >= 4 &&
                            suspiciousRatio >= 0.35f

                val validSingleLabel =
                    compact.length == 1 &&
                            compact[0].isLetterOrDigit()

                if (
                    !validSingleLabel &&
                    (
                            noReadableCharacters ||
                                    repeatedNoise ||
                                    likelyGarbled
                            )
                ) {
                    issues.add(
                        text
                    )
                }
            }

        return issues
            .distinct()
            .take(8)
    }

    /**
     * Crops the saved camera image to the SAME area shown inside viewFrame.
     *
     * Important:
     * - The phone/app stays portrait.
     * - We do NOT rotate the whole photo into landscape.
     * - We first make the JPEG upright using EXIF.
     * - Then we map the visible guide rectangle from PreviewView to the bitmap.
     *
     * PreviewView uses FILL_CENTER, so part of the camera image can extend
     * outside the visible PreviewView. The scale/offset math below accounts
     * for that before computing the crop rectangle.
     */
    private fun cropImageToVisibleGuideFrame(
        imageFile: File
    ): String? {

        return try {

            if (
                !::viewFinder.isInitialized ||
                !::viewFrame.isInitialized ||
                viewFinder.width <= 0 ||
                viewFinder.height <= 0 ||
                viewFrame.width <= 0 ||
                viewFrame.height <= 0
            ) {
                Log.w(
                    "ScanFragment",
                    "Guide frame is not measured yet"
                )
                return null
            }

            val uprightBitmap =
                decodeBitmapUpright(
                    imageFile
                ) ?: return null

            val previewWidth =
                viewFinder.width.toFloat()

            val previewHeight =
                viewFinder.height.toFloat()

            val imageWidth =
                uprightBitmap.width.toFloat()

            val imageHeight =
                uprightBitmap.height.toFloat()

            // PreviewView default / configured behavior: FILL_CENTER.
            // Scale until the whole PreviewView is filled.
            val scale =
                maxOf(
                    previewWidth / imageWidth,
                    previewHeight / imageHeight
                )

            val displayedImageWidth =
                imageWidth * scale

            val displayedImageHeight =
                imageHeight * scale

            // Amount of scaled image that sits outside PreviewView.
            val overflowX =
                (displayedImageWidth - previewWidth) / 2f

            val overflowY =
                (displayedImageHeight - previewHeight) / 2f

            // viewFrame and viewFinder are siblings in the same parent.
            val frameLeftInPreview =
                (viewFrame.left - viewFinder.left).toFloat()

            val frameTopInPreview =
                (viewFrame.top - viewFinder.top).toFloat()

            val frameRightInPreview =
                frameLeftInPreview +
                        viewFrame.width.toFloat()

            val frameBottomInPreview =
                frameTopInPreview +
                        viewFrame.height.toFloat()

            // Convert visible PreviewView coordinates back into bitmap pixels.
            var cropLeft =
                ((frameLeftInPreview + overflowX) / scale)
                    .toInt()

            var cropTop =
                ((frameTopInPreview + overflowY) / scale)
                    .toInt()

            var cropRight =
                ((frameRightInPreview + overflowX) / scale)
                    .toInt()

            var cropBottom =
                ((frameBottomInPreview + overflowY) / scale)
                    .toInt()

            // Clamp safely inside the actual upright bitmap.
            cropLeft =
                cropLeft.coerceIn(
                    0,
                    uprightBitmap.width - 1
                )

            cropTop =
                cropTop.coerceIn(
                    0,
                    uprightBitmap.height - 1
                )

            cropRight =
                cropRight.coerceIn(
                    cropLeft + 1,
                    uprightBitmap.width
                )

            cropBottom =
                cropBottom.coerceIn(
                    cropTop + 1,
                    uprightBitmap.height
                )

            val cropWidth =
                cropRight - cropLeft

            val cropHeight =
                cropBottom - cropTop

            Log.d(
                "ScanFragment",
                "Guide crop bitmap=${uprightBitmap.width}x${uprightBitmap.height}, " +
                        "preview=${viewFinder.width}x${viewFinder.height}, " +
                        "frame=(${viewFrame.left},${viewFrame.top}) " +
                        "${viewFrame.width}x${viewFrame.height}, " +
                        "crop=($cropLeft,$cropTop) ${cropWidth}x${cropHeight}"
            )

            val cropped =
                Bitmap.createBitmap(
                    uprightBitmap,
                    cropLeft,
                    cropTop,
                    cropWidth,
                    cropHeight
                )

            val outputFile =
                File(
                    imageFile.parentFile,
                    imageFile.nameWithoutExtension +
                            "_guide_crop.jpg"
                )

            FileOutputStream(
                outputFile
            ).use { output ->

                cropped.compress(
                    Bitmap.CompressFormat.JPEG,
                    95,
                    output
                )
            }

            if (
                cropped !== uprightBitmap &&
                !uprightBitmap.isRecycled
            ) {
                uprightBitmap.recycle()
            }

            if (!cropped.isRecycled) {
                cropped.recycle()
            }

            outputFile.absolutePath

        } catch (e: Exception) {

            Log.e(
                "ScanFragment",
                "Failed to crop image to guide frame",
                e
            )

            null
        }
    }

    /**
     * Reads JPEG EXIF orientation and returns an upright bitmap.
     * This prevents the previous behavior where the entire image
     * was simply forced/rotated into landscape.
     */
    private fun decodeBitmapUpright(
        imageFile: File
    ): Bitmap? {

        val bitmap =
            BitmapFactory.decodeFile(
                imageFile.absolutePath
            ) ?: return null

        return try {

            val exif =
                ExifInterface(
                    imageFile.absolutePath
                )

            val orientation =
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )

            val rotationDegrees =
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 ->
                        90f

                    ExifInterface.ORIENTATION_ROTATE_180 ->
                        180f

                    ExifInterface.ORIENTATION_ROTATE_270 ->
                        270f

                    else ->
                        0f
                }

            val flipHorizontal =
                orientation ==
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL ||
                        orientation ==
                        ExifInterface.ORIENTATION_TRANSPOSE ||
                        orientation ==
                        ExifInterface.ORIENTATION_TRANSVERSE

            val flipVertical =
                orientation ==
                        ExifInterface.ORIENTATION_FLIP_VERTICAL

            if (
                rotationDegrees == 0f &&
                !flipHorizontal &&
                !flipVertical
            ) {
                bitmap
            } else {

                val matrix =
                    Matrix().apply {

                        if (rotationDegrees != 0f) {
                            postRotate(
                                rotationDegrees
                            )
                        }

                        if (
                            flipHorizontal ||
                            flipVertical
                        ) {
                            postScale(
                                if (flipHorizontal) -1f else 1f,
                                if (flipVertical) -1f else 1f
                            )
                        }
                    }

                val transformed =
                    Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        bitmap.width,
                        bitmap.height,
                        matrix,
                        true
                    )

                if (
                    transformed !== bitmap &&
                    !bitmap.isRecycled
                ) {
                    bitmap.recycle()
                }

                transformed
            }

        } catch (e: Exception) {

            Log.w(
                "ScanFragment",
                "Could not read EXIF orientation; using decoded bitmap",
                e
            )

            bitmap
        }
    }

    private fun copyUriToCache(context: Context, contentUri: Uri): String? {
        return try {
            val cacheFile = File(context.cacheDir, "gallery_import_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(contentUri)?.use { inputStream ->
                cacheFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return null
            cacheFile.absolutePath
        } catch (e: Exception) {
            Log.e("ScanFragment", "Failed to copy URI to cache", e)
            null
        }
    }

    private fun navigateToPdfViewer(
        content: String,
        imagePath: String,
        ocrIssues: List<String> = emptyList()
    ) {
        val safeContext = context ?: return

        val now =
            System.currentTimeMillis()

        val titleTime =
            SimpleDateFormat(
                "MMM d, yyyy HH:mm",
                Locale.getDefault()
            ).format(now)

        val historyDate =
            SimpleDateFormat(
                "MMM d, yyyy",
                Locale.getDefault()
            ).format(now)

        val defaultTitle =
            "Scan $titleTime"

        // IMPORTANT:
        // A successful scan is added to HISTORY immediately.
        // It is NOT added to Notes here.
        lifecycleScope.launch(
            Dispatchers.IO
        ) {
            val dao =
                AppDatabase
                    .getDatabase(safeContext)
                    .appDao()

            val existingHistory =
                dao.getScanHistoryByPath(
                    imagePath
                )

            if (existingHistory == null) {
                dao.insertScanHistory(
                    ScanHistory(
                        title = defaultTitle,
                        imagePath = imagePath,
                        timestamp = now,
                        date = historyDate
                    )
                )
            }

            withContext(
                Dispatchers.Main
            ) {
                if (!isAdded) {
                    return@withContext
                }

                val intent =
                    Intent(
                        safeContext,
                        PdfViewerActivity::class.java
                    ).apply {

                        // Still UNSAVED as a Note.
                        putExtra(
                            "NOTE_ID",
                            -1
                        )

                        putExtra(
                            "TITLE",
                            defaultTitle
                        )

                        putExtra(
                            "CONTENT",
                            content
                        )

                        putExtra(
                            "IMAGE_PATH",
                            imagePath
                        )

                        putExtra(
                            "OCR_REVIEW_COUNT",
                            ocrIssues.size
                        )

                        putStringArrayListExtra(
                            "OCR_REVIEW_LINES",
                            ArrayList(ocrIssues)
                        )
                    }

                startActivity(
                    intent
                )
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            showAnalyzingOverlay()
        } else {
            hideAnalyzingOverlay()
        }
    }

    private fun showAnalyzingOverlay() {
        if (!isAdded || view == null) return

        progressBar.visibility = View.GONE

        val overlay = analyzingOverlay ?: return

        if (overlay.visibility != View.VISIBLE) {
            analyzingStartedAt = SystemClock.elapsedRealtime()
            analysisSequenceStarted = false
            resetAnalysisSteps()

            overlay.alpha = 0f
            overlay.visibility = View.VISIBLE
            overlay.bringToFront()
            overlay.animate()
                .alpha(1f)
                .setDuration(180L)
                .start()

            applyAnalyzingBlur(true)
        }

        if (!analysisSequenceStarted) {
            analysisSequenceStarted = true
            showActualAnalysisState(
                AnalysisStage.PREPARING_IMAGE
            )
        }
    }

    private fun hideAnalyzingOverlay() {
        if (!isAdded || view == null) return

        progressBar.visibility = View.GONE
        analysisSequenceStarted = false

        analyzingOverlay?.apply {
            animate().cancel()
            visibility = View.GONE
            alpha = 1f
        }

        applyAnalyzingBlur(false)
    }

    private fun finishAnalyzingAndNavigate(
        content: String,
        imagePath: String,
        ocrIssues: List<String> = emptyList()
    ) {
        view?.post {
            if (!isAdded) return@post

            showActualAnalysisState(
                AnalysisStage.COMPLETE
            )

            hideAnalyzingOverlay()

            navigateToPdfViewer(
                content = content,
                imagePath = imagePath,
                ocrIssues = ocrIssues
            )
        }
    }

    private enum class AnalysisStage {
        PREPARING_IMAGE,
        DETECTING_TEXT,
        CHECKING_RECOGNITION,
        STRUCTURING_NOTES,
        COMPLETE
    }

    private fun showActualAnalysisState(
        stage: AnalysisStage
    ) {
        val root =
            view ?: return

        root.findViewById<TextView>(
            R.id.tvStepEnhance
        )?.text =
            "Preparing Image"

        root.findViewById<TextView>(
            R.id.tvStepText
        )?.text =
            "Detecting Text"

        root.findViewById<TextView>(
            R.id.tvStepElements
        )?.text =
            "Checking Recognition"

        root.findViewById<TextView>(
            R.id.tvStepStructure
        )?.text =
            "Structuring Notes"

        resetAnalysisSteps()

        when (stage) {
            AnalysisStage.PREPARING_IMAGE -> {
                setAnalysisStep(
                    R.id.tvStepEnhance,
                    R.id.iconEnhance,
                    AnalysisStepState.ACTIVE
                )
            }

            AnalysisStage.DETECTING_TEXT -> {
                setAnalysisStep(
                    R.id.tvStepEnhance,
                    R.id.iconEnhance,
                    AnalysisStepState.DONE
                )

                setAnalysisStep(
                    R.id.tvStepText,
                    R.id.iconText,
                    AnalysisStepState.ACTIVE
                )
            }

            AnalysisStage.CHECKING_RECOGNITION -> {
                setAnalysisStep(
                    R.id.tvStepEnhance,
                    R.id.iconEnhance,
                    AnalysisStepState.DONE
                )

                setAnalysisStep(
                    R.id.tvStepText,
                    R.id.iconText,
                    AnalysisStepState.DONE
                )

                setAnalysisStep(
                    R.id.tvStepElements,
                    R.id.iconElements,
                    AnalysisStepState.ACTIVE
                )
            }

            AnalysisStage.STRUCTURING_NOTES -> {
                setAnalysisStep(
                    R.id.tvStepEnhance,
                    R.id.iconEnhance,
                    AnalysisStepState.DONE
                )

                setAnalysisStep(
                    R.id.tvStepText,
                    R.id.iconText,
                    AnalysisStepState.DONE
                )

                setAnalysisStep(
                    R.id.tvStepElements,
                    R.id.iconElements,
                    AnalysisStepState.DONE
                )

                setAnalysisStep(
                    R.id.tvStepStructure,
                    R.id.iconStructure,
                    AnalysisStepState.ACTIVE
                )
            }

            AnalysisStage.COMPLETE -> {
                completeAllAnalysisSteps()
            }
        }
    }

    private enum class AnalysisStepState {
        PENDING,
        ACTIVE,
        DONE
    }

    private fun resetAnalysisSteps() {
        setAnalysisStep(
            R.id.tvStepEnhance,
            R.id.iconEnhance,
            AnalysisStepState.PENDING,
            "1"
        )
        setAnalysisStep(
            R.id.tvStepText,
            R.id.iconText,
            AnalysisStepState.PENDING,
            "2"
        )
        setAnalysisStep(
            R.id.tvStepElements,
            R.id.iconElements,
            AnalysisStepState.PENDING,
            "3"
        )
        setAnalysisStep(
            R.id.tvStepStructure,
            R.id.iconStructure,
            AnalysisStepState.PENDING,
            "4"
        )
    }

    private fun completeAllAnalysisSteps() {
        setAnalysisStep(
            R.id.tvStepEnhance,
            R.id.iconEnhance,
            AnalysisStepState.DONE
        )
        setAnalysisStep(
            R.id.tvStepText,
            R.id.iconText,
            AnalysisStepState.DONE
        )
        setAnalysisStep(
            R.id.tvStepElements,
            R.id.iconElements,
            AnalysisStepState.DONE
        )
        setAnalysisStep(
            R.id.tvStepStructure,
            R.id.iconStructure,
            AnalysisStepState.DONE
        )
    }

    private fun setAnalysisStep(
        textId: Int,
        iconId: Int,
        state: AnalysisStepState,
        pendingNumber: String = ""
    ) {
        val root = view ?: return

        val label =
            root.findViewById<TextView>(textId)

        val icon =
            root.findViewById<TextView>(iconId)

        when (state) {
            AnalysisStepState.PENDING -> {
                label?.setTextColor(
                    "#E0E0E0".toColorInt()
                )

                icon?.apply {
                    text = pendingNumber
                    setTextColor(
                        "#8A8A92".toColorInt()
                    )
                    setBackgroundResource(
                        R.drawable.bg_analysis_pending
                    )
                }
            }

            AnalysisStepState.ACTIVE -> {
                label?.setTextColor(
                    "#FFFFFF".toColorInt()
                )

                icon?.apply {
                    text = "•"
                    setTextColor(
                        "#FFFFFF".toColorInt()
                    )
                    setBackgroundResource(
                        R.drawable.bg_analysis_active
                    )
                }
            }

            AnalysisStepState.DONE -> {
                label?.setTextColor(
                    "#FFFFFF".toColorInt()
                )

                icon?.apply {
                    text = "✓"
                    setTextColor(
                        "#FFFFFF".toColorInt()
                    )
                    setBackgroundResource(
                        R.drawable.bg_analysis_active
                    )
                }
            }
        }
    }

    private fun applyAnalyzingBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }

        val renderEffect =
            if (enabled) {
                RenderEffect.createBlurEffect(
                    24f,
                    24f,
                    Shader.TileMode.CLAMP
                )
            } else {
                null
            }

        viewFinder.setRenderEffect(renderEffect)
        controlPanelView?.setRenderEffect(renderEffect)
        backButtonContainerView?.setRenderEffect(renderEffect)
        flashControlView?.setRenderEffect(renderEffect)
    }

    override fun onDestroyView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ::viewFinder.isInitialized) {
            viewFinder.setRenderEffect(null)
            controlPanelView?.setRenderEffect(null)
            backButtonContainerView?.setRenderEffect(null)
            flashControlView?.setRenderEffect(null)
        }

        analyzingOverlay = null
        controlPanelView = null
        backButtonContainerView = null
        flashControlView = null

        super.onDestroyView()

        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
    }
}