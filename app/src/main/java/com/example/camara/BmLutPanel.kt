package com.example.camara

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.log2

/**
 * Panel de LUT de CineCam, con la estructura del de la aplicación de referencia:
 * título, una fila por LUT disponible y una escala de diafragmas.
 *
 * Las tiras de color NO son decorativas: se calculan aplicando de verdad cada LUT
 * cargada a una rampa de grises, así que muestran el resultado que dará esa LUT.
 * El marcador de la escala indica la exposición media real medida en el visor.
 */
class BmLutPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Fila del panel: la LUT cargada (o null para «sin LUT») y su estado. */
    class Row(val label: String, val lut: BmLut3D?, val selected: Boolean)

    private var rows: List<Row> = emptyList()
    private var exposureStops = 0f
    private val strips = mutableListOf<IntArray>()

    /** Nombre del juego de LUT que se está usando («Predeterminado» por defecto). */
    var title: String = "Predeterminado"
        set(value) {
            field = value
            invalidate()
        }

    var onRowTap: ((Int) -> Unit)? = null

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC050E19.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF14263A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(8.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val indexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(8.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8ECF2.toInt()
        textSize = dp(7.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99E8ECF2.toInt()
        strokeWidth = dp(1f)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E676.toInt() }
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripRect = RectF()

    init {
        isClickable = true
    }

    /** Escala en diafragmas que Blackmagic rotula bajo las tiras. */
    private val stops = floatArrayOf(-4.5f, -3.0f, -2.0f, -1.0f, -0.6f, -0.3f, 0f, 0.3f)

    /** Solo se rotulan las marcas separadas: el resto queda como tick. */
    private val labelledStops = setOf(-4.5f, -3.0f, -2.0f, -1.0f, 0f, 0.3f)

    fun setRows(rows: List<Row>) {
        this.rows = rows
        strips.clear()
        rows.forEach { row ->
            val strip = IntArray(STRIP_SAMPLES)
            for (index in 0 until STRIP_SAMPLES) {
                val grey = index * 255 / (STRIP_SAMPLES - 1)
                if (row.lut != null) {
                    val out = IntArray(3)
                    row.lut.apply(grey, grey, grey, out)
                    strip[index] = (0xFF shl 24) or (out[0] shl 16) or (out[1] shl 8) or out[2]
                } else {
                    strip[index] = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
                }
            }
            strips.add(strip)
        }
        invalidate()
    }

    /** Exposición media medida en el visor, en diafragmas sobre el gris 18 %. */
    fun setExposureStops(stops: Float) {
        if (kotlin.math.abs(stops - exposureStops) < 0.05f) return
        exposureStops = stops
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(dp(170f).toInt())
        setMeasuredDimension(width, dp(68f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, dp(6f), dp(6f), panelPaint)
        canvas.drawRoundRect(rect, dp(6f), dp(6f), borderPaint)
        canvas.drawText(title, width / 2f, dp(13f), titlePaint)

        val rowHeight = dp(11f)
        val rowTop = dp(18f)
        rows.forEachIndexed { index, row ->
            val top = rowTop + index * (rowHeight + dp(2f))
            indexPaint.color = if (row.selected) 0xFFFFFFFF.toInt() else 0xFF9AA2AC.toInt()
            canvas.drawText("${index + 1}", dp(5f), top + rowHeight - dp(2f), indexPaint)
            stripRect.set(dp(14f), top, width - dp(6f), top + rowHeight)
            val strip = strips.getOrNull(index)
            if (strip != null) {
                for (sample in strip.indices) {
                    val x0 = stripRect.left + stripRect.width() * sample / strip.size
                    val x1 = stripRect.left + stripRect.width() * (sample + 1) / strip.size
                    stripPaint.color = strip[sample]
                    canvas.drawRect(x0, stripRect.top, x1, stripRect.bottom, stripPaint)
                }
            }
            if (row.selected) {
                canvas.drawRect(
                    stripRect.left - dp(1f), stripRect.top - dp(1f),
                    stripRect.right + dp(1f), stripRect.bottom + dp(1f), borderPaint
                )
            }
        }

        // Escala de diafragmas con el marcador de exposición real.
        val scaleTop = height - dp(13f)
        val scaleBottom = height - dp(6f)
        val labelY = height - dp(1.5f)
        val spanLeft = dp(14f)
        val spanRight = width - dp(6f)
        stops.forEach { stop ->
            val fraction = (stop - MIN_STOP) / (MAX_STOP - MIN_STOP)
            val x = spanLeft + (spanRight - spanLeft) * fraction
            canvas.drawLine(x, scaleTop, x, scaleTop + dp(2f), tickPaint)
            if (stop in labelledStops) {
                canvas.drawText(formatStop(stop), x, labelY, scalePaint)
            }
        }
        val markerFraction = ((exposureStops - MIN_STOP) / (MAX_STOP - MIN_STOP)).coerceIn(0f, 1f)
        val markerX = spanLeft + (spanRight - spanLeft) * markerFraction
        canvas.drawRect(markerX - dp(1f), scaleTop - dp(3f), markerX + dp(1f), scaleBottom, markerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val rowHeight = dp(11f)
        val rowTop = dp(18f)
        rows.indices.forEach { index ->
            val top = rowTop + index * (rowHeight + dp(2f))
            if (event.y >= top && event.y <= top + rowHeight) {
                onRowTap?.invoke(index)
                return true
            }
        }
        return true
    }

    private fun formatStop(value: Float): String {
        val tenths = Math.round(value * 10f)
        return when {
            tenths == 0 -> "0"
            tenths > 0 -> "+${tenths / 10}.${tenths % 10}"
            else -> "-${-tenths / 10}.${-tenths % 10}"
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        private const val STRIP_SAMPLES = 64
        private const val MIN_STOP = -4.5f
        private const val MAX_STOP = 0.5f

        /** Diafragmas sobre el gris 18 % a partir de la luminancia media 0..255. */
        fun exposureStopsFromLuma(meanLuma: Double): Float {
            if (meanLuma <= 1.0) return MIN_STOP
            val normalized = (meanLuma / 255.0) / 0.18
            return log2(normalized).toFloat().coerceIn(MIN_STOP, MAX_STOP)
        }
    }
}
