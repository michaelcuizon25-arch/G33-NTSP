package com.example.note2snap.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.example.note2snap.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanFragment : Fragment() {

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var isFlashOn = false

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var viewFinder: PreviewView
    private lateinit var progressBar: ProgressBar
    private lateinit var analyzingOverlay: LinearLayout

    private lateinit var viewFrame: View
    private lateinit var backButtonContainer: View
    private lateinit var flashControl: View
    private lateinit var tvInstruction: View
    private lateinit var controlPanel: View

    private lateinit var iconEnhance: TextView
    private lateinit var iconText: TextView
    private lateinit var iconElements: TextView
    private lateinit var iconStructure: TextView

    private lateinit var tvStepEnhance: TextView
    private lateinit var tvStepText: TextView
    private lateinit var tvStepElements: TextView
    private lateinit var tvStepStructure: TextView

    private val analysisHandler = Handler(Looper.getMainLooper())
    private var analysisStartTime = 0L
    private val minimumAnalysisTime = 2400L

    private val selectImageLauncher =
        registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let { processImageUri(it) }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                startCamera()
            } else {
                context?.let {
                    Toast.makeText(
                        it,
                        "Camera permission is required to scan documents.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(
            R.layout.fragment_scan,
            container,
            false
        )
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        viewFinder = view.findViewById(R.id.viewFinder)
        progressBar = view.findViewById(R.id.progressBarScan)
        analyzingOverlay = view.findViewById(R.id.analyzingOverlay)

        viewFrame = view.findViewById(R.id.viewFrame)
        backButtonContainer = view.findViewById(R.id.backButtonContainer)
        flashControl = view.findViewById(R.id.flashControl)
        tvInstruction = view.findViewById(R.id.tvInstruction)
        controlPanel = view.findViewById(R.id.controlPanel)

        iconEnhance = view.findViewById(R.id.iconEnhance)
        iconText = view.findViewById(R.id.iconText)
        iconElements = view.findViewById(R.id.iconElements)
        iconStructure = view.findViewById(R.id.iconStructure)

        tvStepEnhance = view.findViewById(R.id.tvStepEnhance)
        tvStepText = view.findViewById(R.id.tvStepText)
        tvStepElements = view.findViewById(R.id.tvStepElements)
        tvStepStructure = view.findViewById(R.id.tvStepStructure)

        val btnCapture = view.findViewById<View>(R.id.btnCapture)
        val btnGallery = view.findViewById<View>(R.id.btnGallery)
        val btnFlash = view.findViewById<ImageButton>(R.id.btnFlash)
        val tvFlashState = view.findViewById<TextView>(R.id.tvFlashState)
        val tvCancel = view.findViewById<TextView>(R.id.tvCancel)
        val btnBack = view.findViewById<View>(R.id.btnBack)

        cameraExecutor = Executors.newSingleThreadExecutor()

        resetAnalysisSteps()
        checkCameraPermissionAndStart()

        btnCapture.setOnClickListener {
            takePhoto()
        }

        btnGallery.setOnClickListener {
            selectImageLauncher.launch("image/*")
        }

        btnFlash.setOnClickListener {
            val currentCamera = camera

            if (currentCamera == null) {
                Toast.makeText(
                    requireContext(),
                    "Camera is not ready yet",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (!currentCamera.cameraInfo.hasFlashUnit()) {
                Toast.makeText(
                    requireContext(),
                    "Flash is not available on this device",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            isFlashOn = !isFlashOn
            currentCamera.cameraControl.enableTorch(isFlashOn)
            tvFlashState.text = if (isFlashOn) "On" else "Off"
        }

        tvCancel.setOnClickListener {
            goBackFromScan()
        }

        btnBack.setOnClickListener {
            goBackFromScan()
        }
    }

    private fun setStepPending(
        icon: TextView,
        label: TextView,
        number: String
    ) {
        icon.text = number
        icon.setBackgroundResource(R.drawable.bg_analysis_pending)
        icon.setTextColor(Color.parseColor("#8A8A92"))
        label.setTextColor(Color.parseColor("#74747C"))
        label.setTypeface(null, Typeface.NORMAL)
    }

    private fun setStepActive(
        icon: TextView,
        label: TextView,
        number: String
    ) {
        icon.text = number
        icon.setBackgroundResource(R.drawable.bg_analysis_active)
        icon.setTextColor(Color.WHITE)
        label.setTextColor(Color.WHITE)
        label.setTypeface(null, Typeface.BOLD)
    }

    private fun setStepDone(
        icon: TextView,
        label: TextView
    ) {
        icon.text = "✓"
        icon.setBackgroundResource(R.drawable.bg_analysis_active)
        icon.setTextColor(Color.WHITE)
        label.setTextColor(Color.WHITE)
        label.setTypeface(null, Typeface.NORMAL)
    }

    private fun resetAnalysisSteps() {
        if (!::iconEnhance.isInitialized) return

        setStepPending(iconEnhance, tvStepEnhance, "1")
        setStepPending(iconText, tvStepText, "2")
        setStepPending(iconElements, tvStepElements, "3")
        setStepPending(iconStructure, tvStepStructure, "4")
    }

    private fun startAnalysisAnimation() {
        analysisHandler.removeCallbacksAndMessages(null)
        resetAnalysisSteps()

        setStepActive(iconEnhance, tvStepEnhance, "1")

        analysisHandler.postDelayed({
            if (!isAdded) return@postDelayed

            setStepDone(iconEnhance, tvStepEnhance)
            setStepActive(iconText, tvStepText, "2")
        }, 600)

        analysisHandler.postDelayed({
            if (!isAdded) return@postDelayed

            setStepDone(iconEnhance, tvStepEnhance)
            setStepDone(iconText, tvStepText)
            setStepActive(iconElements, tvStepElements, "3")
        }, 1200)

        analysisHandler.postDelayed({
            if (!isAdded) return@postDelayed

            setStepDone(iconEnhance, tvStepEnhance)
            setStepDone(iconText, tvStepText)
            setStepDone(iconElements, tvStepElements)
            setStepActive(iconStructure, tvStepStructure, "4")
        }, 1800)
    }

    private fun completeAnalysisSteps() {
        setStepDone(iconEnhance, tvStepEnhance)
        setStepDone(iconText, tvStepText)
        setStepDone(iconElements, tvStepElements)
        setStepDone(iconStructure, tvStepStructure)
    }

    private fun hideScanControls() {
        viewFrame.visibility = View.INVISIBLE
        backButtonContainer.visibility = View.INVISIBLE
        flashControl.visibility = View.INVISIBLE
        tvInstruction.visibility = View.INVISIBLE
        controlPanel.visibility = View.INVISIBLE
    }

    private fun showScanControls() {
        viewFrame.visibility = View.VISIBLE
        backButtonContainer.visibility = View.VISIBLE
        flashControl.visibility = View.VISIBLE
        tvInstruction.visibility = View.VISIBLE
        controlPanel.visibility = View.VISIBLE
    }

    private fun applyCameraBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            viewFinder.setRenderEffect(
                RenderEffect.createBlurEffect(
                    60f,
                    60f,
                    Shader.TileMode.CLAMP
                )
            )
        }
    }

    private fun removeCameraBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            viewFinder.setRenderEffect(null)
        }
    }

    private fun checkCameraPermissionAndStart() {
        val safeContext = context ?: return

        if (
            ContextCompat.checkSelfPermission(
                safeContext,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
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
            if (
                !isAdded ||
                viewLifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED
            ) {
                return@addListener
            }

            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview =
                    Preview.Builder()
                        .build()
                        .also {
                            it.setSurfaceProvider(viewFinder.surfaceProvider)
                        }

                imageCapture =
                    ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                if (!cameraProvider.hasCamera(cameraSelector)) {
                    Log.w("ScanFragment", "No back camera found")
                    return@addListener
                }

                cameraProvider.unbindAll()

                camera =
                    cameraProvider.bindToLifecycle(
                        viewLifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )

                camera?.let {
                    setupCameraGestures(
                        safeContext,
                        viewFinder,
                        it
                    )
                }

            } catch (error: Exception) {
                Log.e(
                    "ScanFragment",
                    "Camera binding failed",
                    error
                )
            }

        }, ContextCompat.getMainExecutor(safeContext))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCameraGestures(
        context: Context,
        previewView: PreviewView,
        camera: Camera
    ) {
        val scaleGestureDetector =
            ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(
                        detector: ScaleGestureDetector
                    ): Boolean {
                        val currentZoomRatio =
                            camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f

                        camera.cameraControl.setZoomRatio(
                            currentZoomRatio * detector.scaleFactor
                        )

                        return true
                    }
                }
            )

        previewView.setOnTouchListener { touchedView, event ->
            scaleGestureDetector.onTouchEvent(event)

            if (
                event.action == MotionEvent.ACTION_UP &&
                !scaleGestureDetector.isInProgress
            ) {
                val point =
                    previewView.meteringPointFactory.createPoint(
                        event.x,
                        event.y
                    )

                val action =
                    FocusMeteringAction.Builder(point).build()

                camera.cameraControl.startFocusAndMetering(action)
                touchedView.performClick()
            }

            true
        }
    }

    private fun takePhoto() {
        val safeContext = context ?: return

        val capture =
            imageCapture ?: run {
                Toast.makeText(
                    safeContext,
                    "Camera not ready yet",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

        val cacheDir =
            safeContext.externalCacheDir ?: safeContext.cacheDir

        val photoFile =
            File(
                cacheDir,
                SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    Locale.getDefault()
                ).format(
                    System.currentTimeMillis()
                ) + ".jpg"
            )

        val outputOptions =
            ImageCapture.OutputFileOptions.Builder(photoFile).build()

        setLoading(true)

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(safeContext),
            object : ImageCapture.OnImageSavedCallback {

                override fun onError(
                    exc: ImageCaptureException
                ) {
                    if (!isAdded) return

                    setLoading(false)

                    Toast.makeText(
                        context,
                        "Failed to capture image",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onImageSaved(
                    output: ImageCapture.OutputFileResults
                ) {
                    if (!isAdded) return

                    processImageUri(
                        Uri.fromFile(photoFile),
                        photoFile.absolutePath
                    )
                }
            }
        )
    }

    private fun processImageUri(
        rawUri: Uri,
        rawFilePath: String? = null
    ) {
        val safeContext = context ?: return

        setLoading(true)

        try {
            val filePath =
                rawFilePath ?: if (rawUri.scheme == "content") {
                    copyUriToCache(safeContext, rawUri)
                } else {
                    rawUri.path
                }

            if (filePath == null) {
                setLoading(false)

                Toast.makeText(
                    safeContext,
                    "Failed to load image file",
                    Toast.LENGTH_SHORT
                ).show()

                return
            }

            val imageFile = File(filePath)

            val image =
                InputImage.fromFilePath(
                    safeContext,
                    Uri.fromFile(imageFile)
                )

            val recognizer =
                TextRecognition.getClient(
                    TextRecognizerOptions.DEFAULT_OPTIONS
                )

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!isAdded) return@addOnSuccessListener

                    val extractedText = visionText.text

                    val finalText =
                        if (extractedText.isBlank()) {
                            "[No text detected]"
                        } else {
                            extractedText
                        }

                    finishAnalysisAndNavigate(
                        finalText,
                        filePath
                    )
                }
                .addOnFailureListener {
                    if (!isAdded) return@addOnFailureListener

                    setLoading(false)

                    Toast.makeText(
                        context,
                        "OCR failed to read text",
                        Toast.LENGTH_SHORT
                    ).show()
                }

        } catch (error: Exception) {
            if (isAdded) {
                setLoading(false)
            }

            Log.e(
                "ScanFragment",
                "Error processing image",
                error
            )

            Toast.makeText(
                safeContext,
                "Error loading image",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun finishAnalysisAndNavigate(
        content: String,
        imagePath: String
    ) {
        val elapsed =
            SystemClock.elapsedRealtime() - analysisStartTime

        val remaining =
            (minimumAnalysisTime - elapsed).coerceAtLeast(0L)

        analysisHandler.postDelayed({
            if (!isAdded) return@postDelayed

            completeAnalysisSteps()

            analysisHandler.postDelayed({
                if (!isAdded) return@postDelayed

                setLoading(false)

                navigateToPdfViewer(
                    content,
                    imagePath
                )
            }, 350)

        }, remaining)
    }

    private fun copyUriToCache(
        context: Context,
        contentUri: Uri
    ): String? {
        return try {
            val cacheFile =
                File(
                    context.cacheDir,
                    "gallery_import_${System.currentTimeMillis()}.jpg"
                )

            context.contentResolver
                .openInputStream(contentUri)
                ?.use { inputStream ->
                    cacheFile.outputStream()
                        .use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                } ?: return null

            cacheFile.absolutePath

        } catch (error: Exception) {
            Log.e(
                "ScanFragment",
                "Failed to copy URI",
                error
            )

            null
        }
    }

    private fun navigateToPdfViewer(
        content: String,
        imagePath: String
    ) {
        val safeContext = context ?: return

        val timeStamp =
            SimpleDateFormat(
                "MMM d, yyyy HH:mm",
                Locale.getDefault()
            ).format(
                System.currentTimeMillis()
            )

        val defaultTitle = "Scan $timeStamp"

        val intent =
            Intent(
                safeContext,
                PdfViewerActivity::class.java
            ).apply {
                putExtra("NOTE_ID", -1)
                putExtra("TITLE", defaultTitle)
                putExtra("CONTENT", content)
                putExtra("IMAGE_PATH", imagePath)
            }

        startActivity(intent)
    }

    private fun goBackFromScan() {
        if (!isAdded) return

        val mainActivity = activity as? MainActivity

        if (mainActivity != null) {
            mainActivity.selectTab(R.id.nav_home)
        } else {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setLoading(
        isLoading: Boolean
    ) {
        if (::analyzingOverlay.isInitialized) {
            if (isLoading) {
                analysisStartTime = SystemClock.elapsedRealtime()

                hideScanControls()
                applyCameraBlur()
                startAnalysisAnimation()

                analyzingOverlay.visibility = View.VISIBLE
            } else {
                analyzingOverlay.visibility = View.GONE

                removeCameraBlur()
                showScanControls()
            }
        }

        if (::progressBar.isInitialized) {
            progressBar.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        analysisHandler.removeCallbacksAndMessages(null)

        removeCameraBlur()

        camera?.cameraControl?.enableTorch(false)
        camera = null
        isFlashOn = false

        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }

        super.onDestroyView()
    }
}
