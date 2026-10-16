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
    var tint: Int = 0                        // -50 (verde) .. +50 (magenta), 0 neutro

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
        UHD("4K", "UHD", 3840, 2160, 120_000_000, 1920, 1080),
        K38("3.8K", "2:1", 3840, 1920, 120_000_000, 1920, 960),
        HD("HD", "1080p", 1920, 1080, 100_000_000, 1920, 1080)
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
     * Ganancias RGGB para una temperatura de color y un tinte, en paridad con
     * Blackmagic Camera.
     *
     * 1. Kelvin -> RGB con la aproximación de Planck (Kim et al. / Kang et al.),
     *    válida de 2500 K a 10000 K, normalizada a G = 1.
     * 2. Ganancias = inverso de esa respuesta, normalizadas a G = 1 para no
     *    alterar la exposición: sólo se corrige el punto blanco.
     * 3. Tinte en [-50, +50]: positivo empuja a magenta bajando el verde
     *    (`gFactor = 1 - tint/100`), negativo empuja a verde bajando R y B. Con
     *    G siempre a 1.0 no se sobrepondera el canal verde (G_even = G_odd), que
     *    era el origen de la dominante verdosa.
     * 4. Los cuatro valores se recortan al rango real que declara el sensor en
     *    `COLOR_CORRECTION_GAINS` ([gainMin], [gainMax]).
     */
    fun rggbGains(): RggbChannelVector {
        val (whiteR, whiteG, whiteB) = kelvinToRgb(kelvin)
        // 1) Inverso de la respuesta del iluminante, con el verde como referencia.
        val safeG = max(0.02f, whiteG)
        var red = safeG / max(0.02f, whiteR)
        var green = 1.0f
        var blue = safeG / max(0.02f, whiteB)

        // 2) Eje de tinte sobre las ganancias de color (el verde es la
        // referencia y no se toca, como en FreeDcam):
        //   - Positivo (magenta): se SUBEN R y B, que es lo que quita verde.
        //   - Negativo (verde): se BAJAN R y B, que es lo que deja verde.
        val t = tint.coerceIn(TINT_MIN, TINT_MAX) / 100f
        if (t > 0f) {
            red *= (1f + 0.8f * t)
            blue *= (1f + 2.4f * t)
        } else if (t < 0f) {
            red *= (1f + t)
            blue *= (1f + t)
        }

        // 3) Todos los canales >= 1.0. Camera2 documenta que las ganancias por
        // debajo de 1.0 producen artefactos de color, y es la convención que usa
        // FreeDcam (`MINCAP = 1.0`): se reescala el vector para que el canal más
        // bajo quede exactamente en 1.0 y se conserven las relaciones R:G:B.
        val floor = minOf(red, green, blue, 1.0f)
        if (floor > 0f) {
            red /= floor
            green /= floor
            blue /= floor
        }

        return RggbChannelVector(
            red.coerceIn(gainMin, gainMax),
            green.coerceIn(gainMin, gainMax),
            green.coerceIn(gainMin, gainMax),
            blue.coerceIn(gainMin, gainMax)
        )
    }

    /** Ganancia RGGB resultante del ajuste actual, para diagnóstico en log. */
    fun gainsLabel(): String {
        val g = rggbGains()
        return "R=%.4f Ge=%.4f Go=%.4f B=%.4f".format(
            java.util.Locale.US, g.red, g.greenEven, g.greenOdd, g.blue
        )
    }

    /** Fija la ventana de ganancias RGGB admisible (no hay rango público en Camera2). */
    fun setGainWindow(min: Float, max: Float) {
        if (min > 0f && max > min) {
            gainMin = min
            gainMax = max
        }
    }

    /**
     * Estima la temperatura de color y el tinte que explican unas ganancias RGGB
     * dadas. Se resuelve por búsqueda sobre la misma tabla que usa [rggbGains]:
     * la temperatura es la que mejor reproduce la proporción R/G y B/G, y el
     * tinte el desvío de esa proporción respecto al modelo neutro (positivo =
     * el sensor está quitando verde, magenta).
     */
    fun estimateKelvinTint(gains: RggbChannelVector): Pair<Int, Int> {
        val green = max(0.05f, gains.greenEven)
        val measuredRed = max(0.05f, gains.red) / green
        val measuredBlue = max(0.05f, gains.blue) / green
        var bestKelvin = kelvin
        var bestDelta = Float.MAX_VALUE
        var bestRed = 1f
        var bestBlue = 1f
        for (candidate in 2500..10000 step 100) {
            val (kr, kg, kb) = kelvinToRgb(candidate)
            val modelRed = kr / kg
            val modelBlue = kb / kg
            val delta = kotlin.math.abs(modelRed - measuredRed) + kotlin.math.abs(modelBlue - measuredBlue)
            if (delta < bestDelta) {
                bestDelta = delta
                bestKelvin = candidate
                bestRed = modelRed
                bestBlue = modelBlue
            }
        }
        // Desvío medio de los canales que el tinte modula.
        val ratio = ((measuredRed / max(0.02f, bestRed)) + (measuredBlue / max(0.02f, bestBlue))) / 2f
        val estimatedTint = ((1f - ratio) * 100f).coerceIn(TINT_MIN.toFloat(), TINT_MAX.toFloat())
        return bestKelvin to estimatedTint.toInt()
    }

    /**
     * Aproximación de Planck (Kim et al. / Kang et al.) para 2500-10000 K.
     *
     * Devuelve la respuesta relativa del iluminante en R, G, B normalizada a
     * 0..1 con el verde a 1.0, que es la base del balance de blancos.
     */
    private fun kelvinToRgb(kelvin: Int): Triple<Float, Float, Float> {
        val temperature = kelvin.coerceIn(2500, 10000).toDouble() / 100.0

        // Rojo
        val red = if (temperature <= 66.0) {
            255.0
        } else {
            (329.698727446 * (temperature - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        }

        // Verde
        val green = if (temperature <= 66.0) {
            (99.4708025861 * ln(temperature) - 161.1195681661).coerceIn(0.0, 255.0)
        } else {
            (288.1221695283 * (temperature - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        }

        // Azul
        val blue = when {
            temperature >= 66.0 -> 255.0
            temperature <= 19.0 -> 0.0
            else -> (138.5177312231 * ln(temperature - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
        }

        return Triple(
            (red / 255.0).toFloat().coerceAtLeast(0.02f),
            (green / 255.0).toFloat().coerceAtLeast(0.02f),
            (blue / 255.0).toFloat().coerceAtLeast(0.02f)
        )
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

        /** Tinte en pasos de 1, idéntico al rango de Blackmagic Camera. */
        const val TINT_MIN = -50
        const val TINT_MAX = 50
        val TINT_STOPS: IntArray = IntArray(TINT_MAX - TINT_MIN + 1) { TINT_MIN + it }

        val ISO_STOPS: IntArray = intArrayOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000,
            1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 19200
        )

        /** Límites reales de `COLOR_CORRECTION_GAINS`; se ajustan al abrir la cámara. */
        var gainMin: Float = 0.25f
        var gainMax: Float = 4.0f

        /**
         * Estrecha la ventana de ganancias cuando el HAL rechaza la petición.
         * Devuelve false si ya está en el mínimo razonable.
         */
        fun narrowGainWindow(): Boolean {
            if (gainMin >= 0.9f && gainMax <= 1.1f) return false
            gainMin = (gainMin * 1.5f).coerceAtMost(0.9f)
            gainMax = (gainMax * 0.75f).coerceAtLeast(1.1f)
            return true
        }
    }
}
