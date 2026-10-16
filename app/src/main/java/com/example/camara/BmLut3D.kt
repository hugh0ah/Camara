package com.example.camara

/**
 * LUT 3D en formato `.cube` (Adobe/IWLT), con interpolación trilineal.
 *
 * Se usa para el visor de monitorización: los píxeles reales del visor se
 * transforman antes de calcular falso color, zebras e histograma, de modo que
 * el operador ve la respuesta de la LUT cargada.
 */
class BmLut3D(private val size: Int, private val data: FloatArray) {

    val title: String = "LUT ${size}³"

    /**
     * Aplica la LUT a un color 0..255 y escribe el resultado en [out] (0..255).
     */
    fun apply(r: Int, g: Int, b: Int, out: IntArray) {
        val maxIndex = size - 1
        val rf = (r / 255f) * maxIndex
        val gf = (g / 255f) * maxIndex
        val bf = (b / 255f) * maxIndex

        val r0 = rf.toInt().coerceIn(0, maxIndex)
        val g0 = gf.toInt().coerceIn(0, maxIndex)
        val b0 = bf.toInt().coerceIn(0, maxIndex)
        val r1 = (r0 + 1).coerceAtMost(maxIndex)
        val g1 = (g0 + 1).coerceAtMost(maxIndex)
        val b1 = (b0 + 1).coerceAtMost(maxIndex)

        val dr = rf - r0
        val dg = gf - g0
        val db = bf - b0

        out[0] = interpolate(0, r0, r1, g0, g1, b0, b1, dr, dg, db)
        out[1] = interpolate(1, r0, r1, g0, g1, b0, b1, dr, dg, db)
        out[2] = interpolate(2, r0, r1, g0, g1, b0, b1, dr, dg, db)
    }

    private fun interpolate(
        channel: Int,
        r0: Int, r1: Int, g0: Int, g1: Int, b0: Int, b1: Int,
        dr: Float, dg: Float, db: Float
    ): Int {
        val c000 = sample(r0, g0, b0, channel)
        val c100 = sample(r1, g0, b0, channel)
        val c010 = sample(r0, g1, b0, channel)
        val c110 = sample(r1, g1, b0, channel)
        val c001 = sample(r0, g0, b1, channel)
        val c101 = sample(r1, g0, b1, channel)
        val c011 = sample(r0, g1, b1, channel)
        val c111 = sample(r1, g1, b1, channel)

        val c00 = c000 + (c100 - c000) * dr
        val c10 = c010 + (c110 - c010) * dr
        val c01 = c001 + (c101 - c001) * dr
        val c11 = c011 + (c111 - c011) * dr
        val c0 = c00 + (c10 - c00) * dg
        val c1 = c01 + (c11 - c01) * dg
        val value = c0 + (c1 - c0) * db
        return (value * 255f).toInt().coerceIn(0, 255)
    }

    private fun sample(r: Int, g: Int, b: Int, channel: Int): Float =
        data[((b * size + g) * size + r) * 3 + channel]

    companion object {
        /**
         * Lee un `.cube` 3D. Devuelve null si el texto no es una LUT 3D válida.
         */
        fun parseCube(text: String): BmLut3D? {
            var size = 0
            val values = ArrayList<Float>(64 * 64 * 64)
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("TITLE")) continue
                if (line.startsWith("LUT_3D_SIZE")) {
                    size = line.substringAfter("LUT_3D_SIZE").trim().toIntOrNull() ?: 0
                    continue
                }
                if (line.startsWith("DOMAIN_") || line.startsWith("LUT_1D_SIZE")) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3) continue
                val r = parts[0].toFloatOrNull() ?: continue
                val g = parts[1].toFloatOrNull() ?: continue
                val b = parts[2].toFloatOrNull() ?: continue
                values.add(r); values.add(g); values.add(b)
            }
            if (size < 2 || values.size < size * size * size * 3) return null
            return BmLut3D(size, values.toFloatArray())
        }
    }
}
