package com.example.camara

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout

/**
 * Blackmagic Camera top bar: LENS / FPS / SHTR / ISO / WB selectors.
 *
 * Each selector is a monospaced, condensed numeric label. The active selector
 * gets a thin green frame. Tapping a selector opens the center dial.
 */
class BmTopBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    val lensSelector: BmSelector
    val fpsSelector: BmSelector
    val shtrSelector: BmSelector
    val isoSelector: BmSelector
    val wbSelector: BmSelector

    private val selectors: List<BmSelector>

    var onSelectorTap: ((key: String) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16, 40, 16, 8)

        fun add(key: String): BmSelector {
            val sel = BmSelector(context).apply {
                tag = key
                value = key
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onSelectorTap?.invoke(key) }
            }
            addView(sel)
            return sel
        }

        lensSelector = add("LENS")
        fpsSelector = add("FPS")
        shtrSelector = add("SHTR")
        isoSelector = add("ISO")
        wbSelector = add("WB")

        selectors = listOf(lensSelector, fpsSelector, shtrSelector, isoSelector, wbSelector)
    }

    fun setActive(key: String) {
        selectors.forEach { it.active = it.tag == key }
    }

    fun setLens(text: String) { lensSelector.value = text }
    fun setFps(text: String) { fpsSelector.value = text }
    fun setShtr(text: String) { shtrSelector.value = text }
    fun setIso(text: String) { isoSelector.value = text }
    fun setWb(text: String) { wbSelector.value = text }
}