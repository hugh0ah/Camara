package com.example.camara

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bloque de parámetro de la interfaz de CineCam, con la misma estructura que la
 * barra de parámetros de Blackmagic Camera: rótulo pequeño arriba (con el
 * indicador azul «A» cuando el parámetro está en automático) y valor grande
 * debajo, subrayado en verde solo mientras su regla graduada está abierta.
 *
 * Construido con vistas estándar de Android: no usa recursos, iconos ni fuentes
 * empaquetadas de otra aplicación.
 */
class BmSelector @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val captionView: TextView
    private val autoChip: TextView
    private val valueView: TextView
    private val underline: View

    /** Marca de automático (chip azul «A») que Blackmagic muestra junto al rótulo. */
    var auto: Boolean = false
        set(isAuto) {
            field = isAuto
            autoChip.visibility = if (isAuto) VISIBLE else GONE
        }

    /** Parámetros no ajustables (por ejemplo la apertura fija) se atenúan. */
    var dimmed: Boolean = false
        set(value) {
            field = value
            applyValueColor()
        }

    /**
     * Toque sobre la insignia «A». Devuelve `true` si el toque lo ha consumido la
     * insignia, para que no abra además la regla graduada. Sin este enlace la
     * insignia era sólo informativa y los botones AUTO parecían muertos.
     */
    var onAutoTap: ((active: Boolean) -> Boolean)? = null

    var caption: String = ""
        set(text) {
            field = text
            captionView.text = text.uppercase()
        }

    var value: String = ""
        set(text) {
            field = text
            valueView.text = text
        }

    var active: Boolean = false
        set(isActive) {
            field = isActive
            underline.visibility = if (isActive) VISIBLE else INVISIBLE
            applyValueColor()
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(dp(2), dp(2), dp(2), dp(2))

        val captionRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        captionView = TextView(context).apply {
            textSize = 11f
            letterSpacing = 0.02f
            setTextColor(COLOR_CAPTION)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
        }
        captionRow.addView(captionView)

        autoChip = TextView(context).apply {
            text = "A"
            textSize = 8.5f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_AUTO)
                cornerRadius = dp(2).toFloat()
            }
            visibility = GONE
            // La insignia conmuta el automático del parámetro: alterna entre «A»
            // encendida (AE/AWB del ISP) y apagada (manual con ganancias propias).
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onAutoTap?.invoke(!auto)?.let { handled ->
                    if (handled) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
            }
        }
        captionRow.addView(
            autoChip,
            LayoutParams(dp(12), dp(12)).apply { marginStart = dp(3) }
        )
        addView(captionRow)

        valueView = TextView(context).apply {
            textSize = 18f
            setTextColor(COLOR_IDLE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(valueView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        underline = View(context).apply {
            setBackgroundColor(COLOR_ACTIVE)
            visibility = INVISIBLE
        }
        addView(underline, LayoutParams(dp(14), dp(1.5f).toInt()).apply { topMargin = dp(2) })

        // Área táctil holgada: si el bloque es más bajo que 48 dp, se extiende el
        // toque por encima y por debajo sin mover nada en pantalla. Se aplica
        // sobre el propio bloque (el delegado se evalúa contra la extensión del
        // hijo, así que el rectángulo ampliado es el área sensible del bloque).
        addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            expandTouchTarget(view)
        }
    }

    /**
     * Amplía el área sensible del bloque hasta 48 dp de alto como mínimo.
     *
     * Con la cabecera en dos filas cada parámetro mide poco más de 40 dp, así que
     * los toques en el borde superior o inferior caían fuera y los botones
     * parecían no responder al primer intento.
     */
    private fun expandTouchTarget(view: View) {
        if (view.width <= 0 || view.height <= 0) return
        val minimum = dp(MIN_TOUCH_DP)
        val extraVertical = ((minimum - view.height) / 2).coerceAtLeast(0)
        val extraHorizontal = ((minimum - view.width) / 2).coerceAtLeast(0)
        if (extraVertical == 0 && extraHorizontal == 0) return
        view.touchDelegate = android.view.TouchDelegate(
            android.graphics.Rect(
                -extraHorizontal, -extraVertical,
                view.width + extraHorizontal, view.height + extraVertical
            ),
            view
        )
    }

    private fun applyValueColor() {
        valueView.setTextColor(
            when {
                active -> COLOR_ACTIVE
                dimmed -> COLOR_DIM
                else -> COLOR_IDLE
            }
        )
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        /** Área táctil mínima recomendada por las guías de Android. */
        const val MIN_TOUCH_DP = 48
        const val COLOR_CAPTION = 0xFF9E9E9E.toInt()
        const val COLOR_IDLE = 0xFFF2F4F7.toInt()
        const val COLOR_DIM = 0xFF9AA2AC.toInt()
        const val COLOR_ACTIVE = 0xFF00E676.toInt()
        const val COLOR_AUTO = 0xFF2196F3.toInt()
    }
}
