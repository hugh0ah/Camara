package com.example.camara

import android.content.Context
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Blackmagic Camera center dial.
 *
 * Floating, centered SeekBar that appears when a top-bar selector is tapped.
 * Supports ISO, SHTR, WB, and ZOOM modes. Each mode maps its 0..100 progress
 * to the physical range and updates the host via onValue.
 *
 * The dial is intentionally a single SeekBar (not a rotary encoder) so it works
 * with touch-only devices; the visual ring + label communicates "dial" semantics.
 */
class BmCenterDial @JvmOverloads constructor(
    context: Context,
    attrs: android.util AttributeSet? = null
) : FrameLayout(context, attrs) {

    var mode: String = "NONE"
        set(value) {
            field = value
            label.text = modeLabel(value)
            when (value) {
                "ISO" -> setRange(0, 100, 50)
                "SHTR" -> setRange(0, 100, 50)
                "WB" -> setRange(0, 100, 50)
                "ZOOM" -> setRange(0, 100, 50)
                else -> setRange(0, 100, 0)
            }
            visibility = if (value == "NONE") View.GONE else View.VISIBLE
        }

    var onValue: ((mode: String, raw: Int, display: String) -> Unit)? = null

    private val bar: SeekBar
    private val label: TextView

    init {
        visibility = View.GONE

        label = TextView(context).apply {
            setTextColor(0xFF00FF41.toInt())
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        addView(label, LayoutParams(MATCH_PARENT, WRAP_CONTENT, android.view.Gravity.CENTER))

        bar = SeekBar(context).apply {
            id = R.id.bm_dial_bar
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val disp = formatDisplay(p)
                    onValue?.invoke(mode, p, disp)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        addView(bar, LayoutParams(MATCH_PARENT, WRAP_CONTENT, android.view.Gravity.CENTER))
    }

    private fun setRange(min: Int, max: Int, progress: Int) {
        bar.max = max - min
        bar.progress = progress - min
    }

    private fun modeLabel(m: String): String = when (m) {
        "ISO" -> "ISO"
        "SHTR" -> "SHTR"
        "WB" -> "WB"
        "ZOOM" -> "ZOOM"
        else -> ""
    }

    private fun formatDisplay(p: Int): String = when (mode) {
        "ISO" -> "${50 + (p * 6350 / 100)}"
        "SHTR" -> {
            val ns = (20000000L * 100 / (p + 1))
            val frac = 1000000000L / ns
            "1/$frac"
        }
        "WB" -> "${3000 + (p * 7000 / 100)}K"
        "ZOOM" -> "${100 - p}%"
        else -> "$p"
    }

    fun setProgress(p: Int) { bar.progress = p }

    fun show(m: String) { mode = m }
    fun hide() { mode = "NONE" }
}