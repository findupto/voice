package com.findupto.voicepos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@androidx.camera.core.ExperimentalGetImage
class LiveQrScannerActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private val handled = AtomicBoolean(false)
    private lateinit var previewView: PreviewView
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Camera permission is required to scan QR codes live.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        previewView = PreviewView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        val root = FrameLayout(this)
        root.addView(previewView)
        val instructions = TextView(this).apply {
            text = "LIVE QR SCANNER\nPlace the discount QR inside the camera view"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(28, 48, 28, 28)
            setBackgroundColor(0x99000000.toInt())
        }
        root.addView(instructions, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP))
        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionRequest.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing || isDestroyed) return@addListener
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val scanner = BarcodeScanning.getClient()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy ->
                val mediaImage = proxy.image
                if (mediaImage == null || handled.get()) {
                    proxy.close()
                } else {
                    val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            val code = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE && it.rawValue?.startsWith("VOICEPOS-DISCOUNT|") == true }?.rawValue
                            if (code != null && handled.compareAndSet(false, true)) {
                                runOnUiThread {
                                    setResult(RESULT_OK, intent.putExtra(EXTRA_QR_CODE, code))
                                    finish()
                                }
                            }
                        }
                        .addOnFailureListener {
                            // Keep the live preview running; the next frame can still be scanned.
                        }
                        .addOnCompleteListener { proxy.close() }
                }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not start camera: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QR_CODE = "live_scanned_discount_qr"
    }
}
