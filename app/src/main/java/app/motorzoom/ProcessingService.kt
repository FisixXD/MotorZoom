package app.motorzoom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ProcessingService : Service() {
    companion object {
        private const val CHANNEL_ID = "motorzoom_processing"
        private const val NOTIFICATION_ID = 480
        private const val ACTION_PAUSE = "app.motorzoom.processing.PAUSE"
        private const val ACTION_RESUME = "app.motorzoom.processing.RESUME"
        private const val ACTION_CANCEL_CURRENT = "app.motorzoom.processing.CANCEL_CURRENT"
        private const val ACTION_CANCEL_ALL = "app.motorzoom.processing.CANCEL_ALL"
        private val pendingCount = AtomicInteger(0)
        private val paused = AtomicBoolean(false)
        private val cancelRequested = AtomicBoolean(false)

        fun isProcessing(): Boolean = pendingCount.get() > 0
        fun isPaused(): Boolean = paused.get()

        fun start(
            context: Context,
            input: Uri,
            preset: String,
            motorZoom: NtscVideoProcessor.MotorZoomSettings,
            visual: NtscVideoProcessor.VisualSettings,
            trueInterlaced: Boolean
        ): Boolean {
            val keyframeTimes = LongArray(motorZoom.keyframes.size) { motorZoom.keyframes[it].timeUs }
            val keyframeZooms = FloatArray(motorZoom.keyframes.size) { motorZoom.keyframes[it].zoom }
            val intent = Intent(context, ProcessingService::class.java).apply {
                data = input
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("jobId", UUID.randomUUID().toString())
                putExtra("inputName", input.lastPathSegment?.substringAfterLast('/') ?: "Vídeo")
                putExtra("preset", preset)
                putExtra("trueInterlaced", trueInterlaced)
                putExtra("zoomEnabled", motorZoom.enabled)
                putExtra("zoomStartUs", motorZoom.startUs)
                putExtra("zoomDurationUs", motorZoom.durationUs)
                putExtra("zoomStart", motorZoom.startZoom)
                putExtra("zoomEnd", motorZoom.endZoom)
                putExtra("keyframeTimes", keyframeTimes)
                putExtra("keyframeZooms", keyframeZooms)
                putExtra("colorEnabled", visual.colorEnabled)
                putExtra("temperature", visual.temperature)
                putExtra("saturation", visual.saturation)
                putExtra("contrast", visual.contrast)
                putExtra("brightness", visual.brightness)
                putExtra("tint", visual.tint)
                putExtra("fishEyeEnabled", visual.fishEyeEnabled)
                putExtra("fishEyeStrength", visual.fishEyeStrength)
                putExtra("ccdSmearEnabled", visual.ccdSmearEnabled)
                putExtra("ccdSmearThreshold", visual.ccdSmearThreshold)
                putExtra("ccdSmearKnee", visual.ccdSmearKnee)
                putExtra("ccdSmearLength", visual.ccdSmearLength)
                putExtra("ccdSmearIntensity", visual.ccdSmearIntensity)
                putExtra("ccdSmearTint", visual.ccdSmearTint)
                putExtra("ccdSmearFlicker", visual.ccdSmearFlicker)
                putExtra("overlayEnabled", visual.overlayEnabled)
                putExtra("overlayStartEpochMs", visual.overlayStartEpochMs)
            }
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        fun pause(context: Context) = sendAction(context, ACTION_PAUSE)
        fun resume(context: Context) = sendAction(context, ACTION_RESUME)
        fun cancelCurrent(context: Context) = sendAction(context, ACTION_CANCEL_CURRENT)
        fun cancelAll(context: Context) = sendAction(context, ACTION_CANCEL_ALL)

        private fun sendAction(context: Context, action: String) {
            if (!isProcessing()) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProcessingService::class.java).setAction(action)
            )
        }
    }

    private class ProcessingCancelled : RuntimeException()

    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()
    )
    private lateinit var notifications: NotificationManager
    private lateinit var history: ProcessingQueueStore
    @Volatile private var currentJobId: String? = null

    override fun onCreate() {
        super.onCreate()
        history = ProcessingQueueStore(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Processamento de vídeos",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Fila e progresso dos renders NTSC-RS e 480i"
                setSound(null, null)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                paused.set(true)
                currentJobId?.let { history.update(it, status = ProcessingQueueStore.PAUSED) }
                notifications.notify(NOTIFICATION_ID, progressNotification(0, "Processamento pausado"))
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                paused.set(false)
                currentJobId?.let { history.update(it, status = ProcessingQueueStore.RUNNING) }
                return START_NOT_STICKY
            }
            ACTION_CANCEL_CURRENT -> {
                cancelRequested.set(true)
                paused.set(false)
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ALL -> {
                val dropped = worker.queue.size
                worker.queue.clear()
                pendingCount.updateAndGet { (it - dropped).coerceAtLeast(0) }
                history.cancelWaiting()
                cancelRequested.set(true)
                paused.set(false)
                return START_NOT_STICKY
            }
        }

        val input = intent?.data ?: return START_NOT_STICKY
        val jobId = intent.getStringExtra("jobId") ?: UUID.randomUUID().toString()
        val trueInterlaced = intent.getBooleanExtra("trueInterlaced", false)
        val label = if (trueInterlaced) "NTSC 480i" else "NTSC MP4"
        history.enqueue(jobId, intent.getStringExtra("inputName") ?: "Vídeo", label)
        pendingCount.incrementAndGet()
        startForeground(NOTIFICATION_ID, progressNotification(0, "$label adicionado à fila"))
        worker.execute { process(intent, input, jobId, startId) }
        return START_REDELIVER_INTENT
    }

    private fun process(intent: Intent, input: Uri, jobId: String, startId: Int) {
        val trueInterlaced = intent.getBooleanExtra("trueInterlaced", false)
        val label = if (trueInterlaced) "NTSC 480i" else "NTSC MP4"
        val outputMime = if (trueInterlaced) "video/mpeg" else "video/mp4"
        currentJobId = jobId
        cancelRequested.set(false)
        history.update(jobId, status = ProcessingQueueStore.RUNNING, progress = 0, etaSeconds = -1L)
        val startedAt = System.currentTimeMillis()
        try {
            val times = intent.getLongArrayExtra("keyframeTimes") ?: LongArray(0)
            val zooms = intent.getFloatArrayExtra("keyframeZooms") ?: FloatArray(0)
            val keyframes = (0 until minOf(times.size, zooms.size)).map {
                NtscVideoProcessor.ZoomKeyframe(times[it], zooms[it])
            }
            val motorZoom = NtscVideoProcessor.MotorZoomSettings(
                enabled = intent.getBooleanExtra("zoomEnabled", false),
                startUs = intent.getLongExtra("zoomStartUs", 0L),
                durationUs = intent.getLongExtra("zoomDurationUs", 1L),
                startZoom = intent.getFloatExtra("zoomStart", 1f),
                endZoom = intent.getFloatExtra("zoomEnd", 1f),
                keyframes = keyframes
            )
            val visual = NtscVideoProcessor.VisualSettings(
                colorEnabled = intent.getBooleanExtra("colorEnabled", false),
                temperature = intent.getFloatExtra("temperature", 0f),
                saturation = intent.getFloatExtra("saturation", 1f),
                contrast = intent.getFloatExtra("contrast", 1f),
                brightness = intent.getFloatExtra("brightness", 0f),
                tint = intent.getFloatExtra("tint", 0f),
                fishEyeEnabled = intent.getBooleanExtra("fishEyeEnabled", false),
                fishEyeStrength = intent.getFloatExtra("fishEyeStrength", 0.35f),
                ccdSmearEnabled = intent.getBooleanExtra("ccdSmearEnabled", false),
                ccdSmearThreshold = intent.getFloatExtra("ccdSmearThreshold", 0.995f),
                ccdSmearKnee = intent.getFloatExtra("ccdSmearKnee", 0.005f),
                ccdSmearLength = intent.getFloatExtra("ccdSmearLength", 0.35f),
                ccdSmearIntensity = intent.getFloatExtra("ccdSmearIntensity", 0.06f),
                ccdSmearTint = intent.getIntExtra("ccdSmearTint", 0),
                ccdSmearFlicker = intent.getFloatExtra("ccdSmearFlicker", 0f),
                overlayEnabled = intent.getBooleanExtra("overlayEnabled", false),
                overlayStartEpochMs = intent.getLongExtra("overlayStartEpochMs", 0L)
            )
            var lastProgress = -1
            val output = NtscVideoProcessor(applicationContext).process(
                input,
                intent.getStringExtra("preset") ?: "",
                motorZoom,
                visual,
                trueInterlaced
            ) { progress ->
                checkpoint(jobId)
                if (progress != lastProgress) {
                    lastProgress = progress
                    val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000L
                    val eta = if (progress > 0) elapsedSeconds * (100 - progress) / progress else -1L
                    history.update(
                        jobId,
                        status = if (paused.get()) ProcessingQueueStore.PAUSED else ProcessingQueueStore.RUNNING,
                        progress = progress,
                        etaSeconds = eta
                    )
                    notifications.notify(
                        NOTIFICATION_ID,
                        progressNotification(progress, "$label • $progress%${etaText(eta)}")
                    )
                }
            }
            history.update(
                jobId,
                status = ProcessingQueueStore.COMPLETED,
                progress = 100,
                etaSeconds = 0L,
                outputUri = output.toString(),
                outputMime = outputMime
            )
            notifications.notify(
                NOTIFICATION_ID,
                finishedNotification("$label concluído", "Toque para abrir o resultado", output, outputMime, false)
            )
        } catch (_: ProcessingCancelled) {
            history.update(jobId, status = ProcessingQueueStore.CANCELLED, etaSeconds = -1L)
            notifications.notify(
                NOTIFICATION_ID,
                finishedNotification("Processamento cancelado", label, null, null, false)
            )
        } catch (error: Throwable) {
            history.update(
                jobId,
                status = ProcessingQueueStore.FAILED,
                etaSeconds = -1L,
                error = error.message?.take(300) ?: error.javaClass.simpleName
            )
            notifications.notify(
                NOTIFICATION_ID,
                finishedNotification(
                    "Falha no processamento",
                    error.message?.take(180) ?: error.javaClass.simpleName,
                    null, null, true
                )
            )
        } finally {
            currentJobId = null
            cancelRequested.set(false)
            val remaining = pendingCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            if (remaining > 0) {
                notifications.notify(
                    NOTIFICATION_ID,
                    progressNotification(0, "Abrindo próximo item • $remaining restante(s)")
                )
            } else {
                paused.set(false)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }
    }

    private fun checkpoint(jobId: String) {
        if (cancelRequested.get()) throw ProcessingCancelled()
        var announced = false
        while (paused.get()) {
            if (!announced) {
                history.update(jobId, status = ProcessingQueueStore.PAUSED)
                announced = true
            }
            if (cancelRequested.get()) throw ProcessingCancelled()
            try {
                Thread.sleep(200L)
            } catch (_: InterruptedException) {
                throw ProcessingCancelled()
            }
        }
        if (announced) history.update(jobId, status = ProcessingQueueStore.RUNNING)
        if (cancelRequested.get()) throw ProcessingCancelled()
    }

    private fun etaText(seconds: Long): String = when {
        seconds < 0 -> ""
        seconds >= 60 -> " • ~${seconds / 60}m ${seconds % 60}s"
        else -> " • ~${seconds}s"
    }

    private fun queueContentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        100,
        Intent(this, ProcessingQueueActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun resultContentIntent(uri: Uri, mime: String): PendingIntent = PendingIntent.getActivity(
        this,
        uri.toString().hashCode(),
        Intent(this, GalleryActivity::class.java).apply {
            putExtra("openResultUri", uri.toString())
            putExtra("openResultMime", mime)
            putExtra("openResultName", "Resultado MotorZoom")
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, ProcessingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun progressNotification(progress: Int, text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("MotorZoom processando")
            .setContentText(text)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(queueContentIntent())
            .addAction(Notification.Action.Builder(
                0,
                if (paused.get()) "Retomar" else "Pausar",
                actionIntent(if (paused.get()) ACTION_RESUME else ACTION_PAUSE, 101)
            ).build())
            .addAction(Notification.Action.Builder(
                0, "Cancelar", actionIntent(ACTION_CANCEL_CURRENT, 102)
            ).build())
            .build()

    private fun finishedNotification(
        title: String,
        text: String,
        output: Uri?,
        mime: String?,
        failed: Boolean
    ): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_upload_done)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .setContentIntent(if (output != null && mime != null) resultContentIntent(output, mime) else queueContentIntent())
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
