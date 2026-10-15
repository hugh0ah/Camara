import os

# Read the base kt content
with open('C:/Users/halva/AndroidStudioProjects/Camara/.artifacts/4828c743-3dc7-443a-8034-f180ccb3bcca/scratch/kt_complete.txt') as f:
    base = f.read()

# Find the position of 'cameraManager =-'
idx = base.rfind('cameraManager =-')
if idx < 0:
    print('ERROR: could not find cameraManager =-')
    exit(1)

# Build the complete implementation
tail = '''cameraManager =-

    }

    private fun openCameraWithCurrentSelection() {
        val manager = cameraManager
        try {
            manager.openCamera("0", cameraStateCallback, null)
        } catch (e: SecurityException) {
            Log.e(tag, "Camera open security error", e)
        } catch (e: Exception) {
            Log.e(tag, "Camera open error", e)
        }
    }

    private fun startPreviewSession() {
        val camera = cameraDevice ?: return
        if (!textureView.isAvailable) return

        try {
            val surfaceTexture = textureView.surfaceTexture ?: return
            val surface = Surface(surfaceTexture)
            val outputConfig = OutputConfiguration(surface)
            val physicalId = selectedPhysicalId
            if (physicalId == "2" || physicalId == "3" || physicalId == "4") {
                outputConfig.setPhysicalCameraId(physicalId)
            } else if (physicalId == "0") {
                outputConfig.setPhysicalCameraId(null)
            }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfig),
                executor,
                sessionCallback
            )
            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(tag, "Preview session error", e)
        }
    }

    private fun selectPhysicalCamera(physicalId: String?) {
        closeCamera()
        selectedPhysicalId = physicalId
        isLogical0 = (physicalId == "0")
        openCameraWithCurrentSelection()
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun updateIsoBar() {
        isoBar.max = isoMax - isoMin
        isoBar.progress = currentIso - isoMin
        isoLabel.text = "ISO: $currentIso"
    }

    private fun updateShutterBar() {
        val progress = ((currentShutterNs - shutterMinNs).toDouble() / (shutterMaxNs - shutterMinNs) * 100).toInt()
        shutterBar.progress = progress.coerceIn(0, 100)
        val shutterStr = "1/${(1000000000L / currentShutterNs)}s"
        shutterLabel.text = "Shutter: $shutterStr"
    }

    private fun applyManualSettings() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterNs)
            session.setRepeatingRequest(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e(tag, "Apply manual settings error", e)
        }
    }

    private fun adjustTextureViewAspectRatio(width: Int, height: Int) {
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val previewRect = RectF(0f, 0f, 1920f, 1080f)
        matrix.setRectToRect(viewRect, previewRect, Matrix.Scale.CENTER)
        textureView.setTransform(matrix)
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
    }
}
'''

new_content = base[:idx] + tail

# Write MainActivity.kt
target = 'C:/Users/halva/AndroidStudioProjects/Camara/app/src/main/java/com/example/camara/MainActivity.kt'
with open(target, 'w', encoding='utf-8') as f:
    f.write(new_content)
print('MainActivity.kt written with', new_content.count(chr(10)), 'lines')

# Write write_main.py
script_path = 'C:/Users/halva/AndroidStudioProjects/Camara/.artifacts/4828c743-3dc7-443a-8034-f180ccb3bcca/scratch/write_main.py'
script = 'import os\n\ncontent = """' + new_content + '"""\n\ntarget = "C:/Users/halva/AndroidStudioProjects/Camara/app/src/main/java/com/example/camara/MainActivity.kt"\nwith open(target, "w", encoding="utf-8") as f:\n    f.write(content)\nprint("MainActivity.kt written with", content.count(chr(10)), "lines")\n'
with open(script_path, 'w', encoding='utf-8') as f:
    f.write(script)
print('Script written with', script.count(chr(10)), 'lines')