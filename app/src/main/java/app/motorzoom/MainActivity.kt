package app.motorzoom

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import app.motorzoom.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var recording: Recording? = null

    private var currentZoom = 1f
    private var minZoom = 1f
    private var maxZoom = 4f
    private var zoomDirection = 0
    private var zoomUnitsPerSecond = 0.35f
    private var zoomStartNanos = 0L
    private var zoomStartRatio = 1f
    private var lastZoomSubmitNanos = 0L
    private var latestZoomRequest = 1f
    private var lastAppliedZoom = 1f
    private var presetJson = ""
    private var presetName = "Padrão"

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showPostZoomDialog(uri)
    }

    private val presetPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                presetJson = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                check(presetJson.contains("\"version\""))
                presetName = uri.lastPathSegment?.substringAfterLast('/') ?: "Preset importado"
                Toast.makeText(this, "Preset carregado: $presetName", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                presetJson = ""
                presetName = "Padrão"
                Toast.makeText(this, "Esse arquivo não parece ser um preset NTSC-RS", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val zoomFrame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (zoomDirection == 0) {
                zoomStartNanos = 0L
                return
            }

            if (zoomStartNanos == 0L) {
                zoomStartNanos = frameTimeNanos
                zoomStartRatio = currentZoom
            }
            val elapsedSeconds = (frameTimeNanos - zoomStartNanos) / 1_000_000_000f
            currentZoom = (zoomStartRatio + zoomDirection * zoomUnitsPerSecond * elapsedSeconds)
                .coerceIn(minZoom, maxZoom)
            binding.zoomLabel.text = String.format(Locale.US, "%.2f×", currentZoom)

            // Camera commands are asynchronous. Coalescing them prevents a backlog
            // when video encoding briefly occupies the camera service.
            if (frameTimeNanos - lastZoomSubmitNanos >= 16_666_667L) {
                lastZoomSubmitNanos = frameTimeNanos
                submitZoom(currentZoom)
            }

            if ((currentZoom <= minZoom && zoomDirection < 0) ||
                (currentZoom >= maxZoom && zoomDirection > 0)) {
                stopZoom()
            } else {
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemUi()

        cameraExecutor = Executors.newSingleThreadExecutor()
        setupControls()

        if (hasCameraPermission()) startCamera() else requestPermissions()
    }

    private fun setupControls() {
        installRocker(binding.teleButton, 1)
        installRocker(binding.wideButton, -1)

        binding.speedSlider.addOnChangeListener { _, value, _ ->
            zoomUnitsPerSecond = value
            binding.speedLabel.text = String.format(Locale.US, "Velocidade: %.2f×/s", value)
        }

        binding.recordButton.setOnClickListener { toggleRecording() }
        binding.photoButton.setOnClickListener { takePhoto() }
        binding.ntscButton.setOnClickListener { showProcessorMenu() }
        binding.modeButton.setOnClickListener {
            Toast.makeText(
                this,
                "Para 60 fps, grave na câmera Samsung e importe pelo botão NTSC-RS",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun installRocker(view: View, direction: Int) {
        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchedView.isPressed = true
                    startZoom(direction)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchedView.isPressed = false
                    stopZoom()
                    touchedView.performClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun startZoom(direction: Int) {
        if (zoomDirection == direction) return
        zoomDirection = direction
        zoomStartNanos = 0L
        lastZoomSubmitNanos = 0L
        android.view.Choreographer.getInstance().removeFrameCallback(zoomFrame)
        android.view.Choreographer.getInstance().postFrameCallback(zoomFrame)
    }

    private fun stopZoom() {
        zoomDirection = 0
        zoomStartNanos = 0L
        android.view.Choreographer.getInstance().removeFrameCallback(zoomFrame)
        submitZoom(currentZoom)
    }

    private fun submitZoom(value: Float) {
        latestZoomRequest = value
        val control = camera?.cameraControl ?: return
        if (abs(value - lastAppliedZoom) < 0.001f) return
        lastAppliedZoom = value
        // CameraX cancels an older pending zoom when a newer value arrives. Sending
        // the current value continuously avoids waiting for a capture-result future,
        // which made slow zooms visibly advance in large steps on the Galaxy A06.
        control.setZoomRatio(value)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }
            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.FHD, Quality.HD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                    )
                )
                .build()
            videoCapture = VideoCapture.Builder(recorder).build()

            try {
                provider.unbindAll()
                val viewPort = ViewPort.Builder(
                    Rational(4, 3),
                    binding.previewView.display.rotation
                ).setScaleType(ViewPort.FILL_CENTER).build()
                val useCaseBuilder = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(videoCapture!!)
                    .setViewPort(viewPort)
                useCaseBuilder.addUseCase(imageCapture!!)
                val useCases = useCaseBuilder.build()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    useCases
                )
                camera?.cameraInfo?.zoomState?.observe(this) { state ->
                    minZoom = max(1f, state.minZoomRatio)
                    maxZoom = min(4f, state.maxZoomRatio)
                    if (zoomDirection == 0) {
                        currentZoom = state.zoomRatio.coerceIn(minZoom, maxZoom)
                        latestZoomRequest = currentZoom
                        lastAppliedZoom = currentZoom
                        binding.zoomLabel.text = String.format(Locale.US, "%.2f×", currentZoom)
                    }
                }
            } catch (error: Exception) {
                Toast.makeText(this, "Não foi possível abrir a câmera: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleRecording() {
        val activeRecording = recording
        if (activeRecording != null) {
            activeRecording.stop()
            recording = null
            return
        }

        val capture = videoCapture ?: return
        val filename = "MotorZoom_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MotorZoom")
            }
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = capture.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.recordButton.text = getString(R.string.stop)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    binding.recordButton.text = getString(R.string.record)
                    if (event.hasError()) {
                        Toast.makeText(this, "Erro ao gravar: ${event.error}", Toast.LENGTH_LONG).show()
                    } else {
                        val fps = readRecordedFps(event.outputResults.outputUri)
                        val message = if (fps != null) {
                            "Vídeo salvo • ${String.format(Locale.US, "%.1f", fps)} fps"
                        } else {
                            "Vídeo salvo em Movies/MotorZoom"
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun readRecordedFps(uri: Uri): Float? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val filename = "MotorZoom_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MotorZoom")
            }
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        binding.photoButton.isEnabled = false
        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    binding.photoButton.isEnabled = true
                    Toast.makeText(this@MainActivity, getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
                }

                override fun onError(error: ImageCaptureException) {
                    binding.photoButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Erro ao fotografar: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun showProcessorMenu() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("NTSC-RS • $presetName")
            .setMessage("Importe um vídeo da câmera Samsung para aplicar zoom de filmadora, recorte 4:3 e NTSC-RS.")
            .setPositiveButton("Escolher vídeo") { _, _ -> videoPicker.launch(arrayOf("video/*")) }
            .setNeutralButton("Importar preset") { _, _ ->
                presetPicker.launch(arrayOf("application/json", "text/plain"))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPostZoomDialog(uri: Uri) {
        val durationSeconds = readVideoDurationSeconds(uri)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val scroll = ScrollView(this).apply { addView(content) }

        val info = TextView(this).apply {
            text = if (durationSeconds != null) {
                "Vídeo: ${String.format(Locale.US, "%.1f", durationSeconds)} s • saída 4:3"
            } else "Saída 4:3 • entrelaçamento conforme o preset"
        }
        content.addView(info)

        val interlacedOutput = CheckBox(this).apply {
            text = "Saída NTSC 480i real (.mpg)"
            isChecked = true
        }
        content.addView(interlacedOutput)
        content.addView(TextView(this).apply {
            text = "Use um vídeo 59,94/60 fps. Cada campo usará um instante diferente, como numa filmadora NTSC."
        })

        fun addSlider(
            parent: LinearLayout,
            title: String,
            minimum: Float,
            maximum: Float,
            step: Float,
            initial: Float,
            valueText: (Float) -> String
        ): Slider {
            val label = TextView(this).apply { text = "$title: ${valueText(initial)}" }
            parent.addView(label)
            return Slider(this).also { slider ->
                slider.valueFrom = minimum
                slider.valueTo = maximum
                slider.stepSize = step
                slider.value = initial
                slider.addOnChangeListener { _, value, _ ->
                    label.text = "$title: ${valueText(value)}"
                }
                parent.addView(slider)
            }
        }

        val colorEnabled = CheckBox(this).apply {
            text = "Ajustar cores"
            isChecked = false
        }
        content.addView(colorEnabled)
        val colorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(colorPanel)
        val temperature = addSlider(colorPanel, "Temperatura", -100f, 100f, 1f, 0f) {
            String.format(Locale.US, "%+.0f", it)
        }
        val saturation = addSlider(colorPanel, "Saturação", 0f, 2f, 0.05f, 1f) {
            String.format(Locale.US, "%.0f%%", it * 100f)
        }
        val contrast = addSlider(colorPanel, "Contraste", 0.5f, 1.5f, 0.05f, 1f) {
            String.format(Locale.US, "%.0f%%", it * 100f)
        }
        val brightness = addSlider(colorPanel, "Brilho", -0.5f, 0.5f, 0.05f, 0f) {
            String.format(Locale.US, "%+.0f%%", it * 100f)
        }
        val tint = addSlider(colorPanel, "Matiz verde/magenta", -100f, 100f, 1f, 0f) {
            String.format(Locale.US, "%+.0f", it)
        }
        colorPanel.addView(Button(this).apply {
            text = "Restaurar cores"
            setOnClickListener {
                temperature.value = 0f
                saturation.value = 1f
                contrast.value = 1f
                brightness.value = 0f
                tint.value = 0f
            }
        })
        colorEnabled.setOnCheckedChangeListener { _, checked ->
            colorPanel.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val fishEyeEnabled = CheckBox(this).apply {
            text = "Lente fish-eye"
            isChecked = false
        }
        content.addView(fishEyeEnabled)
        val fishEyePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(fishEyePanel)
        val fishEyeStrength = addSlider(fishEyePanel, "Intensidade fish-eye", 0.05f, 0.8f, 0.05f, 0.35f) {
            String.format(Locale.US, "%.0f%%", it * 100f)
        }
        fishEyeEnabled.setOnCheckedChangeListener { _, checked ->
            fishEyePanel.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val overlayEnabled = CheckBox(this).apply {
            text = "Data e horário de filmadora VHS"
            isChecked = false
        }
        content.addView(overlayEnabled)
        val overlayPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(overlayPanel)
        overlayPanel.addView(TextView(this).apply { text = "Data/hora inicial (DD/MM/AAAA HH:MM:SS)" })
        val overlayDate = EditText(this).apply {
            setText(SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date()))
            inputType = InputType.TYPE_CLASS_DATETIME
            setSelectAllOnFocus(true)
        }
        overlayPanel.addView(overlayDate)
        overlayEnabled.setOnCheckedChangeListener { _, checked ->
            overlayPanel.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val enabled = CheckBox(this).apply {
            text = "Aplicar zoom motorizado em pós"
            isChecked = true
        }
        content.addView(enabled)

        fun addNumberField(label: String, initial: String): EditText {
            content.addView(TextView(this).apply { text = label })
            return EditText(this).also {
                it.setText(initial)
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                it.setSelectAllOnFocus(true)
                content.addView(it)
            }
        }

        val startField = addNumberField("Começar em (segundos)", "0.0")
        val suggestedDuration = min(5f, durationSeconds ?: 5f).coerceAtLeast(0.5f)
        val durationField = addNumberField(
            "Duração do zoom (segundos)",
            String.format(Locale.US, "%.1f", suggestedDuration)
        )

        content.addView(TextView(this).apply { text = "Direção" })
        val direction = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Aproximar: 1× → zoom final", "Afastar: zoom inicial → 1×")
            )
        }
        content.addView(direction)

        val zoomLabel = TextView(this).apply { text = "Zoom máximo: 2.00×" }
        content.addView(zoomLabel)
        val zoomSlider = Slider(this).apply {
            valueFrom = 1.1f
            valueTo = 4f
            stepSize = 0.05f
            value = 2f
            addOnChangeListener { _, zoom, _ ->
                zoomLabel.text = String.format(Locale.US, "Zoom máximo: %.2f×", zoom)
            }
        }
        content.addView(zoomSlider)

        content.addView(TextView(this).apply {
            text = "Modo Filmadora: velocidade constante com partida e parada suaves de 120 ms."
        })

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Processar vídeo")
            .setView(scroll)
            .setPositiveButton("Processar") { _, _ ->
                val start = startField.text.toString().replace(',', '.').toFloatOrNull() ?: 0f
                val duration = durationField.text.toString().replace(',', '.').toFloatOrNull() ?: suggestedDuration
                val endLimit = durationSeconds ?: Float.MAX_VALUE
                val safeStart = start.coerceIn(0f, endLimit)
                val safeDuration = duration.coerceAtLeast(0.1f)
                    .coerceAtMost((endLimit - safeStart).coerceAtLeast(0.1f))
                val maximum = zoomSlider.value
                val zoomIn = direction.selectedItemPosition == 0
                val settings = NtscVideoProcessor.MotorZoomSettings(
                    enabled = enabled.isChecked,
                    startUs = (safeStart * 1_000_000f).toLong(),
                    durationUs = (safeDuration * 1_000_000f).toLong(),
                    startZoom = if (zoomIn) 1f else maximum,
                    endZoom = if (zoomIn) maximum else 1f
                )
                val dateParser = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).apply {
                    isLenient = false
                }
                val overlayEpoch = runCatching { dateParser.parse(overlayDate.text.toString())!!.time }
                    .getOrElse {
                        Toast.makeText(this, "Data inválida; usando a data atual", Toast.LENGTH_LONG).show()
                        System.currentTimeMillis()
                    }
                val visual = NtscVideoProcessor.VisualSettings(
                    colorEnabled = colorEnabled.isChecked,
                    temperature = temperature.value / 100f,
                    saturation = saturation.value,
                    contrast = contrast.value,
                    brightness = brightness.value,
                    tint = tint.value / 100f,
                    fishEyeEnabled = fishEyeEnabled.isChecked,
                    fishEyeStrength = fishEyeStrength.value,
                    overlayEnabled = overlayEnabled.isChecked,
                    overlayStartEpochMs = overlayEpoch
                )
                startNtscProcessing(uri, settings, visual, interlacedOutput.isChecked)
            }
            .setNeutralButton("Somente NTSC") { _, _ ->
                startNtscProcessing(
                    uri,
                    NtscVideoProcessor.MotorZoomSettings(),
                    NtscVideoProcessor.VisualSettings(),
                    interlacedOutput.isChecked
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun readVideoDurationSeconds(uri: Uri): Float? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.div(1000f)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun startNtscProcessing(
        uri: Uri,
        motorZoom: NtscVideoProcessor.MotorZoomSettings,
        visual: NtscVideoProcessor.VisualSettings,
        trueInterlaced: Boolean
    ) {
        binding.ntscButton.isEnabled = false
        binding.ntscButton.text = "0%"
        cameraExecutor.execute {
            try {
                NtscVideoProcessor(applicationContext).process(
                    uri,
                    presetJson,
                    motorZoom,
                    visual,
                    trueInterlaced
                ) { percent ->
                    runOnUiThread { binding.ntscButton.text = "$percent%" }
                }
                runOnUiThread {
                    binding.ntscButton.isEnabled = true
                    binding.ntscButton.text = getString(R.string.ntsc_rs)
                    val format = if (trueInterlaced) "NTSC 480i (.mpg)" else "MP4 progressivo"
                    Toast.makeText(this, "$format salvo em Movies/MotorZoom", Toast.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    binding.ntscButton.isEnabled = true
                    binding.ntscButton.text = getString(R.string.ntsc_rs)
                    val message = "Falha ao processar:\n\n${error.message ?: error.javaClass.simpleName}"
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Erro no processamento")
                        .setMessage(message)
                        .setPositiveButton("Fechar", null)
                        .setNeutralButton("Copiar erro") { _, _ ->
                            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MotorZoom", message))
                            Toast.makeText(this, "Erro copiado", Toast.LENGTH_SHORT).show()
                        }
                        .show()
                }
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onDestroy() {
        stopZoom()
        recording?.stop()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
