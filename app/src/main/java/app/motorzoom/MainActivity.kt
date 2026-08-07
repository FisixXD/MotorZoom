package app.motorzoom

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Rational
import android.view.MotionEvent
import android.view.View
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
import app.motorzoom.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
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
    private var lastZoomFrameNanos = 0L
    private var presetJson = ""
    private var presetName = "Padrão"

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startNtscProcessing(uri)
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
                lastZoomFrameNanos = 0L
                return
            }

            if (lastZoomFrameNanos == 0L) lastZoomFrameNanos = frameTimeNanos
            val elapsedSeconds = (frameTimeNanos - lastZoomFrameNanos) / 1_000_000_000f
            lastZoomFrameNanos = frameTimeNanos

            currentZoom = (currentZoom + zoomDirection * zoomUnitsPerSecond * elapsedSeconds)
                .coerceIn(minZoom, maxZoom)
            binding.zoomLabel.text = String.format(Locale.US, "%.2f×", currentZoom)

            // Envia uma posição em cada quadro da interface. No Galaxy A06,
            // limitar a 20 Hz deixa os degraus do zoom muito aparentes.
            submitZoom(currentZoom)

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
        lastZoomFrameNanos = 0L
        android.view.Choreographer.getInstance().removeFrameCallback(zoomFrame)
        android.view.Choreographer.getInstance().postFrameCallback(zoomFrame)
    }

    private fun stopZoom() {
        zoomDirection = 0
        lastZoomFrameNanos = 0L
        android.view.Choreographer.getInstance().removeFrameCallback(zoomFrame)
        submitZoom(currentZoom)
    }

    private fun submitZoom(value: Float) {
        camera?.cameraControl?.setZoomRatio(value)
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
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                provider.unbindAll()
                val viewPort = ViewPort.Builder(
                    Rational(4, 3),
                    binding.previewView.display.rotation
                ).setScaleType(ViewPort.FILL_CENTER).build()
                val useCases = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(videoCapture!!)
                    .addUseCase(imageCapture!!)
                    .setViewPort(viewPort)
                    .build()
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
                        Toast.makeText(this, "Vídeo salvo em Movies/MotorZoom", Toast.LENGTH_SHORT).show()
                    }
                }
            }
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
            .setTitle("NTSC-RS offline")
            .setMessage("Preset atual: $presetName\nA saída é criada em 640×480; o original não é alterado.")
            .setPositiveButton("Escolher vídeo") { _, _ -> videoPicker.launch(arrayOf("video/*")) }
            .setNeutralButton("Importar preset") { _, _ ->
                presetPicker.launch(arrayOf("application/json", "text/plain"))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startNtscProcessing(uri: Uri) {
        binding.ntscButton.isEnabled = false
        binding.ntscButton.text = "0%"
        cameraExecutor.execute {
            try {
                NtscVideoProcessor(contentResolver).process(uri, presetJson) { percent ->
                    runOnUiThread { binding.ntscButton.text = "$percent%" }
                }
                runOnUiThread {
                    binding.ntscButton.isEnabled = true
                    binding.ntscButton.text = getString(R.string.ntsc_rs)
                    Toast.makeText(this, "Vídeo NTSC salvo em Movies/MotorZoom", Toast.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    binding.ntscButton.isEnabled = true
                    binding.ntscButton.text = getString(R.string.ntsc_rs)
                    Toast.makeText(this, "Falha ao processar: ${error.message}", Toast.LENGTH_LONG).show()
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
