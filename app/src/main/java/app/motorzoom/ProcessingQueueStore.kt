package app.motorzoom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ProcessingRecord(
    val id: String,
    val inputName: String,
    val label: String,
    val status: String,
    val progress: Int,
    val etaSeconds: Long,
    val createdAt: Long,
    val outputUri: String?,
    val outputMime: String?,
    val error: String?
)

class ProcessingQueueStore(context: Context) {
    companion object {
        const val WAITING = "WAITING"
        const val RUNNING = "RUNNING"
        const val PAUSED = "PAUSED"
        const val COMPLETED = "COMPLETED"
        const val FAILED = "FAILED"
        const val CANCELLED = "CANCELLED"
        private const val PREFS = "motorzoom_processing_queue"
        private const val KEY_RECORDS = "records"
        private const val MAX_HISTORY = 50
        private val lock = Any()
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enqueue(id: String, inputName: String, label: String) = synchronized(lock) {
        val current = readMutable()
        if (current.none { it.id == id }) {
            current.add(0, ProcessingRecord(
                id, inputName, label, WAITING, 0, -1L,
                System.currentTimeMillis(), null, null, null
            ))
            write(current)
        }
    }

    fun update(
        id: String,
        status: String? = null,
        progress: Int? = null,
        etaSeconds: Long? = null,
        outputUri: String? = null,
        outputMime: String? = null,
        error: String? = null
    ) = synchronized(lock) {
        val current = readMutable()
        val at = current.indexOfFirst { it.id == id }
        if (at < 0) return@synchronized
        val old = current[at]
        current[at] = old.copy(
            status = status ?: old.status,
            progress = progress ?: old.progress,
            etaSeconds = etaSeconds ?: old.etaSeconds,
            outputUri = outputUri ?: old.outputUri,
            outputMime = outputMime ?: old.outputMime,
            error = error ?: old.error
        )
        write(current)
    }

    fun cancelWaiting() = synchronized(lock) {
        val current = readMutable().map {
            if (it.status == WAITING) it.copy(status = CANCELLED, etaSeconds = -1L) else it
        }.toMutableList()
        write(current)
    }

    fun records(): List<ProcessingRecord> = synchronized(lock) { readMutable().toList() }

    fun clearFinished() = synchronized(lock) {
        write(readMutable().filter {
            it.status == WAITING || it.status == RUNNING || it.status == PAUSED
        }.toMutableList())
    }

    private fun readMutable(): MutableList<ProcessingRecord> {
        val array = runCatching { JSONArray(prefs.getString(KEY_RECORDS, "[]")) }
            .getOrElse { JSONArray() }
        return MutableList(array.length()) { index ->
            val item = array.getJSONObject(index)
            ProcessingRecord(
                id = item.optString("id"),
                inputName = item.optString("inputName", "Vídeo"),
                label = item.optString("label", "NTSC"),
                status = item.optString("status", WAITING),
                progress = item.optInt("progress", 0),
                etaSeconds = item.optLong("etaSeconds", -1L),
                createdAt = item.optLong("createdAt", 0L),
                outputUri = item.optString("outputUri").takeIf { it.isNotBlank() },
                outputMime = item.optString("outputMime").takeIf { it.isNotBlank() },
                error = item.optString("error").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun write(records: MutableList<ProcessingRecord>) {
        val trimmed = records.take(MAX_HISTORY)
        val array = JSONArray()
        trimmed.forEach { record ->
            array.put(JSONObject().apply {
                put("id", record.id)
                put("inputName", record.inputName)
                put("label", record.label)
                put("status", record.status)
                put("progress", record.progress)
                put("etaSeconds", record.etaSeconds)
                put("createdAt", record.createdAt)
                put("outputUri", record.outputUri ?: "")
                put("outputMime", record.outputMime ?: "")
                put("error", record.error ?: "")
            })
        }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }
}
