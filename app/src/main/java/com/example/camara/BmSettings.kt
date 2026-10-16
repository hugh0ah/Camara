package com.example.camara

import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Estado vivo de captura de CineCam.
 *
 * Aquí viven los parámetros que se escriben en el [android.hardware.camera2.CaptureRequest]
 * y las conversiones necesarias (Kelvin -> ganancias RGGB, etiquetas de obturador, etc.).
 * Todo lo que cambia aquí se aplica al sensor mediante `setRepeatingRequest`.
 */
class BmSettings {

    // --- Exposición ---
    var iso: Int = 800
    var isoMin: Int = 50
    var isoMax: Int = 19200
    var shutterNs: Long = 20_000_000L        // 1/50 s
    var shutterMinNs: Long = 125_000L        // 1/8000 s (suelo de la app)
    var shutterMaxNs: Long = 41_666_666L     // 1/24 s (techo de la app)
    var fps: Int = 24

    // --- Color ---
    var kelvin: Int = 5600
    var tint: Int = 0                        // -100 (verde) .. +100 (magenta)

    // --- Óptica ---
    var lensKey: String = LENS_MAIN
    var autoFocus: Boolean = true            // AF continuo (por defecto) o MF manual
    var focusDiopters: Float = 0f            // 0 = infinito
    var focusMinDiopters: Float = 0f         // LENS_INFO_MINIMUM_FOCUS_DISTANCE
    var apertureLabel: String = "—"

    // --- Formato de grabación: todas las calidades nativas del sensor ---
    /**
     * Calidades ofrecidas en el selector del badge de formato. Cada una fija el
     * tamaño real de grabación, su caudal y el tamaño del stream de previsualización
     * (que define la relación de aspecto del visor).
     */
    enum class Quality(
        val main: String,
        val sub: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val previewWidth: Int,
        val previewHeight: Int
    ) {
        OG43("4K", "OG 4:3", 4096, 3072, 120_000_000, 1440, 1080),
        DCI("4K", "DCI", 4096, 2304, 120_000_000, 1920, 1080),
        UHD("4K", "UHD", 3840, 2160, 100_000_000, 1920, 1080),
        K38("3.8K", "2:1", 3840, 1920, 90_000_000, 1920, 960),
        HD("HD", "1080p", 1920, 1080, 50_000_000, 1920, 1080)
    }

    var quality: Quality = Quality.OG43

    /** Códec y caudal, ajustables desde Preferencias. */
    var useHevc: Boolean = true
    var bitrateOverrideMbps: Int = 0         // 0 = el caudal propio del formato

    // --- Modos automáticos reales del sensor (se activan desde la barra inferior) ---
    var autoExposure: Boolean = false        // AE del dispositivo: ISO y obturación solos
    var autoWb: Boolean = false              // AWB del dispositivo: balance solos
    var autoIris: Boolean = false            // apertura: informativo, la lente es fija

    val focusLabel: String
        get() = if (autoFocus) "AF" else if (focusDiopters <= 0.001f) "MF ∞" else "MF %.2fm".format(1f / focusDiopters)

    val formatLabel: String
        get() = "${quality.main} | ${quality.sub}"

    /** Indicador de la cabecera: resolución sobre el recuadro real del sensor. */
    val formatBadge: Pair<String, String>
        get() = quality.main to quality.sub

    /** Caudal objetivo en bits por segundo del formato elegido. */
    val targetBitrate: Int
        get() = if (bitrateOverrideMbps > 0) bitrateOverrideMbps * 1_000_000 else quality.bitrate

    /** Relación de aspecto del cuadro grabado (ancho / alto). */
    val frameAspect: Float
        get() = quality.width.toFloat() / quality.height.toFloat()

    // --- Monitores ---
    var logEnabled: Boolean = false
    var peaking: Boolean = false
    var zebra: Boolean = false
    var falseColor: Boolean = false
    var guides: Boolean = true
    var histogram: Boolean = true
    var lutEnabled: Boolean = false
    var zebraLowIre: Int = 75
    var zebraHighIre: Int = 95
    var videoStabilization: Boolean = false
    var overlaysVisible: Boolean = true

    val shutterLabel: String
        get() {
            val denominator = max(1L, 1_000_000_000L / max(1L, shutterNs))
            return "1/$denominator"
        }

    /** Pasos finos de obturación en tercios de paso sobre una escala de 1/24 a 1/8000. */
    fun stepShutter(direction: Int): Long {
        val stops = SUTTER_STOPS
        val current = shutterNs
        var bestIndex = 0
        var bestDelta = Long.MAX_VALUE
        stops.forEachIndexed { index, value ->
            val delta = kotlin.math.abs(value - current)
            if (delta < bestDelta) { bestDelta = delta; bestIndex = index }
        }
        val next = (bestIndex - direction).coerceIn(0, stops.size - 1)
        shutterNs = stops[next]
        return shutterNs
    }

