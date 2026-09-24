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
import android.widget.ProgressBar
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

        val btnCapture = view.findViewById<View>(R.id.btnCapture)
        val btnGallery = view.findViewById<View>(R.id.btnGallery)

        cameraExecutor = Executors.newSingleThreadExecutor()

        checkCameraPermissionAndStart()

        btnCapture?.setOnClickListener { takePhoto() }
        btnGallery?.setOnClickListener { selectImageLauncher.launch("image/*") }
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
            // Check if fragment is attached and lifecycle is active before binding camera
            if (!isAdded || viewLifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener

            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Ensure device has back camera available before binding
                if (!cameraProvider.hasCamera(cameraSelector)) {
                    Log.w("ScanFragment", "No back camera found on this device")
                    return@addListener
                }

                cameraProvider.unbindAll()

                val camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                setupCameraGestures(safeContext, viewFinder, camera)

            } catch (exc: Exception) {
                Log.e("ScanFragment", "Camera binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(safeContext))
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
            // Convert gallery content:// URIs to cache file paths safely
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

    /**
     * Copies a content:// Uri to a local file in cache and returns its absolute file path.
     */
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
            putExtra("IMAGE_PATH", imagePath) // Passes absolute path: "/data/user/0/.../cache/photo.jpg"
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