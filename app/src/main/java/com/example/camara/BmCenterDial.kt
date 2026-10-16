package com.example.camara

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * Regla graduada de CineCam: 64 dp de alto, fondo negro sólido, marcas mayores
 * y menores, marcador central y botones `<` `>` de paso fino.
 *
 * Se coloca siempre por encima de la barra inferior y se trae al frente para que
 * nunca quede tapada por el visor.
 */
class BmCenterDial @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Posición en la escala (0..1) mientras se arrastra o al soltar. */
    var onValue: ((key: String, fraction: Float) -> Unit)? = null

    /** Paso fino: -1 = `<` (izquierda), +1 = `>` (derecha). */
    var onStep: ((key: String, direction: Int) -> Unit)? = null

    /** Botón AUTO del dial: alterna automático/manual del parámetro abierto. */
    var onAutoToggle: ((key: String, active: Boolean) -> Unit)? = null

    /** Si el parámetro abierto admite modo automático, se dibuja el botón AUTO. */
    var autoAvailable: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var autoActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val autoButton = RectF()

    private var key: String = ""
    private var readableValue: String = ""
    private var fraction: Float = 0f

    private val panel = RectF()
    private val leftButton = RectF()
    private val rightButton = RectF()

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        strokeWidth = dp(1f)
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = dp(1.5f)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FF41.toInt()
        strokeWidth = dp(2f)
    }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FF41.toInt()
        style = Paint.Style.FILL
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9A9A9A.toInt()
        textSize = dp(8f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.12f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FF41.toInt()
        textSize = dp(14f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val autoFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF101C2A.toInt() }
    private val autoStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val autoText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8ECF2.toInt()
        textSize = dp(10f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
        visibility = GONE
        elevation = dp(16f)
    }

    fun show(parameterKey: String, readable: String, position: Float) {
        key = parameterKey
        readableValue = readable
        fraction = position.coerceIn(0f, 1f)
        visibility = VISIBLE
        bringToFront()
        invalidate()
    }

    fun update(readable: String, position: Float) {
        readableValue = readable
        fraction = position.coerceIn(0f, 1f)
        invalidate()
    }

    fun hide() {
        visibility = GONE
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(64f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        panel.set(0f, 0f, w.toFloat(), h.toFloat())
        val button = dp(24f)
        val top = h / 2f + dp(4f) - button / 2f
        leftButton.set(dp(8f), top, dp(8f) + button, top + button)
        rightButton.set(w - dp(8f) - button, top, w - dp(8f), top + button)
        autoButton.set(w / 2f - dp(28f), dp(6f), w / 2f + dp(28f), dp(28f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (panel.isEmpty) return
        val radius = dp(8f)
        canvas.drawRoundRect(panel, radius, radius, panelPaint)
        canvas.drawRoundRect(panel, radius, radius, borderPaint)

        canvas.drawText(key.uppercase(), dp(10f), dp(14f), captionPaint)
        val valueWidth = valuePaint.measureText(readableValue)
        canvas.drawText(readableValue, width - dp(10f) - valueWidth, dp(16f), valuePaint)

        if (autoAvailable) {
            autoFill.color = if (autoActive) 0xFF2196F3.toInt() else 0xFF101C2A.toInt()
            autoText.color = if (autoActive) 0xFFFFFFFF.toInt() else 0xFFE8ECF2.toInt()
            canvas.drawRoundRect(autoButton, dp(11f), dp(11f), autoFill)
            canvas.drawRoundRect(autoButton, dp(11f), dp(11f), autoStroke)
            val baseline = autoButton.centerY() - (autoText.descent() + autoText.ascent()) / 2f
            canvas.drawText("AUTO", autoButton.centerX(), baseline, autoText)
        }

        val baseY = height - dp(10f)
        val rulerLeft = leftButton.right + dp(10f)
        val rulerRight = rightButton.left - dp(10f)
        val rulerWidth = rulerRight - rulerLeft
        if (rulerWidth <= 0f) return

        val tickCount = 40
        val step = rulerWidth / tickCount
        for (index in 0..tickCount) {
            val x = rulerLeft + index * step
            val major = index % 5 == 0
            val tickHeight = if (major) dp(12f) else dp(6f)
            canvas.drawLine(x, baseY, x, baseY - tickHeight, if (major) majorTickPaint else tickPaint)
        }
        canvas.drawLine(rulerLeft, baseY, rulerRight, baseY, tickPaint)

        val centerX = rulerLeft + fraction * rulerWidth
        canvas.drawLine(centerX, baseY - dp(18f), centerX, baseY + dp(4f), markerPaint)
        val triangle = Path().apply {
            moveTo(centerX, baseY + dp(5f))
            lineTo(centerX - dp(4f), baseY + dp(11f))
            lineTo(centerX + dp(4f), baseY + dp(11f))
            close()
        }
        canvas.drawPath(triangle, markerFill)

        drawChevron(canvas, leftButton, -1)
        drawChevron(canvas, rightButton, +1)
    }

    private fun drawChevron(canvas: Canvas, area: RectF, direction: Int) {
        canvas.drawCircle(area.centerX(), area.centerY(), area.width() / 2f, borderPaint)
        val size = dp(4.5f)
        val path = Path().apply {
            if (direction < 0) {
                moveTo(area.centerX() + size / 2f, area.centerY() - size)
                lineTo(area.centerX() - size / 2f, area.centerY())
                lineTo(area.centerX() + size / 2f, area.centerY() + size)
            } else {
                moveTo(area.centerX() - size / 2f, area.centerY() - size)
                lineTo(area.centerX() + size / 2f, area.centerY())
                lineTo(area.centerX() - size / 2f, area.centerY() + size)
            }
        }
        canvas.drawPath(path, chevronPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (autoAvailable && autoButton.contains(event.x, event.y)) {
                    autoActive = !autoActive
                    onAutoToggle?.invoke(key, autoActive)
                    return true
                }
                if (leftButton.contains(event.x, event.y)) {
                    onStep?.invoke(key, -1)
                    return true
                }
                if (rightButton.contains(event.x, event.y)) {
                    onStep?.invoke(key, +1)
                    return true
                }
                dragTo(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                dragTo(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onValue?.invoke(key, fraction)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dragTo(x: Float) {
        val rulerLeft = leftButton.right + dp(10f)
        val rulerRight = rightButton.left - dp(10f)
        val rulerWidth = rulerRight - rulerLeft
        if (rulerWidth <= 0f) return
        fraction = ((x - rulerLeft) / rulerWidth).coerceIn(0f, 1f)
        onValue?.invoke(key, fraction)
        invalidate()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
