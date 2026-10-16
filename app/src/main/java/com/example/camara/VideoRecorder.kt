package com.example.camara

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Graba vídeo para CineCam con Camera2 + MediaRecorder sobre una superficie
 * compartida: la misma sesión Camera2 alimenta la pantalla y el codificador, de
 * modo que los ajustes manuales (ISO, obturador, WB, lente física) siguen siendo
 * los del visor durante la grabación.
 *
 * Salida: HEVC (H.265) MP4 de alto bitrate en MediaStore (`Movies/CineCam`).
 */
class VideoRecorder(private val context: Context) {

    companion object {
        const val TAG = "CINE_REC"
        const val RELATIVE_PATH = "Movies/CineCam"
        const val MAX_WIDTH = 4096
        const val MAX_HEIGHT = 3072

        /** Bitrate objetivo por resolución, dentro del rango de cámara de cine. */
        fun defaultBitrate(size: Size, fps: Int): Int {
            val pixels = size.width.toLong() * size.height.toLong()
            return when {
                pixels >= 3840L * 2160L -> 120_000_000
                pixels >= 2560L * 1440L -> 80_000_000
                pixels >= 1920L * 1080L -> 50_000_000
                else -> 25_000_000
            }.coerceAtMost((pixels * fps * 0.5).toInt().coerceAtLeast(10_000_000))
        }
    }

    private var recorder: MediaRecorder? = null
    private var pendingFd: ParcelFileDescriptor? = null
    private var pendingUri: Uri? = null

    /** Superficie de entrada del codificador; se añade como target del CaptureRequest. */
    var inputSurface: Surface? = null
        private set

    var isPrepared = false
        private set

    var isRecording = false
        private set

    var outputUri: Uri? = null
        private set

    var lastFormatLabel: String = "—"

    /** Caudal real configurado en el codificador (bits por segundo). */
    var lastBitrate: Int = 0
        private set

    /**
     * Tamaño de grabación más cercano a la calidad pedida. Se busca primero el
     * tamaño exacto y, si el dispositivo no lo ofrece, el más próximo que conserve
     * la relación de aspecto (nunca se inventa una resolución).
     */
    fun chooseSize(characteristics: CameraCharacteristics, quality: BmSettings.Quality): Size? {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(MediaRecorder::class.java) ?: return null
        if (sizes.isEmpty()) return null
        val within = sizes.filter { it.width <= MAX_WIDTH && it.height <= MAX_HEIGHT }.ifEmpty { sizes.toList() }

        within.firstOrNull { it.width == quality.width && it.height == quality.height }?.let { return it }

        val targetAspect = quality.width.toFloat() / quality.height
        return within
            .filter { kotlin.math.abs(it.width.toFloat() / it.height - targetAspect) < 0.03f }
            .minByOrNull {
                kotlin.math.abs(it.width - quality.width).toLong() * 1000 +
                    kotlin.math.abs(it.height - quality.height)
            }
            ?: within.minByOrNull {
                kotlin.math.abs(it.width - quality.width).toLong() * 1000 +
                    kotlin.math.abs(it.height - quality.height)
            }
    }

    private fun hevcEncoderAvailable(): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "No se pudo consultar MediaCodecList: ${t.message}")
        false
    }

    /**
     * Crea la fila en MediaStore y prepara el codificador.
     * [bitrateBps] <= 0 usa el bitrate por defecto de la resolución.
     */
    fun prepare(
        size: Size,
        fps: Int,
        orientationHint: Int,
        bitrateBps: Int = 0,
        audioEnabled: Boolean = true,
        preferHevc: Boolean = true
    ): Boolean {
        release()
        val resolver = context.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "CINE_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (t: Throwable) {
            Log.e(TAG, "MediaStore insert falló: ${t.message}")
            null
        }
        if (uri == null) return false

        val fd = try {
            resolver.openFileDescriptor(uri, "w")
        } catch (t: Throwable) {
            Log.e(TAG, "openFileDescriptor falló: ${t.message}")
            null
        }
        if (fd == null) {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }

        val hevc = preferHevc && hevcEncoderAvailable()
        val effectiveBitrate = if (bitrateBps > 0) bitrateBps else defaultBitrate(size, fps)
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        return try {
            if (audioEnabled) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(fd.fileDescriptor)
            rec.setVideoSize(size.width, size.height)
            rec.setVideoFrameRate(fps)
            rec.setVideoEncoder(if (hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            rec.setVideoEncodingBitRate(effectiveBitrate)
            if (audioEnabled) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(256_000)
                rec.setAudioSamplingRate(48_000)
                // Estéreo real: dos canales, que es lo que miden los dos vúmetros.
                rec.setAudioChannels(2)
            }
            rec.setOrientationHint(orientationHint)
            rec.prepare()

            recorder = rec
            pendingFd = fd
            pendingUri = uri
            inputSurface = rec.surface
            isPrepared = true
            lastBitrate = effectiveBitrate
            lastFormatLabel = buildString {
                append(if (hevc) "HEVC" else "H.264")
                append(' ')
                append(if (size.height >= 2160) "4K" else "${size.height}p")
                append(' ')
                append(effectiveBitrate / 1_000_000)
                append("M")
            }
            Log.i(
                TAG,
                "Preparado ${size.width}x${size.height}@$fps ${if (hevc) "HEVC" else "H264"} " +
                    "${effectiveBitrate / 1_000_000}Mbps -> $uri"
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "prepare() falló: ${t.message}")
            runCatching { rec.release() }
            runCatching { fd.close() }
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    /** Arranca el codificador. Debe llamarse con la sesión Camera2 ya emitiendo al [inputSurface]. */
    fun start(): Boolean {
        val rec = recorder ?: return false
        return try {
            rec.start()
            isRecording = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start() falló: ${t.message}")
            abort()
            false
        }
    }

    /** Amplitud de pico del audio real capturado (0..32767). */
    fun peakAmplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (t: Throwable) {
        0
    }

    /**
     * Detiene la grabación y publica el clip en MediaStore.
     * Devuelve el URI final, o null si el clip se descartó.
     */
    fun stop(): Uri? {
        if (recorder == null) return null
        val uri = pendingUri
        var valid = false
        try {
            recorder?.stop()
            valid = true
        } catch (t: Throwable) {
            Log.w(TAG, "stop() descartó el clip: ${t.message}")
        }
        release()
        if (uri != null) {
            if (valid) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                runCatching { context.contentResolver.update(uri, values, null, null) }
                outputUri = uri
                Log.i(TAG, "Clip publicado: $uri")
            } else {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
        return if (valid) uri else null
    }

    /** Cancela un prepare() que no llegó a grabar. */
    fun abort() {
        val uri = pendingUri
        runCatching { recorder?.reset() }
        release()
        if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun release() {
        runCatching { recorder?.release() }
        runCatching { pendingFd?.close() }
        recorder = null
        pendingFd = null
        pendingUri = null
        inputSurface = null
        isPrepared = false
        isRecording = false
    }
}
