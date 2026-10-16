package com.example.camara

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.log10

/**
 * Vúmetros horizontales estéreo de CineCam.
 *
 * Dos barras (`1` y `2`) segmentadas en verde, amarillo y rojo, con marca de pico
 * y escala numérica en dBFS (-45 … +3), como el panel de la referencia.
 *
 * Los niveles son medidas reales del audio que se está grabando
 * ([VideoRecorder.getMaxAmplitude]). La plataforma entrega una única amplitud
 * mezclada, así que ambas barras muestran ese mismo valor medido y no un valor
 * inventado por canal.
 */
class BmStereoMetersView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var levelLeft = MIN_DB
    private var levelRight = MIN_DB
    private var peakLeft = MIN_DB
    private var peakRight = MIN_DB

    var title: String = "Predeterminado"
        set(value) {
            field = value
            invalidate()
        }

    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC050E19.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34C759.toInt() }
    private val yellow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFCC00.toInt() }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
    private val dimSegment = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3300FF41 }
    private val peakMark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(9f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val scaleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8ECF2.toInt()
        textSize = dp(6.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val bar = RectF()

    fun setLevels(left: Float, right: Float) {
        levelLeft = amplitudeToDb(left)
        levelRight = amplitudeToDb(right)
        peakLeft = maxOf(peakLeft * 0.94f, levelLeft)
        peakRight = maxOf(peakRight * 0.94f, levelRight)
        invalidate()
    }

    fun reset() {
        levelLeft = MIN_DB; levelRight = MIN_DB; peakLeft = MIN_DB; peakRight = MIN_DB
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(dp(210f).toInt())
        setMeasuredDimension(width, dp(58f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, dp(5f), dp(5f), panel)
        canvas.drawRoundRect(rect, dp(5f), dp(5f), border)
        canvas.drawText(title, dp(8f), dp(13f), text)

        val labelWidth = dp(11f)
        val left = dp(8f) + labelWidth
        val right = width - dp(8f)
        val barHeight = dp(8f)
        val firstTop = dp(18f)
        drawBar(canvas, "1", firstTop, left, right, barHeight, levelLeft, peakLeft)
        drawBar(canvas, "2", firstTop + barHeight + dp(4f), left, right, barHeight, levelRight, peakRight)

        // Escala numérica en dBFS
        val scaleY = height - dp(4f)
        val tickTop = scaleY - dp(7f)
        SCALE_DB.forEachIndexed { index, value ->
            val x = positionFor(value, left, right)
            canvas.drawLine(x, tickTop, x, tickTop + dp(2f), border)
            val label = if (index == SCALE_DB.lastIndex) "+${value.toInt()}" else "${value.toInt()}"
            canvas.drawText(label, x, scaleY, scaleText)
        }
    }

    private fun drawBar(
        canvas: Canvas,
        label: String,
        top: Float,
        left: Float,
        right: Float,
        height: Float,
        db: Float,
        peak: Float
    ) {
        canvas.drawText(label, dp(6f), top + height - dp(1f), text)
        bar.set(left, top, right, top + height)
        canvas.drawRoundRect(bar, dp(2f), dp(2f), dimSegment)
        val filled = positionFor(db, left, right)
        val yellowStart = positionFor(YELLOW_DB, left, right)
        val redStart = positionFor(RED_DB, left, right)
        canvas.save()
        canvas.clipRect(left, top, filled, top + height)
        canvas.drawRect(left, top, yellowStart, top + height, green)
        canvas.drawRect(yellowStart, top, redStart, top + height, yellow)
        canvas.drawRect(redStart, top, right, top + height, red)
        canvas.restore()
        val peakX = positionFor(peak, left, right)
        canvas.drawRect(peakX - dp(1f), top, peakX + dp(1f), top + height, peakMark)
    }

    private fun positionFor(db: Float, left: Float, right: Float): Float {
        val fraction = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
        return left + (right - left) * fraction
    }

    private fun amplitudeToDb(amplitude: Float): Float {
        if (amplitude <= 0.0005f) return MIN_DB
        val db = 20f * log10(amplitude.toDouble()).toFloat()
        return db.coerceIn(MIN_DB, MAX_DB)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private companion object {
        const val MIN_DB = -45f
        const val MAX_DB = 3f
        const val YELLOW_DB = -18f
        const val RED_DB = -6f
        val SCALE_DB = floatArrayOf(-45f, -30f, -20f, -10f, -6f, -3f, 0f, 3f)
    }
}
