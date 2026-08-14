package app.motorzoom

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProcessingQueueActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var pauseButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
            setBackgroundColor(Color.BLACK)
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(Button(this).apply {
            text = "‹ CÂMERA"
            setOnClickListener { finish() }
        })
        controls.addView(TextView(this).apply {
            text = "FILA DE PROCESSAMENTO"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        pauseButton = Button(this).apply {
            text = if (ProcessingService.isPaused()) "RETOMAR" else "PAUSAR"
            setOnClickListener {
                if (ProcessingService.isPaused()) ProcessingService.resume(this@ProcessingQueueActivity)
                else ProcessingService.pause(this@ProcessingQueueActivity)
                postDelayed({ refresh() }, 250L)
            }
        }
        controls.addView(pauseButton)
        controls.addView(Button(this).apply {
            text = "CANCELAR ATUAL"
            setOnClickListener {
                ProcessingService.cancelCurrent(this@ProcessingQueueActivity)
                postDelayed({ refresh() }, 350L)
            }
        })
        controls.addView(Button(this).apply {
            text = "ATUALIZAR"
            setOnClickListener { refresh() }
        })
        root.addView(controls)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val footer = LinearLayout(this).apply { gravity = Gravity.END }
        footer.addView(Button(this).apply {
            text = "CANCELAR FILA"
            setOnClickListener {
                ProcessingService.cancelAll(this@ProcessingQueueActivity)
                postDelayed({ refresh() }, 350L)
            }
        })
        footer.addView(Button(this).apply {
            text = "LIMPAR CONCLUÍDOS"
            setOnClickListener {
                ProcessingQueueStore(this@ProcessingQueueActivity).clearFinished()
                refresh()
            }
        })
        root.addView(footer)
        setContentView(root)
    }

    private fun refresh() {
        pauseButton.text = if (ProcessingService.isPaused()) "RETOMAR" else "PAUSAR"
        list.removeAllViews()
        val records = ProcessingQueueStore(this).records()
        if (records.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nenhum processamento no histórico"
                setTextColor(0xffbbbbbb.toInt())
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, dp(80), 0, 0)
            })
            return
        }
        records.forEach { record -> addRecord(record) }
    }

    private fun addRecord(record: ProcessingRecord) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(0xff181818.toInt())
        }
        val state = when (record.status) {
            ProcessingQueueStore.WAITING -> "NA FILA"
            ProcessingQueueStore.RUNNING -> "PROCESSANDO"
            ProcessingQueueStore.PAUSED -> "PAUSADO"
            ProcessingQueueStore.COMPLETED -> "CONCLUÍDO"
            ProcessingQueueStore.FAILED -> "ERRO"
            else -> "CANCELADO"
        }
        card.addView(TextView(this).apply {
            text = "${record.label} • $state"
            setTextColor(if (record.status == ProcessingQueueStore.FAILED) 0xffff7777.toInt() else Color.WHITE)
            textSize = 17f
        })
        val eta = if (record.etaSeconds >= 0 && record.status in listOf(
                ProcessingQueueStore.RUNNING, ProcessingQueueStore.PAUSED
            )) " • faltam ~${formatDuration(record.etaSeconds)}" else ""
        card.addView(TextView(this).apply {
            text = "${record.inputName}\n${record.progress}%$eta • ${SimpleDateFormat(
                "dd/MM HH:mm", Locale.getDefault()
            ).format(Date(record.createdAt))}"
            setTextColor(0xffcccccc.toInt())
        })
        record.error?.let { detail ->
            card.addView(TextView(this).apply {
                text = detail
                setTextColor(0xffff9999.toInt())
            })
        }
        if (record.status == ProcessingQueueStore.COMPLETED && record.outputUri != null) {
            card.addView(Button(this).apply {
                text = "ABRIR RESULTADO"
                setOnClickListener {
                    startActivity(Intent(this@ProcessingQueueActivity, GalleryActivity::class.java).apply {
                        putExtra("openResultUri", record.outputUri)
                        putExtra("openResultMime", record.outputMime ?: "video/mp4")
                        putExtra("openResultName", record.label)
                    })
                }
            })
        }
        list.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(5), 0, dp(5)) })
    }

    private fun formatDuration(seconds: Long): String = if (seconds >= 60) {
        "${seconds / 60}m ${seconds % 60}s"
    } else "${seconds}s"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
