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

    // --- Bloqueos y óptica (portado de OpenCamera, CameraController2) ---
    /** `CONTROL_AE_LOCK_ON`: congela la exposición que resolvió el AE. */
    var aeLock: Boolean = false

    /** `CONTROL_AWB_LOCK_ON`: congela el balance de blancos automático. */
    var awbLock: Boolean = false

    /** `LENS_OPTICAL_STABILIZATION_MODE_ON` además de la estabilización de vídeo. */
    var opticalStabilization: Boolean = false

    /** `CONTROL_ZOOM_RATIO` suave; 1.0 equivale a sin recorte. */
    var zoomRatio: Float = 1.0f

    /** Perfil de curva de tonos del botón LOG (portado de FreeDcam). */
    var toneProfile: ToneProfile = ToneProfile.LOG

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
     * Portado de FreeDcam:
     *  1. Respuesta del iluminante desde la tabla `wbct_lookup` de FreeDcam
     *     (interpolada) y, fuera de su rango, la aproximacion de Planck.
     *  2. `WbHandler.getRGBToDouble`: `(2/255) * color + 1`, con el suelo
     *     `MINCAP = 1.0`. El verde se divide por 2 porque el patron RGGB lleva
     *     dos canales verdes.
     *  3. Eje de tinte sobre los canales de color, con el verde como referencia:
     *     positivo (magenta) sube R y B; negativo (verde) los baja.
     *  4. Todos los canales quedan en 1.0 o por encima, que es la convencion de
     *     FreeDcam y lo que recomienda Camera2 (por debajo de 1.0 aparecen
     *     artefactos de color), conservando las relaciones R:G:B.
     */
    fun rggbGains(): RggbChannelVector {
        val (whiteR, whiteG, whiteB) = kelvinToRgb(kelvin)

        var red = (2.0f / 255.0f) * whiteR + 1.0f
        var green = ((2.0f / 255.0f) * whiteG + 1.0f) / 2.0f
        var blue = (2.0f / 255.0f) * whiteB + 1.0f
        if (green < MIN_GAIN_CAP) green = MIN_GAIN_CAP

        val t = tint.coerceIn(TINT_MIN, TINT_MAX) / 100f
        if (t > 0f) {
            red *= (1f + 0.8f * t)
            blue *= (1f + 2.4f * t)
        } else if (t < 0f) {
            red *= (1f + t)
            blue *= (1f + t)
        }

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
     * Respuesta del iluminante para una temperatura de color, en escala 0-255.
     *
     * Usa la tabla `wbct_lookup` de FreeDcam (86 entradas, 1500-10000 K,
     * interpolada linealmente) y solo cae en la aproximación de Planck
     * (Kim et al. / Kang et al.) fuera de ese rango.
     */
    private fun kelvinToRgb(kelvin: Int): Triple<Float, Float, Float> {
        wbctLookup(kelvin)?.let { return it }
        return planckRgb(kelvin)
    }

    /** Interpolación lineal sobre `wbct_lookup`; null si la temperatura se sale. */
    private fun wbctLookup(kelvin: Int): Triple<Float, Float, Float>? {
        if (kelvin < WBCT_TEMPERATURES.first() || kelvin > WBCT_TEMPERATURES.last()) return null
        var low = 0
        var high = WBCT_TEMPERATURES.size - 1
        while (high - low > 1) {
            val mid = (low + high) / 2
            if (WBCT_TEMPERATURES[mid] <= kelvin) low = mid else high = mid
        }
        val t0 = WBCT_TEMPERATURES[low]
        val t1 = WBCT_TEMPERATURES[high]
        val ratio = if (t1 == t0) 0f else (kelvin - t0).toFloat() / (t1 - t0).toFloat()
        val base0 = low * 3
        val base1 = high * 3
        return Triple(
            WBCT_RGB[base0] + (WBCT_RGB[base1] - WBCT_RGB[base0]) * ratio,
            WBCT_RGB[base0 + 1] + (WBCT_RGB[base1 + 1] - WBCT_RGB[base0 + 1]) * ratio,
            WBCT_RGB[base0 + 2] + (WBCT_RGB[base1 + 2] - WBCT_RGB[base0 + 2]) * ratio
        )
    }

    /** Aproximación de Planck (Kim/Kang) en la escala 0-255, para fuera de tabla. */
    private fun planckRgb(kelvin: Int): Triple<Float, Float, Float> {
        val temperature = kelvin.coerceIn(1500, 20000).toDouble() / 100.0

        val red = if (temperature <= 66.0) {
            255.0
        } else {
            (329.698727446 * (temperature - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        }

        val green = if (temperature <= 66.0) {
            (99.4708025861 * ln(temperature) - 161.1195681661).coerceIn(0.0, 255.0)
        } else {
            (288.1221695283 * (temperature - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        }

        val blue = when {
            temperature >= 66.0 -> 255.0
            temperature <= 19.0 -> 0.0
            else -> (138.5177312231 * ln(temperature - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
        }

        return Triple(
            red.toFloat().coerceAtLeast(1f),
            green.toFloat().coerceAtLeast(1f),
            blue.toFloat().coerceAtLeast(1f)
        )
    }

    // ------------------------------------------------------- curvas de tonos

    /**
     * Perfiles de curva de tonos del botón LOG.
     *
     * FreeDcam (`ManualToneMapCurveApi2`) no trae curvas Log/Flat/Rec.709: su
     * tabla es una rampa neutra ajustable por el usuario
     * (`{0,0, 0.25,0.25, 0.5,0.5, 0.75,0.75, 1,1}` con puntos negro, sombras,
     * medios, altas luces y blanco) y `ToneMapProfile` documenta el tonemap del
     * DNG. Aquí se conserva esa estructura de cinco puntos y el valor de cada
     * punto sale de fórmulas publicadas, no de constantes inventadas:
     *
     *  - REC.709: función de transferencia OETF de la recomendación ITU-R BT.709
     *    (lineal hasta 0.018, `1.099·L^0.45 − 0.099` por encima).
     *  - LOG: curva logarítmica de cine al estilo Cineon (la misma familia que
     *    usan las cámaras de cine), con la ganancia y el desplazamiento
     *    habituales; deja las sombras altas y comprime las altas luces.
     *  - FLAT: la curva logarítmica con menos ganancia, para etalonar después.
     */
    enum class ToneProfile(val label: String) {
        LOG("Log"),
        FLAT("Flat"),
        REC709("Rec.709")
    }

    /**
     * Curva de tonos del perfil activo, en pares entrada/salida 0..1.
     *
     * Los puntos de entrada son los de FreeDcam (negro, sombras, medios, altas
     * luces, blanco) y la salida se calcula con la fórmula del perfil. La curva
     * es monótona y queda dentro de rango, que es lo que exige `TONEMAP_CURVE`.
     */
    fun toneCurve(profile: ToneProfile): FloatArray {
        val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val curve = FloatArray(stops.size * 2)
        for (index in stops.indices) {
            val linear = stops[index]
            curve[index * 2] = linear
            curve[index * 2 + 1] = when (profile) {
                ToneProfile.REC709 -> rec709(linear)
                ToneProfile.LOG -> cineon(linear, 0.9f, 0.06f)
                ToneProfile.FLAT -> cineon(linear, 0.6f, 0.09f)
            }.coerceIn(0f, 1f)
        }
        return curve
    }

    /** OETF de ITU-R BT.709: lineal hasta 0.018, potencia desde ahí. */
    private fun rec709(linear: Float): Float =
        if (linear < 0.018f) 4.5f * linear
        else 1.099f * linear.pow(0.45f) - 0.099f

    /**
     * Curva logarítmica de cine (familia Cineon):
     * `(log10(offset + gain·L) + d) / (d + log10(offset + gain))`, normalizada
     * para que 0 -> 0 y 1 -> 1.
     */
    private fun cineon(linear: Float, gain: Float, offset: Float): Float {
        val high = kotlin.math.log10((offset + gain).toDouble()).toFloat()
        val low = kotlin.math.log10(offset.toDouble()).toFloat()
        val current = kotlin.math.log10((offset + gain * linear).toDouble()).toFloat()
        val span = high - low
        return if (span <= 0f) linear else (current - low) / span
    }

    // ------------------------------------------- indices discretos (FreeDcam)

    /**
     * Valores de ISO del sensor, generados como en FreeDcam
     * (`featuredetector/camera2/IsoDetector.getIsoStrings`): desde el minimo del
     * rango declarado en pasos de 50, duplicando el salto por encima de 3200.
     */
    fun buildIsoStops(minIso: Int, maxIso: Int): IntArray {
        val result = ArrayList<Int>()
        var value = max(1, minIso)
        val ceiling = max(value, maxIso)
        while (value <= ceiling) {
            result.add(value)
            if (value > 3200) {
                val next = value * 2
                value = if (next > ceiling) ceiling else next
                if (result.last() == value) break
            } else {
                value += 50
            }
        }
        if (result.lastOrNull() != ceiling) result.add(ceiling)
        return result.toIntArray()
    }

    /**
     * Obturaciones validas: la tabla de FreeDcam filtrada por
     * `SENSOR_INFO_EXPOSURE_TIME_RANGE`, como hace
     * `ExposureTimeDetector.getShutterStrings` (cada entrada a microsegundos).
     */
    fun buildShutterStops(minNs: Long, maxNs: Long): LongArray {
        if (maxNs <= minNs) return longArrayOf(minNs.coerceAtLeast(1L))
        val minUs = minNs / 1000.0
        val maxUs = maxNs / 1000.0
        val filtered = SHUTTER_TABLE_NS.filter { it / 1000.0 in minUs..maxUs }
        return if (filtered.isEmpty()) longArrayOf(minNs, maxNs) else filtered.toLongArray()
    }

    /** Indice del valor mas cercano dentro de una tabla ordenada. */
    fun nearestIndex(values: IntArray, target: Int): Int {
        var best = 0
        var bestDelta = Int.MAX_VALUE
        values.forEachIndexed { index, value ->
            val delta = kotlin.math.abs(value - target)
            if (delta < bestDelta) { bestDelta = delta; best = index }
        }
        return best
    }

    /** Indice del valor mas cercano dentro de una tabla ordenada de long. */
    fun nearestIndex(values: LongArray, target: Long): Int {
        var best = 0
        var bestDelta = Long.MAX_VALUE
        values.forEachIndexed { index, value ->
            val delta = kotlin.math.abs(value - target)
            if (delta < bestDelta) { bestDelta = delta; best = index }
        }
        return best
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
        /** Suelo de ganancia de FreeDcam (`WbHandler.MINCAP`). */
        const val MIN_GAIN_CAP = 1.0f

        /**
         * Tabla `wbct_lookup` de FreeDcam (KillerInk/FreeDcam,
         * `app/src/main/res/values/arrays.xml`), recortada a 2500-10000 K.
         *
         * Cada fila es `temperatura, R, G, B` con los canales en escala 0-255.
         * FreeDcam los convierte con `WbHandler.getRGBToDouble`
         * (`(2/255)*color + 1`) y arma `RggbChannelVector(r, g/2, g/2, b)` con
         * `MINCAP = 1.0`.
         */
        val WBCT_TABLE: LongArray = longArrayOf(
            2500, 255, 159, 70,
            2600, 255, 163, 79,
            2700, 255, 167, 87,
            2800, 255, 170, 95,
            2900, 255, 174, 103,
            3000, 255, 177, 110,
            3100, 255, 180, 117,
            3200, 255, 184, 123,
            3300, 255, 187, 129,
            3400, 255, 190, 135,
            3500, 255, 193, 141,
            3600, 255, 195, 146,
            3700, 255, 198, 151,
            3800, 255, 201, 157,
            3900, 255, 203, 161,
            4000, 255, 206, 166,
            4100, 255, 208, 171,
            4200, 255, 211, 175,
            4300, 255, 213, 179,
            4400, 255, 215, 183,
            4500, 255, 218, 187,
            4600, 255, 220, 191,
            4700, 255, 222, 195,
            4800, 255, 224, 199,
            4900, 255, 226, 202,
            5000, 255, 228, 206,
            5100, 255, 230, 209,
            5200, 255, 232, 213,
            5300, 255, 234, 216,
            5400, 255, 236, 219,
            5500, 255, 237, 222,
            5600, 255, 239, 225,
            5700, 255, 241, 228,
            5800, 255, 243, 231,
            5900, 255, 244, 234,
            6000, 255, 246, 237,
            6100, 255, 248, 240,
            6200, 255, 249, 242,
            6300, 255, 251, 245,
            6400, 255, 253, 248,
            6500, 255, 254, 250,
            6600, 255, 255, 255,
            6700, 254, 249, 255,
            6800, 250, 246, 255,
            6900, 246, 244, 255,
            7000, 243, 242, 255,
            7100, 240, 240, 255,
            7200, 237, 239, 255,
            7300, 234, 237, 255,
            7400, 232, 236, 255,
            7500, 230, 235, 255,
            7600, 228, 234, 255,
            7700, 226, 233, 255,
            7800, 224, 232, 255,
            7900, 223, 231, 255,
            8000, 221, 230, 255,
            8100, 220, 229, 255,
            8200, 218, 228, 255,
            8300, 217, 227, 255,
            8400, 216, 227, 255,
            8500, 215, 226, 255,
            8600, 214, 225, 255,
            8700, 213, 225, 255,
            8800, 212, 224, 255,
            8900, 211, 223, 255,
            9000, 210, 223, 255,
            9100, 209, 222, 255,
            9200, 208, 222, 255,
            9300, 207, 221, 255,
            9400, 206, 221, 255,
            9500, 205, 220, 255,
            9600, 205, 220, 255,
            9700, 204, 219, 255,
            9800, 203, 219, 255,
            9900, 202, 218, 255,
            10000, 201, 218, 255,
        )

        /** Temperaturas de [WBCT_TABLE]. */
        val WBCT_TEMPERATURES: IntArray =
            IntArray(WBCT_TABLE.size / 4) { WBCT_TABLE[it * 4].toInt() }

        /** Canales R, G y B de [WBCT_TABLE] en orden, escala 0-255. */
        val WBCT_RGB: FloatArray = FloatArray((WBCT_TABLE.size / 4) * 3) { index ->
            val row = index / 3
            val channel = index % 3
            WBCT_TABLE[row * 4 + 1 + channel].toFloat()
        }

        /**
         * Tabla de obturaciones de FreeDcam (`res/values/shutter_values.xml`,
         * array `shutter_values_autocreate`): los nombres `@string/shutter_A_B`
         * se convierten a nanosegundos (A/B segundos) y `@string/shutter_N` a N
         * segundos. 140 entradas, filtradas por [buildShutterStops]
         * al rango real del sensor.
         */
        val SHUTTER_TABLE_NS: LongArray = longArrayOf(
            10000L, 11111L, 13333L, 20000L, 22222L, 33333L,
            50000L, 66667L, 83333L, 100000L, 125000L, 156250L,
            166667L, 200000L, 250000L, 312500L, 400000L, 500000L,
            625000L, 800000L, 909091L, 1000000L, 1052632L, 1111111L,
            1176471L, 1250000L, 1333333L, 1428571L, 1538462L, 1562500L,
            1666667L, 1818182L, 2000000L, 2222222L, 2500000L, 2666667L,
            2857143L, 3076923L, 3125000L, 3333333L, 3636364L, 4000000L,
            4444444L, 5000000L, 5714286L, 6666667L, 8000000L, 10000000L,
            11764706L, 12500000L, 13333333L, 15384615L, 16666667L, 18181818L,
            20000000L, 22222222L, 25000000L, 28571429L, 33333333L, 40000000L,
            50000000L, 66666667L, 76923077L, 100000000L, 111111111L, 125000000L,
            142857143L, 166666667L, 200000000L, 250000000L, 333333333L, 500000000L,
            1000000000L, 2000000000L, 3000000000L, 4000000000L, 5000000000L, 6000000000L,
            7000000000L, 8000000000L, 9000000000L, 10000000000L, 11000000000L, 12000000000L,
            13000000000L, 14000000000L, 15000000000L, 16000000000L, 17000000000L, 18000000000L,
            19000000000L, 20000000000L, 21000000000L, 22000000000L, 23000000000L, 24000000000L,
            25000000000L, 26000000000L, 27000000000L, 28000000000L, 29000000000L, 30000000000L,
            31000000000L, 32000000000L, 33000000000L, 34000000000L, 35000000000L, 36000000000L,
            37000000000L, 38000000000L, 39000000000L, 40000000000L, 41000000000L, 42000000000L,
            43000000000L, 44000000000L, 45000000000L, 46000000000L, 47000000000L, 48000000000L,
            49000000000L, 50000000000L, 51000000000L, 52000000000L, 53000000000L, 54000000000L,
            55000000000L, 56000000000L, 57000000000L, 58000000000L, 59000000000L, 60000000000L,
            90000000000L, 120000000000L, 180000000000L, 240000000000L, 900000000000L, 1200000000000L,
            1800000000000L, 3600000000000L,
        )

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
