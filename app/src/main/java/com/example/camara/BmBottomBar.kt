package com.example.camara

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Botón con icono vectorial y punto de estado, como los de la fila del disparador
 * de la referencia: el punto inferior se enciende cuando la función está activa.
 */
class BmIconToggle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val icon = ImageView(context)
    private val dot = View(context)

    var active: Boolean = false
        set(value) {
            field = value
            dot.visibility = if (value) VISIBLE else INVISIBLE
            icon.setColorFilter(if (value) 0xFFFFFFFF.toInt() else 0xFFE8ECF2.toInt())
        }

    init {
        isClickable = true
        isFocusable = true
        icon.layoutParams = LayoutParams(dp(26), dp(26), Gravity.CENTER)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(icon)
        dot.setBackgroundColor(0xFFFFFFFF.toInt())
        addView(dot, LayoutParams(dp(4), dp(4), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(1)
        })
        active = false
    }

    fun setIcon(resId: Int) {
        icon.setImageResource(resId)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}

/**
 * Fila de controles del disparador, con la distribución de la referencia:
 *
 *   [guías] [LUT] [enfoque puntual]      ( GRABAR )      [EV A] [AF A] [pantalla limpia]
 *
 * Los iconos son VectorDrawable propios.
 */
class BmBottomBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    val recordButton: BmRecordButton
    val guidesButton: BmIconToggle
    val lutButton: BmLutBadge
    val focusTargetButton: BmIconToggle
    val exposureButton: BmIconToggle
    val autoFocusButton: BmIconToggle
    val cleanScreenButton: BmIconToggle

    /** Bloqueo de exposición y balance, portado de OpenCamera. */
    val aeLockButton: BmIconToggle
    val awbLockButton: BmIconToggle

    /** Estabilización óptica del objetivo. */
    val oisButton: BmIconToggle

    var onGuides: (() -> Unit)? = null
    var onLutToggle: (() -> Unit)? = null
    var onFocusTarget: (() -> Unit)? = null
    var onExposureToggle: (() -> Unit)? = null
    var onAutoFocusToggle: (() -> Unit)? = null
    var onCleanScreen: (() -> Unit)? = null
    var onAeLock: (() -> Unit)? = null
    var onAwbLock: (() -> Unit)? = null
    var onOisToggle: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(10))
        setBackgroundColor(0xFF050E19.toInt())

        val startCluster = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        guidesButton = BmIconToggle(context).apply {
            setIcon(R.drawable.ic_bm_guides)
            setOnClickListener { onGuides?.invoke() }
        }
        lutButton = BmLutBadge(context).apply {
            isClickable = true
            setOnClickListener { onLutToggle?.invoke() }
        }
        focusTargetButton = BmIconToggle(context).apply {
            setIcon(R.drawable.ic_bm_focus_target)
            setOnClickListener { onFocusTarget?.invoke() }
        }
        startCluster.addView(guidesButton, LayoutParams(dp(40), dp(40)))
        startCluster.addView(lutButton, LayoutParams(dp(32), dp(22)).apply { marginStart = dp(8) })
        startCluster.addView(
            focusTargetButton,
            LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) }
        )
        addView(startCluster, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        recordButton = BmRecordButton(context)
        addView(recordButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val endCluster = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        exposureButton = BmIconToggle(context).apply {
            setIcon(R.drawable.ic_bm_ev)
            setOnClickListener { onExposureToggle?.invoke() }
        }
        autoFocusButton = BmIconToggle(context).apply {
            setIcon(R.drawable.ic_bm_af)
            setOnClickListener { onAutoFocusToggle?.invoke() }
        }
        cleanScreenButton = BmIconToggle(context).apply {
            setIcon(R.drawable.ic_bm_expand)
            setOnClickListener { onCleanScreen?.invoke() }
        }
        endCluster.addView(exposureButton, LayoutParams(dp(40), dp(40)))
        endCluster.addView(autoFocusButton, LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
        endCluster.addView(cleanScreenButton, LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })

        // Bloqueos AE/AWB y estabilización óptica: mismo icono vectorial propio,
        // con área táctil de 40 dp ampliada a 48 dp por TouchDelegate.
        aeLockButton = BmIconToggle(context).apply {
            setIcon(R.drawable.ic_bm_ev)
            setOnClickListener { onAeLock?.invoke() }
        }
        awbLockButton = BmIconToggle(context).apply {
            setIcon(R.drawable.ic_bm_af)
            setOnClickListener { onAwbLock?.invoke() }
        }
        oisButton = BmIconToggle(context).apply {
            setIcon(R.drawable.ic_bm_stabilizer)
            setOnClickListener { onOisToggle?.invoke() }
        }
        endCluster.addView(aeLockButton, LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
        endCluster.addView(awbLockButton, LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
        endCluster.addView(oisButton, LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })

        addView(
            endCluster,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.END }
        )

        // Respuesta al primer toque en todos los botones de la fila.
        listOf(
            guidesButton, focusTargetButton, exposureButton,
            autoFocusButton, cleanScreenButton, aeLockButton, awbLockButton, oisButton
        ).forEach { button ->
            button.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                expandTouchTarget(view)
            }
        }
    }

    /** Amplía el área sensible de un botón hasta 48 dp sin moverlo en pantalla. */
    private fun expandTouchTarget(view: View) {
        if (view.width <= 0 || view.height <= 0) return
        val minimum = dp(48)
        val extraV = ((minimum - view.height) / 2).coerceAtLeast(0)
        val extraH = ((minimum - view.width) / 2).coerceAtLeast(0)
        if (extraV == 0 && extraH == 0) return
        view.touchDelegate = android.view.TouchDelegate(
            android.graphics.Rect(-extraH, -extraV, view.width + extraH, view.height + extraV),
            view
        )
    }

    fun setRecording(recording: Boolean) {
        recordButton.recording = recording
    }

    /** Refresca los puntos de estado de los botones. */
    fun setStates(
        monitorsActive: Boolean,
        lutEnabled: Boolean,
        exposureAuto: Boolean,
        focusAuto: Boolean,
        cleanScreen: Boolean,
        aeLocked: Boolean = false,
        awbLocked: Boolean = false,
        oisActive: Boolean = false
    ) {
        guidesButton.active = monitorsActive
        lutButton.active = lutEnabled
        exposureButton.active = exposureAuto
        autoFocusButton.active = focusAuto
        cleanScreenButton.active = cleanScreen
        aeLockButton.active = aeLocked
        awbLockButton.active = awbLocked
        oisButton.active = oisActive
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}

/** Punto de estado reutilizable. */
internal class BmDotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    override fun onDraw(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, paint)
    }
}
