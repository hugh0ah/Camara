package com.example.camara

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * Blackmagic Camera top bar: LENS / FPS / SHTR / ISO / WB selectors.
 *
 * Each selector is a monospaced, condensed numeric label. The active selector
 * gets a thin green frame. Tapping a selector opens the center dial.
 */
class BmTopBar @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    val lensSelector: BmSelector
    val fpsSelector: BmSelector
    val shtrSelector: BmSelector
    val isoSelector: BmSelector
    val wbSelector: BmSelector

    var onSelectorTap: ((key: String) -> Unit)? = null

    init {
        setPadding(0, 24, 0, 0)
        val lp = LayoutParams(0, LayoutParams.WRAP_CONTENT)
        val ids = intArrayOf(
            R.id.bm_lens, R.id.bm_fps, R.id.bm_shtr, R.id.bm_iso, R.id.bm_wb
        )
        ids.forEach { id ->
            val v = BmSelector(context).apply { this.id = id }
            addView(v, lp)
        }

        lensSelector = findViewById(R.id.bm_lens)
        fpsSelector = findViewById(R.id.bm_fps)
        shtrSelector = findViewById(R.id.bm_shtr)
        isoSelector = findViewById(R.id.bm_iso)
        wbSelector = findViewById(R.id.bm_wb)

        // Chain the selectors horizontally
        val chain = intArrayOf(
            R.id.bm_lens, R.id.bm_fps, R.id.bm_shtr, R.id.bm_iso, R.id.bm_wb
        )
        chain.forEachIndexed { i, id ->
            val left = if (i == 0) LayoutParams.PARENT_ID else chain[i - 1]
            val right = if (i == chain.size - 1) LayoutParams.PARENT_ID else chain[i + 1]
            ( findViewById<View>(id).layoutParams as LayoutParams).apply {
                startToStart = left
                endToEnd = right
                horizontalChainStyle = ConstraintLayout.CHAIN_SPREAD
            }
        }

        bind(lensSelector, "LENS") { onSelectorTap?.invoke("LENS") }
        bind(fpsSelector, "FPS") { onSelectorTap?.invoke("FPS") }
        bind(shtrSelector, "SHTR") { onSelectorTap?.invoke("SHTR") }
        bind(isoSelector, "ISO") { onSelectorTap?.invoke("ISO") }
        bind(wbSelector, "WB") { onSelectorTap?.invoke("WB") }
    }

    private fun bind(sel: BmSelector, key: String, tap: () -> Unit) {
        sel.tag = key
        sel.setOnClickListener { tap() }
    }

    fun setActive(key: String) {
        listOf(lensSelector, fpsSelector, shtrSelector, isoSelector, wbSelector).forEach {
            it.setActive(it.tag == key)
        }
    }

    fun setLens(text: String) { lensSelector.setValue(text) }
    fun setFps(text: String) { fpsSelector.setValue(text) }
    fun setShtr(text: String) { shtrSelector.setValue(text) }
    fun setIso(text: String) { isoSelector.setValue(text) }
    fun setWb(text: String) { wbSelector.setValue(text) }
}