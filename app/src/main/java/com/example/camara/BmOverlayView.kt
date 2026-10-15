package com.example.camara

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.View

/**
 * Blackmagic-style overlay: zebra stripes and false color.
 *
 * Both modes are pure software draws over the preview. They are visual aids only
 * and never touch the Camera2 request pipeline.
 *
 * - Zebra: 10% IRE stripes between [zebraLow, zebraHigh].
 * - False color: discrete luminance buckets mapped to BM color palette.
 */
class BmOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context) {

    var zebraEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var falseColorEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var zebraLow: Int = 75      // 0..100 IRE
    var zebraHigh: Int = 95    // 0..100 IRE

    private val zebraPaint = Paint().apply {
        color = Color.argb(90, 0x00, 0xFF, 0x41) // BM green @40%
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    private val falseColorPaint = Paint().apply {
        style = Paint.Style_FILL
        isAntiAlias = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        if (falseColorEnabled) {
            drawFalseColor(canvas, w, h)
        } else if (zebraEnabled) {
            drawZebra(canvas, w, h)
        }
    }

    private fun drawZebra(canvas: Canvas, w: Int, h: Int) {
        val stripeHeight = h / 24f
        val low = zebraLow / 100f
        val high = zebraHigh / 100f
        var y = 0f
        var on = false
        while (y < h) {
            val band = ((y / h).coerceIn(0f, 1f))
            val inZebra = band in low..high
            if (inZebra) {
                canvas.drawRect(0f, y, w.float, (y + stripeHeight).coerceAtMost(h.float), zebraPaint)
            }
            y += stripeHeight
            on = !on
        }
    }

    private fun drawFalseColor(canvas: Canvas, w: Int, h: Int) {
        // 6 luminance bands -> BM false color palette
        val bands = listOf(
            0.00f to Color.argb(150, 0x10, 0x10, 0x60), // under-exposed blue
            0.10f to Color.argb(150, 0x00, 0x40, 0xFF), // blue
            0.25f to Color.argb(150, 0x00, 0xFF, 0xFF), // cyan
            0.45f to Color.argb(150, 0x00, 0xFF, 0x41), // green
            0.70f to Color.argb(150, 0xFF, 0xAE, 0x00), // amber
            0.90f to Color.argb(150, 0xFF, 0x00, 0x00)  // over-exposed red
        )
        val bandH = h / bands.size
        bands.forEachIndexed { i, (_, color) ->
            falseColorPaint.color = color
            canvas.drawRect(0f, (i * bandH).float, w.float, ((i + 1) * bandH).float, falseColorPaint)
        }
    }
}