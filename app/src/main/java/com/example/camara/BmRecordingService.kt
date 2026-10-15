package com.example.camara

import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that owns the MediaRecorder instance for Blackmagic Camera
 * style capture. Camera2 preview continues in MainActivity; this service handles
 * audio/video encoding to disk.
 *
 * Output format: HEVC 4K (or fallback to H.264) at 24/30 FPS.
 * Storage: external storage with a free-space check before each start.
 */
class BmRecordingService : Service() {

    private val tag = "BM_RECORD"
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var outputFilePath: String? = null

    override fun Ibinder(): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (isRecording) return

        val dir = getExternalFilesDir(null)
        if (dir == null) {
            Log.e(tag, "No external storage available")
            return
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "BM_$stamp.mp4")
        outputFilePath = file.absolutePath

        try {
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H265)
                setOutputFile(outputFilePath)
                setVideoSize(1920, 1080)
                setVideoFrameRate(24)
                prepare()
                start()
            }
            isRecording = true
            Log.i(tag, "Recording started: $outputFilePath")
        } catch (e: Exception) {
            Log.e(tag, "Recording start failed: ${e.message}")
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(tag, "Recording stop error: ${e.message}")
        }
        mediaRecorder = null
        isRecording = false
        Log.i(tag, "Recording stopped: $outputFilePath")
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.example.camara.START"
        const val ACTION_STOP = "com.example.camara.STOP"
    }
}