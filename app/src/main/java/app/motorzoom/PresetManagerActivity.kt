package app.motorzoom

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class PresetManagerActivity : AppCompatActivity() {
    private lateinit var store: PresetStore
    private lateinit var list: LinearLayout
    private var exportText = ""
    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        }.onSuccess { editPreset(MotorZoomPreset("", "Preset importado", it), true) }
            .onFailure { toast(it.message ?: "Não foi possível importar") }
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(exportText) } }
            .onSuccess { toast("Preset exportado") }.onFailure { toast(it.message ?: "Erro ao exportar") }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); store = PresetStore(this); buildUi() }
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)); setBackgroundColor(0xff111111.toInt()) }
        root.addView(TextView(this).apply { text="PRESETS MOTORZOOM"; textSize=24f; setTextColor(Color.WHITE) })
        val actions=LinearLayout(this)
        actions.addView(Button(this).apply { text="NOVO"; setOnClickListener { editPreset(store.defaultPreset(), true) } })
        actions.addView(Button(this).apply { text="IMPORTAR"; setOnClickListener { importer.launch(arrayOf("application/json","text/plain")) } })
        actions.addView(Button(this).apply { text="FECHAR"; setOnClickListener { finish() } })
        root.addView(actions); list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) },LinearLayout.LayoutParams(-1,0,1f)); setContentView(root); refresh()
    }
    private fun refresh() {
        list.removeAllViews(); val current=store.current()
        addRow(store.defaultPreset(),current.id=="default",false)
        listOf("VHS doméstico","Video8 / Hi8","MiniDV","Skate VX","Fita desgastada","Filmadora noturna","CCD urbano")
            .forEach { addRow(store.builtin(it),current.id=="builtin:$it",false) }
        store.list().forEach { addRow(it,current.id==it.id,true) }
    }
    private fun addRow(preset: MotorZoomPreset, selected:Boolean, editable:Boolean) {
        val row=LinearLayout(this)
        row.addView(Button(this).apply { text=(if(selected)"✓ " else "")+preset.name; setOnClickListener { store.select(preset);refresh();toast("Preset selecionado") } },LinearLayout.LayoutParams(0,dp(52),1f))
        row.addView(Button(this).apply { text="EDITAR";setOnClickListener { editPreset(preset,!editable) } })
        row.addView(Button(this).apply { text="⋮";setOnClickListener { actions(preset,editable) } });list.addView(row)
    }
    private fun actions(preset:MotorZoomPreset,deletable:Boolean) {
        val labels=if(deletable) arrayOf("Duplicar","Exportar","Excluir") else arrayOf("Duplicar","Exportar")
        AlertDialog.Builder(this).setTitle(preset.name).setItems(labels){_,which->when(labels[which]){
            "Duplicar"->editPreset(preset.copy(id="",name=preset.name+" cópia"),true)
            "Exportar"->{exportText=preset.json;exporter.launch(preset.name+".json")}
            "Excluir"->AlertDialog.Builder(this).setMessage("Excluir ${preset.name}?").setPositiveButton("Excluir"){_,_->store.delete(preset.id);refresh()}.setNegativeButton("Cancelar",null).show()
        }}.show()
    }
    private fun editPreset(source:MotorZoomPreset,saveAsNew:Boolean) {
        val normalized=NativeNtsc.normalizePreset(source.json);if(normalized==null){toast("Preset incompatível");return}
        val values=JSONObject(normalized);val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),0) }
        val name=EditText(this).apply { setText(source.name);hint="Nome do preset" };content.addView(name)
        val editors=linkedMapOf<String,View>()
        values.keys().asSequence().filter{it!="version"}.sorted().forEach { key->
            content.addView(TextView(this).apply { text=key.replace('_',' ').replaceFirstChar { it.titlecase(Locale.getDefault()) } })
            val value=values.get(key);val editor:View=if(value is Boolean) CheckBox(this).apply { isChecked=value } else EditText(this).apply {
                setText(value.toString());inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            };editors[key]=editor;content.addView(editor)
        }
        val dialog=AlertDialog.Builder(this).setTitle("Editor NTSC-RS").setView(ScrollView(this).apply { addView(content) }).setPositiveButton("Salvar",null).setNegativeButton("Cancelar",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { editors.forEach { (key,view)->if(view is CheckBox) values.put(key,view.isChecked) else {
                val text=(view as EditText).text.toString().replace(',','.');val old=values.get(key);values.put(key,if(old is Int)text.toInt() else text.toDouble())
            }};store.save(name.text.toString(),values.toString(),if(saveAsNew)UUID.randomUUID().toString() else source.id) }
                .onSuccess { dialog.dismiss();refresh();toast("Preset salvo e selecionado") }.onFailure { toast(it.message?:"Valor inválido") }
        }};dialog.show()
    }
    private fun toast(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
}
