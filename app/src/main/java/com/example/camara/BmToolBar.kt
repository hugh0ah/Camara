package com.example.camara

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Barra de monitores de CineCam.
 *
 * Botones tipo píldora: fondo oscuro, borde fino y, cuando están activos, fondo
 * verde con texto negro en negrita. Se puede disponer en fila (por defecto) o
 * en columna, que es como la usa el raíl lateral —igual que los raíles que
 * Blackmagic pliega en los laterales del visor—. Sin dibujos en Canvas.
 */
class BmToolBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    enum class Tool(val key: String, val caption: String) {
        PEAKING("PEAKING", "PEAK"),
        ZEBRA("ZEBRA", "ZEBRA"),
        FALSE_COLOR("FALSE", "FALSE"),
        GUIDES("GUIDES", "GUIDES"),
        HISTOGRAM("HIST", "HIST"),
        LUT("LUT", "LUT"),
        LOG("LOG", "LOG"),
        ROTATE("ROTATE", "ROTAR")
    }

    private val buttons = LinkedHashMap<String, TextView>()
    private val vertical: Boolean

    var onToggle: ((String, Boolean) -> Unit)? = null

    init {
        val styled = context.obtainStyledAttributes(attrs, R.styleable.BmToolBar)
        vertical = styled.getBoolean(R.styleable.BmToolBar_bmVertical, false)
        styled.recycle()

        orientation = if (vertical) VERTICAL else HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (vertical) setPadding(dp(4), dp(4), dp(4), dp(4)) else setPadding(dp(6), dp(2), dp(6), dp(4))

        Tool.entries.forEach { tool ->
            val pill = TextView(context).apply {
                text = tool.caption
                textSize = 9.5f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.04f
                isClickable = true
                isFocusable = true
                maxLines = 1
                layoutParams = if (vertical) {
                    LayoutParams(LayoutParams.MATCH_PARENT, dp(28)).apply { bottomMargin = dp(3) }
                } else {
                    LayoutParams(0, dp(26), 1f).apply { marginEnd = dp(3) }
                }
                setOnClickListener {
                    val active = !isSelectedState()
                    applyVisual(active)
                    onToggle?.invoke(tool.key, active)
                }
                applyVisual(false)
            }
            addView(pill)
            buttons[tool.key] = pill
        }
    }

    private fun TextView.isSelectedState(): Boolean = tag == true

    private fun TextView.applyVisual(active: Boolean) {
        tag = active
        background = pillBackground(active)
        setTextColor(if (active) Color.BLACK else COLOR_TEXT)
        setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun pillBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(6).toFloat()
        if (active) {
            setColor(COLOR_ACTIVE)
            setStroke(dp(1), COLOR_ACTIVE)
        } else {
            setColor(COLOR_IDLE_BG)
            setStroke(dp(1), COLOR_IDLE_STROKE)
        }
    }

    fun isActive(key: String): Boolean = buttons[key]?.tag == true

    fun setActive(key: String, active: Boolean) {
        buttons[key]?.applyVisual(active)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val COLOR_ACTIVE = 0xFF00E676.toInt()
        const val COLOR_IDLE_BG = 0xE6101C2A.toInt()
        const val COLOR_IDLE_STROKE = 0xFF2A3B4F.toInt()
        const val COLOR_TEXT = 0xFFE8ECF2.toInt()
    }
}
