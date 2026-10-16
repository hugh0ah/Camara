package com.example.camara

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/**
 * Superposición de la interfaz de CineCam.
 *
 * Dibuja las guías de encuadre y el resultado real de monitorización calculado
 * por [BmMonitorAnalyzer] sobre los píxeles del visor: focus peaking (Sobel),
 * zebras por umbral de IRE y falso color por luminancia. No hay bandas de
 * prueba estáticas.
 *
 * La vista está estrictamente limitada al tamaño real del vídeo: su padre
 * `previewFrame` recibe en MainActivity la relación de aspecto del stream
 * (3:4 / 9:16 en vertical, 4:3 / 16:9 en horizontal) y esta vista lo llena con
 * `match_parent`, así que las guías y la monitorización nunca sobresalen del
 * cuadro grabado.
 */
class BmOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    var guidesEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var monitorBitmap: Bitmap? = null
    private val sourceRect = Rect()
    private val destRect = android.graphics.RectF()

    /**
     * Grados que hay que girar la superposición para que coincida con el visor.
     * El analizador entrega los píxeles en la orientación del sensor (apaisada);
     * si el visor se muestra girado 90°, la superposición se gira igual.
     */
    var overlayRotation: Int = 0
        set(value) {
            field = ((value % 360) + 360) % 360
            invalidate()
        }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x59FFFFFF
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x8CFFFFFF.toInt()
    }

    private val monitorPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isAntiAlias = true
        alpha = 235
    }

    /** Bitmap de monitorización (peaking/zebras/falso color) sobre el visor. */
    fun setMonitorBitmap(bitmap: Bitmap?) {
        monitorBitmap = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        monitorBitmap?.let { bitmap ->
            sourceRect.set(0, 0, bitmap.width, bitmap.height)
            val cx = w / 2f
            val cy = h / 2f
            val rotated = overlayRotation % 180 == 90
            val boxWidth = if (rotated) h.toFloat() else w.toFloat()
            val boxHeight = if (rotated) w.toFloat() else h.toFloat()
            // Misma regla que el visor: escala uniforme que cubre el marco y
            // recorta el sobrante, para que peaking/zebras/falso color queden
            // alineados con la imagen recortada.
            val scale = maxOf(boxWidth / bitmap.width, boxHeight / bitmap.height)
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            destRect.set(cx - drawWidth / 2f, cy - drawHeight / 2f, cx + drawWidth / 2f, cy + drawHeight / 2f)
            canvas.save()
            if (rotated) canvas.rotate(overlayRotation.toFloat(), cx, cy)
            canvas.drawBitmap(bitmap, sourceRect, destRect, monitorPaint)
            canvas.restore()
        }

        if (guidesEnabled) drawGuides(canvas, w, h)
    }

    private fun drawGuides(canvas: Canvas, w: Int, h: Int) {
        val fw = w.toFloat()
        val fh = h.toFloat()

        canvas.drawLine(fw / 3f, 0f, fw / 3f, fh, guidePaint)
        canvas.drawLine(2f * fw / 3f, 0f, 2f * fw / 3f, fh, guidePaint)
        canvas.drawLine(0f, fh / 3f, fw, fh / 3f, guidePaint)
        canvas.drawLine(0f, 2f * fh / 3f, fw, 2f * fh / 3f, guidePaint)

        val cross = fw * 0.035f
        canvas.drawLine(fw / 2f - cross, fh / 2f, fw / 2f + cross, fh / 2f, centerPaint)
        canvas.drawLine(fw / 2f, fh / 2f - cross, fw / 2f, fh / 2f + cross, centerPaint)

        val insetX = fw * 0.05f
        val insetY = fh * 0.05f
        canvas.drawRect(insetX, insetY, fw - insetX, fh - insetY, guidePaint)
    }
}
