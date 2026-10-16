package com.example.camara

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Panel de histograma de CineCam, con el mismo aspecto que el de la aplicación
 * de referencia: rótulo del espacio de color («Rec. 709»), histograma real de los
 * tres canales sobre los píxeles del visor y una columna de nivel a la derecha.
 */
class BmHistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var red = IntArray(256)
    private var green = IntArray(256)
    private var blue = IntArray(256)
    private var peak = 1
    private var clipFraction = 0f
    var spaceLabel: String = "Rec. 709"
        set(value) {
            field = value
            invalidate()
        }

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC050E19.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF14263A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(8.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FF3B30.toInt() }
    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x9900E676.toInt() }
    private val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99007AFF.toInt() }
    private val meterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0xFF9AA2AC.toInt()
    }
    private val meterFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8ED0FF.toInt() }
    private val chart = RectF()

    /** Histogramas reales de los tres canales sobre el fotograma del visor. */
    fun update(snapshot: BmMonitorAnalyzer.Snapshot) {
        red = snapshot.redHistogram
        green = snapshot.greenHistogram
        blue = snapshot.blueHistogram
        clipFraction = snapshot.clippedFraction
        peak = maxOf(
            red.maxOrNull() ?: 1,
            green.maxOrNull() ?: 1,
            blue.maxOrNull() ?: 1,
            1
        )
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(dp(150f).toInt())
        setMeasuredDimension(width, dp(46f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, dp(6f), dp(6f), panelPaint)
        canvas.drawRoundRect(rect, dp(6f), dp(6f), borderPaint)
        canvas.drawText(spaceLabel, dp(7f), dp(13f), captionPaint)

        val meterWidth = dp(11f)
        val meterLeft = width - meterWidth - dp(6f)
        chart.set(dp(7f), dp(18f), meterLeft - dp(6f), height - dp(5f))
        if (chart.height() <= 0f || chart.width() <= 0f) return

        drawChannel(canvas, red, redPaint)
        drawChannel(canvas, green, greenPaint)
        drawChannel(canvas, blue, bluePaint)

        // Columna de nivel: proporción real de píxeles en zona de recorte.
        val meterTop = chart.top
        val meterBottom = chart.bottom
        val meterRect = RectF(meterLeft, meterTop, meterLeft + meterWidth, meterBottom)
        canvas.drawRoundRect(meterRect, dp(3f), dp(3f), meterPaint)
        val level = (clipFraction * 8f).coerceIn(0f, 1f)
        if (level > 0.01f) {
            val fillTop = meterBottom - (meterBottom - meterTop) * level
            val fillRect = RectF(meterLeft + dp(1.5f), fillTop, meterLeft + meterWidth - dp(1.5f), meterBottom - dp(1.5f))
            canvas.drawRoundRect(fillRect, dp(2f), dp(2f), meterFill)
        }
        val segment = (meterBottom - meterTop) / 4f
        for (index in 1..3) {
            val y = meterTop + segment * index
            canvas.drawLine(meterLeft + dp(1.5f), y, meterLeft + meterWidth - dp(1.5f), y, borderPaint)
        }
    }

    private fun drawChannel(canvas: Canvas, values: IntArray, paint: Paint) {
        val barWidth = chart.width() / values.size
        values.forEachIndexed { index, value ->
            if (value == 0) return@forEachIndexed
            val normalized = (value.toFloat() / peak).coerceIn(0f, 1f)
            val barHeight = chart.height() * normalized
            val x = chart.left + index * barWidth
            canvas.drawRect(x, chart.bottom - barHeight, x + barWidth.coerceAtLeast(1f), chart.bottom, paint)
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
