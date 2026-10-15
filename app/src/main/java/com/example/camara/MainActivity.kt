package com.example.camara

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.hardware.camera2.*
import android.hardware.camera2.params.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val tag = "BM_CAMERA"
    private lateinit var textureView: TextureView
    private lateinit var topBar: BmTopBar
    private lateinit var bottomBar: BmBottomBar
    private lateinit var centerDial: BmCenterDial
    private lateinit var overlayView: BmOverlayView

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    // Physical Camera IDs for Vivo X300 / MediaTek Dimensity 9500
    // "0" = logical (groups all), "2" = main 1x (6.25mm), "3" = tele (13.3mm), "4" = UW
    private var selectedPhysicalId: String = "2"
    private var isLogical0: Boolean = false

    // Manual controls state
    private var currentIso: Int = 800
    private var currentShutterNs: Long = 20000000L // 1/50s
    private var currentWbTemp: Int = 5600
    private var currentFps: Int = 24

    private val executor = Executors.newSingleThreadExecutor()
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        // Bind views from XML layout
        textureView = findViewById(R.id.textureView)
        overlayView = findViewById(R.id.bmOverlay)
        topBar = findViewById(R.id.bmTopBar)
        centerDial = findViewById(R.id.bmCenterDial)
        bottomBar = findViewById(R.id.bmBottomBar)

        // Configure TextureView
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                s.setDefaultBufferSize(1920, 1080)
                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }

        // Overlay defaults
        overlayView.zebraEnabled = false
        overlayView.falseColorEnabled = false

        // Top bar -> center dial
        topBar.onSelectorTap = { key ->
            centerDial.show(key)
        }

        // Center dial -> manual settings
        centerDial.onValue = { mode, raw, display ->
            onDialValueChanged(mode, raw, display)
        }

        // Bottom bar -> recording
        bottomBar.onRecordLongPress = {
            toggleRecording()
        }

        updateFreeSpace()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (textureView.isAvailable) openCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun openCamera() {
        try {
            val targetId = if (isLogical0) "0" else selectedPhysicalId
            cameraManager?.openCamera(targetId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    cameraDevice = c
                    startPreview()
                }
                override fun onDisconnected(c: CameraDevice) {
                    c.close()
                    cameraDevice = null
                }
                override fun onError(c: CameraDevice, e: Int) {
                    c.close()
                    cameraDevice = null
                    Log.e(tag, "Camera Error: $e")
                }
            }, null)
        } catch (e: Exception) {
            Log.e(tag, "Open Exception: ${e.message}")
        }
    }

    private fun startPreview() {
        val surface = Surface(textureView.surfaceTexture ?: return)
        val outputConfig = OutputConfiguration(surface).apply {
            if (!isLogical0) setPhysicalCameraId(selectedPhysicalId)
        }
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(outputConfig),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    captureSession = s
                    try {
                        val requestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                            set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterNs)
                        }
                        previewRequestBuilder = requestBuilder
                        captureSession?.setRepeatingRequest(requestBuilder.build(), null, null)
                    } catch (e: Exception) {
                        Log.e(tag, "Preview Start Exception: ${e.message}")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(tag, "Camera Configuration Failed")
                }
            }
        )
        cameraDevice?.createCaptureSession(sessionConfig)
    }

    private fun applyManualSettings() {
        val b = previewRequestBuilder ?: return
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterNs)
        try {
            captureSession?.setRepeatingRequest(b.build(), null, null)
        } catch (e: Exception) {
            Log.e(tag, "Apply Settings Exception: ${e.message}")
        }
    }

    private fun onDialValueChanged(mode: String, raw: Int, display: String) {
        when (mode) {
            "ISO" -> {
                currentIso = 50 + (raw * 6350 / 100)
                topBar.setIso("ISO $currentIso")
                applyManualSettings()
            }
            "SHTR" -> {
                currentShutterNs = 20000000L * 100 / (raw + 1)
                val frac = 1000000000L / currentShutterNs
                topBar.setShtr("1/$frac")
                applyManualSettings()
            }
            "WB" -> {
                currentWbTemp = 3000 + (raw * 7000 / 100)
                topBar.setWb("${currentWbTemp}K")
            }
            "FPS" -> {
                currentFps = if (raw < 50) 24 else 30
                topBar.setFps("$currentFps")
            }
            "LENS" -> {
                val lenses = listOf("2", "3", "4", "0")
                val labels = listOf("1x", "TELE", "UW", "LOG")
                val idx = (raw * lenses.size / 100).coerceIn(0, lenses.size - 1)
                selectedPhysicalId = lenses[idx]
                isLogical0 = (selectedPhysicalId == "0")
                topBar.setLens(labels[idx])

                cameraDevice?.close()
                cameraDevice = null
                captureSession?.close()
                captureSession = null
                openCamera()
            }
        }
    }

    private fun updateFreeSpace() {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val freeGb = stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024 * 1024)
        bottomBar.setMedia("MEDIA: ${freeGb}GB FREE", freeGb > 5)
    }

    private fun toggleRecording() {
        isRecording = !isRecording
        bottomBar.setRecording(isRecording)
        Log.i(tag, "Recording state: $isRecording")
    }

    override fun onResume() {
        super.onResume()
        if (textureView.isAvailable && cameraDevice == null) openCamera()
    }

    override fun onPause() {
        super.onPause()
        cameraDevice?.close()
        cameraDevice = null
        captureSession?.close()
        captureSession = null
    }
}