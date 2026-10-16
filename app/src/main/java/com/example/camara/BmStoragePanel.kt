package com.example.camara

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Icono del dispositivo que Blackmagic dibuja junto al tiempo restante: el
 * contorno de un teléfono con su cámara frontal. Dibujado por nosotros.
 */
class BmDeviceIcon @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : android.view.View(context, attrs) {

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        color = 0xFFFFFFFF.toInt()
    }
    private val body = RectF()

    override fun onDraw(canvas: Canvas) {
        val radius = dp(4f)
        body.set(dp(1.6f), dp(1.6f), width - dp(1.6f), height - dp(1.6f))
        canvas.drawRoundRect(body, radius, radius, outline)
        val notch = dp(2.6f)
        val cy = dp(5f)
        canvas.drawLine(
            width / 2f - notch, cy, width / 2f + notch, cy,
            outline
        )
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}

/**
 * Panel de almacenamiento y batería, sobre la esquina inferior izquierda del
 * visor, igual que en Blackmagic Camera: tiempo de grabación restante, barra de
 * batería y espacio libre. Todos los valores son reales: el tiempo restante sale
 * del espacio libre y del caudal de grabación configurado.
 */
class BmStoragePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val remaining: TextView
    private val batteryText: TextView
    private val freeText: TextView
    private val batteryBar: android.view.View

    init {
        orientation = VERTICAL
        minimumWidth = dp(168)
        setPadding(dp(9), dp(7), dp(9), dp(7))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xCC050E19.toInt())
            cornerRadius = dp(7).toFloat()
        }

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = BmDeviceIcon(context)
        row.addView(icon, LayoutParams(dp(15), dp(26)))
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            minimumWidth = dp(130)
        }
        remaining = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
        }
        column.addView(remaining)
        batteryBar = android.view.View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF4696E9.toInt())
                cornerRadius = dp(1).toFloat()
            }
        }
        column.addView(
            batteryBar,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(3) }
        )
        val footer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        batteryText = TextView(context).apply {
            textSize = 10f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
        }
        freeText = TextView(context).apply {
            textSize = 10f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.END
            includeFontPadding = false
        }
        footer.addView(batteryText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(freeText)
        column.addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        row.addView(
            column,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        )
        addView(row)

        update(0, 0, 0L)
    }

    /** Tiempo restante, porcentaje de batería y espacio libre reales. */
    fun update(remainingSeconds: Long, batteryPercent: Int, freeBytes: Long) {
        val hours = remainingSeconds / 3600
        val minutes = (remainingSeconds % 3600) / 60
        val seconds = remainingSeconds % 60
        remaining.text = String.format(
            java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds
        )
        batteryText.text = "$batteryPercent%"
        freeText.text = "${freeBytes / (1024L * 1024L * 1024L)}GB"
        val level = (batteryPercent / 100f).coerceIn(0f, 1f)
        batteryBar.post {
            val full = batteryBar.parent?.let { (it as android.view.View).width } ?: 0
            if (full > 0) {
                val params = batteryBar.layoutParams
                params.width = (full * level).toInt().coerceAtLeast(dp(2))
                batteryBar.layoutParams = params
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
