package com.example.camara

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Barra de navegación inferior de CineCam, con las cuatro pestañas de la
 * referencia: Cámara (activa), Multimedia, Mensajes y Preferencias.
 *
 * La pestaña activa lleva recuadro azul oscuro y texto/icono azul; las demás
 * quedan en gris. Los iconos son VectorDrawable propios.
 */
class BmBottomNav @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    enum class Tab(val label: String, val icon: Int) {
        CAMERA("Cámara", R.drawable.ic_nav_camera),
        MEDIA("Multimedia", R.drawable.ic_nav_media),
        MESSAGES("Mensajes", R.drawable.ic_nav_messages),
        SETTINGS("Preferencias", R.drawable.ic_nav_settings)
    }

    var onTabSelected: ((Tab) -> Unit)? = null

    private val tabs = LinkedHashMap<Tab, LinearLayout>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xFF000000.toInt())
        setPadding(dp(6), dp(6), dp(6), dp(8))

        Tab.entries.forEach { tab ->
            val item = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(4), dp(5), dp(4), dp(5))
                setOnClickListener {
                    select(tab)
                    onTabSelected?.invoke(tab)
                }
            }
            val icon = ImageView(context).apply {
                setImageResource(tab.icon)
                setColorFilter(0xFF9E9E9E.toInt())
            }
            val label = TextView(context).apply {
                text = tab.label
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTextColor(0xFF9E9E9E.toInt())
                includeFontPadding = false
                maxLines = 1
            }
            item.addView(icon, LayoutParams(dp(26), dp(26)))
            item.addView(
                label,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
            )
            addView(item, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            tabs[tab] = item
        }
        select(Tab.CAMERA)
    }

    /** Marca una pestaña como activa (recuadro azul oscuro, texto azul). */
    fun select(tab: Tab) {
        tabs.forEach { (key, item) ->
            val active = key == tab
            item.setBackgroundColor(if (active) 0xFF0A2A4A.toInt() else 0x00000000)
            val icon = item.getChildAt(0) as ImageView
            val label = item.getChildAt(1) as TextView
            val color = if (active) 0xFF2196F3.toInt() else 0xFF9E9E9E.toInt()
            icon.setColorFilter(color)
            label.setTextColor(color)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
