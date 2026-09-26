package com.example.note2snap.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var isFlashOn = false
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var viewFinder: PreviewView
    private lateinit var progressBar: ProgressBar

    // Gallery Picker Contract
    private val selectImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processImageUri(it) }
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
        progressBar = view.findViewById(R.id.progressBarScan)

        val backButtonContainer = view.findViewById<View>(R.id.backButtonContainer)
        val btnBack = view.findViewById<View>(R.id.btnBack)
        val flashControl = view.findViewById<View>(R.id.flashControl)
        val btnFlash = view.findViewById<ImageView>(R.id.btnFlash)
        val controlPanel = view.findViewById<View>(R.id.controlPanel)
        val btnCapture = view.findViewById<View>(R.id.btnCapture)
        val btnGallery = view.findViewById<View>(R.id.btnGallery)
        val tvCancel = view.findViewById<View>(R.id.tvCancel)

        // Elevate parent containers above Camera PreviewView in the Z-axis
        backButtonContainer?.bringToFront()
        flashControl?.bringToFront()
        controlPanel?.bringToFront()

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

        setLoading(true)

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
                    processImageUri(Uri.fromFile(photoFile), photoFile.absolutePath)
                }
            }
        )
    }

    private fun processImageUri(rawUri: Uri, rawFilePath: String? = null) {
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
            val image = InputImage.fromFilePath(safeContext, Uri.fromFile(imageFile))
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!isAdded) return@addOnSuccessListener
                    setLoading(false)
                    val extractedText = visionText.text

                    val finalText = if (extractedText.isBlank()) "[No text detected]" else extractedText
                    navigateToPdfViewer(finalText, filePath)
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

    private fun navigateToPdfViewer(content: String, imagePath: String) {
        val safeContext = context ?: return
        val timeStamp = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(System.currentTimeMillis())
        val defaultTitle = "Scan $timeStamp"

        val intent = Intent(safeContext, PdfViewerActivity::class.java).apply {
            putExtra("NOTE_ID", -1)
            putExtra("TITLE", defaultTitle)
            putExtra("CONTENT", content)
            putExtra("IMAGE_PATH", imagePath)
        }
        startActivity(intent)
    }

    private fun setLoading(isLoading: Boolean) {
        if (::progressBar.isInitialized) {
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
    }
}