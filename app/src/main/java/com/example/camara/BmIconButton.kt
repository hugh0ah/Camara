package com.example.camara

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Botón con pictograma propio para la barra inferior de CineCam.
 *
 * Los trazos son nuestros (formas simples: marcos, círculos y barras) y el punto
 * inferior repite el indicador de estado que usa Blackmagic: aparece cuando la
 * herramienta o el modo automático correspondiente está activo.
 */
class BmIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Glyph { MONITOR, LENS, AUTO_EV, AUTO_WB, AUTO_FOCUS }

    var glyph: Glyph = Glyph.MONITOR
        set(value) {
            field = value
            invalidate()
        }

    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
        color = 0xFFF2F4F7.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F4F7.toInt() }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66F2F4F7.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val box = RectF()
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(38f).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f - dp(2f)
        val radius = dp(9f)
        when (glyph) {
            Glyph.MONITOR -> {
                box.set(cx - radius, cy - radius * 0.72f, cx + radius, cy + radius * 0.72f)
                canvas.drawRoundRect(box, dp(3f), dp(3f), stroke)
                canvas.drawCircle(cx, cy, radius * 0.30f, stroke)
                canvas.drawCircle(cx, cy, radius * 0.12f, fill)
                canvas.drawLine(cx - radius - dp(2.5f), cy, cx - radius, cy, dim)
                canvas.drawLine(cx + radius, cy, cx + radius + dp(2.5f), cy, dim)
            }
            Glyph.LENS -> {
                canvas.drawCircle(cx, cy, radius, stroke)
                canvas.drawCircle(cx + dp(1.5f), cy + dp(1.5f), radius * 0.52f, dim)
            }
            Glyph.AUTO_EV -> {
                box.set(cx - radius, cy - radius, cx + radius, cy + radius)
                canvas.drawRoundRect(box, dp(4f), dp(4f), stroke)
                path.reset()
                path.moveTo(box.left, box.bottom)
                path.lineTo(box.right, box.top)
                canvas.drawPath(path, stroke)
                canvas.drawText("+", cx - radius * 0.45f, cy - radius * 0.18f, smallText)
                canvas.drawText("−", cx + radius * 0.42f, cy + radius * 0.72f, smallText)
            }
            Glyph.AUTO_WB -> {
                canvas.drawArc(
                    RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                    200f, 140f, false, stroke
                )
                canvas.drawArc(
                    RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                    20f, 140f, false, stroke
                )
                canvas.drawCircle(cx, cy, radius * 0.34f, stroke)
            }
            Glyph.AUTO_FOCUS -> {
                val arm = radius * 0.45f
                val corner = radius
                listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
                    val x = cx + sx * corner
                    val y = cy + sy * corner
                    canvas.drawLine(x, y, x - sx * arm, y, stroke)
                    canvas.drawLine(x, y, x, y - sy * arm, stroke)
                }
            }
        }
        if (active) {
            canvas.drawCircle(cx, height - dp(4f), dp(1.8f), dot)
        }
    }

    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2F4F7.toInt()
        textSize = dp(8f)
        textAlign = Paint.Align.CENTER
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
