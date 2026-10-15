package com.example.camara

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Blackmagic Camera selector widget.
 *
 * Renders a monospaced, condensed numeric label. When active, a thin green frame
 * surrounds the label (BM palette: #00FF41). Tapping the widget notifies the host
 * so the center dial can be re-targeted.
 */
class BmSelector @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val label: TextView
    private val frame: View

    var value: String = ""
        set(text) {
            field = text
            label.text = text
        }

    var active: Boolean = false
        set(value) {
            field = value
            frame.visibility = if (value) View.VISIBLE else View.GONE
            label.setTextColor(if (value) 0xFF00FF41.toInt() else 0xFF888888.toInt())
        }

    init {
        // Thin green frame (hidden by default)
        frame = View(context).apply {
            id = R.id.bm_selector_frame
            visibility = View.GONE
            background = ContextCompat.getDrawable(context, R.drawable.bm_selector_frame)
        }
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Condensed monospaced label
        label = TextView(context).apply {
            id = R.id.bm_selector_label
            setTextColor(0xFF888888.toInt())
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}