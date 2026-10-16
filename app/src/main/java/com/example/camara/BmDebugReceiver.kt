package com.example.camara

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Puente de depuración de CineCam.
 *
 * Permite dirigir la app desde `adb` para verificaciones reproducibles:
 *
 *   adb shell am broadcast -a com.example.camara.DEBUG --es cmd "iso:3200"
 *   adb shell am broadcast -a com.example.camara.DEBUG --es cmd "shutter:1/24"
 *   adb shell am broadcast -a com.example.camara.DEBUG --es cmd "wb:3200"
 *   adb shell am broadcast -a com.example.camara.DEBUG --es cmd "focus:2.0"
 *   adb shell am broadcast -a com.example.camara.DEBUG --es cmd "lens:TELE"
 *   adb shell am broadcast -a com.example.camara.DEBUG --es cmd "tool:PEAKING:on"
 *   adb shell am broadcast -a com.example.camara.DEBUG --es cmd "record:start"
 *
 * Solo actúa en compilaciones de depuración: en release ignora todo.
 */
class BmDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        if (intent.action != ACTION) return
        val command = intent.getStringExtra("cmd") ?: return
        Log.i("BM_CAMERA", "DEBUG recibido: $command")
        BmDebugBridge.dispatch(command)
    }

    companion object {
        const val ACTION = "com.example.camara.DEBUG"
    }
}

/** Punto de entrega entre el receptor y la actividad viva. */
object BmDebugBridge {
    @Volatile
    private var handler: ((String) -> Unit)? = null

    fun attach(handler: (String) -> Unit) {
        this.handler = handler
    }

    fun detach() {
        handler = null
    }

    fun dispatch(command: String) {
        handler?.invoke(command)
    }
}
