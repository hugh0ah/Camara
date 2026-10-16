package com.example.camara

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.TextureView
import kotlin.math.sqrt

/**
 * Analizador de monitorización de CineCam.
 *
 * Toma fotogramas reales del visor ([TextureView]) a resolución reducida y
 * calcula sobre esos píxeles: histograma de luminancia, focus peaking por
 * gradiente Sobel, zebras sobre umbral de IRE y mapa de falso color. Si hay una
 * LUT cargada, la aplica antes de calcular las métricas.
 *
 * No hay bandas de prueba ni valores simulados: todo sale del frame real.
 */
class BmMonitorAnalyzer(
    private val textureView: TextureView,
    private val settings: BmSettings
) {

    /** Resultado listo para dibujar en el visor. */
    class Snapshot(
        val overlay: Bitmap,
        val histogram: IntArray,
        val redHistogram: IntArray,
        val greenHistogram: IntArray,
        val blueHistogram: IntArray,
        val clippedFraction: Float,
        val meanLuma: Double,
        val clippedHighlights: Int,
        val clippedShadows: Int
    )

    var onSnapshot: ((Snapshot) -> Unit)? = null
    var lut: BmLut3D? = null
    var lutEnabled: Boolean = false

    private var width = ANALYSIS_WIDTH
    private var height = ANALYSIS_HEIGHT
    private var pixels = IntArray(width * height)
    private var luma = IntArray(width * height)
    private var overlayPixels = IntArray(width * height)
    private val histogram = IntArray(256)
    private val redHistogram = IntArray(256)
    private val greenHistogram = IntArray(256)
    private val blueHistogram = IntArray(256)
    private val lutOut = IntArray(3)

    private var frameBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var overlayBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /**
     * Ajusta el tamaño de análisis a la relación de aspecto del stream real, de
     * modo que la superposición coincida píxel a píxel con lo que se ve en el
     * visor (apaisado 16:9, apaisado 4:3, etc.). El lado mayor se fija en 320.
     */
    fun configure(bufferWidth: Int, bufferHeight: Int) {
        if (bufferWidth <= 0 || bufferHeight <= 0) return
        val newWidth = ANALYSIS_WIDTH
        val newHeight = (ANALYSIS_WIDTH.toFloat() * bufferHeight / bufferWidth).toInt()
            .coerceIn(60, ANALYSIS_WIDTH * 2)
        if (newWidth == width && newHeight == height) return
        width = newWidth
        height = newHeight
        pixels = IntArray(width * height)
        luma = IntArray(width * height)
        overlayPixels = IntArray(width * height)
        frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        overlayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private val workerThread = HandlerThread("cinecam-monitor").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var running = false

    fun start() {
        if (running) return
        running = true
        main.post(capture)
    }

    fun stop() {
        running = false
        main.removeCallbacks(capture)
    }

    fun shutdown() {
        stop()
        workerThread.quitSafely()
    }

    private val capture = object : Runnable {
        override fun run() {
            if (!running) return
            if (textureView.isAvailable) {
                val frame = try {
                    textureView.getBitmap(frameBitmap)
                } catch (t: Throwable) {
                    null
                }
                if (frame != null) worker.post { analyze(frame) }
            }
            main.postDelayed(this, INTERVAL_MS)
        }
    }

    private fun analyze(frame: Bitmap) {
        try {
            frame.getPixels(pixels, 0, width, 0, 0, width, height)
        } catch (t: Throwable) {
            return
        }

        histogram.fill(0)
        redHistogram.fill(0)
        greenHistogram.fill(0)
        blueHistogram.fill(0)
        var lumaSum = 0L
        var highlights = 0
        var shadows = 0
        val useLut = lutEnabled && lut != null

        for (index in pixels.indices) {
            val color = pixels[index]
            var r = (color shr 16) and 0xFF
            var g = (color shr 8) and 0xFF
            var b = color and 0xFF

            if (useLut) {
                lut!!.apply(r, g, b, lutOut)
                r = lutOut[0]; g = lutOut[1]; b = lutOut[2]
                pixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            val y = (r * 54 + g * 183 + b * 19) shr 8   // luminancia aproximada (Rec.601)
            luma[index] = y
            histogram[y]++
            redHistogram[r]++
            greenHistogram[g]++
            blueHistogram[b]++
            lumaSum += y
            if (y >= 250) highlights++
            if (y <= 5) shadows++
        }

        val mean = lumaSum.toDouble() / pixels.size
        val falseColor = settings.falseColor
        val zebra = settings.zebra
        val peaking = settings.peaking
        val zebraThreshold = settings.zebraLowIre * 255 / 100

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val value = luma[index]
                var out = 0

                if (falseColor) {
                    out = falseColorFor(value)
                } else if (zebra && value >= zebraThreshold) {
                    // Franjas diagonales dinámicas sobre las zonas que pasan el umbral
                    if (((x + y) / 6) % 2 == 0) out = 0xCCFFFFFF.toInt()
                }

                if (peaking) {
                    val magnitude = sobelMagnitude(x, y)
                    if (magnitude > PEAK_STRONG) out = 0xE6FF3B30.toInt()
                    else if (magnitude > PEAK_THRESHOLD) out = 0xE600E676.toInt()
                }

                overlayPixels[index] = out
            }
        }

        overlayBitmap.setPixels(overlayPixels, 0, width, 0, 0, width, height)
        val snapshot = Snapshot(
            overlayBitmap.copy(Bitmap.Config.ARGB_8888, false),
            histogram.copyOf(),
            redHistogram.copyOf(),
            greenHistogram.copyOf(),
            blueHistogram.copyOf(),
            highlights.toFloat() / pixels.size,
            mean,
            highlights,
            shadows
        )
        main.post { onSnapshot?.invoke(snapshot) }
    }

    /** Gradiente Sobel sobre la luminancia del frame actual. */
    private fun sobelMagnitude(x: Int, y: Int): Int {
        if (x < 1 || y < 1 || x >= width - 1 || y >= height - 1) return 0
        val topLeft = luma[(y - 1) * width + (x - 1)]
        val top = luma[(y - 1) * width + x]
        val topRight = luma[(y - 1) * width + (x + 1)]
        val left = luma[y * width + (x - 1)]
        val right = luma[y * width + (x + 1)]
        val bottomLeft = luma[(y + 1) * width + (x - 1)]
        val bottom = luma[(y + 1) * width + x]
        val bottomRight = luma[(y + 1) * width + (x + 1)]

        val gx = (topRight + 2 * right + bottomRight) - (topLeft + 2 * left + bottomLeft)
        val gy = (bottomLeft + 2 * bottom + bottomRight) - (topLeft + 2 * top + topRight)
        return sqrt((gx * gx + gy * gy).toDouble()).toInt()
    }

    /** Mapa de falso color por bandas de luminancia reales. */
    private fun falseColorFor(lumaValue: Int): Int {
        val fraction = lumaValue / 255f
        val color = when {
            fraction < 0.02f -> 0xAA7A00FF.toInt()   // negro recortado
            fraction < 0.12f -> 0xAA0040FF.toInt()   // sombras
            fraction < 0.30f -> 0xAA00C8FF.toInt()   // subexpuesto
            fraction < 0.45f -> 0xAA00E676.toInt()   // gris medio correcto
            fraction < 0.62f -> 0xAAFFE600.toInt()   // piel / altas
            fraction < 0.85f -> 0xAAFF9100.toInt()   // casi recorte
            else -> 0xAAFF3B30.toInt()               // recorte
        }
        return color
    }

    private companion object {
        const val ANALYSIS_WIDTH = 320
        const val ANALYSIS_HEIGHT = 180
        const val INTERVAL_MS = 110L
        const val PEAK_THRESHOLD = 46
        const val PEAK_STRONG = 110
    }
}
