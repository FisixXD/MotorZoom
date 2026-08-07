package app.motorzoom

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Camera2 recorder used only by the 16:9 VHS60 mode. */
class Vhs60Camera(
    private val activity: Activity,
    private val textureView: TextureView,
    private val listener: Listener
) {
    interface Listener {
        fun onReady(minZoom: Float, maxZoom: Float, fpsRange: Range<Int>)
        fun onRecordingStarted()
        fun onRecordingFinished(uri: Uri, measuredFps: Float?)
        fun onError(message: String)
    }

    private val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var recorder: MediaRecorder? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var cameraId = ""
    private var characteristics: CameraCharacteristics? = null
    private var previewSize = Size(1920, 1080)
    private var fpsRange = Range(60, 60)
    private var zoomRatio = 1f
    private var opening = false
    private var startingRecording = false

    var isRecording: Boolean = false
        private set

    val isBusy: Boolean
        get() = isRecording || startingRecording

    fun start() {
        if (camera != null || opening) return
        if (cameraThread == null) {
            cameraThread = HandlerThread("MotorZoom-VHS60").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
        if (textureView.isAvailable) openCamera() else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = openCamera()
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    fun stop() {
        if (isRecording) {
            try {
                recorder?.stop()
                recorder?.reset()
                closeOutput(delete = false)
            } catch (_: Exception) {
                closeOutput(delete = true)
            }
            isRecording = false
        }
        startingRecording = false
        session?.close()
        session = null
        camera?.close()
        camera = null
        recorder?.release()
        recorder = null
        closeOutput(delete = true)
        opening = false
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    fun setZoomRatio(value: Float) {
        zoomRatio = value
        cameraHandler?.post {
            val builder = requestBuilder ?: return@post
            applyCameraOptions(builder)
            try {
                session?.setRepeatingRequest(builder.build(), null, cameraHandler)
            } catch (_: Exception) {
                // A session can be changing between preview and recording here.
            }
        }
    }

    fun startRecording() {
        if (isRecording || startingRecording || camera == null) return
        startingRecording = true
        cameraHandler?.post {
            try {
                prepareRecorder()
                createSession(recording = true)
            } catch (error: Exception) {
                startingRecording = false
                closeOutput(delete = true)
                postError("Não foi possível iniciar o VHS60: ${error.message}")
                startPreview()
            }
        }
    }

    fun stopRecording() {
        if (!isRecording || startingRecording) return
        cameraHandler?.post {
            isRecording = false
            try {
                recorder?.stop()
                recorder?.reset()
                val savedUri = outputUri
                closeOutput(delete = false)
                if (savedUri != null) {
                    val fps = readRecordedFps(savedUri)
                    activity.runOnUiThread { listener.onRecordingFinished(savedUri, fps) }
                }
            } catch (error: Exception) {
                closeOutput(delete = true)
                postError("Falha ao finalizar o VHS60: ${error.message}")
            } finally {
                startPreview()
            }
        }
    }

    private fun openCamera() {
        if (opening || camera != null) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            opening = true
            cameraId = manager.cameraIdList.first { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
            characteristics = manager.getCameraCharacteristics(cameraId)
            chooseConfiguration(characteristics!!)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    opening = false
                    camera = device
                    startPreview()
                    val maxDigital = characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                    val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        characteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    } else null
                    val minZoom = max(1f, zoomRange?.lower ?: 1f)
                    val maxZoom = min(4f, zoomRange?.upper ?: maxDigital)
                    activity.runOnUiThread { listener.onReady(minZoom, maxZoom, fpsRange) }
                }

                override fun onDisconnected(device: CameraDevice) {
                    opening = false
                    device.close()
                    camera = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    opening = false
                    device.close()
                    camera = null
                    postError("A câmera recusou o modo VHS60 (erro $error)")
                }
            }, cameraHandler)
        } catch (error: Exception) {
            opening = false
            postError("VHS60 indisponível: ${error.message}")
        }
    }

    private fun chooseConfiguration(info: CameraCharacteristics) {
        val map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("A câmera não informou resoluções")
        val sizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty()
            .filter { it.width * 9 == it.height * 16 }
        previewSize = sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height } ?: error("Sem resolução 16:9")

        val ranges = info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            .filter { it.upper >= 60 && it.lower <= 60 }
        fpsRange = ranges.firstOrNull { it.lower == 60 && it.upper == 60 }
            ?: ranges.maxByOrNull { it.lower } ?: error("A câmera traseira não anuncia 60 fps")
    }

    private fun startPreview() {
        if (camera == null || !textureView.isAvailable) return
        createSession(recording = false)
    }

    private fun createSession(recording: Boolean) {
        val device = camera ?: return
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)
        val surfaces = mutableListOf(previewSurface)
        val recordSurface = if (recording) recorder?.surface else null
        if (recordSurface != null) surfaces += recordSurface

        session?.close()
        session = null
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(newSession: CameraCaptureSession) {
                session = newSession
                val template = if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                val builder = device.createCaptureRequest(template).apply {
                    addTarget(previewSurface)
                    if (recordSurface != null) addTarget(recordSurface)
                }
                requestBuilder = builder
                applyCameraOptions(builder)
                try {
                    newSession.setRepeatingRequest(builder.build(), null, cameraHandler)
                    if (recording) {
                        recorder?.start()
                        startingRecording = false
                        isRecording = true
                        activity.runOnUiThread { listener.onRecordingStarted() }
                    }
                } catch (error: Exception) {
                    startingRecording = false
                    if (recording) closeOutput(delete = true)
                    postError("A sessão VHS60 falhou: ${error.message}")
                }
            }

            override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                startingRecording = false
                if (recording) closeOutput(delete = true)
                postError("O A06 recusou a combinação de preview e gravação a 60 fps")
            }
        }, cameraHandler)
    }

    private fun applyCameraOptions(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            characteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else {
            val active = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (active != null) builder.set(CaptureRequest.SCALER_CROP_REGION, cropForZoom(active, zoomRatio))
        }
    }

    private fun cropForZoom(active: Rect, zoom: Float): Rect {
        val width = (active.width() / zoom).toInt()
        val height = (active.height() / zoom).toInt()
        val left = active.centerX() - width / 2
        val top = active.centerY() - height / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun prepareRecorder() {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "MotorZoom_VHS60_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()))
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MotorZoom")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        outputUri = activity.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Não foi possível criar o arquivo")
        outputDescriptor = activity.contentResolver.openFileDescriptor(outputUri!!, "rw")
            ?: error("Não foi possível abrir o arquivo")

        recorder?.release()
        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(activity) else MediaRecorder()
        recorder!!.apply {
            val hasAudio = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (hasAudio) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputDescriptor!!.fileDescriptor)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(previewSize.width, previewSize.height)
            setVideoFrameRate(60)
            setVideoEncodingBitRate(if (previewSize.width >= 1920) 20_000_000 else 12_000_000)
            if (hasAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(48_000)
            }
            setOrientationHint(0)
            prepare()
        }
    }

    private fun closeOutput(delete: Boolean) {
        try { outputDescriptor?.close() } catch (_: Exception) { }
        outputDescriptor = null
        val uri = outputUri
        if (uri != null) {
            if (delete) activity.contentResolver.delete(uri, null, null)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
        }
        outputUri = null
    }

    private fun readRecordedFps(uri: Uri): Float? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(activity, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun postError(message: String) {
        activity.runOnUiThread { listener.onError(message) }
    }
}
