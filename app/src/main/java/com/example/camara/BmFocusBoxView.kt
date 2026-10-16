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
 * Recuadro temporal de enfoque (tap-to-focus).
 *
 * Dibuja esquinas de encuadre en la posición tocada del visor y se oculta solo
 * pasados dos segundos.
 *
 * Está limitada al tamaño real del vídeo: llena el contenedor `previewFrame`,
 * que MainActivity ajusta a la relación de aspecto del stream (3:4 / 9:16 en
 * vertical, 4:3 / 16:9 en horizontal). Las coordenadas que recibe son las del
 * visor; MainActivity las pasa al espacio del sensor con la matriz del visor
 * antes de construir el `MeteringRectangle`.
 */
class BmFocusBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var centerX: Float = 0.5f
    private var centerY: Float = 0.5f
    private var visibleBox: Boolean = false

    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFF00FF41.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x99FFFFFF.toInt()
    }
    private val rect = RectF()

    private val hideRunnable = Runnable {
        visibleBox = false
        invalidate()
    }

    /** Muestra el recuadro en coordenadas normalizadas (0..1) del visor. */
    fun show(normalizedX: Float, normalizedY: Float) {
        centerX = normalizedX.coerceIn(0f, 1f)
        centerY = normalizedY.coerceIn(0f, 1f)
        visibleBox = true
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, 2000)
        invalidate()
    }

    fun hide() {
        removeCallbacks(hideRunnable)
        visibleBox = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visibleBox || width == 0 || height == 0) return

        val half = min(width, height) * 0.11f
        val cx = centerX * width
        val cy = centerY * height
        rect.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRect(rect, framePaint)

        val corner = half * 0.45f
        // Esquinas superiores e inferiores
        canvas.drawLine(rect.left, rect.top, rect.left + corner, rect.top, bracketPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + corner, bracketPaint)
        canvas.drawLine(rect.right, rect.top, rect.right - corner, rect.top, bracketPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + corner, bracketPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + corner, rect.bottom, bracketPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - corner, bracketPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right - corner, rect.bottom, bracketPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - corner, bracketPaint)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
