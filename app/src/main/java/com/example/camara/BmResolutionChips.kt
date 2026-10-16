package com.example.camara

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Selector directo de resolución, con un chip por formato.
 *
 * Sustituye al menú: cada formato se elige con un toque, y el activo se marca con
 * fondo claro. Las etiquetas son las de la app de referencia más el nombre del
 * formato, para que se lea de un vistazo qué se está grabando.
 */
class BmResolutionChips @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val chips = LinkedHashMap<BmSettings.Quality, TextView>()

    var onQualitySelected: ((BmSettings.Quality) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(2), dp(6), dp(2))
    }

    /** Crea un chip por formato disponible. */
    fun bind(qualities: List<BmSettings.Quality>) {
        removeAllViews()
        chips.clear()
        qualities.forEach { quality ->
            val chip = TextView(context).apply {
                text = "${quality.main} ${quality.sub}"
                setTextColor(0xE6FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                minimumHeight = dp(30)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = chipBackground(false)
                setOnClickListener { onQualitySelected?.invoke(quality) }
            }
            addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = if (chips.isEmpty()) 0 else dp(6)
            })
            chips[quality] = chip
        }
    }

    /** Marca el chip activo. */
    fun setActive(quality: BmSettings.Quality) {
        chips.forEach { (candidate, chip) ->
            val active = candidate == quality
            chip.setTextColor(if (active) Color.BLACK else 0xE6FFFFFF.toInt())
            chip.background = chipBackground(active)
        }
    }

    private fun chipBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(15).toFloat()
        setColor(if (active) 0xFFFFFFFF.toInt() else 0xB3121214.toInt())
        setStroke(dp(1), if (active) 0xFFFFFFFF.toInt() else 0x33FFFFFF)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
