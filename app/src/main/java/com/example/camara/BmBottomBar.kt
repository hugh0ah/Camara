package com.example.camara

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Blackmagic Camera bottom bar.
 */
class BmBottomBar @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : LinearLayout(context, attrs) {

    val mediaText: TextView
    val recordButton: View
    val formatText: TextView

    var onRecordLongPress: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(24, 16, 24, 16)
        setBackgroundColor(Color.parseColor("#0A0A0A"))

        mediaText = TextView(context).apply {
            text = "MEDIA: DISCO LIBRE"
            setTextColor(Color.parseColor("#00FF41"))
            textSize = 12f
        }
        val mediaParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        addView(mediaText, mediaParams)

        recordButton = View(context).apply {
            layoutParams = LayoutParams(192, 192).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF0000"))
            }
            setOnLongClickListener {
                onRecordLongPress?.invoke()
                true
            }
        }
        addView(recordButton)

        formatText = TextView(context).apply {
            text = "HEVC 4K"
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 12f
            gravity = Gravity.END
        }
        val formatParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        addView(formatText, formatParams)
    }

    fun setMedia(text: String, ready: Boolean) {
        mediaText.text = text
        mediaText.setTextColor(if (ready) Color.parseColor("#00FF41") else Color.parseColor("#FF0000"))
    }

    fun setRecording(recording: Boolean) {
        (recordButton.background as? GradientDrawable)?.setColor(
            if (recording) Color.parseColor("#FFFFFF") else Color.parseColor("#FF0000")
        )
    }
}