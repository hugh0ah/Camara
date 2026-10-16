package com.example.camara

import android.content.Context
import java.io.File

/**
 * Salida de diagnóstico a un archivo legible desde `adb`.
 *
 * En este dispositivo (Vivo X300 / HyperOS) el `logcat` no entrega las líneas de
 * la aplicación, así que las comprobaciones en vivo se vuelcan aquí y se leen
 * con:
 *
 *   adb shell cat /sdcard/Android/data/com.example.camara/files/bm-diag.log
 *
 * El directorio es propiedad de la app, no requiere permisos y desaparece al
 * desinstalarla.
 */
object BmDiagnostics {

    private const val FILE_NAME = "bm-diag.log"
    private const val COMMAND_FILE_NAME = "bm-cmd.txt"
    private const val MAX_BYTES = 64 * 1024L

    fun file(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    /**
     * Comandos lanzados desde `adb` sin depender de `am broadcast`:
     *
     *   adb shell 'echo wb:5600 > /sdcard/Android/data/com.example.camara/files/bm-cmd.txt'
     *
     * La app lee y borra el archivo en cada ciclo, así que un `echo` basta para
     * pilotarla en una verificación en vivo.
     */
    fun takeCommand(context: Context): String? = try {
        val source = File(context.getExternalFilesDir(null) ?: context.filesDir, COMMAND_FILE_NAME)
        if (!source.exists() || source.length() == 0L) {
            null
        } else {
            val command = source.readText().trim()
            source.delete()
            command.ifEmpty { null }
        }
    } catch (t: Throwable) {
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        null
    }

    fun clear(context: Context) {
        runCatching { file(context).writeText("") }
    }

    fun write(context: Context, line: String) {
        try {
            val target = file(context)
            if (target.length() > MAX_BYTES) target.writeText("")
            target.appendText(line + "\n")
            lastError = null
            lastPath = target.absolutePath
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** Último error de escritura, para diagnosticar el canal desde la propia app. */
    @Volatile
    var lastError: String? = null

    /** Ruta realmente usada por el canal de diagnóstico. */
    @Volatile
    var lastPath: String? = null
}
