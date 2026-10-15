content = """package com.example.camara

import android.annotation.TargetApi
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceTexture
import java.util.concurrent.CopyOnWriteArrayList

object SensorScanner {

    private const val TAG = "VIVO_CAM_SCAN"

    data class SensorInfo(
        val logicalId: String,
        val physicalId: String,
        val isLogical: Boolean,
        val lensFacing: Int?,
        val focalLengthMm: Double?,
        val isoMin: Int?,
        val isoMax: Int?,
        val exposureMinNs: Long?,
        val exposureMaxNs: Long?,
        val minFocusDistance: Float?,
        val supportedFpsRanges: List<Pair<Int, Int>>,
        val surfaceTextureResolutions: List<Size>,
        val physicalCameraIds: List<String>,
    )

    @TargetApi(Build.VERSION_CODES.P)
    fun scanCameras(context: Context): List<SensorInfo> {
        val manager = getCameraManager(context)
        val results = CopyOnWriteArrayList<SensorInfo>()

        val cameraIds = try {
            manager.cameraIdList
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al obtener cameraIdList: " + e.message)
            return results
        }

        Log.d(TAG, "=== INICIO ESCANEO HARDWARE CAMARA ===")
        Log.d(TAG, "IDs logicos detectados: " + cameraIds.toList())

        for (logicalId in cameraIds) {
            val logicalSensor = scanLogicalSensor(manager, logicalId, emptyList())
            if (logicalSensor != null) {
                results.add(logicalSensor)
            }

            val physicalIds = getPhysicalCameraIds(manager, logicalId)
            for (physicalId in physicalIds) {
                val physicalSensor = scanPhysicalSensor(manager, logicalId, physicalId)
                if (physicalSensor != null) {
                    results.add(physicalSensor)
                }
            }
        }

        Log.d(TAG, "Sensores traseros detectados: " + results.size)
        results.forEach { dumpSensorInfo(it) }
        Log.d(TAG, "=== FIN ESCANEO HARDWARE CAMARA ===")

        return results
    }

    private fun getCameraManager(context: Context): CameraManager {
        return context
            .getDeclaredMethod("getCameraManager")
            .let { /* fallback */ }
    }
}
"""

with open("C:/Users/halva/AndroidStudioProjects/Camara/app/src/main/java/com/example/camara/SensorScanner.kt", "w", encoding="utf-8") as f:
    f.write(content)
print("OK")