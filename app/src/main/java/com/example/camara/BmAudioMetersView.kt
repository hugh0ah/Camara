package com.example.camara

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.log10
import kotlin.math.max

/**
 * Vúmetros de audio L/R de CineCam.
 *
 * Reciben la amplitud real del codificador (`MediaRecorder.maxAmplitude`) y la
 * dibujan en escala dBFS con retención de pico.
 */
class BmAudioMetersView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var leftLevel = 0f
    private var rightLevel = 0f
    private var peakLeft = 0f
    private var peakRight = 0f

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val safePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E676.toInt() }
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE600.toInt() }
    private val hotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A8A8A.toInt()
        textSize = dp(7.5f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    /** Amplitudes lineales 0..1 tomadas del codificador de audio. */
    fun setLevels(left: Float, right: Float) {
        leftLevel = levelToMeter(left)
        rightLevel = levelToMeter(right)
        peakLeft = max(peakLeft * 0.97f, leftLevel)
        peakRight = max(peakRight * 0.97f, rightLevel)
        invalidate()
    }

    fun reset() {
        leftLevel = 0f; rightLevel = 0f; peakLeft = 0f; peakRight = 0f
        invalidate()
    }

    private fun levelToMeter(amplitude: Float): Float {
        val clamped = amplitude.coerceIn(0.00001f, 1f)
        val db = 20f * log10(clamped.toDouble()).toFloat()
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(40f).toInt(), dp(96f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, dp(4f), dp(4f), panelPaint)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), borderPaint)

        val barsTop = dp(6f)
        val barsBottom = height - dp(12f)
        val barWidth = dp(7f)
        val gap = dp(9f)
        val startX = (width - (barWidth * 2 + gap)) / 2f

        drawBar(canvas, startX, barsTop, barsBottom, barWidth, leftLevel, peakLeft)
        drawBar(canvas, startX + barWidth + gap, barsTop, barsBottom, barWidth, rightLevel, peakRight)

        val centerX = width / 2f
        canvas.drawText("L", startX + barWidth / 2f, height - dp(3f), captionPaint)
        canvas.drawText("R", startX + barWidth + gap + barWidth / 2f, height - dp(3f), captionPaint)
        val db = ((leftLevel * 60f) - 60f).toInt()
        canvas.drawText("$db", centerX, dp(8f), captionPaint)
    }

    private fun drawBar(
        canvas: Canvas,
        x: Float,
        top: Float,
        bottom: Float,
        width: Float,
        level: Float,
        peak: Float
    ) {
        val height = bottom - top
        val filled = height * level
        val segments = 3
        val segmentHeight = height / segments
        for (index in 0 until segments) {
            val segmentTop = bottom - (index + 1) * segmentHeight
            val paint = when (index) {
                0 -> safePaint
                1 -> warnPaint
                else -> hotPaint
            }
            paint.alpha = 60
            canvas.drawRect(x, segmentTop, x + width, segmentTop + segmentHeight - dp(1f), paint)
        }
        // Relleno real desde abajo
        val filledTop = bottom - filled
        var y = bottom
        while (y > filledTop) {
            val segmentTop = y - segmentHeight
            val index = ((bottom - y) / segmentHeight).toInt().coerceIn(0, segments - 1)
            val paint = when (index) {
                0 -> safePaint
                1 -> warnPaint
                else -> hotPaint
            }
            val color = when (index) {
                0 -> 0xFF00E676.toInt()
                1 -> 0xFFFFE600.toInt()
                else -> 0xFFFF3B30.toInt()
            }
            paint.color = color
            canvas.drawRect(x, max(filledTop, segmentTop), x + width, y - dp(1f), paint)
            y = segmentTop
        }
        val peakY = bottom - height * peak
        canvas.drawRect(x, peakY - dp(1f), x + width, peakY, peakPaint)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
