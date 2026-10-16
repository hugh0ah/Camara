package com.example.camara

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.min

/**
 * Botón de grabación dibujado a mano por CineCam.
 *
 * En reposo muestra un círculo rojo con anillo fino; al grabar pasa a un
 * cuadrado redondeado con un anillo de aviso, que es el lenguaje visual
 * habitual de las cámaras de cine.
 */
class BmRecordButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0x99FFFFFF.toInt()
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0x40FF3B30.toInt()
    }

    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_REC_IDLE
    }

    var recording: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(72f).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val outerRadius = min(width, height) / 2f - ringPaint.strokeWidth

        canvas.drawCircle(cx, cy, outerRadius, ringPaint)

        val inset = width * 0.17f
        if (recording) {
            innerPaint.color = COLOR_REC
            val half = width / 2f - inset
            val square = RectF(cx - half, cy - half, cx + half, cy + half)
            val corner = half * 0.30f
            canvas.drawRoundRect(square, corner, corner, innerPaint)
            canvas.drawCircle(cx, cy, outerRadius - dp(3f), pulsePaint)
        } else {
            innerPaint.color = COLOR_REC_IDLE
            canvas.drawCircle(cx, cy, width / 2f - inset, innerPaint)
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        )

    private companion object {
        const val COLOR_REC = 0xFFFF1A1A.toInt()
        const val COLOR_REC_IDLE = 0xFF9E1B1B.toInt()
    }
}
