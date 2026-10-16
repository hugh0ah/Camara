package com.example.camara

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Galería de CineCam: lista los vídeos grabados en `Movies/CineCam/` desde
 * MediaStore y los abre con el reproductor del sistema. No hay nada simulado: si
 * no hay clips, la lista aparece vacía con un aviso.
 */
class BmMediaActivity : AppCompatActivity() {

    private class Clip(val name: String, val uri: Uri, val sizeBytes: Long, val durationMs: Long, val dateAdded: Long) {
        val summary: String
            get() {
                val mb = sizeBytes / (1024.0 * 1024.0)
                val seconds = durationMs / 1000
                val date = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(dateAdded * 1000))
                return String.format(Locale.US, "%.1f MB · %d:%02d · %s", mb, seconds / 60, seconds % 60, date)
            }
    }

    private val clips = mutableListOf<Clip>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF050E19.toInt())
        }
        val title = TextView(this).apply {
            text = "Multimedia · Movies/CineCam"
            setTextColor(0xFFF2F4F7.toInt())
            textSize = 16f
            setPadding(28, 40, 28, 16)
        }
        root.addView(title)
        val empty = TextView(this).apply {
            text = "Todavía no hay clips grabados en Movies/CineCam"
            setTextColor(0xFF9E9E9E.toInt())
            gravity = Gravity.CENTER
            setPadding(28, 60, 28, 28)
        }
        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(empty)
        setContentView(root)

        loadClips()
        empty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        list.adapter = object : BaseAdapter() {
            override fun getCount(): Int = clips.size
            override fun getItem(position: Int): Any = clips[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val clip = clips[position]
                val row = LinearLayout(this@BmMediaActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(30, 22, 30, 22)
                }
                row.addView(TextView(this@BmMediaActivity).apply {
                    text = clip.name
                    setTextColor(0xFFF2F4F7.toInt())
                    textSize = 15f
                })
                row.addView(TextView(this@BmMediaActivity).apply {
                    text = clip.summary
                    setTextColor(0xFF9E9E9E.toInt())
                    textSize = 12f
                })
                row.setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(clip.uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (t: Throwable) {
                        android.util.Log.w("BM_MEDIA", "No hay reproductor: ${t.message}")
                    }
                }
                return row
            }
        }
    }

    private fun loadClips() {
        clips.clear()
        val projection = arrayOf(
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.RELATIVE_PATH
        )
        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("%${VideoRecorder.RELATIVE_PATH.trimEnd('/')}%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: "clip"
                    val size = cursor.getLong(1)
                    val duration = cursor.getLong(2)
                    val added = cursor.getLong(3)
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                    )
                    clips += Clip(name, uri, size, duration, added)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("BM_MEDIA", "Consulta de MediaStore falló: ${t.message}")
        }
    }
}
