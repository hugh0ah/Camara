import os

kt_content = """package com.example.camara

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceTexture
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.OutputConfiguration
import android.hardware.camera2.SessionConfiguration
import android.hardware.camera2.params.Range
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val tag = "VIVO_CAM_SCAN"
    private lateinit var textureView: TextureView
    private lateinit var controlsLayout: FrameLayout
    private lateinit var isoBar: SeekBar
    private lateinit var shutterBar: SeekBar
    private lateinit var isoLabel: TextView
    private lateinit var shutterLabel: TextView
    private lateinit var lens2Button: Button
    private lateinit var lens3Button: Button
    private lateinit var lens4Button: Button
    private lateinit var logic0Button: Button

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var selectedPhysicalId: String? = null
    private var isLogical0 = false

    private val isoMin = 50
    private val isoMax = 6400
    private val shutterMinNs: Long = 1000000L
    private val shutterMaxNs: Long = 41666666L

    private var currentIso = 100
    private var currentShutterNs: Long = 1000000L

    private val executor: Executor = Executors.newSingleThreadExecutor()

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCameraWithCurrentSelection()
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            try {
                previewRequestBuilder = session.device.createCaptureRequest(
                    CameraDevice.TEMPLATE_RECORD
                )
                previewRequestBuilder?.let { builder ->
                    builder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_OFF
                    )
                    builder.set(
                        CaptureRequest.SENSOR_SENSITIVITY,
                        currentIso
                    )
                    builder.set(
                        CaptureRequest.SENSOR_EXPOSURE_TIME,
                        currentShutterNs
                    )
                    val fpsRange = if (isLogical0) {
                        Range(60, 60)
                    } else {
                        Range(24, 24)
                    }
                    builder.set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        fpsRange
                    )
                    builder.set(
                        CaptureRequest.EDGE_MODE,
                        CaptureRequest.EDGE_MODE_OFF
                    )
                    builder.set(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF
                    )
                    builder.set(
                        CaptureRequest.CONTROL_AWB_MODE,
                        CaptureRequest.CONTROL_AWB_MODE_AUTO
                    )
                }
                previewRequestBuilder?.build()?.let { request ->
                    session.setRepeatingRequest(request, null, null)
                }
            } catch (e: Exception) {
                Log.e(tag, "onConfigured error", e)
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(tag, "Session configuration failed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraManager =-

    }
}
"""

# Write MainActivity.kt
target = "C:/Users/halva/AndroidStudioProjects/Camara/app/src/main/java/com/example/camara/MainActivity.kt"
with open(target, "w", encoding="utf-8") as f:
    f.write(kt_content)
print("MainActivity.kt written with", kt_content.count(chr(10)), "lines")

# Write write_main.py script
script_path = "C:/Users/halva/AndroidStudioProjects/Camara/.artifacts/4828c743-3dc7-443a-8034-f180ccb3bcca/scratch/write_main.py"
script_content = 'import os\n\ncontent = """' + kt_content + '"""\n\ntarget = "C:/Users/halva/AndroidStudioProjects/Camara/app/src/main/java/com/example/camara/MainActivity.kt"\nwith open(target, "w", encoding="utf-8") as f:\n    f.write(content)\nprint("MainActivity.kt written with", content.count(chr(10)), "lines")\n'

with open(script_path, "w", encoding="utf-8") as f:
    f.write(script_content)
print("Script written with", script_content.count(chr(10)), "lines")