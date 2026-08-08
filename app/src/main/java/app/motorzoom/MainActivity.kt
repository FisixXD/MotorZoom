package app.motorzoom

import android.Manifest
import android.app.Dialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Rational
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.view.TextureView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
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
    private var presetJson = ""
    private var presetName = "Padrão"

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showPostZoomDialog(uri)
    }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showPhotoProcessorDialog(uri)
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

            // Keep sending the desired ratio on every display frame. CameraX drops
            // superseded requests itself; waiting for each Future made the Galaxy A06
            // visibly jump between zoom values, especially while recording.
            camera?.cameraControl?.setZoomRatio(currentZoom)

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
        android.view.Choreographer.getInstance().removeFrameCallback(zoomFrame)
        android.view.Choreographer.getInstance().postFrameCallback(zoomFrame)
    }

    private fun stopZoom() {
        zoomDirection = 0
        zoomStartNanos = 0L
        android.view.Choreographer.getInstance().removeFrameCallback(zoomFrame)
        camera?.cameraControl?.setZoomRatio(currentZoom)
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
            .setItems(arrayOf("Processar vídeo", "Processar foto", "Importar preset")) { _, choice ->
                when (choice) {
                    0 -> videoPicker.launch(arrayOf("video/*"))
                    1 -> photoPicker.launch(arrayOf("image/*"))
                    2 -> presetPicker.launch(arrayOf("application/json", "text/plain"))
                }
            }
            .show()
    }

    private fun showPhotoProcessorDialog(uri: Uri) {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        content.addView(TextView(this).apply {
            text = "Saída PNG sem perdas • recorte central 4:3 • resolução NTSC 640×480"
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

        val colorEnabled = CheckBox(this).apply { text = "Ajustar cores" }
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
        colorEnabled.setOnCheckedChangeListener { _, checked ->
            colorPanel.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val fishEyeEnabled = CheckBox(this).apply { text = "Lente fish-eye" }
        content.addView(fishEyeEnabled)
        val fishEyePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(fishEyePanel)
        val fishEyeStrength = addSlider(
            fishEyePanel, "Intensidade fish-eye", 0.05f, 0.8f, 0.05f, 0.35f
        ) { String.format(Locale.US, "%.0f%%", it * 100f) }
        fishEyeEnabled.setOnCheckedChangeListener { _, checked ->
            fishEyePanel.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val ccdSmearEnabled = CheckBox(this).apply { text = "CCD Vertical Smear" }
        content.addView(ccdSmearEnabled)
        val ccdSmearPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(ccdSmearPanel)
        val ccdThreshold = addSlider(
            ccdSmearPanel, "Limiar da luz", 0.85f, 0.98f, 0.01f, 0.93f
        ) { String.format(Locale.US, "%.0f%%", it * 100f) }
        val ccdKnee = addSlider(
            ccdSmearPanel, "Suavidade", 0.02f, 0.15f, 0.01f, 0.06f
        ) { String.format(Locale.US, "%.0f%%", it * 100f) }
        val ccdLength = addSlider(
            ccdSmearPanel, "Comprimento", 0f, 1f, 0.01f, 0.82f
        ) { String.format(Locale.US, "%.0f%%", it * 100f) }
        val ccdIntensity = addSlider(
            ccdSmearPanel, "Intensidade", 0f, 1f, 0.05f, 0.35f
        ) { String.format(Locale.US, "%.0f%%", it * 100f) }
        ccdSmearPanel.addView(TextView(this).apply { text = "Cor da faixa" })
        val ccdTint = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Branco", "Branco-esverdeado", "Branco-âmbar", "Branco-violeta")
            )
            setSelection(1)
        }
        ccdSmearPanel.addView(ccdTint)
        val ccdFlicker = addSlider(
            ccdSmearPanel, "Instabilidade", 0f, 0.3f, 0.01f, 0.04f
        ) { String.format(Locale.US, "%.0f%%", it * 100f) }
        ccdSmearEnabled.setOnCheckedChangeListener { _, checked ->
            ccdSmearPanel.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val overlayEnabled = CheckBox(this).apply { text = "Data e horário de filmadora VHS" }
        content.addView(overlayEnabled)
        val overlayDate = EditText(this).apply {
            setText(SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date()))
            inputType = InputType.TYPE_CLASS_DATETIME
            visibility = View.GONE
        }
        content.addView(overlayDate)
        overlayEnabled.setOnCheckedChangeListener { _, checked ->
            overlayDate.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Processar foto • $presetName")
            .setView(scroll)
            .setPositiveButton("Processar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val dateParser = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).apply {
                    isLenient = false
                }
                val overlayEpoch = if (overlayEnabled.isChecked) {
                    runCatching { dateParser.parse(overlayDate.text.toString())!!.time }
                        .getOrElse {
                            Toast.makeText(this, "Data inválida", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                } else System.currentTimeMillis()
                val visual = NtscVideoProcessor.VisualSettings(
                    colorEnabled = colorEnabled.isChecked,
                    temperature = temperature.value / 100f,
                    saturation = saturation.value,
                    contrast = contrast.value,
                    brightness = brightness.value,
                    tint = tint.value / 100f,
                    fishEyeEnabled = fishEyeEnabled.isChecked,
                    fishEyeStrength = fishEyeStrength.value,
                    ccdSmearEnabled = ccdSmearEnabled.isChecked,
                    ccdSmearThreshold = ccdThreshold.value,
                    ccdSmearKnee = ccdKnee.value,
                    ccdSmearLength = ccdLength.value,
                    ccdSmearIntensity = ccdIntensity.value,
                    ccdSmearTint = ccdTint.selectedItemPosition,
                    ccdSmearFlicker = ccdFlicker.value,
                    overlayEnabled = overlayEnabled.isChecked,
                    overlayStartEpochMs = overlayEpoch
                )
                dialog.dismiss()
                startPhotoProcessing(uri, visual)
            }
        }
        dialog.show()
    }

    private fun startPhotoProcessing(uri: Uri, visual: NtscVideoProcessor.VisualSettings) {
        binding.ntscButton.isEnabled = false
        binding.ntscButton.text = "FOTO…"
        cameraExecutor.execute {
            try {
                NtscVideoProcessor(applicationContext).processPhoto(uri, presetJson, visual)
                runOnUiThread {
                    binding.ntscButton.isEnabled = true
                    binding.ntscButton.text = getString(R.string.ntsc_rs)
                    Toast.makeText(
                        this,
                        "PNG salvo em Pictures/MotorZoom",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    binding.ntscButton.isEnabled = true
                    binding.ntscButton.text = getString(R.string.ntsc_rs)
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Erro ao processar foto")
                        .setMessage(error.message ?: error.javaClass.simpleName)
                        .setPositiveButton("Fechar", null)
                        .show()
                }
            }
        }
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

        var rockerAutomation: List<NtscVideoProcessor.ZoomKeyframe> = emptyList()
        val automationStatus = TextView(this).apply {
            text = "Rocker em pós: nenhuma automação gravada"
        }
        content.addView(automationStatus)
        content.addView(Button(this).apply {
            text = "GRAVAR ROCKER T/W EM PÓS"
            setOnClickListener {
                showZoomAutomationEditor(uri) { points ->
                    rockerAutomation = points
                    val lastZoom = points.lastOrNull()?.zoom ?: 1f
                    automationStatus.text = String.format(
                        Locale.US,
                        "Rocker em pós: %d pontos • termina em %.2f×",
                        points.size,
                        lastZoom
                    )
                }
            }
        })
        content.addView(Button(this).apply {
            text = "LIMPAR ROCKER EM PÓS"
            setOnClickListener {
                rockerAutomation = emptyList()
                automationStatus.text = "Rocker em pós: nenhuma automação gravada"
            }
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

        val ccdSmearEnabled = CheckBox(this).apply {
            text = "CCD Vertical Smear"
            isChecked = false
        }
        content.addView(ccdSmearEnabled)
        val ccdSmearPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(ccdSmearPanel)
        val ccdThreshold = addSlider(
            ccdSmearPanel, "Limiar da luz", 0.85f, 0.98f, 0.01f, 0.93f
        ) { String.format(Locale.US, "%.0f%%", it * 100f) }
        val ccdKnee = addSlider(
            ccdSmearPanel, "Suavidade", 0.02f, 0.15f, 0.01f, 0.06f
        ) { String.format(Locale.US, "%.0f%%", it * 100f) }
        val ccdLength = addSlider(
            ccdSmearPanel, "Comprimento", 0f, 1f, 0.01f, 0.82f
        ) { String.format(Locale.US, "%.0f%%", it * 100f) }
        val ccdIntensity = addSlider(
            ccdSmearPanel, "Intensidade", 0f, 1f, 0.05f, 0.35f
        ) { String.format(Locale.US, "%.0f%%", it * 100f) }
        ccdSmearPanel.addView(TextView(this).apply { text = "Cor da faixa" })
        val ccdTint = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Branco", "Branco-esverdeado", "Branco-âmbar", "Branco-violeta")
            )
            setSelection(1)
        }
        ccdSmearPanel.addView(ccdTint)
        val ccdFlicker = addSlider(
            ccdSmearPanel, "Instabilidade", 0f, 0.3f, 0.01f, 0.04f
        ) { String.format(Locale.US, "%.0f%%", it * 100f) }
        ccdSmearEnabled.setOnCheckedChangeListener { _, checked ->
            ccdSmearPanel.visibility = if (checked) View.VISIBLE else View.GONE
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
                    enabled = enabled.isChecked || rockerAutomation.isNotEmpty(),
                    startUs = (safeStart * 1_000_000f).toLong(),
                    durationUs = (safeDuration * 1_000_000f).toLong(),
                    startZoom = if (zoomIn) 1f else maximum,
                    endZoom = if (zoomIn) maximum else 1f,
                    keyframes = rockerAutomation
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
                    ccdSmearEnabled = ccdSmearEnabled.isChecked,
                    ccdSmearThreshold = ccdThreshold.value,
                    ccdSmearKnee = ccdKnee.value,
                    ccdSmearLength = ccdLength.value,
                    ccdSmearIntensity = ccdIntensity.value,
                    ccdSmearTint = ccdTint.selectedItemPosition,
                    ccdSmearFlicker = ccdFlicker.value,
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

    private fun showZoomAutomationEditor(
        uri: Uri,
        onSave: (List<NtscVideoProcessor.ZoomKeyframe>) -> Unit
    ) {
        stopZoom()
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                providerFuture.get().unbindAll()
                camera = null
                videoCapture = null
                imageCapture = null
                binding.previewView.visibility = View.INVISIBLE
                openZoomAutomationEditor(uri, onSave)
            } catch (error: Exception) {
                Toast.makeText(
                    this,
                    "Não foi possível abrir o editor: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
                startCamera()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun openZoomAutomationEditor(
        uri: Uri,
        onSave: (List<NtscVideoProcessor.ZoomKeyframe>) -> Unit
    ) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = TextureView(this)
        root.addView(video, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val cropWidth = (resources.displayMetrics.heightPixels * 4f / 3f).toInt()
        val sideMaskWidth = ((resources.displayMetrics.widthPixels - cropWidth) / 2).coerceAtLeast(0)
        if (sideMaskWidth > 0) {
            root.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, FrameLayout.LayoutParams(
                sideMaskWidth,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START
            ))
            root.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, FrameLayout.LayoutParams(
                sideMaskWidth,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END
            ))
        }

        var recordingAutomation = false
        var editorDirection = 0
        var editorSpeed = 0.35f
        var editorZoom = 1f
        var lastPositionMs = 0L
        var preparedPlayer: MediaPlayer? = null
        var playbackSurface: Surface? = null
        val points = mutableListOf<NtscVideoProcessor.ZoomKeyframe>()

        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 16f
            text = "Prepare o vídeo e toque em GRAVAR"
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply { setMargins(dp(16), dp(16), 0, 0) })

        val rocker = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun rockerButton(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.control_button)
        }
        val tele = rockerButton("T")
        val wide = rockerButton("W")
        rocker.addView(tele, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { bottomMargin = dp(5) })
        rocker.addView(wide, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(5) })
        root.addView(rocker, FrameLayout.LayoutParams(
            dp(105),
            (resources.displayMetrics.heightPixels * 0.65f).toInt(),
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { rightMargin = dp(16) })

        fun installEditorRocker(view: View, direction: Int) {
            view.setOnTouchListener { touched, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touched.isPressed = true
                        if (recordingAutomation) editorDirection = direction
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touched.isPressed = false
                        if (editorDirection == direction) editorDirection = 0
                        touched.performClick()
                        true
                    }
                    else -> true
                }
            }
        }
        installEditorRocker(tele, 1)
        installEditorRocker(wide, -1)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xbb000000.toInt())
        }
        val speedLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "Velocidade: 0.35×/s"
        }
        bottom.addView(speedLabel)
        bottom.addView(Slider(this).apply {
            valueFrom = 0.10f
            valueTo = 1.50f
            stepSize = 0.05f
            value = editorSpeed
            addOnChangeListener { _, value, _ ->
                editorSpeed = value
                speedLabel.text = String.format(Locale.US, "Velocidade: %.2f×/s", value)
            }
        })
        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val recordButton = Button(this).apply {
            text = "CARREGANDO…"
            isEnabled = false
        }
        val saveButton = Button(this).apply { text = "USAR MOVIMENTO" }
        val cancelButton = Button(this).apply { text = "CANCELAR" }
        buttonRow.addView(recordButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttonRow.addView(saveButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttonRow.addView(cancelButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        bottom.addView(buttonRow)
        val bottomWidth = min(
            dp(480),
            resources.displayMetrics.widthPixels - dp(150)
        ).coerceAtLeast(dp(300))
        root.addView(bottom, FrameLayout.LayoutParams(
            bottomWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply { setMargins(dp(16), 0, 0, dp(12)) })

        fun startAutomationRecording() {
            recordingAutomation = false
            points.clear()
            editorZoom = 1f
            editorDirection = 0
            lastPositionMs = 0L
            video.scaleX = 1f
            video.scaleY = 1f
            points += NtscVideoProcessor.ZoomKeyframe(0L, 1f)
            recordButton.text = "REGRAVAR"
            status.text = "Voltando ao início…"
            fun beginPlayback() {
                if (!dialog.isShowing) return
                lastPositionMs = 0L
                preparedPlayer?.start()
                recordingAutomation = true
                status.text = "REC • 1.00× • segure T ou W"
            }
            val player = preparedPlayer
            if (player == null) {
                Toast.makeText(this, "O vídeo ainda está carregando", Toast.LENGTH_SHORT).show()
            } else if (player.currentPosition > 50) {
                player.setOnSeekCompleteListener { completed ->
                    completed.setOnSeekCompleteListener(null)
                    beginPlayback()
                }
                player.seekTo(0)
            } else {
                player.seekTo(0)
                beginPlayback()
            }
        }

        recordButton.setOnClickListener { startAutomationRecording() }
        saveButton.setOnClickListener {
            if (points.size < 2) {
                Toast.makeText(this, "Grave um movimento primeiro", Toast.LENGTH_SHORT).show()
            } else {
                recordingAutomation = false
                editorDirection = 0
                preparedPlayer?.pause()
                onSave(points.toList())
                dialog.dismiss()
            }
        }
        cancelButton.setOnClickListener { dialog.dismiss() }
        fun updateVideoTransform(videoWidth: Int, videoHeight: Int) {
            if (video.width == 0 || video.height == 0 || videoWidth == 0 || videoHeight == 0) return
            val viewWidth = video.width.toFloat()
            val viewHeight = video.height.toFloat()
            val fill = max(viewWidth / videoWidth, viewHeight / videoHeight)
            val scaleX = videoWidth * fill / viewWidth
            val scaleY = videoHeight * fill / viewHeight
            video.setTransform(Matrix().apply {
                setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
            })
        }

        fun preparePlayer(surfaceTexture: SurfaceTexture) {
            playbackSurface?.release()
            val surface = Surface(surfaceTexture)
            playbackSurface = surface
            val player = MediaPlayer()
            preparedPlayer = player
            player.setSurface(surface)
            player.setOnPreparedListener { ready ->
                ready.seekTo(1)
                recordButton.text = "GRAVAR"
                recordButton.isEnabled = true
                status.text = "Pronto • toque em GRAVAR e use T/W durante a reprodução"
            }
            player.setOnVideoSizeChangedListener { _, width, height ->
                video.post { updateVideoTransform(width, height) }
            }
            player.setOnErrorListener { _, what, extra ->
                status.text = "Erro ao abrir o vídeo ($what/$extra)"
                recordButton.text = "ERRO"
                recordButton.isEnabled = false
                Toast.makeText(this, "Não foi possível reproduzir este vídeo", Toast.LENGTH_LONG).show()
                true
            }
            player.setOnCompletionListener { completed ->
                if (recordingAutomation) {
                    points += NtscVideoProcessor.ZoomKeyframe(
                        (completed.duration.coerceAtLeast(0) * 1_000L),
                        editorZoom
                    )
                    recordingAutomation = false
                    editorDirection = 0
                    status.text = String.format(
                        Locale.US,
                        "Concluído • %.2f× • toque em USAR MOVIMENTO",
                        editorZoom
                    )
                }
            }
            try {
                player.setDataSource(this, uri)
                player.prepareAsync()
            } catch (error: Exception) {
                status.text = "Erro ao abrir o vídeo: ${error.message}"
                recordButton.text = "ERRO"
            }
        }

        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                preparePlayer(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                val player = preparedPlayer ?: return
                updateVideoTransform(player.videoWidth, player.videoHeight)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                preparedPlayer?.setSurface(null)
                playbackSurface?.release()
                playbackSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        val frameCallback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!dialog.isShowing) return
                val player = preparedPlayer
                if (recordingAutomation && player?.isPlaying == true) {
                    val positionMs = player.currentPosition.toLong().coerceAtLeast(lastPositionMs)
                    val elapsedSeconds = (positionMs - lastPositionMs) / 1000f
                    if (elapsedSeconds > 0f) {
                        editorZoom = (editorZoom + editorDirection * editorSpeed * elapsedSeconds)
                            .coerceIn(1f, 4f)
                        video.scaleX = editorZoom
                        video.scaleY = editorZoom
                        points += NtscVideoProcessor.ZoomKeyframe(positionMs * 1_000L, editorZoom)
                        lastPositionMs = positionMs
                    }
                    status.text = String.format(
                        Locale.US,
                        "REC • %.2f× • %02d:%02d",
                        editorZoom,
                        positionMs / 60_000L,
                        (positionMs / 1_000L) % 60L
                    )
                }
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            recordingAutomation = false
            editorDirection = 0
            preparedPlayer?.let { player ->
                runCatching { player.setSurface(null) }
                player.release()
            }
            preparedPlayer = null
            playbackSurface?.release()
            playbackSurface = null
            android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
            binding.previewView.visibility = View.VISIBLE
            binding.previewView.postDelayed({
                if (!isFinishing && !isDestroyed) startCamera()
            }, 200L)
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        video.requestFocus()
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
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
