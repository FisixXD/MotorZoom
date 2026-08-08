package app.motorzoom

import android.app.Dialog
import android.app.ProgressDialog
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class GalleryActivity : AppCompatActivity() {
    private data class MediaItem(
        val uri: Uri,
        val name: String,
        val mime: String,
        val dateAddedSeconds: Long,
        val sizeBytes: Long,
        val isVideo: Boolean
    ) {
        val isMpeg: Boolean
            get() = mime.equals("video/mpeg", true) ||
                name.endsWith(".mpg", true) || name.endsWith(".mpeg", true)
    }

    private lateinit var grid: GridLayout
    private lateinit var emptyLabel: TextView
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        buildUi()
        loadGallery()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xff151515.toInt())
        }
        val back = Button(this).apply {
            text = "‹ CÂMERA"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "GALERIA MOTORZOOM"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        val refresh = Button(this).apply {
            text = "ATUALIZAR"
            setOnClickListener { loadGallery() }
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(130), dp(48)))
        bar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        bar.addView(refresh, LinearLayout.LayoutParams(dp(130), dp(48)))
        root.addView(bar)

        val body = FrameLayout(this)
        grid = GridLayout(this).apply {
            columnCount = 3
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setPadding(dp(8), dp(8), dp(8), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(grid) }
        emptyLabel = TextView(this).apply {
            text = "Nenhuma mídia do MotorZoom encontrada"
            setTextColor(0xffbbbbbb.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        body.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        body.addView(emptyLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun loadGallery() {
        grid.removeAllViews()
        emptyLabel.visibility = View.GONE
        Thread {
            val items = runCatching {
                (queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false) +
                    queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true))
                    .sortedByDescending { it.dateAddedSeconds }
            }.getOrElse { error ->
                runOnUiThread {
                    Toast.makeText(this, "Erro ao abrir galeria: ${error.message}", Toast.LENGTH_LONG).show()
                }
                emptyList()
            }
            runOnUiThread {
                if (items.isEmpty()) {
                    emptyLabel.visibility = View.VISIBLE
                } else {
                    items.forEach { addTile(it) }
                }
            }
        }.start()
    }

    private fun queryCollection(collection: Uri, isVideo: Boolean): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE
        )
        contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("MotorZoom_%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idAt = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameAt = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeAt = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateAt = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val sizeAt = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idAt)
                result += MediaItem(
                    uri = ContentUris.withAppendedId(collection, id),
                    name = cursor.getString(nameAt) ?: "MotorZoom",
                    mime = cursor.getString(mimeAt) ?: if (isVideo) "video/*" else "image/*",
                    dateAddedSeconds = cursor.getLong(dateAt),
                    sizeBytes = cursor.getLong(sizeAt),
                    isVideo = isVideo
                )
            }
        }
        return result
    }

    private fun addTile(item: MediaItem) {
        val availableWidth = resources.displayMetrics.widthPixels - dp(32)
        val tileWidth = max(dp(180), availableWidth / 3 - dp(8))
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { openItem(item) }
        }
        val previewFrame = FrameLayout(this).apply { setBackgroundColor(0xff242424.toInt()) }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xff242424.toInt())
        }
        val badge = TextView(this).apply {
            text = when {
                item.isMpeg -> "MPG • 480i"
                item.isVideo -> "VÍDEO"
                else -> "FOTO"
            }
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(7), dp(3), dp(7), dp(3))
            setBackgroundColor(if (item.isMpeg) 0xffa9342f.toInt() else 0xcc111111.toInt())
        }
        previewFrame.addView(preview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        previewFrame.addView(badge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        container.addView(previewFrame, LinearLayout.LayoutParams(tileWidth, dp(145)))

        val date = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.getDefault())
            .format(Date(item.dateAddedSeconds * 1000L))
        val megabytes = item.sizeBytes / (1024f * 1024f)
        container.addView(TextView(this).apply {
            text = item.name
            maxLines = 1
            setTextColor(Color.WHITE)
            textSize = 13f
        })
        container.addView(TextView(this).apply {
            text = String.format(Locale.getDefault(), "%s  •  %.1f MB", date, megabytes)
            maxLines = 1
            setTextColor(0xffaaaaaa.toInt())
            textSize = 11f
        })
        grid.addView(container, GridLayout.LayoutParams().apply {
            width = tileWidth
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(dp(3), dp(3), dp(3), dp(3))
        })

        thumbnailExecutor.execute {
            val bitmap = loadThumbnail(item)
            if (bitmap != null) runOnUiThread {
                if (!isDestroyed) preview.setImageBitmap(bitmap)
            }
        }
    }

    private fun loadThumbnail(item: MediaItem): Bitmap? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.loadThumbnail(item.uri, Size(480, 270), null)
            } else if (!item.isVideo) {
                contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it) }
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, item.uri)
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            }
        }.getOrNull()
    }

    private fun openItem(item: MediaItem) {
        when {
            !item.isVideo -> showImage(item)
            item.isMpeg -> prepareMpegPreview(item)
            else -> showVideo(item.uri, item.name)
        }
    }

    private fun showImage(item: MediaItem) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageURI(item.uri)
        }
        val close = Button(this).apply {
            text = "FECHAR"
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(image, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(close, FrameLayout.LayoutParams(dp(120), dp(50), Gravity.TOP or Gravity.END).apply {
            setMargins(dp(12), dp(12), dp(12), dp(12))
        })
        dialog.setContentView(root)
        dialog.show()
    }

    private fun prepareMpegPreview(item: MediaItem) {
        val progress = ProgressDialog(this).apply {
            setTitle("Preparando MPG 480i")
            setMessage("Criando uma prévia para reprodução…")
            setCancelable(false)
            show()
        }
        Thread {
            val preview = runCatching { createMpegPreview(item) }
            runOnUiThread {
                progress.dismiss()
                preview.onSuccess { showVideo(Uri.fromFile(it), item.name) }
                    .onFailure { showMpegFallback(item, it.message ?: "erro desconhecido") }
            }
        }.start()
    }

    private fun createMpegPreview(item: MediaItem): File {
        val cacheKey = item.uri.toString().hashCode().toUInt().toString(16)
        val preview = File(cacheDir, "gallery_mpg_$cacheKey.mp4")
        if (preview.length() > 0L) return preview
        val source = File(cacheDir, "gallery_mpg_$cacheKey.mpg")
        contentResolver.openInputStream(item.uri)!!.use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val ffmpeg = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
        check(ffmpeg.canExecute()) { "FFmpeg não está disponível nesta instalação" }
        val command = listOf(
            ffmpeg.absolutePath,
            "-hide_banner", "-loglevel", "warning", "-y",
            "-i", source.absolutePath,
            "-map", "0:v:0", "-map", "0:a:0?",
            "-vf", "yadif=0:-1:0,scale=640:480",
            "-c:v", "mpeg4", "-q:v", "4",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
            preview.absolutePath
        )
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            check(exit == 0 && preview.length() > 0L) {
                "Não foi possível preparar a prévia (${log.takeLast(500)})"
            }
            return preview
        } finally {
            source.delete()
            if (preview.length() == 0L) preview.delete()
        }
    }

    private fun showVideo(uri: Uri, name: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = VideoView(this)
        val controller = MediaController(this).apply { setAnchorView(video) }
        video.setMediaController(controller)
        video.setVideoURI(uri)
        video.setOnPreparedListener { player ->
            player.isLooping = false
            video.start()
        }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Não foi possível reproduzir $name", Toast.LENGTH_LONG).show()
            true
        }
        val close = Button(this).apply {
            text = "FECHAR"
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(video, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))
        root.addView(close, FrameLayout.LayoutParams(dp(120), dp(50), Gravity.TOP or Gravity.END).apply {
            setMargins(dp(12), dp(12), dp(12), dp(12))
        })
        dialog.setOnDismissListener { video.stopPlayback() }
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showMpegFallback(item: MediaItem, detail: String) {
        AlertDialog.Builder(this)
            .setTitle("Não foi possível abrir o MPG")
            .setMessage(detail)
            .setPositiveButton("Abrir em outro app") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(item.uri, "video/mpeg")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }.onFailure {
                    Toast.makeText(this, "Nenhum reprodutor compatível encontrado", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }
}
