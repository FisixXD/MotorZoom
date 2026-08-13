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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ProcessingService : Service() {
    companion object {
        private const val CHANNEL_ID = "motorzoom_processing"
        private const val NOTIFICATION_ID = 480
        private val busy = AtomicBoolean(false)

        fun isProcessing(): Boolean = busy.get()

        fun start(
            context: Context,
            input: Uri,
            preset: String,
            motorZoom: NtscVideoProcessor.MotorZoomSettings,
            visual: NtscVideoProcessor.VisualSettings,
            trueInterlaced: Boolean
        ): Boolean {
            if (!busy.compareAndSet(false, true)) return false
            val keyframeTimes = LongArray(motorZoom.keyframes.size) { motorZoom.keyframes[it].timeUs }
            val keyframeZooms = FloatArray(motorZoom.keyframes.size) { motorZoom.keyframes[it].zoom }
            val intent = Intent(context, ProcessingService::class.java).apply {
                data = input
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (error: Throwable) {
                busy.set(false)
                throw error
            }
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Processamento de vídeos",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progresso dos renders NTSC-RS e 480i"
                setSound(null, null)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val input = intent?.data
        if (input == null) {
            busy.set(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, progressNotification(0, "Preparando processamento…"))
        worker.execute { process(intent, input, startId) }
        return START_NOT_STICKY
    }

    private fun process(intent: Intent, input: Uri, startId: Int) {
        val trueInterlaced = intent.getBooleanExtra("trueInterlaced", false)
        val label = if (trueInterlaced) "NTSC 480i" else "NTSC MP4"
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
            NtscVideoProcessor(applicationContext).process(
                input,
                intent.getStringExtra("preset") ?: "",
                motorZoom,
                visual,
                trueInterlaced
            ) { progress ->
                if (progress != lastProgress) {
                    lastProgress = progress
                    notifications.notify(
                        NOTIFICATION_ID,
                        progressNotification(progress, "$label • $progress%")
                    )
                }
            }
            notifications.notify(
                NOTIFICATION_ID,
                finishedNotification(
                    "$label concluído",
                    "Salvo em Movies/MotorZoom",
                    false
                )
            )
        } catch (error: Throwable) {
            notifications.notify(
                NOTIFICATION_ID,
                finishedNotification(
                    "Falha no processamento",
                    error.message?.take(180) ?: error.javaClass.simpleName,
                    true
                )
            )
        } finally {
            busy.set(false)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, GalleryActivity::class.java),
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
            .setContentIntent(contentIntent())
            .build()

    private fun finishedNotification(title: String, text: String, failed: Boolean): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
