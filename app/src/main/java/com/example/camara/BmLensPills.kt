package com.example.camara

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Botonera flotante de lentes, al estilo del selector rápido de las apps de cine
 * y de iOS Pro: una píldora por objetivo sobre la base inferior del visor, para
 * cambiar de sensor con un solo toque sin entrar en ningún menú.
 *
 *   [0.6x UW]  [1x]  [3x TELE]  [60 FPS]
 *
 * Las píldoras de lente se ocultan si el dispositivo no publica ese sensor
 * físico; la de 60 fps siempre está, porque es la lógica agrupada.
 */
class BmLensPills @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Identificadores de la lógica agrupada que ya usa la app. */
    companion object {
        const val LENS_UW = "UW"
        const val LENS_MAIN = "1x"
        const val LENS_TELE = "TELE"
        const val LENS_LOGICAL = "LOG"
        const val LENS_FRONT = "FRONT"

        private const val COLOR_ACTIVE = 0xFFFFFFFF.toInt()
        private const val COLOR_IDLE = 0xE6FFFFFF.toInt()
        private const val BG_ACTIVE = 0xF2FFFFFF.toInt()
        private const val BG_IDLE = 0xB3121214.toInt()
    }

    private val pills = LinkedHashMap<String, TextView>()

    var onLensSelected: ((String) -> Unit)? = null
    var onFps60Selected: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        visibility = VISIBLE
    }

    /**
     * Reconstruye las píldoras disponibles.
     *
     * @param availableLenses etiquetas de lente publicadas por el dispositivo.
     * @param grouped60fps    si la lógica agrupada soporta 60 fps.
     */
    fun setAvailableLenses(availableLenses: List<String>, grouped60fps: Boolean) {
        removeAllViews()
        pills.clear()
        val order = listOf(LENS_UW to "0.6x UW", LENS_MAIN to "1x", LENS_TELE to "3x TELE", LENS_FRONT to "FRONT")
        order.forEach { (key, label) ->
            if (availableLenses.contains(key)) addPill(key, label)
        }
        // Si el dispositivo no publica sensores físicos, queda al menos la lógica.
        if (pills.isEmpty()) addPill(LENS_LOGICAL, "1x")
        if (grouped60fps) {
            addPill(LENS_LOGICAL + ":60", "60 FPS")
        }
    }

    private fun addPill(key: String, label: String) {
        val pill = TextView(context).apply {
            text = label
            setTextColor(COLOR_IDLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minimumHeight = dp(32)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(BG_IDLE)
                setStroke(dp(1), 0x33FFFFFF)
            }
            setOnClickListener {
                when {
                    key.endsWith(":60") -> onFps60Selected?.invoke()
                    else -> onLensSelected?.invoke(key)
                }
            }
        }
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = if (pills.isEmpty()) 0 else dp(8)
        })
        pills[key] = pill
    }

    /** Marca la píldora activa y devuelve el resto al estado inactivo. */
    fun setActive(activeKey: String, fps60: Boolean) {
        pills.forEach { (key, pill) ->
            val is60 = key.endsWith(":60")
            val active = if (is60) fps60 else (!fps60 && key == activeKey)
            pill.setTextColor(if (active) Color.BLACK else COLOR_IDLE)
            (pill.background as? GradientDrawable)?.setColor(if (active) BG_ACTIVE else BG_IDLE)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
