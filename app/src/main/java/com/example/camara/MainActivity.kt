package com.example.camara

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StatFs
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Actividad principal de CineCam.
 *
 * Toda la captura es manual: los parámetros de [BmSettings] se escriben en el
 * [CaptureRequest] y se aplican en vivo con `setRepeatingRequest`. El cambio de
 * lente usa el identificador de cámara física sobre la cámara lógica mediante
 * `OutputConfiguration.setPhysicalCameraId`. La grabación reutiliza exactamente
 * los mismos ajustes y la misma lente.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG_CAMERA = "BM_CAMERA"
        const val TAG_METER = "BM_METER"
        const val LOGICAL_CAMERA_ID = "0"
        const val PHYSICAL_MAIN = "2"
        const val PHYSICAL_TELE = "3"
        const val PHYSICAL_UW = "4"
        const val FOUR_K_BITRATE = 120_000_000

        /** Tamaño de búfer de previsualización, fijo para no reiniciar la sesión. */
        val STABLE_PREVIEW_SIZE = Size(1920, 1080)

        /**
         * Resoluciones de búfer aceptadas, en orden de preferencia. Este HAL
         * entrega 1920x1080 y 1440x1080 sin problemas; son las que evitan la
         * pantalla en negro al crear la sesión.
         */
        val supportedBufferSizes = listOf(Size(1920, 1080), Size(1440, 1080))
        const val MENU_ZEBRA = 1
        const val MENU_PEAKING = 2
        const val MENU_FALSE = 3
        const val MENU_GUIDES = 4
    }

    private val settings = BmSettings()
    private lateinit var textureView: TextureView
    private lateinit var topBar: BmTopBar
    private lateinit var header: BmHeaderBar
    private lateinit var toolRail: BmToolBar
    private lateinit var railHandle: BmRailHandle
    private lateinit var centerDial: BmCenterDial
    private lateinit var overlayView: BmOverlayView
    private lateinit var focusBox: BmFocusBoxView
    private lateinit var bottomBar: BmBottomBar
    private lateinit var bottomNav: BmBottomNav
    private lateinit var histogramView: BmHistogramView
    private lateinit var stereoMeters: BmStereoMetersView
    private lateinit var storagePanel: BmStoragePanel

    /** Botonera flotante de lentes sobre el visor. */
    private lateinit var lensPills: BmLensPills
    private lateinit var stabilizerIcon: android.widget.ImageView
    private lateinit var rightHandle: BmRailHandle
    private lateinit var panelRow: LinearLayout
    private lateinit var previewArea: FrameLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var bottomContainer: LinearLayout

    /** Tamaño real del stream de previsualización (define la relación de aspecto). */
    private var previewSize: Size = Size(1920, 1080)
    private var focusRegions: Array<MeteringRectangle>? = null
    private var tapFocusActive = false

    /** Orientación declarada por el HAL y giro realmente aplicado al visor. */
    private var sensorOrientation = 90
    private var previewRotation = 0

    /**
     * Orientación física del teléfono (0/90/180/270) medida con el sensor.
     *
     * Es la referencia que hace que el visor coincida con la aplicación de
     * referencia: se comprobó lado a lado contra ella (misma escena y posición) y
     * con el giro declarado por el HAL la imagen salía tumbada. El control ROTAR
     * del raíl permite forzarlo en el momento.
     */
    private var deviceRotation = 0

    private val orientationListener: OrientationEventListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                // La corrección sigue la rotación real de la pantalla: en este
                // dispositivo los cuadros llegan ya orientados con ella.
                val rotation = when {
                    orientation < 45 || orientation >= 315 -> 0
                    orientation < 135 -> 270
                    orientation < 225 -> 180
                    else -> 90
                }
                if (rotation == deviceRotation) return
                deviceRotation = rotation
                // Sólo el giro: no se relayouta el contenedor mientras la cámara
                // está renderizando.
                previewArea.post { applyPreviewTransform() }
            }
        }
    }

    /**
     * Desfase medido entre la orientación que declara el HAL y la de los cuadros
     * que realmente entrega este dispositivo (Vivo X300, cámara lógica 0).
     */
    private var sensorOrientationOffset = -90

    /** Fuerza el giro del visor (puente de depuración y control ROTAR). */
    private var previewRotationOverride: Int? = null

    /** Parámetro cuya regla graduada está abierta (para el botón AUTO). */
    private var activeDialKey: String = ""

    /** Última matriz aplicada al visor, para invertirla al mapear los toques. */
    private val previewMatrix = Matrix()

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null
    private var characteristics: CameraCharacteristics? = null

    private val supportedPhysicalIds = mutableSetOf<String>()
    private var activePhysicalId: String? = null
    private var availableFpsRanges: List<Range<Int>> = emptyList()
    private var contrastCurveSupported = false
    private var isoStops: IntArray = BmSettings.ISO_STOPS

    /** Índices de obturación válidos, generados de la tabla de FreeDcam. */
    private var shutterStops: LongArray = BmSettings.SUTTER_STOPS

    /** El HAL publica `CONTROL_ZOOM_RATIO`; si no, se omite el ajuste. */
    private var zoomRatioSupported = false

    /** Evita encolar más de una actualización del repeating request a la vez. */
    private var updatePending = false

    /**
     * Matriz de calibración del sensor (`SENSOR_COLOR_TRANSFORM1`), leída al
     * abrir la cámara. Es la que exige `CONTROL_AWB_MODE_OFF` con
     * `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` para que MediaTek acepte
     * `COLOR_CORRECTION_GAINS` sin teñir la imagen. Null si el sensor no la publica.
     */
    private var sensorColorTransform: ColorSpaceTransform? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Hilo dedicado a la cámara, como en FreeDcam y OpenCamera.
     *
     * Todas las llamadas bloqueantes de Camera2 (`openCamera`,
     * `createCaptureSession`, `setRepeatingRequest`, `stopRepeating`, `close`) se
     * hacen aquí y nunca en el hilo de interfaz: era lo que congelaba la UI al
     * tocar ISO o arrastrar la regla graduada.
     */
    private val cameraThread = HandlerThread("BmCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    /** Ejecutor que despacha al hilo de cámara, para SessionConfiguration. */
    private val cameraExecutor = java.util.concurrent.Executor { command -> cameraHandler.post(command) }

    private var videoRecorder: VideoRecorder? = null
    private var analyzer: BmMonitorAnalyzer? = null
    private var isRecording = false
    private var startRecordingOnConfigured = false
    private var pendingDebugCommand: String? = null
    private var pendingRecorderSurface: Surface? = null

    private var recordStartedAt = 0L
    private var lastMeterLog = 0L

    private val timerTick = object : Runnable {
        override fun run() {
            updateTimecode()
            val amplitude = videoRecorder?.peakAmplitude() ?: 0
            val level = amplitude / 32767f
            // Vúmetros horizontales estéreo: la plataforma entrega una amplitud
            // mezclada, así que ambas barras muestran esa medida real.
            stereoMeters.setLevels(level, level)
            updateFreeSpace()
            if (isRecording) mainHandler.postDelayed(this, 200)
        }
    }

    /**
     * Sondea el archivo de comandos cada 500 ms.
     *
     * Es el canal de verificación en vivo en este dispositivo: `logcat` no
     * entrega las líneas de la app y `am broadcast` no llega al puente, así que
     * los comandos se dejan en un archivo del directorio de la app.
     */
    private val commandPoller = object : Runnable {
        override fun run() {
            BmDiagnostics.takeCommand(this@MainActivity)?.let { command ->
                handleDebugCommand(command)
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    /** Timecode real: 00:00:00:00 en reposo y contando durante la grabación. */
    private fun updateTimecode() {
        val millis = if (isRecording) System.currentTimeMillis() - recordStartedAt else 0L
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val frames = (millis % 1000) * settings.fps / 1000
        header.setTimecode(
            String.format(Locale.US, "%02d:%02d:%02d:%02d", hours, minutes, seconds, frames)
        )
    }

    private val lutPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        loadLut(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A sangre por los cuatro lados: la app decide dónde colocar su contenido
        // leyendo los insets reales (barra de estado + recorte de la cámara frontal).
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        overlayView = findViewById(R.id.bmOverlay)
        focusBox = findViewById(R.id.bmFocusBox)
        topBar = findViewById(R.id.bmTopBar)
        header = findViewById(R.id.bmHeader)
        toolRail = findViewById(R.id.bmToolRail)
        railHandle = findViewById(R.id.bmRailHandle)
        centerDial = findViewById(R.id.bmCenterDial)
        bottomBar = findViewById(R.id.bmBottomBar)
        bottomNav = findViewById(R.id.bmBottomNav)
        histogramView = findViewById(R.id.bmHistogram)
        stereoMeters = findViewById(R.id.bmStereoMeters)
        storagePanel = findViewById(R.id.bmStorage)
        lensPills = findViewById(R.id.bmLensPills)
        stabilizerIcon = findViewById(R.id.bmStabilizer)
        rightHandle = findViewById(R.id.bmRailHandleRight)
        panelRow = findViewById(R.id.panelRow)
        previewArea = findViewById(R.id.previewArea)
        previewFrame = findViewById(R.id.previewFrame)
        bottomContainer = findViewById(R.id.bottomContainer)

        applyWindowInsets()

        // El visor se ajusta a la relación de aspecto real del stream, girado para
        // mostrarse en vertical: sin deformación y sin quedar tapado por las barras.
        previewArea.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewAspect()
            applyPreviewTransform()
            placeFloatingControls()
        }
        previewFrame.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP && view.width > 0 && view.height > 0) {
                val nx = event.x / view.width
                val ny = event.y / view.height
                focusBox.show(nx, ny)
                val buffer = viewToBuffer(nx, ny)
                triggerTapToFocus(buffer.first, buffer.second)
            }
            true
        }

        videoRecorder = VideoRecorder(this)
        stereoMeters.reset()

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                surface.setDefaultBufferSize(previewSize.width, previewSize.height)
                previewArea.post { applyPreviewAspect() }
                // La superficie puede ser NUEVA (primer arranque, o vuelta del
                // segundo plano, donde onSurfaceTextureDestroyed liberó la
                // anterior). Hay que rehacer la sesión con este Surface: si no, la
                // sesión sigue escribiendo al Surface muerto y el visor se queda
                // negro para siempre, porque openCamera() ya no puede reabrir un
                // dispositivo que sigue abierto.
                cameraHandler.post { rebindPreviewSurface(surface) }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                previewArea.post { applyPreviewTransform() }
            }

            /**
             * El TextureView libera el SurfaceTexture anterior; la sesión se
             * reconstruye en [onSurfaceTextureAvailable] con el nuevo.
             */
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                cameraHandler.post {
                    runCatching { captureSession?.stopRepeating() }
                    runCatching { captureSession?.close() }
                    captureSession = null
                    previewSurface?.release()
                    previewSurface = null
                }
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        analyzer = BmMonitorAnalyzer(textureView, settings).apply {
            onSnapshot = { snapshot ->
                lastSnapshot = snapshot
                overlayView.setMonitorBitmap(snapshot.overlay)
                if (settings.histogram) histogramView.update(snapshot)
                val now = System.currentTimeMillis()
                if (now - lastMeterLog > 1000) {
                    lastMeterLog = now
                    Log.i(
                        TAG_METER,
                        "mean=%.1f clipHi=%d clipLo=%d iso=%d shutter=%s wb=%dK tint=%d fps=%d lens=%s tools[peak=%b zebra=%b false=%b lut=%b log=%b]".format(
                            Locale.US, snapshot.meanLuma, snapshot.clippedHighlights, snapshot.clippedShadows,
                            settings.iso, settings.shutterLabel, settings.kelvin, settings.tint, settings.fps,
                            settings.lensKey, settings.peaking, settings.zebra, settings.falseColor,
                            settings.lutEnabled, settings.logEnabled
                        )
                    )
                }
            }
        }

        wireTopBar()
        wireHeader()
        wireBottomBar()
        wireBottomNav()
        wireToolBar()
        wireDial()
        wireRecordButton()
        updateParameterDisplay()
        updateFreeSpace()
        analyzer?.configure(previewSize.width, previewSize.height)
        analyzer?.start()   // histograma en vivo activo por defecto
        BmDebugBridge.attach { command -> runOnUiThread { handleDebugCommand(command) } }
        // Permite dirigir la app desde adb: am start ... --es cmd "iso:3200"
        intent?.getStringExtra("cmd")?.let { command -> mainHandler.post { handleDebugCommand(command) } }
        // Y también por archivo, porque este dispositivo no entrega logcat:
        //   adb shell 'echo wb:5600 > .../files/bm-cmd.txt'
        mainHandler.post(commandPoller)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (hasCameraPermission()) {
            if (textureView.isAvailable) openCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                1
            )
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // -------------------------------------------------- márgenes del sistema

    /** Recorte superior mínimo en vertical, en dp (bajo la cámara perforada). */
    private val minTopInsetDp = 48

    /** Último margen aplicado, para no reescribir el layout en cada frame. */
    private var lastInsetTop = -1
    private var lastInsetLeft = -1
    private var lastInsetRight = -1
    private var lastInsetBottom = -1

    /**
     * Lee los márgenes reales del sistema (barra de estado y recorte de la cámara
     * perforada) y los aplica a la cabecera y a la barra de navegación.
     *
     * En vertical se reserva `max(insets.top, 48 dp)`: el recorte del X300 queda
     * dentro de la barra de estado, así que se garantiza siempre el mínimo de
     * 48 dp para que la cabecera nunca quede debajo de la lente. En horizontal se
     * usan `insets.left` / `insets.right` y el recorte lateral.
     */
    private fun applyWindowInsets() {
        val root: View = findViewById(R.id.bmRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val nav = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val portrait = isPortraitDisplay()
            val top = if (portrait) {
                maxOf(bars.top, cutout.top, dp(minTopInsetDp))
            } else {
                maxOf(bars.top, cutout.top)
            }
            val left = maxOf(bars.left, cutout.left)
            val right = maxOf(bars.right, cutout.right)
            val bottom = maxOf(nav.bottom, cutout.bottom)
            if (
                top != lastInsetTop || left != lastInsetLeft ||
                right != lastInsetRight || bottom != lastInsetBottom
            ) {
                lastInsetTop = top
                lastInsetLeft = left
                lastInsetRight = right
                lastInsetBottom = bottom
                val topContainer: View = findViewById(R.id.topContainer)
                topContainer.setPadding(left, top, right, 0)
                val navBar: View = findViewById(R.id.bmBottomNav)
                navBar.setPadding(left, 0, right, bottom)
                Log.i(
                    TAG_CAMERA,
                    "Insets barras=$bars recorte=$cutout vertical=$portrait -> cabecera($left,$top,$right) nav=$bottom"
                )
                previewArea.post {
                    applyPreviewAspect()
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    // ------------------------------------------------------------- cabecera

    /** Primera fila: LUT, badge de formato (abre el selector) y timecode. */
    private fun wireHeader() {
        header.onLutClick = { applyToolState("LUT", !settings.lutEnabled) }
        header.onFormatClick = { showQualityDialog() }
        railHandle.setOnClickListener { setRailOpen(toolRail.visibility != View.VISIBLE) }
        // El asa derecha oculta o muestra la superposición de monitores.
        rightHandle.setOnClickListener { setOverlaysVisible(!settings.overlaysVisible) }
        stabilizerIcon.setOnClickListener { toggleStabilization() }
        refreshStabilizerIcon()
    }

    private fun setRailOpen(open: Boolean) {
        toolRail.visibility = if (open) View.VISIBLE else View.GONE
        railHandle.expanded = open
        refreshBottomStates()
        Log.i(TAG_CAMERA, "Raíl de monitores -> ${if (open) "abierto" else "plegado"}")
    }

    /**
     * Pantalla limpia: oculta la superposición (histograma, vúmetros, almacenamiento
     * y guías) para ver solo la imagen. Lo controlan el botón `[ ]` y el asa derecha.
     */
    private fun setOverlaysVisible(visible: Boolean) {
        settings.overlaysVisible = visible
        panelRow.visibility = if (visible) View.VISIBLE else View.GONE
        storagePanel.visibility = if (visible) View.VISIBLE else View.GONE
        overlayView.guidesEnabled = visible && settings.guides
        stabilizerIcon.visibility = if (visible) View.VISIBLE else View.GONE
        refreshBottomStates()
        Log.i(TAG_CAMERA, "Superposición de monitores -> ${if (visible) "visible" else "oculta"}")
    }

    // ------------------------------------------------------------- barra inferior

    /** Fila del disparador: guías, LUT, enfoque puntual, grabación y modos auto. */
    private fun wireBottomBar() {
        bottomBar.onGuides = { showMonitorMenu() }
        bottomBar.onLutToggle = { applyToolState("LUT", !settings.lutEnabled) }
        bottomBar.onFocusTarget = { focusCenter() }
        bottomBar.onExposureToggle = { setAutoExposure(!settings.autoExposure) }
        bottomBar.onAutoFocusToggle = { toggleFocusMode() }
        bottomBar.onCleanScreen = { setOverlaysVisible(!settings.overlaysVisible) }
        // Bloqueos AE/AWB y estabilización óptica (OpenCamera,
        // CameraController2): se aplican al CaptureRequest en el acto.
        bottomBar.onAeLock = {
            settings.aeLock = !settings.aeLock
            refreshBottomStates()
            pushSettings()
            Log.i(TAG_CAMERA, "CONTROL_AE_LOCK -> ${settings.aeLock}")
        }
        bottomBar.onAwbLock = {
            settings.awbLock = !settings.awbLock
            refreshBottomStates()
            pushSettings()
            Log.i(TAG_CAMERA, "CONTROL_AWB_LOCK -> ${settings.awbLock}")
        }
        bottomBar.onOisToggle = {
            settings.opticalStabilization = !settings.opticalStabilization
            refreshBottomStates()
            pushSettings()
            Log.i(TAG_CAMERA, "OIS -> ${settings.opticalStabilization}")
        }
        refreshBottomStates()
    }

    /** Menú de monitores del botón de guías: zebra, peaking, falso color y rejilla. */
    private fun showMonitorMenu() {
        val popup = android.widget.PopupMenu(this, bottomBar.guidesButton)
        popup.menu.add(0, MENU_ZEBRA, 0, "Zebra").isCheckable = true
        popup.menu.add(0, MENU_PEAKING, 1, "Focus Peaking").isCheckable = true
        popup.menu.add(0, MENU_FALSE, 2, "False Color").isCheckable = true
        popup.menu.add(0, MENU_GUIDES, 3, "Cuadrícula").isCheckable = true
        popup.menu.findItem(MENU_ZEBRA).isChecked = settings.zebra
        popup.menu.findItem(MENU_PEAKING).isChecked = settings.peaking
        popup.menu.findItem(MENU_FALSE).isChecked = settings.falseColor
        popup.menu.findItem(MENU_GUIDES).isChecked = settings.guides
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_ZEBRA -> applyToolState("ZEBRA", !settings.zebra)
                MENU_PEAKING -> applyToolState("PEAKING", !settings.peaking)
                MENU_FALSE -> applyToolState("FALSE", !settings.falseColor)
                MENU_GUIDES -> applyToolState("GUIDES", !settings.guides)
            }
            true
        }
        popup.show()
    }

    /** Enfoque puntual en el centro del encuadre (botón diana). */
    private fun focusCenter() {
        if (!settings.autoFocus) {
            settings.autoFocus = true
            Log.i(TAG_CAMERA, "Enfoque puntual -> AF continuo")
        }
        focusBox.show(0.5f, 0.5f)
        triggerTapToFocus(0.5f, 0.5f)
        refreshBottomStates()
        updateParameterDisplay()
    }

    /** Estabilización de vídeo del dispositivo (OIS/EIS del HAL). */
    private fun toggleStabilization() {
        settings.videoStabilization = !settings.videoStabilization
        refreshStabilizerIcon()
        pushSettings()
        Log.i(TAG_CAMERA, "Estabilización de vídeo -> ${settings.videoStabilization}")
    }

    private fun refreshStabilizerIcon() {
        val active = settings.videoStabilization
        stabilizerIcon.setBackgroundColor(if (active) 0xCC0A2A4A.toInt() else 0x66000000.toInt())
        stabilizerIcon.setColorFilter(if (active) 0xFF2196F3.toInt() else 0xFFFFFFFF.toInt())
    }

    // ------------------------------------------------------- barra de navegación

    private fun wireBottomNav() {
        bottomNav.onTabSelected = { tab ->
            when (tab) {
                BmBottomNav.Tab.CAMERA -> Unit
                BmBottomNav.Tab.MEDIA -> openMediaGallery()
                BmBottomNav.Tab.MESSAGES -> {
                    Toast.makeText(this, "Mensajes no se usa en CineCam", Toast.LENGTH_SHORT).show()
                    bottomNav.select(BmBottomNav.Tab.CAMERA)
                }
                BmBottomNav.Tab.SETTINGS -> showPreferencesDialog()
            }
        }
    }

    /** Multimedia: lista de los vídeos grabados en Movies/CineCam. */
    private fun openMediaGallery() {
        startActivity(Intent(this, BmMediaActivity::class.java))
    }

    /** Preferencias: códec, caudal y carga de LUT .cube. */
    private fun showPreferencesDialog() {
        val codecs = arrayOf("HEVC (H.265)", "H.264")
        val bitrates = arrayOf("Automático (según formato)", "50 Mbps", "90 Mbps", "100 Mbps", "120 Mbps", "160 Mbps")
        val checkedCodec = if (settings.useHevc) 0 else 1
        val checkedBitrate = when (settings.bitrateOverrideMbps) {
            0 -> 0; 50 -> 1; 90 -> 2; 100 -> 3; 120 -> 4; 160 -> 5; else -> 0
        }
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Preferencias")
            .setSingleChoiceItems(codecs, checkedCodec) { _, which ->
                settings.useHevc = which == 0
                Log.i(TAG_CAMERA, "Códec -> ${if (settings.useHevc) "HEVC" else "H.264"}")
            }
            .setPositiveButton("Caudal") { _, _ -> showBitrateDialog(bitrates, checkedBitrate) }
            .setNeutralButton("Cargar LUT .cube") { _, _ -> lutPicker.launch(arrayOf("*/*")) }
            .setNegativeButton("Cerrar", null)
        builder.show()
    }

    private fun showBitrateDialog(bitrates: Array<String>, checked: Int) {
        val values = intArrayOf(0, 50, 90, 100, 120, 160)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Caudal de grabación")
            .setSingleChoiceItems(bitrates, checked) { dialog, which ->
                settings.bitrateOverrideMbps = values[which]
                Log.i(TAG_CAMERA, "Caudal -> ${if (values[which] == 0) "automático" else "${values[which]} Mbps"}")
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Enciende o apaga el modo automático de exposición del dispositivo. */
    private fun setAutoExposure(enabled: Boolean) {
        settings.autoExposure = enabled
        if (!enabled) {
            // Al volver a manual se conserva lo último que midió el sensor.
            Log.i(TAG_CAMERA, "AE -> manual iso=${settings.iso} shutter=${settings.shutterLabel}")
        } else {
            // En automático la regla graduada no tiene sentido: se cierra.
            closeDialIfActive("SHUTTER", "ISO")
        }
        refreshBottomStates()
        updateParameterDisplay()
        syncDialAuto()
        pushSettings()
        Log.i(
            TAG_CAMERA,
            "Exposición automática -> $enabled (${if (enabled) "CONTROL_AE_MODE_ON" else "CONTROL_AE_MODE_OFF + SENSOR_*"})"
        )
    }

    /** Enciende o apaga el balance de blancos automático del dispositivo. */
    private fun setAutoWb(enabled: Boolean) {
        settings.autoWb = enabled
        if (enabled) {
            closeDialIfActive("WB", "TINT")
        }
        refreshBottomStates()
        updateParameterDisplay()
        syncDialAuto()
        pushSettings()
        Log.i(
            TAG_CAMERA,
            "Balance automático -> $enabled (${if (enabled) "AWB_MODE_AUTO" else "AWB_MODE_OFF + COLOR_CORRECTION_GAINS"})"
        )
    }

    /** Cierra la regla graduada si pertenece a alguno de estos parámetros. */
    private fun closeDialIfActive(vararg keys: String) {
        if (keys.contains(activeDialKey)) {
            activeDialKey = ""
            centerDial.hide()
            topBar.setActive("")
        }
    }

    private fun refreshBottomStates() {
        bottomBar.setStates(
            toolRail.visibility == View.VISIBLE,
            settings.lutEnabled,
            settings.autoExposure,
            settings.autoFocus,
            !settings.overlaysVisible,
            aeLocked = settings.aeLock,
            awbLocked = settings.awbLock,
            oisActive = settings.opticalStabilization
        )
        header.setLutActive(settings.lutEnabled)
        refreshLensPills()
    }

    // ---------------------------------------------------------------- top bar

    private fun wireTopBar() {
        topBar.onSelectorTap = { key ->
            if (isRecording) {
                Log.i(TAG_CAMERA, "Ajustes bloqueados durante la grabación: $key")
            } else if (key == "FOCUS") {
                toggleFocusMode()
            } else {
                openDial(key)
            }
        }
        // Insignia «A» operativa: conmuta el automático del parámetro al tocarla
        // y aplica el cambio al CaptureRequest en el acto.
        topBar.onAutoTap = { key, active ->
            when (key) {
                "SHUTTER", "ISO" -> setAutoExposure(active)
                "WB", "TINT" -> setAutoWb(active)
                "FOCUS" -> {
                    if (settings.autoFocus != active) toggleFocusMode()
                }
                else -> Log.i(TAG_CAMERA, "La insignia A no aplica a $key")
            }
            Log.i(TAG_CAMERA, "Insignia A -> $key=${if (active) "AUTO" else "MANUAL"}")
            true
        }
    }

    /** Alterna entre AF continuo y MF manual (dial de dioptrías). */
    private fun toggleFocusMode() {
        settings.autoFocus = !settings.autoFocus
        if (settings.autoFocus) {
            focusRegions = null
            tapFocusActive = false
            centerDial.hide()
            topBar.setActive("")
        } else {
            openDial("FOCUS")
        }
        updateParameterDisplay()
        pushSettings()
        Log.i(TAG_CAMERA, "Enfoque -> ${if (settings.autoFocus) "AF continuo" else "MF manual"}")
    }

    private fun openDial(key: String) {
        activeDialKey = key
        topBar.setActive(key)
        val (readable, position) = dialState(key)
        centerDial.show(key, readable, position)
        syncDialAuto()
    }

    /** Devuelve (texto legible, posición 0..1) del parámetro pedido. */
    private fun dialState(key: String): Pair<String, Float> = when (key) {
        "LENS" -> {
            val labels = lensLabels()
            val index = labels.indexOf(settings.lensKey).coerceAtLeast(0)
            settings.lensKey to index / (labels.size - 1).toFloat()
        }
        "FPS" -> {
            val values = fpsValues()
            val index = values.indexOf(settings.fps).coerceAtLeast(0)
            "${settings.fps}" to index / (values.size - 1).toFloat()
        }
        "SHUTTER" -> {
            val index = settings.nearestIndex(shutterStops, settings.shutterNs)
            settings.shutterLabel to index / (shutterStops.size - 1).toFloat()
        }
        "ISO" -> {
            val index = settings.nearestIndex(isoStops, settings.iso)
            "${settings.iso}" to index / (isoStops.size - 1).toFloat()
        }
        "WB" -> {
            val index = nearestIndex(BmSettings.KELVIN_STOPS.map { it.toFloat() }, settings.kelvin.toFloat())
            "${settings.kelvin}K" to index / (BmSettings.KELVIN_STOPS.size - 1).toFloat()
        }
        "TINT" -> {
            val index = nearestIndex(BmSettings.TINT_STOPS.map { it.toFloat() }, settings.tint.toFloat())
            "${settings.tint}" to index / (BmSettings.TINT_STOPS.size - 1).toFloat()
        }
        "FOCUS" -> {
            val maxDiopters = max(0.001f, settings.focusMinDiopters)
            val position = (settings.focusDiopters / maxDiopters).coerceIn(0f, 1f)
            settings.focusLabel to position
        }
        else -> "—" to 0f
    }

    private fun wireDial() {
        centerDial.onValue = { key, fraction ->
            // Mover la regla graduada implica pasar a manual puro.
            when (key) {
                "SHUTTER", "ISO" -> if (settings.autoExposure) setAutoExposure(false)
                "WB", "TINT" -> if (settings.autoWb) setAutoWb(false)
                "FOCUS" -> if (settings.autoFocus) {
                    settings.autoFocus = false
                    refreshBottomStates()
                }
            }
            applyDialValue(key, fraction)
            val (readable, position) = dialState(key)
            centerDial.update(readable, position)
            syncDialAuto()
        }
        centerDial.onStep = { key, direction ->
            stepDial(key, direction)
            val (readable, position) = dialState(key)
            centerDial.update(readable, position)
        }
        centerDial.onAutoToggle = { key, active ->
            when (key) {
                "SHUTTER", "ISO" -> setAutoExposure(active)
                "WB", "TINT" -> setAutoWb(active)
                "FOCUS" -> {
                    settings.autoFocus = active
                    refreshBottomStates()
                    updateParameterDisplay()
                    pushSettings()
                }
            }
            syncDialAuto()
        }
    }

    /** El botón AUTO del dial refleja el estado real del parámetro abierto. */
    private fun syncDialAuto() {
        val key = activeDialKey
        centerDial.autoAvailable = key == "SHUTTER" || key == "ISO" || key == "WB" || key == "TINT" || key == "FOCUS"
        centerDial.autoActive = when (key) {
            "SHUTTER", "ISO" -> settings.autoExposure
            "WB", "TINT" -> settings.autoWb
            "FOCUS" -> settings.autoFocus
            else -> false
        }
    }

    private fun applyDialValue(key: String, fraction: Float) {
        if (isRecording) return
        val position = fraction.coerceIn(0f, 1f)
        when (key) {
            "LENS" -> {
                val labels = lensLabels()
                val index = (position * (labels.size - 1)).toInt().coerceIn(0, labels.size - 1)
                selectLens(labels[index])
                return
            }
            "FPS" -> {
                val values = fpsValues()
                val index = (position * (values.size - 1)).toInt().coerceIn(0, values.size - 1)
                val fps = values[index]
                settings.fps = fps
                if (fps == 60 && settings.lensKey != BmSettings.LENS_LOGICAL) {
                    selectLens(BmSettings.LENS_LOGICAL)
                    return
                }
            }
            "SHUTTER" -> settings.shutterNs = shutterStops[(position * (shutterStops.size - 1)).toInt().coerceIn(0, shutterStops.size - 1)]
            "ISO" -> settings.iso = isoStops[(position * (isoStops.size - 1)).toInt().coerceIn(0, isoStops.size - 1)]
            "WB" -> settings.kelvin = valueAt(BmSettings.KELVIN_STOPS.map { it.toFloat() }, position).toInt()
            "TINT" -> settings.tint = valueAt(BmSettings.TINT_STOPS.map { it.toFloat() }, position).toInt()
            "FOCUS" -> settings.focusDiopters = position * max(0.001f, settings.focusMinDiopters)
        }
        updateParameterDisplay()
        pushSettings()
    }

    private fun stepDial(key: String, direction: Int) {
        if (isRecording) return
        when (key) {
            "SHUTTER" -> settings.stepShutter(direction)
            "ISO" -> {
                val index = settings.nearestIndex(isoStops, settings.iso)
                settings.iso = isoStops[(index + direction).coerceIn(0, isoStops.size - 1)]
            }
            "WB" -> {
                val index = nearestIndex(BmSettings.KELVIN_STOPS.map { it.toFloat() }, settings.kelvin.toFloat())
                settings.kelvin = BmSettings.KELVIN_STOPS[(index + direction * 2).coerceIn(0, BmSettings.KELVIN_STOPS.size - 1)]
            }
            "TINT" -> {
                val index = nearestIndex(BmSettings.TINT_STOPS.map { it.toFloat() }, settings.tint.toFloat())
                settings.tint = BmSettings.TINT_STOPS[(index + direction).coerceIn(0, BmSettings.TINT_STOPS.size - 1)]
            }
            "FPS" -> {
                val values = fpsValues()
                val index = values.indexOf(settings.fps).coerceAtLeast(0)
                val fps = values[(index + direction).coerceIn(0, values.size - 1)]
                settings.fps = fps
                if (fps == 60 && settings.lensKey != BmSettings.LENS_LOGICAL) selectLens(BmSettings.LENS_LOGICAL)
            }
            "LENS" -> {
                val labels = lensLabels()
                val index = labels.indexOf(settings.lensKey).coerceAtLeast(0)
                selectLens(labels[(index + direction).coerceIn(0, labels.size - 1)])
                return
            }
            "FOCUS" -> {
                val stepSize = max(0.001f, settings.focusMinDiopters) / 20f
                settings.focusDiopters = (settings.focusDiopters - direction * stepSize)
                    .coerceIn(0f, max(0f, settings.focusMinDiopters))
            }
        }
        updateParameterDisplay()
        pushSettings()
    }

    private fun valueAt(values: List<Float>, position: Float): Float {
        if (values.isEmpty()) return 0f
        val index = (position * (values.size - 1)).toInt().coerceIn(0, values.size - 1)
        return values[index]
    }

    private fun nearestIndex(values: List<Float>, target: Float): Int {
        var best = 0
        var bestDelta = Float.MAX_VALUE
        values.forEachIndexed { index, value ->
            val delta = kotlin.math.abs(value - target)
            if (delta < bestDelta) {
                bestDelta = delta
                best = index
            }
        }
        return best
    }

    private fun lensLabels(): List<String> {
        val labels = mutableListOf<String>()
        if (supportedPhysicalIds.contains(PHYSICAL_MAIN)) labels += BmSettings.LENS_MAIN
        if (supportedPhysicalIds.contains(PHYSICAL_TELE)) labels += BmSettings.LENS_TELE
        if (supportedPhysicalIds.contains(PHYSICAL_UW)) labels += BmSettings.LENS_UW
        labels += BmSettings.LENS_LOGICAL
        return labels
    }

    /** Refresca la botonera flotante de lentes con la lente activa. */
    private fun refreshLensPills() {
        val physical = mutableListOf<String>()
        if (supportedPhysicalIds.contains(PHYSICAL_UW)) physical += BmLensPills.LENS_UW
        if (supportedPhysicalIds.contains(PHYSICAL_MAIN)) physical += BmLensPills.LENS_MAIN
        if (supportedPhysicalIds.contains(PHYSICAL_TELE)) physical += BmLensPills.LENS_TELE
        if (lensPills.childCount == 0) {
            lensPills.setAvailableLenses(physical, supportsFps(60))
        }
        val logical = settings.lensKey == BmSettings.LENS_LOGICAL
        lensPills.setActive(
            activeKey = if (logical) BmLensPills.LENS_LOGICAL else settings.lensKey,
            fps60 = logical && settings.fps == 60
        )
    }

    /** Conecta las píldoras de lente con el cambio de sensor. */
    private fun wireLensPills() {
        lensPills.onLensSelected = { key ->
            selectLens(key)
            refreshLensPills()
        }
        lensPills.onFps60Selected = {
            settings.fps = 60
            if (settings.lensKey != BmSettings.LENS_LOGICAL) selectLens(BmSettings.LENS_LOGICAL)
            refreshLensPills()
            pushSettings()
        }
    }

    private fun fpsValues(): List<Int> {
        val values = mutableListOf(24)
        if (supportsFps(30)) values += 30
        if (supportsFps(60)) values += 60
        return values
    }

    private fun supportsFps(fps: Int): Boolean =
        availableFpsRanges.isEmpty() || availableFpsRanges.any { it.upper >= fps && it.lower <= fps }

    // ------------------------------------------------------------- tool bar

    private fun wireToolBar() {
        toolRail.setActive("GUIDES", settings.guides)
        toolRail.setActive("HIST", settings.histogram)
        toolRail.onToggle = { key, enabled -> applyToolState(key, enabled) }
    }

    /** Aplica el estado de un monitor; lo usan la UI y el puente de depuración. */
    private fun applyToolState(key: String, enabled: Boolean) {
        when (key) {
            "PEAKING" -> settings.peaking = enabled
            "ZEBRA" -> settings.zebra = enabled
            "FALSE" -> settings.falseColor = enabled
            "GUIDES" -> {
                settings.guides = enabled
                overlayView.guidesEnabled = enabled
            }
            "HIST" -> settings.histogram = enabled
            "LUT" -> {
                if (enabled && analyzer?.lut == null) lutPicker.launch(arrayOf("*/*"))
                settings.lutEnabled = enabled
                analyzer?.lutEnabled = enabled
                header.setLutActive(enabled)
            }
            "LOG" -> {
                settings.logEnabled = enabled
                pushSettings()
            }
            "ROTATE" -> {
                // Acción (no conmutador): recorre 0/90/180/270 y vuelve a automático.
                previewRotationOverride = when (previewRotation) {
                    0 -> 90
                    90 -> 180
                    180 -> 270
                    else -> null
                }
                toolRail.setActive("ROTATE", false)
                Log.i(TAG_CAMERA, "Giro del visor -> ${previewRotationOverride ?: "automático"}")
                // Cambiar de giro recalcula el lienzo de la superposición y la
                // matriz del visor, sin tocar el tamaño del contenedor.
                previewArea.post { applyPreviewTransform() }
            }
        }
        if (settings.peaking || settings.zebra || settings.falseColor || settings.histogram) analyzer?.start()
        if (!settings.peaking && !settings.zebra && !settings.falseColor) analyzer?.stop()
        refreshBottomStates()
        Log.i(
            TAG_CAMERA,
            "Monitor $key -> $enabled (peak=%b zebra=%b false=%b lut=%b log=%b)".format(
                settings.peaking, settings.zebra, settings.falseColor, settings.lutEnabled, settings.logEnabled
            )
        )
    }

    /**
     * Vuelca el estado de color real a un archivo legible desde adb, porque en
     * este dispositivo (HyperOS) `logcat` no entrega las líneas de la app.
     *
     *   adb shell cat /sdcard/Android/data/com.example.camara/files/bm-diag.log
     */
    private fun dumpDiagnostics(origin: String) {
        val gains = settings.rggbGains()
        val applied = lastAppliedGains
        val line = buildString {
            append(
                "%-6s wb=%dK tint=%d autoWb=%b mode=%s cc=FAST | gains R=%.4f Ge=%.4f Go=%.4f B=%.4f | win=%s..%s".format(
                    Locale.US, origin, settings.kelvin, settings.tint, settings.autoWb,
                    if (settings.autoExposure) "AE" else "M",
                    gains.red, gains.greenEven, gains.greenOdd, gains.blue,
                    BmSettings.gainMin, BmSettings.gainMax
                )
            )
            if (applied != null) {
                append(
                    " | aplicado R=%.4f Ge=%.4f Go=%.4f B=%.4f".format(
                        applied.red, applied.greenEven, applied.greenOdd, applied.blue
                    )
                )
            } else {
                append(" | aplicado n/d")
            }
            lastSnapshot?.let { snapshot ->
                val r = meanChannel(snapshot.redHistogram)
                val g = meanChannel(snapshot.greenHistogram)
                val b = meanChannel(snapshot.blueHistogram)
                append(
                    " | visor R=%.1f G=%.1f B=%.1f  R/G=%.3f B/G=%.3f".format(
                        r, g, b, r / max(0.01, g), b / max(0.01, g)
                    )
                )
            } ?: append(" | visor n/d")
        }
        BmDiagnostics.write(this, line)
        Log.i(TAG_METER, line)
    }

    /** Media de un histograma de 256 niveles, para medir la dominante de color. */
    private fun meanChannel(histogram: IntArray): Double {
        var total = 0L
        var weighted = 0L
        histogram.forEachIndexed { level, count ->
            total += count
            weighted += level.toLong() * count
        }
        return if (total == 0L) 0.0 else weighted.toDouble() / total
    }

    /** Comandos del puente de depuración (BmDebugReceiver, solo build debug). */
    private fun handleDebugCommand(command: String) {        if (captureSession == null) {
            Log.i(TAG_CAMERA, "DEBUG en espera de sesión: $command")
            pendingDebugCommand = command
            return
        }
        executeDebugCommand(command)
    }

    private fun executeDebugCommand(command: String) {
        val parts = command.split(":")
        val mutating = setOf("iso", "shutter", "wb", "tint", "focus", "fps", "lens")
        if (isRecording && mutating.contains(parts.firstOrNull())) {
            Log.i(TAG_CAMERA, "Ajuste bloqueado durante la grabación: $command")
            return
        }
        when (parts.getOrNull(0)) {
            "iso" -> parts.getOrNull(1)?.toIntOrNull()?.let {
                settings.iso = it.coerceIn(settings.isoMin, settings.isoMax)
                pushSettings()
            }
            "shutter" -> parts.getOrNull(1)?.let { value ->
                val nanos = if (value.contains('/')) {
                    value.substringAfter('/').toLongOrNull()?.takeIf { it > 0 }?.let { 1_000_000_000L / it }
                } else {
                    value.toLongOrNull()
                }
                if (nanos != null) {
                    settings.shutterNs = nanos.coerceIn(settings.shutterMinNs, settings.shutterMaxNs)
                    pushSettings()
                }
            }
            "wb" -> parts.getOrNull(1)?.toIntOrNull()?.let {
                settings.kelvin = it.coerceIn(2000, 12000)
                pushSettings()
                dumpDiagnostics("cmd")
            }
            "tint" -> parts.getOrNull(1)?.toIntOrNull()?.let {
                settings.tint = it.coerceIn(BmSettings.TINT_MIN, BmSettings.TINT_MAX)
                pushSettings()
                dumpDiagnostics("cmd")
            }
            "focus" -> parts.getOrNull(1)?.toFloatOrNull()?.let {
                settings.focusDiopters = it.coerceIn(0f, settings.focusMinDiopters)
                pushSettings()
            }
            "fps" -> parts.getOrNull(1)?.toIntOrNull()?.let {
                settings.fps = it
                pushSettings()
            }
            "lens" -> parts.getOrNull(1)?.let { value ->
                selectLens(if (value.equals("1x", ignoreCase = true)) BmSettings.LENS_MAIN else value.uppercase())
            }
            "tool" -> {
                val key = parts.getOrNull(1)?.uppercase()
                if (key != null) {
                    val enabled = parts.getOrNull(2)?.equals("on", ignoreCase = true) == true
                    toolRail.setActive(key, enabled)
                    applyToolState(key, enabled)
                }
            }
            "lut" -> {
                val path = command.substringAfter("lut:", "").trim()
                if (path.isNotEmpty()) loadLutFromPath(path)
            }
            "tapfocus" -> {
                val nx = parts.getOrNull(1)?.toFloatOrNull() ?: 0.5f
                val ny = parts.getOrNull(2)?.toFloatOrNull() ?: 0.5f
                focusBox.show(nx, ny)
                // Igual que el toque real: se pasa por la conversión de coordenadas.
                val buffer = viewToBuffer(nx, ny)
                triggerTapToFocus(buffer.first, buffer.second)
                Log.i(TAG_CAMERA, "Tap-to-focus vista ($nx, $ny) -> búfer (%.3f, %.3f)".format(buffer.first, buffer.second))
            }
            "record" -> when (parts.getOrNull(1)?.lowercase()) {
                "start" -> if (!isRecording) beginRecording()
                "stop" -> if (isRecording) stopRecording()
            }
            "format" -> {
                val want = parts.getOrNull(1)?.lowercase()
                val quality = when (want) {
                    "og", "opengate", "4:3", "og43" -> BmSettings.Quality.OG43
                    "dci" -> BmSettings.Quality.DCI
                    "uhd", "4k" -> BmSettings.Quality.UHD
                    "2:1", "k38", "38k" -> BmSettings.Quality.K38
                    "hd", "1080", "1080p" -> BmSettings.Quality.HD
                    else -> null
                }
                if (quality != null) setQuality(quality)
            }
            "rotate" -> {
                previewRotationOverride = parts.getOrNull(1)?.toIntOrNull()?.let { ((it % 360) + 360) % 360 }
                previewArea.post {
                    applyPreviewAspect()
                    applyPreviewTransform()
                }
            }
            // Modos automáticos reales: "auto:ae:on", "auto:wb:off", "auto:af:on".
            "auto" -> {
                val enabled = parts.getOrNull(2)?.equals("on", ignoreCase = true) == true
                when (parts.getOrNull(1)?.lowercase()) {
                    "ae" -> setAutoExposure(enabled)
                    "wb" -> setAutoWb(enabled)
                    "af" -> if (settings.autoFocus != enabled) toggleFocusMode()
                }
            }
            // Diagnóstico en archivo: cat /sdcard/Android/data/com.example.camara/files/bm-diag.log
            "diag" -> {
                if (parts.getOrNull(1)?.equals("clear", ignoreCase = true) == true) {
                    BmDiagnostics.clear(this)
                } else {
                    dumpDiagnostics("cmd")
                }
            }
            // Matriz de color: propia del ISP o neutra, para comparar en vivo.
            "matrix" -> {
                Log.i(TAG_CAMERA, "La matriz de color la fija ahora el HAL (COLOR_CORRECTION_MODE_FAST)")
            }
            "rail" -> setRailOpen(parts.getOrNull(1)?.equals("on", ignoreCase = true) == true)
            // Bloqueo de orientación (puente de depuración): orient:portrait|landscape|auto
            "orient" -> {
                requestedOrientation = when (parts.getOrNull(1)?.lowercase()) {
                    "portrait", "v" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    "landscape", "h" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
                Log.i(TAG_CAMERA, "Orientación pedida: ${parts.getOrNull(1) ?: "auto"}")
            }
            "meters" -> setOverlaysVisible(
                parts.getOrNull(1)?.equals("on", ignoreCase = true) == true
            )
        }
        updateParameterDisplay()
    }

    /** Carga una LUT desde una ruta absoluta (usado por el puente de depuración). */
    private fun loadLutFromPath(path: String) {
        try {
            val file = java.io.File(path)
            if (!file.exists()) {
                Log.e(TAG_CAMERA, "LUT no encontrada: $path")
                return
            }
            val lut = BmLut3D.parseCube(file.readText())
            if (lut == null) {
                Log.e(TAG_CAMERA, "El archivo no es una LUT .cube 3D válida: $path")
                return
            }
            analyzer?.lut = lut
            analyzer?.lutEnabled = true
            settings.lutEnabled = true
            toolRail.setActive("LUT", true)
            refreshBottomStates()
            Log.i(TAG_CAMERA, "LUT cargada desde ruta: ${lut.title} ($path)")
        } catch (t: Throwable) {
            Log.e(TAG_CAMERA, "Error cargando LUT de $path: ${t.message}")
        }
    }

    private fun loadLut(uri: Uri) {        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (text == null) {
                Log.e(TAG_CAMERA, "LUT ilegible: $uri")
                return
            }
            val lut = BmLut3D.parseCube(text)
            if (lut == null) {
                Log.e(TAG_CAMERA, "El archivo no es una LUT .cube 3D válida")
                toolRail.setActive("LUT", false)
                return
            }
            analyzer?.lut = lut
            analyzer?.lutEnabled = true
            settings.lutEnabled = true
            toolRail.setActive("LUT", true)
            Log.i(TAG_CAMERA, "LUT cargada: ${lut.title} desde $uri")
        } catch (t: Throwable) {
            Log.e(TAG_CAMERA, "Error cargando LUT: ${t.message}")
        }
    }

    // ------------------------------------------------------------- cámara

    /** Abre la cámara en el hilo dedicado, nunca en el de interfaz. */
    private fun openCamera() {
        cameraHandler.post { openCameraInternal() }
    }

    private fun openCameraInternal() {
        val manager = cameraManager ?: return
        probeCapabilities(manager)
        val cameraId = if (manager.getCameraIdList().contains(LOGICAL_CAMERA_ID)) LOGICAL_CAMERA_ID
        else manager.getCameraIdList().firstOrNull() ?: return
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createSession(null)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                    captureSession = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG_CAMERA, "CameraDevice error $error")
                    device.close()
                    cameraDevice = null
                    captureSession = null
                }
            }, cameraHandler)
        } catch (t: Throwable) {
            Log.e(TAG_CAMERA, "openCamera($cameraId) falló: ${t.message}")
        }
    }

    /** Registra en log lo que realmente ofrece el dispositivo (evidencia de configuración). */
    private fun probeCapabilities(manager: CameraManager) {
        try {
            val ids = manager.getCameraIdList()
            Log.i(TAG_CAMERA, "PROBE cámaras disponibles: ${ids.joinToString()}")
            ids.forEach { id ->
                val chars = manager.getCameraCharacteristics(id)
                val level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val physical = if (android.os.Build.VERSION.SDK_INT >= 28) chars.physicalCameraIds else emptySet()
                val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val focusMin = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val recorderSizes = map?.getOutputSizes(android.media.MediaRecorder::class.java)
                    ?.sortedByDescending { it.width.toLong() * it.height }
                    ?.take(5)
                    ?.joinToString { "${it.width}x${it.height}" }
                Log.i(
                    TAG_CAMERA,
                    "PROBE[$id] level=$level physical=${physical.joinToString()} iso=$isoRange exposure=$exposureRange " +
                        "focusMin=$focusMin apertures=${apertures?.joinToString()} fps=${fpsRanges?.joinToString()} " +
                        "sensorOrientation=${chars.get(CameraCharacteristics.SENSOR_ORIENTATION)} " +
                        "focales=${chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()} " +
                        "mediaRecorderSizes=$recorderSizes"
                )
                if (id == LOGICAL_CAMERA_ID) {
                    supportedPhysicalIds.clear()
                    supportedPhysicalIds += physical
                    availableFpsRanges = fpsRanges?.toList() ?: emptyList()
                    characteristics = chars
                    sensorOrientation =
                        chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                    // El visor arranca con la relación de aspecto de la calidad activa.
                    previewSize = choosePreviewSize(settings.quality)
                    analyzer?.configure(previewSize.width, previewSize.height)
                    previewArea.post {
                        applyPreviewAspect()
                        applyPreviewTransform()
                    }
                    // Índices discretos generados desde los rangos reales del
                    // sensor, como en FreeDcam (IsoDetector / ExposureTimeDetector).
                    isoRange?.let {
                        settings.isoMin = it.lower
                        settings.isoMax = it.upper
                    }
                    isoStops = settings.buildIsoStops(settings.isoMin, settings.isoMax)
                    exposureRange?.let {
                        settings.shutterMinNs = it.lower
                        settings.shutterMaxNs = it.upper
                    }
                    shutterStops = settings.buildShutterStops(settings.shutterMinNs, settings.shutterMaxNs)
                    // El valor activo se ajusta al índice válido más cercano.
                    settings.iso = isoStops[settings.nearestIndex(isoStops, settings.iso)]
                    settings.shutterNs =
                        shutterStops[settings.nearestIndex(shutterStops, settings.shutterNs)]
                    Log.i(
                        TAG_CAMERA,
                        "Índices: isoStops=${isoStops.size} (${isoStops.first()}..${isoStops.last()}) " +
                            "shutterStops=${shutterStops.size} " +
                            "(${shutterStops.first()}ns..${shutterStops.last()}ns) " +
                            "activos iso=${settings.iso} shutter=${settings.shutterLabel}"
                    )
                    settings.focusMinDiopters = focusMin ?: 0f
                    settings.apertureLabel = apertures?.firstOrNull()?.let { value ->
                        String.format(Locale.US, "f/%.1f", value)
                    } ?: "—"
                    val toneModes: IntArray? = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
                    zoomRatioSupported = chars.keys.any { it.name == "CONTROL_ZOOM_RATIO" }
                    // Matriz de calibración del sensor para el balance manual.
                    sensorColorTransform = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
                        ?: chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
                    Log.i(
                        TAG_CAMERA,
                        "PROBE colorTransform=" +
                            (sensorColorTransform?.toString()?.replace("\n", " ") ?: "no publicada") +
                            " awbPresets=${chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.size}"
                    )
                    contrastCurveSupported =
                        toneModes?.any { it == CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE } == true
                    // Rango real de ganancias RGGB del sensor: acota el balance de
                    // blancos manual.
                    // Android no publica el rango de COLOR_CORRECTION_GAINS, así
                    // que se acota a una ventana segura y se estrecha en caliente
                    // si el HAL rechaza un valor (ver pushSettings).
                    val vendorGainMax = chars.keys
                        .firstOrNull { it.name.contains("MAX_GAIN", ignoreCase = true) }
                        ?.let { runCatching { chars.get(it) as? Int }.getOrNull() }
                    settings.setGainWindow(0.25f, (vendorGainMax?.coerceIn(1, 16)?.toFloat()) ?: 4.0f)
                    val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                    Log.i(
                        TAG_CAMERA,
                        "PROBE awbModes=${awbModes?.joinToString()} gainWindow=" +
                            "${BmSettings.gainMin}..${BmSettings.gainMax} vendorMaxKey=${vendorGainMax ?: "n/d"}"
                    )
                    Log.i(TAG_CAMERA, "PROBE tonemapModes=${toneModes?.joinToString()} curvaLog=$contrastCurveSupported")
                    Log.i(
                        TAG_CAMERA,
                        "PROBE ajustes app: iso=${settings.iso} isoStops=${isoStops.size} shutterMax=${settings.shutterMaxNs} " +
                            "focusMin=${settings.focusMinDiopters} contrastCurve=$contrastCurveSupported " +
                            "gainRange=${BmSettings.gainMin}..${BmSettings.gainMax}"
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG_CAMERA, "PROBE falló: ${t.message}")
        }
    }

    /** Crea la sesión en el hilo de cámara; el llamante nunca se bloquea. */
    private fun createSession(recorderSurface: Surface?) {
        cameraHandler.post { createSessionInternal(recorderSurface) }
    }

    /**
     * Rehace la previsualización sobre una superficie nueva.
     *
     * Se ejecuta siempre en el hilo de cámara. Si el dispositivo ya está abierto
     * no se reabre —volver a llamar a `openCamera` falla y dejaba el visor en
     * negro—: sólo se cambia el destino de la sesión.
     */
    private fun rebindPreviewSurface(surfaceTexture: SurfaceTexture) {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        captureSession = null
        previewSurface?.release()
        previewSurface = Surface(surfaceTexture)
        Log.i(TAG_CAMERA, "Superficie del visor reenlazada (dispositivo=${cameraDevice != null})")
        if (cameraDevice != null) createSessionInternal(null) else openCameraInternal()
    }

    private fun createSessionInternal(recorderSurface: Surface?) {
        val device = cameraDevice ?: return
        // Nunca se crea una sesión sin superficie lista: en MediaTek eso deja el
        // visor en negro hasta reiniciar la cámara.
        if (!textureView.isAvailable) {
            Log.w(TAG_CAMERA, "createSession aplazado: la superficie del visor aún no está disponible")
            return
        }
        val texture = textureView.surfaceTexture ?: run {
            Log.w(TAG_CAMERA, "createSession aplazado: surfaceTexture nulo")
            return
        }
        // Se reutiliza el Surface ya enlazado si es el de esta misma textura; sólo
        // se crea uno nuevo cuando no hay ninguno (primer arranque).
        val surface = previewSurface ?: Surface(texture).also { previewSurface = it }

        val outputs = ArrayList<OutputConfiguration>()
        val previewConfig = OutputConfiguration(surface)
        val recorderConfig = recorderSurface?.let { OutputConfiguration(it) }

        val physical = activePhysicalId
        if (physical != null && supportedPhysicalIds.contains(physical)) {
            try {
                previewConfig.setPhysicalCameraId(physical)
                recorderConfig?.setPhysicalCameraId(physical)
                Log.i(TAG_CAMERA, "Sesión con cámara física id=$physical sobre lógica $LOGICAL_CAMERA_ID")
            } catch (t: Throwable) {
                Log.e(TAG_CAMERA, "setPhysicalCameraId($physical) falló: ${t.message}")
            }
        }
        outputs += previewConfig
        if (recorderConfig != null) outputs += recorderConfig

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val builder = buildRequest(recorderSurface) ?: return
                        requestBuilder = builder
                        session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
                        if (isRecording && recorderSurface == null) {
                            // La sesión de grabación se recreó sin superficie: no ocurre en uso normal.
                            Log.w(TAG_CAMERA, "Sesión configurada sin superficie de grabación")
                        }
                        if (startRecordingOnConfigured) {
                            startRecordingOnConfigured = false
                            runOnUiThread { startEncoder() }
                        } else {
                            val pending = pendingDebugCommand
                            if (pending != null) {
                                pendingDebugCommand = null
                                runOnUiThread { handleDebugCommand(pending) }
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG_CAMERA, "setRepeatingRequest falló: ${t.message}")
                        if (startRecordingOnConfigured) {
                            startRecordingOnConfigured = false
                            videoRecorder?.abort()
                        }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG_CAMERA, "Configuración de sesión fallida (physical=$physical)")
                    if (startRecordingOnConfigured) {
                        startRecordingOnConfigured = false
                        videoRecorder?.abort()
                    }
                    // Reintento sin cámara física para no dejar el visor en negro.
                    if (physical != null) {
                        activePhysicalId = null
                        createSession(recorderSurface)
                    }
                }
            }
        )
        try {
            device.createCaptureSession(config)
        } catch (t: Throwable) {
            Log.e(TAG_CAMERA, "createCaptureSession falló: ${t.message}")
            if (startRecordingOnConfigured) {
                startRecordingOnConfigured = false
                videoRecorder?.abort()
            }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val appliedIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val appliedExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val appliedGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            lastAppliedGains = appliedGains
            val appliedFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            val appliedAfMode = result.get(CaptureResult.CONTROL_AF_MODE)
            val appliedAfState = result.get(CaptureResult.CONTROL_AF_STATE)
            val now = System.currentTimeMillis()
            // En automático, los valores que muestra la barra son los que el
            // propio sensor está aplicando, leídos del resultado de captura.
            if (settings.autoExposure) {
                appliedIso?.let { if (it != settings.iso) { settings.iso = it; autoDisplayDirty = true } }
                appliedExposure?.let {
                    if (it != settings.shutterNs) { settings.shutterNs = it; autoDisplayDirty = true }
                }
            }
            if (settings.autoWb) {
                // La cámara resuelve el balance: se muestra su estado real y, como
                // diagnóstico, la estimación de temperatura equivalente.
                appliedGains?.let { gains ->
                    val (kelvin, tint) = settings.estimateKelvinTint(gains)
                    if (now - lastAppliedLog > 2000) {
                        Log.i(TAG_METER, "AWB dispositivo: ganancias=$gains ~${kelvin}K tinte=$tint")
                    }
                }
            }
            if (autoDisplayDirty && now - lastAutoDisplayUpdate > 400) {
                autoDisplayDirty = false
                lastAutoDisplayUpdate = now
                mainHandler.post { updateParameterDisplay() }
            }
            if (now - lastAppliedLog > 2000) {
                lastAppliedLog = now
                Log.i(
                    TAG_METER,
                    "APPLIED iso=$appliedIso exposure=$appliedExposure gains=$appliedGains focus=$appliedFocus " +
                        "afMode=$appliedAfMode afState=$appliedAfState lens=${settings.lensKey}"
                )
            }
        }
    }

    private var lastAppliedLog = 0L
    private var lastAutoDisplayUpdate = 0L
    private var autoDisplayDirty = false

    /** Últimas ganancias RGGB que confirmó el sensor en el CaptureResult. */
    private var lastAppliedGains: RggbChannelVector? = null

    /** Último fotograma analizado, para medir la dominante de color en vivo. */
    private var lastSnapshot: BmMonitorAnalyzer.Snapshot? = null

    private fun buildRequest(recorderSurface: Surface?): CaptureRequest.Builder? {
        val device = cameraDevice ?: return null
        val preview = previewSurface ?: return null
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(preview)
        if (recorderSurface != null) builder.addTarget(recorderSurface)
        fillRequest(builder)
        return builder
    }

    /** Escribe todos los ajustes manuales en el builder (se reutiliza al reenviar). */
    private fun fillRequest(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (settings.videoStabilization) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        // Exposición: manual (ISO y obturación de BmSettings) o automática del
        // dispositivo. En AUTO NO se envían SENSOR_EXPOSURE_TIME ni
        // SENSOR_SENSITIVITY: si se mandan, el HAL de MediaTek ignora el AE. Los
        // valores reales que decide la cámara se leen del CaptureResult.
        if (settings.autoExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.shutterNs)
        }
        // La duración de fotograma la gobierna el AE en automático; en manual se
        // fija para respetar los fps pedidos.
        if (!settings.autoExposure) {
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / max(1, settings.fps))
        }
        bestFpsRange(settings.fps)?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        // Bloqueos de exposición y balance (OpenCamera, CameraController2).
        // En Camera2 estos dos ejes son booleanos: true congela el valor que
        // acaba de resolver el algoritmo automático. El bloqueo de AE sólo tiene
        // sentido con el AE activo.
        builder.set(CaptureRequest.CONTROL_AE_LOCK, settings.aeLock && settings.autoExposure)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, settings.awbLock && settings.autoWb)
        // Estabilización óptica del objetivo, sumada a la electrónica.
        builder.set(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            if (settings.opticalStabilization) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        )
        // Zoom suave por ratio, si el HAL lo publica.
        if (zoomRatioSupported) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, settings.zoomRatio.coerceAtLeast(1.0f))
        }
        // Balance de blancos: AWB nativo del ISP (insignia «A») o ganancias RGGB
        // calculadas con Planck + tinte.
        if (settings.autoWb) {
            // Arranque en automático: el ISP del Vivo resuelve el punto blanco y
            // mantiene su matriz calibrada (FAST), que es el color de fábrica.
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        } else {
            // Manual: AWB_MODE_OFF + COLOR_CORRECTION_MODE_FAST y sólo las
            // ganancias RGGB. Medido en el Vivo X300:
            //  - TRANSFORM_MATRIX con SENSOR_COLOR_TRANSFORM1 hunde el rojo
            //    (visor R/G = 0,117) porque esa matriz lleva el RGB del sensor a
            //    CIE XYZ, no al espacio lineal de sRGB que espera
            //    COLOR_CORRECTION_TRANSFORM.
            //  - FAST conserva la matriz calibrada del HAL y acepta las
            //    ganancias, que es el camino de PhotonCamera (ParamController).
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, settings.rggbGains())
        }
        // Enfoque: AF continuo por defecto; MF con distancia de foco. El
        // disparo de tap-to-focus se hace aparte con un capture() puntual, NUNCA
        // dentro del repeatingRequest: si se deja AF_TRIGGER_START ahí, el HAL
        // reinicia el enfoque en cada fotograma y nunca llega a enfocar.
        if (settings.autoFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            focusRegions?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, it) }
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDiopters)
        }
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        if (settings.logEnabled && contrastCurveSupported) {
            builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            builder.set(CaptureRequest.TONEMAP_CURVE, toneMapCurve())
        } else {
            builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        }
    }

    /**
     * Preset de balance de blancos del HAL más cercano a la temperatura pedida.
     *
     * Es el respaldo cuando el sensor no publica matriz de calibración: los
     * presets los aplica el ISP, así que no pueden teñir la imagen.
     */
    private fun presetAwbMode(kelvin: Int): Int = when {
        kelvin <= 3000 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        kelvin <= 4200 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        kelvin <= 5200 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        kelvin <= 6500 -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        else -> CaptureRequest.CONTROL_AWB_MODE_SHADE
    }

    /** Reenvía los ajustes actuales al sensor en el hilo de cámara.
     *
     * Se sincroniza y se coalesce: arrastrar la regla graduada genera decenas de
     * llamadas por segundo y cada `setRepeatingRequest` es una petición al HAL.
     * Aquí sólo se deja una actualización pendiente en la cola del hilo de
     * cámara, así que el HAL recibe como mucho una por ciclo y ninguna desde el
     * hilo de interfaz.
     */
    @Synchronized
    private fun pushSettings() {
        if (updatePending) return
        updatePending = true
        cameraHandler.post(updatePreviewRunnable)
    }

    private val updatePreviewRunnable = Runnable {
        synchronized(this) { updatePending = false }
        pushSettingsInternal()
    }

    private fun pushSettingsInternal() {
        val session = captureSession ?: return
        val builder = requestBuilder ?: return
        fillRequest(builder)
        try {
            session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
            Log.i(
                TAG_METER,
                "PUSH iso=%d shutter=%s wb=%dK tint=%d fps=%d focus=%.2f lens=%s autoAe=%b autoWb=%b %s".format(
                    Locale.US, settings.iso, settings.shutterLabel, settings.kelvin, settings.tint,
                    settings.fps, settings.focusDiopters, settings.lensKey,
                    settings.autoExposure, settings.autoWb,
                    if (settings.autoWb) "AWB=ISP" else settings.gainsLabel()
                )
            )
        } catch (t: IllegalArgumentException) {
            handleRejectedSettings(t)
        } catch (t: android.hardware.camera2.CameraAccessException) {
            handleRejectedSettings(t)
        }
    }

    /**
     * El HAL rechazó la petición. Si el problema está en las ganancias RGGB, se
     * estrecha la ventana al rango neutro y se reintenta una vez; nunca propaga
     * la excepción al hilo de interfaz.
     */
    private fun handleRejectedSettings(cause: Throwable) {
        val session = captureSession
        val builder = requestBuilder
        if (session != null && builder != null && !settings.autoWb && BmSettings.narrowGainWindow()) {
            Log.w(
                TAG_METER,
                "Petición rechazada (${cause.message}); ventana de ganancias -> " +
                    "${BmSettings.gainMin}..${BmSettings.gainMax}, reintentando"
            )
            fillRequest(builder)
            try {
                session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
                Log.i(TAG_METER, "PUSH reintentado con ${settings.gainsLabel()}")
                return
            } catch (retry: Exception) {
                Log.e(TAG_CAMERA, "Reintento de ganancias falló: ${retry.message}")
            }
        }
        Log.e(TAG_CAMERA, "No se pudo aplicar el ajuste: ${cause.message}")
    }

    private fun bestFpsRange(fps: Int): Range<Int>? {
        if (availableFpsRanges.isEmpty()) return Range(fps, fps)
        val exact = availableFpsRanges.filter { it.upper == fps && it.lower == fps }
        if (exact.isNotEmpty()) return exact.first()
        val containing = availableFpsRanges.filter { it.lower <= fps && it.upper >= fps }
        return containing.minByOrNull { it.upper - it.lower } ?: availableFpsRanges.maxByOrNull { it.upper }
    }

    /** Curva de tonos del perfil activo, lista para `TONEMAP_CURVE`. */
    private fun toneMapCurve(): TonemapCurve {
        val curve = settings.toneCurve(settings.toneProfile)
        return TonemapCurve(curve, curve.copyOf(), curve.copyOf())
    }

    /** Cambia de lente física (o vuelve a la lógica) recreando la sesión. */
    private fun selectLens(label: String) {
        if (isRecording) {
            Log.i(TAG_CAMERA, "Lente bloqueada durante la grabación")
            return
        }
        settings.lensKey = label
        centerDial.hide()
        topBar.setActive("")
        activePhysicalId = when (label) {
            BmSettings.LENS_MAIN -> PHYSICAL_MAIN.takeIf { supportedPhysicalIds.contains(it) }
            BmSettings.LENS_TELE -> PHYSICAL_TELE.takeIf { supportedPhysicalIds.contains(it) }
            BmSettings.LENS_UW -> PHYSICAL_UW.takeIf { supportedPhysicalIds.contains(it) }
            else -> null
        }
        if (label == BmSettings.LENS_LOGICAL) settings.fps = 60
        Log.i(TAG_CAMERA, "Lente -> $label physical=$activePhysicalId fps=${settings.fps}")
        captureSession?.let {
            try {
                it.stopRepeating()
                it.close()
            } catch (t: Throwable) {
                Log.w(TAG_CAMERA, "close sesión previa: ${t.message}")
            }
        }
        captureSession = null
        updateParameterDisplay()
        createSession(null)
    }

    private fun updateParameterDisplay() {
        topBar.setValues(
            lens = settings.lensKey,
            fps = "${settings.fps}",
            shutter = settings.shutterLabel,
            iris = settings.apertureLabel,
            iso = "${settings.iso}",
            wb = if (settings.autoWb) "AUTO" else "${settings.kelvin}K",
            tint = if (settings.autoWb) "AUTO" else "${settings.tint}",
            focus = settings.focusLabel,
            autoExposure = settings.autoExposure,
            autoWb = settings.autoWb,
            manualFocus = !settings.autoFocus
        )
        val (main, sub) = settings.formatBadge
        header.setFormat(main, sub)
        refreshBottomStates()
    }

    // ---------------------------------------------------------- grabación

    private fun wireRecordButton() {
        bottomBar.recordButton.setOnClickListener { toggleRecording() }
    }

    /** Selector de calidad: todas las resoluciones nativas del sensor. */
    private fun showQualityDialog() {
        if (isRecording) {
            Log.i(TAG_CAMERA, "Formato bloqueado durante la grabación")
            Toast.makeText(this, "Formato bloqueado durante la grabación", Toast.LENGTH_SHORT).show()
            return
        }
        val qualities = BmSettings.Quality.entries
        val labels = qualities.map {
            "${it.main} | ${it.sub}   (${it.width}x${it.height} · ${it.bitrate / 1_000_000} Mbps)"
        }.toTypedArray()
        val checked = qualities.indexOf(settings.quality)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Calidad de grabación")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                setQuality(qualities[which])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Aplica la calidad elegida: visor, analizador, cabecera y sesión. */
    private fun setQuality(quality: BmSettings.Quality) {
        if (isRecording || quality == settings.quality) return
        settings.quality = quality
        val previous = previewSize
        val next = choosePreviewSize(quality)
        previewSize = next
        analyzer?.configure(previewSize.width, previewSize.height)
        val (badge, subBadge) = settings.formatBadge
        header.setFormat(badge, subBadge)
        Log.i(
            TAG_CAMERA,
            "CALIDAD -> ${settings.formatLabel} (${quality.width}x${quality.height}) " +
                "preview ${previewSize.width}x${previewSize.height} caudal ${settings.targetBitrate / 1_000_000} Mbps"
        )
        // Sólo se ajusta la geometría del contenedor: la superficie y la sesión de
        // cámara siguen vivas, así que el pipeline de MediaTek no se reinicia.
        previewArea.post { applyPreviewAspect() }
        if (previous != next) {
            // El búfer no puede cambiar con la sesión en marcha: se rehace la sesión
            // de forma ordenada, con la superficie ya disponible.
            Log.i(TAG_CAMERA, "Búfer de previsión ${previous} -> $next: se recrea la sesión")
            textureView.surfaceTexture?.let { it.setDefaultBufferSize(next.width, next.height) }
            if (textureView.isAvailable) createSession(null)
        }
    }

    /**
     * Tamaño de búfer del visor, elegido entre las resoluciones que MediaTek
     * publica para este sensor: 1920x1080 y, si no está, 1440x1080 (4:3 con
     * recorte), que son las que este HAL entrega sin problemas.
     *
     * Es un tamaño ÚNICO y estable: no cambia con la calidad elegida. Cambiar
     * `setDefaultBufferSize` con la sesión viva invalida el búfer del productor
     * en MediaTek y deja el visor en negro; la geometría la fija exclusivamente
     * [applyPreviewAspect] sobre el contenedor, no el búfer.
     */
    private fun choosePreviewSize(quality: BmSettings.Quality): Size {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return STABLE_PREVIEW_SIZE
        supportedBufferSizes.firstOrNull { candidate ->
            sizes.any { it.width == candidate.width && it.height == candidate.height }
        }?.let { return it }
        // Último recurso: el mayor 16:9 disponible, o el mayor a secas.
        val sameAspect = sizes.filter {
            kotlin.math.abs(it.width.toFloat() / it.height - 16f / 9f) < 0.02f
        }
        return (sameAspect.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height })
            ?: STABLE_PREVIEW_SIZE
    }

    /**
     * Relación de aspecto del contenedor del visor.
     *
     * Es la única fuente de la geometría: el contenedor `previewFrame` (y con él
     * el `TextureView`, la superposición y el recuadro de enfoque, que lo llenan
     * con `match_parent`) se dimensiona con la proporción exacta de los píxeles
     * que entrega el sensor. Así la matriz del visor sólo tiene que girar, nunca
     * escalar ni estirar.
     *
     *  - Vertical: proporción inversa a la del sensor, porque el búfer se gira
     *    90°. 16:9 / UHD -> 9:16 (alto = ancho * 16 / 9) y Open Gate 4:3 -> 3:4
     *    (alto = ancho * 4 / 3). Si no cabe en el alto disponible se invierte el
     *    cálculo sobre ese alto.
     *  - Horizontal: proporción nativa del sensor (16:9 o 4:3).
     */
    private fun applyPreviewAspect() {
        val areaWidth = previewArea.width
        val available = previewArea.height
        if (areaWidth <= 0 || available <= 0) return
        // El marco se ajusta al formato de GRABACIÓN elegido, no al búfer de
        // previsualización: así Open Gate mide 3:4 en vertical y 4:3 en
        // horizontal, y UHD/16:9 mide 9:16 / 16:9, siempre centrado.
        val frameAspect = settings.frameAspect
        if (!frameAspect.isFinite() || frameAspect <= 0f) return

        // Ancho / alto que debe tener el contenedor en pantalla.
        val targetRatio = if (isPortraitDisplay()) 1f / frameAspect else frameAspect
        val margin = dp(7)
        var width = (areaWidth - margin * 2).coerceAtLeast(1)
        var height = (width / targetRatio).toInt()
        if (height > available) {
            height = available
            width = (height * targetRatio).toInt().coerceAtLeast(1)
        }
        val params = previewFrame.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            previewFrame.layoutParams = params
            Log.i(
                TAG_CAMERA,
                "Visor ${width}x${height} contenedor=${"%.4f".format(Locale.US, width.toFloat() / height)} " +
                    "formato=${settings.formatLabel} búfer=${previewSize.width}x${previewSize.height} " +
                    "vertical=${isPortraitDisplay()}"
            )
        }
    }

    private fun isPortraitDisplay(): Boolean {
        val rotation = display?.rotation ?: Surface.ROTATION_0
        return rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
    }

    /**
     * Giro que hay que aplicar al visor: la orientación física del teléfono, que
     * es la que hace coincidir la imagen con lo que se ve en la aplicación de
     * referencia (medido lado a lado). El puente de depuración y el control ROTAR
     * pueden forzarlo.
     */
    /**
     * Giro del visor.
     *
     * Medido contra la aplicación de referencia, con la misma escena y posición,
     * en las dos orientaciones: en vertical el cuadro ya llega derecho (0°) y en
     * horizontal hay que girarlo 270°. Es decir, el giro es el opuesto a la
     * rotación de la pantalla; el HAL declara una orientación de sensor de 90°
     * que no corresponde a los cuadros que entrega. El control ROTAR del raíl
     * permite forzarlo en el momento.
     */
    private fun effectivePreviewRotation(): Int {
        previewRotationOverride?.let { return it }
        return (360 - displayRotationDegrees()) % 360
    }

    private fun displayRotationDegrees(): Int = when (display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /**
     * Transformación del visor: única y sin reintentos.
     *
     * No toca `layoutParams` ni se reaplica sola, así que no puede entrar en un
     * bucle de relayout que invalide la superficie mientras Camera2 renderiza
     * (ese bucle congelaba el pipeline en MediaTek).
     *
     * El contenedor `previewFrame` ya tiene la proporción exacta de los píxeles
     * del búfer, así que `TextureView` mapea búfer -> vista sin estirar. Lo único
     * que no puede resolverse con el tamaño es la orientación: el búfer del
     * sensor llega apaisado y hay que girarlo para que llene el contenedor
     * vertical. Ese giro es un intercambio de ejes (escala uniforme, sin
     * deformación) y no altera las dimensiones de la vista.
     */
    private fun applyPreviewTransform() {
        previewRotation = effectivePreviewRotation()
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        val bw = previewSize.width.toFloat()
        val bh = previewSize.height.toFloat()

        val matrix = Matrix()
        if (vw > 0f && vh > 0f && bw > 0f && bh > 0f) {
            if (previewRotation % 180 == 90) {
                // Con giro, el búfer intercambia ejes: ocupa (bh x bw) en la vista.
                // La escala es única y se toma la mayor de las dos exigencias para
                // que la imagen CUBRA el contenedor (recorte centrado). Con
                // `vh / bw` la imagen quedaba más estrecha que el marco y aparecían
                // bandas negras.
                val scale = maxOf(vw / bh, vh / bw)
                matrix.postTranslate(vw / 2f, vh / 2f)
                matrix.postRotate(previewRotation.toFloat())
                matrix.postScale(scale, scale)
                matrix.postTranslate(-bh / 2f, -bw / 2f)
            } else {
                // Sin giro, la escala sigue siendo ÚNICA: se cubre el contenedor
                // con el lado más exigente y se centra (recorte, nunca estirado).
                // Sin esto, un búfer 16:9 dentro de un contenedor 4:3 se estiraba
                // y la imagen salía achatada en apaisado.
                val scale = maxOf(vw / bw, vh / bh)
                matrix.postTranslate(vw / 2f, vh / 2f)
                matrix.postScale(scale, scale)
                matrix.postTranslate(-bw / 2f, -bh / 2f)
            }
        }
        textureView.setTransform(matrix)
        previewMatrix.set(matrix)
        overlayView.overlayRotation = previewRotation
        Log.i(
            TAG_CAMERA,
            "Visor ${vw.toInt()}x${vh.toInt()} buffer ${bw.toInt()}x${bh.toInt()} giro=$previewRotation " +
                "sensor=$sensorOrientation físico=$deviceRotation pantalla=${display?.rotation ?: -1}"
        )
    }

    /**
     * Punto normalizado del visor -> punto normalizado del cuadro del sensor.
     *
     * Se aplica la matriz del visor en sentido directo (vista -> búfer) y se
     * normaliza con el tamaño del búfer, que es lo que esperan el recuadro de
     * enfoque y `MeteringRectangle`.
     */
    private fun viewToBuffer(nx: Float, ny: Float): Pair<Float, Float> {
        val view = textureView
        if (view.width <= 0 || view.height <= 0) return nx to ny
        val point = floatArrayOf(nx * view.width, ny * view.height)
        previewMatrix.mapPoints(point)
        val bx = (point[0] / previewSize.width).coerceIn(0f, 1f)
        val by = (point[1] / previewSize.height).coerceIn(0f, 1f)
        return bx to by
    }

    /** Coloca la regla graduada y los vúmetros flotantes justo encima de la barra inferior. */
    private fun placeFloatingControls() {
        val barHeight = bottomContainer.height
        if (barHeight <= 0) return
        val dialHeight = if (centerDial.height > 0) centerDial.height else dp(64)
        (centerDial.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.bottomMargin = barHeight + dp(6)
            centerDial.layoutParams = it
        }
        val stackBottom = barHeight + dp(6) + dialHeight + dp(8)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Tap-to-focus: convierte el punto tocado en un MeteringRectangle sobre
     * SENSOR_INFO_ACTIVE_ARRAY_SIZE, enfoca esa zona y vuelve a AF continuo.
     */
    private fun triggerTapToFocus(normalizedX: Float, normalizedY: Float) {
        val chars = characteristics ?: return
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        if (maxRegions < 1) {
            Log.i(TAG_CAMERA, "Tap-to-focus no soportado por el dispositivo")
            return
        }
        val side = (minOf(active.width(), active.height()) * 0.16f).toInt().coerceAtLeast(1)
        val centerX = active.left + (normalizedX * active.width()).toInt()
        val centerY = active.top + (normalizedY * active.height()).toInt()
        val left = (centerX - side / 2).coerceIn(active.left, (active.right - side).coerceAtLeast(active.left))
        val top = (centerY - side / 2).coerceIn(active.top, (active.bottom - side).coerceAtLeast(active.top))
        val rect = Rect(left, top, left + side, top + side)
        focusRegions = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX))
        Log.i(
            TAG_CAMERA,
            "Tap-to-focus en (%.2f, %.2f) -> region $rect".format(normalizedX, normalizedY)
        )
        // 1) La región se deja puesta para el modo continuo.
        pushSettings()
        // 2) Disparo puntual: un único capture() con AF_MODE_AUTO + TRIGGER_START.
        cameraHandler.post {
            val session = captureSession
            val builder = requestBuilder
            if (session != null && builder != null) {
                try {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, focusRegions)
                    session.capture(
                        builder.build(),
                        null,
                        cameraHandler
                    )
                    Log.i(TAG_CAMERA, "Tap-to-focus: disparo AF puntual enviado")
                } catch (t: android.hardware.camera2.CameraAccessException) {
                    Log.w(TAG_CAMERA, "Disparo AF puntual falló: ${t.message}")
                } catch (t: IllegalArgumentException) {
                    Log.w(TAG_CAMERA, "Disparo AF puntual rechazado: ${t.message}")
                } finally {
                    // 3) El repeating vuelve a continuo con el disparador en IDLE.
                    pushSettings()
                }
            }
        }
    }

    private fun toggleRecording() {
        if (isRecording) stopRecording() else beginRecording()
    }

    private fun beginRecording() {
        centerDial.hide()
        topBar.setActive("")
        val recorder = videoRecorder ?: return
        val chars = characteristics
        if (cameraDevice == null || captureSession == null || chars == null) {
            Log.w(TAG_CAMERA, "Cámara no lista para grabar")
            return
        }
        val size: Size = recorder.chooseSize(chars, settings.quality) ?: run {
            Log.e(TAG_CAMERA, "La cámara no ofrece tamaños para MediaRecorder")
            return
        }
        Log.i(
            TAG_CAMERA,
            "Formato de grabación: ${settings.formatLabel} -> ${size.width}x${size.height}"
        )
        val bitrate = settings.targetBitrate
        // Giro real del visor, en el mismo sentido que espera la pista del MP4.
        val orientation = recorderOrientationHint()
        if (!recorder.prepare(
                size, settings.fps, orientation, bitrate,
                audioEnabled = true, preferHevc = settings.useHevc
            )
        ) {
            Log.e(TAG_CAMERA, "No se pudo preparar el codificador")
            return
        }
        Log.i(
            TAG_CAMERA,
            "Codificador listo: ${recorder.lastFormatLabel} giro=$orientation " +
                "sensor=$sensorOrientation pantalla=${display?.rotation ?: -1}"
        )
        val target = recorder.inputSurface ?: return
        pendingRecorderSurface = target
        startRecordingOnConfigured = true
        captureSession?.let {
            try {
                it.stopRepeating()
                it.close()
            } catch (t: Throwable) {
                Log.w(TAG_CAMERA, "close sesión previa: ${t.message}")
            }
        }
        captureSession = null
        createSession(target)
    }

    private fun startEncoder() {
        val recorder = videoRecorder ?: return
        if (!recorder.start()) {
            Log.e(TAG_CAMERA, "El codificador no arrancó")
            pendingRecorderSurface = null
            createSession(null)
            return
        }
        isRecording = true
        bottomBar.setRecording(true)
        textureView.keepScreenOn = true
        recordStartedAt = System.currentTimeMillis()
        mainHandler.post(timerTick)
        Log.i(
            TAG_CAMERA,
            "Grabación iniciada con ajustes bloqueados: iso=%d shutter=%s wb=%dK tint=%d fps=%d focus=%.2f lens=%s physical=%s".format(
                Locale.US, settings.iso, settings.shutterLabel, settings.kelvin, settings.tint,
                settings.fps, settings.focusDiopters, settings.lensKey, activePhysicalId ?: "0"
            )
        )
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        mainHandler.removeCallbacks(timerTick)
        updateTimecode()
        textureView.keepScreenOn = false
        stereoMeters.reset()
        val uri = videoRecorder?.stop()
        bottomBar.setRecording(false)
        updateFreeSpace()
        Log.i(TAG_CAMERA, "Grabación detenida: ${uri ?: "clip descartado"}")
        pendingRecorderSurface = null
        captureSession?.let {
            try {
                it.stopRepeating()
                it.close()
            } catch (t: Throwable) {
                Log.w(TAG_CAMERA, "close sesión de grabación: ${t.message}")
            }
        }
        captureSession = null
        createSession(null)
    }

    /**
     * `MediaRecorder.setOrientationHint` a partir del giro ya aplicado al visor.
     *
     * El visor usa un giro en sentido horario y la pista del codificador se
     * declara también en sentido horario, así que basta con invertirla para que
     * el MP4 quede derecho con la misma referencia que la previsualización.
     */
    private fun recorderOrientationHint(): Int {
        val rotation = if (previewRotationOverride != null) {
            effectivePreviewRotation()
        } else {
            previewRotation
        }
        return ((360 - rotation) % 360 + 360) % 360
    }

    /**
     * Actualiza el panel de almacenamiento con datos reales: espacio libre,
     * batería y tiempo de grabación restante al caudal configurado.
     */
    private fun updateFreeSpace() {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val freeGb = freeBytes / (1024 * 1024 * 1024)
            val bytesPerSecond = currentBytesPerSecond()
            val remaining = if (bytesPerSecond > 0) freeBytes / bytesPerSecond else 0L
            storagePanel.update(remaining, batteryPercent(), freeBytes)
            Log.i(
                TAG_CAMERA,
                "Almacenamiento: ${freeGb}GB libres, restante=${remaining}s batería=${batteryPercent()}%"
            )
        } catch (t: Throwable) {
            Log.w(TAG_CAMERA, "StatFs falló: ${t.message}")
        }
    }

    /** Caudal de grabación real del codificador, o el previsto en 4K. */
    private fun currentBytesPerSecond(): Long {
        videoRecorder?.let { recorder ->
            val bitrate = recorder.lastBitrate
            if (bitrate > 0) return (bitrate / 8).toLong()
        }
        return (settings.targetBitrate / 8).toLong()
    }

    private fun batteryPercent(): Int {
        val manager = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        return manager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
    }

    // ------------------------------------------------------------ ciclo vida

    override fun onResume() {
        super.onResume()
        if (textureView.isAvailable && cameraDevice == null) openCamera()
        if (settings.peaking || settings.zebra || settings.falseColor || settings.histogram) analyzer?.start()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
        if (isRecording) stopRecording()
        mainHandler.removeCallbacks(timerTick)
        analyzer?.stop()
        cameraDevice?.close()
        cameraDevice = null
        captureSession?.close()
        captureSession = null
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("cmd")?.let { command -> handleDebugCommand(command) }
    }

    override fun onDestroy() {
        super.onDestroy()
        BmDebugBridge.detach()
        analyzer?.shutdown()
        executor.shutdown()
    }
}
