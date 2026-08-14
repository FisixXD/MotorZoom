package app.motorzoom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MotorZoomPreset(val id: String, val name: String, val json: String)

class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("motorzoom_settings", Context.MODE_PRIVATE)
    fun current(): MotorZoomPreset {
        val json = prefs.getString("current_preset_json", null) ?: NativeNtsc.defaultPreset()
        return MotorZoomPreset(prefs.getString("current_preset_id", "default") ?: "default",
            prefs.getString("current_preset_name", "Padrão NTSC-RS") ?: "Padrão NTSC-RS", json)
    }
    fun select(preset: MotorZoomPreset) {
        prefs.edit().putString("current_preset_id", preset.id)
            .putString("current_preset_name", preset.name)
            .putString("current_preset_json", preset.json).apply()
    }
    fun list(): MutableList<MotorZoomPreset> {
        val array = runCatching { JSONArray(prefs.getString("preset_library", "[]")) }.getOrElse { JSONArray() }
        return MutableList(array.length()) { i -> array.getJSONObject(i).let {
            MotorZoomPreset(it.getString("id"), it.getString("name"), it.getString("json"))
        }}
    }
    fun save(name: String, json: String, id: String = UUID.randomUUID().toString()): MotorZoomPreset {
        val normalized = NativeNtsc.normalizePreset(json) ?: error("Preset incompatível com esta versão do NTSC-RS")
        val preset = MotorZoomPreset(id, name.trim().ifBlank { "Preset sem nome" }, normalized)
        val items = list(); val position = items.indexOfFirst { it.id == id }
        if (position >= 0) items[position] = preset else items.add(preset)
        write(items); select(preset); return preset
    }
    fun delete(id: String) {
        write(list().filterNot { it.id == id })
        if (current().id == id) select(defaultPreset())
    }
    fun defaultPreset() = MotorZoomPreset("default", "Padrão NTSC-RS", NativeNtsc.defaultPreset())
    fun builtin(name: String): MotorZoomPreset {
        val json = JSONObject(NativeNtsc.defaultPreset())
        fun set(key: String, value: Any) { if (json.has(key)) json.put(key, value) }
        when (name) {
            "VHS doméstico" -> { set("vhs_settings", true); set("vhs_tape_speed", 2); set("vhs_chroma_loss", 0.00004); set("head_switching", true); set("composite_noise_intensity", 0.08) }
            "Video8 / Hi8" -> { set("vhs_settings", false); set("chroma_delay_horizontal", 2.0); set("composite_noise_intensity", 0.035); set("luma_smear", 0.25) }
            "MiniDV" -> { set("vhs_settings", false); set("head_switching", false); set("tracking_noise", false); set("composite_noise_intensity", 0.01) }
            "Skate VX" -> { set("vhs_settings", false); set("composite_preemphasis", 1.3); set("chroma_delay_horizontal", 1.0); set("luma_smear", 0.18) }
            "Fita desgastada" -> { set("vhs_settings", true); set("vhs_tape_speed", 3); set("tracking_noise", true); set("tracking_noise_snow_intensity", 0.08); set("head_switching", true); set("snow_intensity", 0.012) }
            "Filmadora noturna" -> { set("luma_noise", true); set("luma_noise_intensity", 0.12); set("chroma_noise", true); set("chroma_noise_intensity", 0.06) }
            "CCD urbano" -> { set("composite_noise_intensity", 0.025); set("chroma_phase_error", 0.08); set("chroma_delay_horizontal", 2.0) }
        }
        return MotorZoomPreset("builtin:$name", name, json.toString())
    }
    private fun write(items: List<MotorZoomPreset>) {
        val array = JSONArray(); items.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("json", it.json)) }
        prefs.edit().putString("preset_library", array.toString()).apply()
    }
}
