package com.example.camara

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Botón LUT de la cabecera: rectángulo redondeado con anillo degradado y el
 * rótulo «LUT», equivalente al de la primera fila de Blackmagic Camera.
 *
 * El dibujo es propio (formas y tipografía del sistema): no se extrae ningún
 * recurso gráfico de la aplicación original.
 */
class BmLutBadge @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0E1A28.toInt()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = dp(9.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val box = RectF()

    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = ring.strokeWidth / 2f
        box.set(inset, inset, w - inset, h - inset)
        ring.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFE600.toInt(),
                0xFF34C759.toInt(), 0xFF00C7BE.toInt(), 0xFF007AFF.toInt(),
                0xFFAF52DE.toInt()
            ),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dp(4f)
        canvas.drawRoundRect(box, radius, radius, fill)
        canvas.drawRoundRect(box, radius, radius, ring)
        val baseline = height / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText("LUT", width / 2f, baseline, text)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}

/**
 * Primera fila de la interfaz, sobre fondo sólido:
 *
 *   [LUT]                    4K                00:00:00:00
 *                          OG 4:3
 *
 * El indicador de formato y el timecode son dados reales: el primero refleja el
 * tamaño configurado y el segundo el tiempo de grabación en curso.
 */
class BmHeaderBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    val lutButton: BmLutBadge
    private val bigFormat: TextView
    private val smallFormat: TextView
    val timecodeView: TextView

    var onLutClick: (() -> Unit)? = null
    var onFormatClick: (() -> Unit)? = null

    init {
        setBackgroundColor(COLOR_BAR)

        lutButton = BmLutBadge(context).apply {
            layoutParams = LayoutParams(dp(26), dp(18), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = dp(16) }
            isClickable = true
            setOnClickListener { onLutClick?.invoke() }
        }
        addView(lutButton)

        val stack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        bigFormat = TextView(context).apply {
            text = "4K"
            textSize = 13f
            setTextColor(COLOR_BIG_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            background = pill(COLOR_BIG_BG, 0, 3f)
            setPadding(dp(7), dp(1), dp(7), dp(1))
            isClickable = true
            setOnClickListener { onFormatClick?.invoke() }
        }
        smallFormat = TextView(context).apply {
            text = "OG 4:3"
            textSize = 9f
            setTextColor(COLOR_SMALL_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            background = pill(COLOR_SMALL_BG, 0xFFFFFFFF.toInt(), 3f)
            setPadding(dp(8), dp(1), dp(8), dp(1))
            isClickable = true
            setOnClickListener { onFormatClick?.invoke() }
        }
        stack.addView(bigFormat)
        stack.addView(
            smallFormat,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(1) }
        )

        timecodeView = TextView(context).apply {
            text = "00:00:00:00"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
            letterSpacing = 0.01f
        }

        // Badge y timecode forman un grupo centrado, como en la referencia.
        val centerGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        }
        centerGroup.addView(stack)
        centerGroup.addView(
            timecodeView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
        )
        addView(centerGroup)
    }

    fun setFormat(main: String, sub: String) {
        bigFormat.text = main
        smallFormat.text = sub
    }

    fun setTimecode(text: String) {
        if (timecodeView.text != text) timecodeView.text = text
    }

    fun setLutActive(active: Boolean) {
        lutButton.active = active
    }

    private fun pill(color: Int, strokeColor: Int, radiusDp: Float): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != 0) setStroke(dp(1), strokeColor)
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        const val COLOR_BAR = 0xFF050E19.toInt()
        private const val COLOR_BIG_BG = 0xFFFFFFFF.toInt()
        private const val COLOR_BIG_TEXT = 0xFF0A0A0A.toInt()
        private const val COLOR_SMALL_BG = 0xFF0B1521.toInt()
        private const val COLOR_SMALL_TEXT = 0xFFE8ECF2.toInt()
    }
}
