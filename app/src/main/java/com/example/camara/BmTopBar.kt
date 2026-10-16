package com.example.camara

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * Barra de parámetros de CineCam, con los mismos rótulos y la misma disposición
 * que la segunda fila de Blackmagic Camera:
 *
 *   OBJ. · F/S · OBT. · AP. · ISO · BAL. B. · TIN.   (+ FOCUS cuando hay MF)
 *
 * El parámetro activo se subraya en verde solo mientras su regla graduada está
 * abierta. AP. es informativo (apertura fija, atenuado) y FOCUS alterna AF/MF:
 * en AF la caja desaparece, igual que en la aplicación de referencia.
 */
class BmTopBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    val lensSelector: BmSelector
    val fpsSelector: BmSelector
    val shtrSelector: BmSelector
    val irisSelector: BmSelector
    val isoSelector: BmSelector
    val wbSelector: BmSelector
    val tintSelector: BmSelector
    val focusSelector: BmSelector

    private val selectors: List<BmSelector>

    var onSelectorTap: ((key: String) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4f), dp(3f), dp(4f), dp(3f))

        fun add(key: String, caption: String, clickable: Boolean): BmSelector {
            val selector = BmSelector(context).apply {
                this.caption = caption
                tag = key
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                isClickable = clickable
                if (clickable) setOnClickListener { onSelectorTap?.invoke(key) }
            }
            addView(selector)
            return selector
        }

        lensSelector = add("LENS", "OBJ.", true)
        fpsSelector = add("FPS", "F/S", true)
        shtrSelector = add("SHUTTER", "OBT.", true)
        irisSelector = add("IRIS", "AP.", false).apply { dimmed = true }
        isoSelector = add("ISO", "ISO", true)
        wbSelector = add("WB", "BAL. B.", true)
        tintSelector = add("TINT", "TIN.", true)
        focusSelector = add("FOCUS", "FOCUS", true)

        selectors = listOf(
            lensSelector, fpsSelector, shtrSelector, irisSelector,
            isoSelector, wbSelector, tintSelector, focusSelector
        )
    }

    /** Marca el parámetro cuya regla está abierta; cadena vacía = ninguno. */
    fun setActive(key: String) {
        selectors.forEach { it.active = it.tag == key }
    }

    /**
     * Vuelca el estado real de los ajustes. `autoExposure` y `autoWb` encienden
     * el chip azul «A» de los parámetros que el sensor está resolviendo solo.
     */
    fun setValues(
        lens: String,
        fps: String,
        shutter: String,
        iris: String,
        iso: String,
        wb: String,
        tint: String,
        focus: String,
        autoExposure: Boolean,
        autoWb: Boolean,
        manualFocus: Boolean
    ) {
        lensSelector.value = lens
        fpsSelector.value = fps
        shtrSelector.value = shutter
        irisSelector.value = iris
        isoSelector.value = iso
        wbSelector.value = wb
        tintSelector.value = tint
        focusSelector.value = focus

        shtrSelector.auto = autoExposure
        isoSelector.auto = autoExposure
        wbSelector.auto = autoWb

        focusSelector.visibility = if (manualFocus) View.VISIBLE else View.GONE
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
