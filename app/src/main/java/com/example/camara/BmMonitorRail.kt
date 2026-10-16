package com.example.camara

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Asa del raíl de monitores: la pestaña con tres puntos que Blackmagic muestra
 * en el borde izquierdo del visor para desplegar las herramientas de
 * monitorización. El dibujo es propio (tres círculos y un fondo redondeado).
 */
class BmRailHandle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8ECF2.toInt() }
    private val dotDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99E8ECF2.toInt() }

    var expanded: Boolean = false
        set(value) {
            field = value
            background = rounded(if (value) 0xE600E676.toInt() else 0xCC0A1420.toInt())
            dot.color = if (value) 0xFF06210F.toInt() else 0xFFE8ECF2.toInt()
            dotDim.color = if (value) 0x9906210F.toInt() else 0x99E8ECF2.toInt()
            invalidate()
        }

    init {
        background = rounded(0xCC0A1420.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = dp(1.8f)
        val gap = dp(5f)
        canvas.drawCircle(cx, cy - gap, radius, dot)
        canvas.drawCircle(cx, cy, radius, dotDim)
        canvas.drawCircle(cx, cy + gap, radius, dotDim)
    }

    private fun rounded(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(9f)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