    /**
     * Ganancias RGGB para una temperatura de color y un tinte.
     *
     * Se parte de la curva práctica Kelvin -> RGB, se invierte para obtener la
     * corrección que neutraliza esa temperatura y se normaliza sobre el verde.
     * El tinte desplaza verde contra magenta. Es una aproximación de ingeniería:
     * sin calibración por dispositivo no pretende ser colorimetría exacta.
     */
    fun rggbGains(): RggbChannelVector {
        val (r, g, b) = kelvinToRgb(kelvin)
        val safeG = max(0.05f, g)
        var red = 1f / r
        var green = 1f / safeG
        var blue = 1f / b
        // Normalizar a verde = 1
        red /= green
        blue /= green
        green = 1f

        val tintAmount = tint / 100f
        green *= (1f - 0.25f * tintAmount)
        red *= (1f + 0.10f * tintAmount)
        blue *= (1f + 0.10f * tintAmount)

        val minGain = gainMin
        val maxGain = gainMax
        return RggbChannelVector(
            (red).coerceIn(minGain, maxGain),
            (green).coerceIn(minGain, maxGain),
            (green).coerceIn(minGain, maxGain),
            (blue).coerceIn(minGain, maxGain)
        )
    }

    /**
     * Estima temperatura y matiz a partir de las ganancias que aplica el AWB del
     * dispositivo, invirtiendo la misma tabla que usa el modo manual. Es una
     * aproximación de ingeniería coherente con [rggbGains], no colorimetría.
     */
    fun estimateKelvinTint(gains: RggbChannelVector): Pair<Int, Int> {
        val green = max(0.05f, gains.greenEven)
        val red = max(0.05f, gains.red) / green
        val blue = max(0.05f, gains.blue) / green
        var bestKelvin = kelvin
        var bestDelta = Float.MAX_VALUE
        var bestRedRatio = 1f
        for (candidate in 2500..10000 step 100) {
            val (kr, kg, kb) = kelvinToRgb(candidate)
            val safeGreen = max(0.05f, kg)
            val candidateRed = kr / safeGreen
            val candidateBlue = kb / safeGreen
            val delta = kotlin.math.abs(candidateRed - red) + kotlin.math.abs(candidateBlue - blue)
            if (delta < bestDelta) {
                bestDelta = delta
                bestKelvin = candidate
                bestRedRatio = candidateRed
            }
        }
        val ratio = red / max(0.05f, bestRedRatio)
        val estimatedTint = ((ratio - 1f) / (0.1f + 0.25f * ratio) * 100f).coerceIn(-100f, 100f)
        return bestKelvin to estimatedTint.toInt()
    }

    private fun kelvinToRgb(kelvin: Int): Triple<Float, Float, Float> {
        val t = kelvin.coerceIn(1000, 40000) / 100f
        val red = if (t <= 66f) 255f
        else (329.698727446f * (t - 60f).pow(-0.1332047592f)).coerceIn(0f, 255f)
        val green = if (t <= 66f) (99.4708025861f * ln(t.toDouble()).toFloat() - 161.1195681661f).coerceIn(0f, 255f)
        else (288.1221695283f * (t - 60f).pow(-0.0755148492f)).coerceIn(0f, 255f)
        val blue = when {
            t >= 66f -> 255f
            t <= 19f -> 0f
            else -> (138.5177312231f * ln((t - 10f).toDouble()).toFloat() - 305.0447927307f).coerceIn(0f, 255f)
        }
        return Triple(max(1f, red) / 255f, max(0.02f, green) / 255f, max(0.02f, blue) / 255f)
    }

    companion object {
        const val LENS_MAIN = "1x"
        const val LENS_TELE = "TELE"
        const val LENS_UW = "UW"
        const val LENS_LOGICAL = "LOG"

        /** Escala de obturación de cine, de 1/24 a 1/8000. */
        val SUTTER_STOPS: LongArray = longArrayOf(
            41_666_666L, 33_333_333L, 20_000_000L, 16_666_666L, 12_500_000L,
            10_000_000L, 8_333_333L, 6_250_000L, 5_000_000L, 4_000_000L,
            3_333_333L, 2_500_000L, 2_000_000L, 1_666_666L, 1_250_000L,
            1_000_000L, 833_333L, 625_000L, 500_000L, 400_000L,
            333_333L, 250_000L, 200_000L, 166_666L, 125_000L
        )

        val KELVIN_STOPS: IntArray = IntArray(76) { 2_500 + it * 100 }

        val TINT_STOPS: IntArray = IntArray(41) { -100 + it * 5 }

        val ISO_STOPS: IntArray = intArrayOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000,
            1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 19200
        )

        /** Límites de ganancias RGGB que acepta el transporte (rango habitual de Camera2). */
        var gainMin: Float = 0.25f
        var gainMax: Float = 4.0f
    }
}
