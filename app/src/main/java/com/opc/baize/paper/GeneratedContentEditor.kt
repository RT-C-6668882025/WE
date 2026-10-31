package com.opc.baize.paper

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.*
import com.opc.baize.agent.WritableDocument
import org.json.JSONArray
import org.json.JSONObject

/** Edit the source document, rather than flattened PDF pixels. No JSON knowledge required. */
object GeneratedContentEditor {
    fun show(activity: Activity, source: String, save: (String) -> Unit) {
        val structured = source.trim().startsWith("{")
        val document = if (structured) JSONObject(source).also { WritableDocument.parse(it) } else null
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24,16,24,16) }
        val edits = mutableListOf<() -> Unit>()
        fun label(text: String) { root.addView(TextView(activity).apply { this.text = text; textSize = 15f }) }
        fun field(caption: String, initial: String, numeric: Boolean = false, apply: (String) -> Unit) {
            label(caption)
            val input = EditText(activity).apply {
                hint = caption; setText(initial); textSize = 17f
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                if (!numeric) minLines = 2
            }
            root.addView(input); edits += { apply(input.text.toString()) }
        }
        val deleted = mutableSetOf<Int>()
        val newBlocks = mutableListOf<JSONObject>()
        var plain = source
        if (document == null) field("页面内容",source) { plain = it }
        else {
            field("标题",document.optString("title")) { document.put("title",it) }
            field("字号（16～28）",document.optInt("font_size",20).toString(),true) { document.put("font_size",it.toInt()) }
            field("行高",document.optInt("line_height",32).toString(),true) { document.put("line_height",it.toInt()) }
            val blocks = document.getJSONArray("blocks")
            fun block(b: JSONObject, index: Int?) {
                val name = when(b.getString("type")) { "heading" -> "标题"; "question" -> "题目"; "writing" -> "书写区"; "drawing" -> "绘图区"; "figure" -> "参考图"; else -> "正文" }
                label("—— $name ——")
                field("内容 / 说明",b.optString("text")) { b.put("text",it) }
                if (b.getString("type") == "question") field("答题留白行数（1～10）",b.optInt("answer_lines",3).toString(),true) { b.put("answer_lines",it.toInt()) }
                if (b.getString("type") in setOf("writing","drawing","figure")) {
                    field("区域高度（64～500）",b.optInt("height",240).toString(),true) { b.put("height",it.toInt()) }
                    val grids = arrayOf("blank","ruled","square","dots")
                    label("底纹：空白 / 横线 / 方格 / 点阵")
                    val grid = Spinner(activity).apply {
                        adapter = ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,arrayOf("空白","横线","方格","点阵"))
                        setSelection(grids.indexOf(b.optString("grid","blank")).coerceAtLeast(0))
                    }; root.addView(grid); edits += { b.put("grid",grids[grid.selectedItemPosition]) }
                }
                val shapes = b.optJSONArray("shapes") ?: JSONArray()
                for (i in 0 until shapes.length()) {
                    val shape = shapes.getJSONObject(i)
                    label("图形 ${i+1} · ${shape.getString("type")}")
                    if (shape.getString("type") == "text") field("图中文字",shape.optString("text")) { shape.put("text",it) }
                    for (key in listOf("x","y","x2","y2","width","height")) if (shape.has(key))
                        field("$key（相对位置 / 尺寸，0～1000）",shape.get(key).toString(),true) { shape.put(key,it.toDouble()) }
                    field("图形颜色（例如 #353535）",shape.optString("color","#353535")) { shape.put("color",it) }
                }
                if (index != null) root.addView(CheckBox(activity).apply {
                    text = "删除这个内容块"
                    setOnCheckedChangeListener { _,checked -> if (checked) deleted.add(index) else deleted.remove(index) }
                })
            }
            for (i in 0 until blocks.length()) block(blocks.getJSONObject(i),i)
            root.addView(Button(activity).apply {
                text = "添加正文"
                setOnClickListener {
                    val b = JSONObject().put("id","user-${java.util.UUID.randomUUID()}").put("type","text").put("text","")
                    newBlocks += b; block(b,null)
                }
            })
        }
        label("保存为新版本，原页和笔迹保留；新版会重新排版，原笔迹不自动搬移。")
        val scroll = ScrollView(activity).apply { addView(root) }
        val dialog = AlertDialog.Builder(activity).setTitle("修改生成内容").setView(scroll)
            .setNegativeButton("取消",null).setPositiveButton("保存并使用",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    edits.forEach { it() }
                    val result = if (document == null) plain.also { require(it.isNotBlank()) { "内容不能为空" } }
                    else {
                        val original = document.getJSONArray("blocks")
                        val kept = JSONArray()
                        for (i in 0 until original.length()) if (i !in deleted) kept.put(original.getJSONObject(i))
                        newBlocks.forEach { kept.put(it) }
                        val updated = JSONObject(document.toString()).put("blocks",kept)
                        WritableDocument.parse(updated); updated.toString()
                    }
                    require(result.toByteArray(Charsets.UTF_8).size <= 256000) { "内容过长" }
                    save(result); dialog.dismiss()
                } catch (e: Exception) { Toast.makeText(activity,"无法保存：${e.message}",Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
    }
}
