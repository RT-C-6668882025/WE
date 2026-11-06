package com.opc.baize.paper

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.widget.*

/** Drawing controls stay in panels so the paper keeps its space. */
class DrawingPanels(private val activity: Activity,private val ink: PaperInkView,private val write: ()->Unit,private val importImage: ()->Unit = {},private val setTool:(PaperInkView.Mode)->Unit = {ink.mode=it}) {
    private fun box()=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12)}
    private fun button(box: LinearLayout,label: String,action: ()->Unit) {
        box.addView(Button(activity).apply {text=label;setOnClickListener {action()}})
    }
    private fun spinner(box: LinearLayout,label: String,items: List<String>,selected: Int): Spinner {
        box.addView(TextView(activity).apply {text=label})
        return Spinner(activity).apply {adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,items);setSelection(selected.coerceAtLeast(0));box.addView(this)}
    }
    private fun slider(box: LinearLayout,name: String,value: Float): SeekBar {
        val label=TextView(activity);box.addView(label)
        return SeekBar(activity).apply {max=100;progress=(value*100).toInt();box.addView(this)
            fun update() {label.text="$name：$progress%"};update()
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b:SeekBar?,v:Int,u:Boolean){update()}
                override fun onStartTrackingTouch(b:SeekBar?){};override fun onStopTrackingTouch(b:SeekBar?){}
            })
        }
    }
    fun brushes() {
        val initialBrush=ink.brushSettings;val initialWidth=ink.penWidth;val initialColor=ink.penColor
        val box=box()
        val preset=spinner(box,"笔刷预设",BrushSettings.names.toList(),0)
        val brush=ink.brushSettings
        val hardness=slider(box,"硬度",brush.hardness);val opacity=slider(box,"不透明度",brush.opacity)
        val smudge=slider(box,"混色／涂抹",brush.smudge);val spacing=slider(box,"间距",brush.spacing)
        val size=EditText(activity).apply {hint="粗细（0.1–500）";setText(ink.penWidth.toString());inputType=8194};box.addView(size)
        val color=EditText(activity).apply {hint="#RRGGBB 或 #AARRGGBB";setText(String.format("#%08X",ink.penColor));maxLines=1};box.addView(color)
        val swatches=LinearLayout(activity);box.addView(swatches)
        listOf(Color.BLACK,Color.WHITE,0xff1955af.toInt(),0xffbe2828.toInt(),0xff237d4b.toInt(),0xffffb000.toInt()).forEach { c ->
            swatches.addView(Button(activity).apply {text="●";setTextColor(c);contentDescription=String.format("颜色 #%08X",c)
                setOnClickListener {color.setText(String.format("#%08X",c))}},LinearLayout.LayoutParams(0,-2,1f))
        }
        val pressure=Switch(activity).apply {text="压感控制粗细";isChecked=brush.pressure};box.addView(pressure)
        val mypaint=Switch(activity).apply {text="MyPaint 引擎";isChecked=brush.mypaint};box.addView(mypaint)
        button(box,"载入所选预设") {val b=BrushSettings.presets[preset.selectedItemPosition]
            hardness.progress=(b.hardness*100).toInt();opacity.progress=(b.opacity*100).toInt();smudge.progress=(b.smudge*100).toInt()
            spacing.progress=(b.spacing*100).toInt();pressure.isChecked=b.pressure;mypaint.isChecked=b.mypaint
        }
        val scroll=ScrollView(activity).apply {addView(box)}
        val dialog=AlertDialog.Builder(activity).setTitle("绘画笔刷").setView(scroll).setNegativeButton("取消",null).setPositiveButton("使用",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(ink.brushSettings!=initialBrush || ink.penWidth!=initialWidth || ink.penColor!=initialColor) {size.error="笔刷已变化，请重新打开设置";return@setOnClickListener}
            val width=size.text.toString().toFloatOrNull()
            val parsed=try {Color.parseColor(color.text.toString())} catch(_:IllegalArgumentException){null}
            if(width==null || !width.isFinite() || width !in .1f..500f) {size.error="请输入 0.1–500";return@setOnClickListener}
            if(parsed==null) {color.error="请输入有效的十六进制颜色";return@setOnClickListener}
            ink.penWidth=width;ink.penColor=parsed;ink.brushEngine=DrawdanceBrush.ENGINE
            ink.brushSettings=BrushSettings(hardness.progress/100f,opacity.progress/100f,(spacing.progress/100f).coerceAtLeast(.01f),smudge.progress/100f,pressure.isChecked,mypaint.isChecked)
            activity.getSharedPreferences("moling",Activity.MODE_PRIVATE).edit().putFloat("drawing_width",width)
                .putInt("drawing_color",parsed).putString("drawing_brush",ink.brushSettings.encode().toString()).apply()
            ink.drawingTool="freehand";write();dialog.dismiss()
        }};dialog.show()
    }
    private fun unchanged(page:PaperPage):Boolean {
        if(ink.page==page) return true
        Toast.makeText(activity,"画布已变化，请重新打开面板",Toast.LENGTH_SHORT).show();return false
    }
    fun layers() {
        val page=ink.page;val box=box()
        val dialog=AlertDialog.Builder(activity).setTitle("图层 · 从上到下").setView(ScrollView(activity).apply {addView(box)})
            .setNegativeButton("关闭",null).create()
        fun action(call:()->Unit) {if(unchanged(page)) {call();dialog.dismiss();layers()}}
        for(layer in page.layers.asReversed()) {
            val row=LinearLayout(activity);box.addView(row)
            row.addView(Button(activity).apply {
                text=(if(layer.id==page.activeLayerId) "✓ " else "")+layer.name;isAllCaps=false;maxLines=2;minHeight=48
                contentDescription="选择图层 ${layer.name}"
                setOnClickListener {action {ink.selectLayer(layer.id)}}
            },LinearLayout.LayoutParams(0,-2,1f))
            row.addView(Button(activity).apply {text=if(layer.visible) "显示" else "隐藏";minWidth=0;minimumWidth=0
                contentDescription="切换图层 ${layer.name} 的显示状态"
                setOnClickListener {action {ink.updateLayer(layer.copy(visible=!layer.visible))}}
            },LinearLayout.LayoutParams(68.dp(),-2))
            row.addView(Button(activity).apply {text=if(layer.locked) "锁定" else "解锁";minWidth=0;minimumWidth=0
                contentDescription="切换图层 ${layer.name} 的锁定状态"
                setOnClickListener {action {ink.updateLayer(layer.copy(locked=!layer.locked))}}
            },LinearLayout.LayoutParams(68.dp(),-2))
        }
        button(box,"＋ 新图层") {action {ink.addLayer()}}
        button(box,"当前图层设置") {if(unchanged(page)) {dialog.dismiss();layerDetails()}}
        dialog.show()
    }
    private fun Int.dp()=(this*activity.resources.displayMetrics.density).toInt()
    private fun layerDetails() {
        val page=ink.page;val layer=page.activeLayer;val box=box()
        val name=EditText(activity).apply {setText(layer.name);hint="图层名称";maxLines=1};box.addView(name)
        val visible=Switch(activity).apply {text="显示";isChecked=layer.visible};box.addView(visible)
        val locked=Switch(activity).apply {text="锁定";isChecked=layer.locked};box.addView(locked)
        val opacity=slider(box,"不透明度",layer.opacity)
        val blend=spinner(box,"混合模式",listOf("正常","正片叠底","滤色","叠加","变暗","变亮"),PaperLayer.blends.indexOf(layer.blend))
        val dialog=AlertDialog.Builder(activity).setTitle("图层设置").setView(ScrollView(activity).apply {addView(box)})
            .setNegativeButton("取消",null).setPositiveButton("保存",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(!unchanged(page)) return@setOnClickListener
            val title=name.text.toString().trim()
            if(title.isBlank() || title.length>120) {name.error="请输入 1–120 个字";return@setOnClickListener}
            ink.updateLayer(layer.copy(name=title,visible=visible.isChecked,locked=locked.isChecked,opacity=opacity.progress/100f,blend=PaperLayer.blends[blend.selectedItemPosition]))
            dialog.dismiss()
        }}
        fun action(label:String,call:()->Unit) {button(box,label){if(unchanged(page)) {call();dialog.dismiss();layers()}}}
        action("上移") {ink.reorderLayer(1)};action("下移") {ink.reorderLayer(-1)};action("复制图层") {ink.duplicateLayer()}
        button(box,if(layer.locked) "请先解锁，再删除图层" else "删除当前图层") {
            if(layer.locked || !unchanged(page)) return@button
            AlertDialog.Builder(activity).setTitle("删除当前图层及其内容？").setMessage("可通过撤销恢复。")
                .setNegativeButton("取消",null).setPositiveButton("删除") {_,_->if(unchanged(page)) {ink.deleteLayer();dialog.dismiss();layers()}}.show()
        }
        dialog.show()
    }
    fun addObjects() {
        AlertDialog.Builder(activity).setTitle("添加到当前图层")
            .setItems(arrayOf("文字","图片","矢量图","编辑已有文字或矢量图")) {_,i ->
                when(i) {0->artwork("text");1->importImage();2->artwork("svg");3->editObjects()}
            }.setNegativeButton("取消",null).show()
    }
    private fun editObjects() {
        val page=ink.page
        val objects=page.strokes.withIndex().filter {it.value.layerId==page.activeLayerId && it.value.operation in setOf("text","svg")}
        if(objects.isEmpty()) {Toast.makeText(activity,"当前图层没有文字或矢量对象",Toast.LENGTH_SHORT).show();return}
        AlertDialog.Builder(activity).setTitle("选择对象").setItems(objects.map {"${it.index} · ${if(it.value.operation=="text") it.value.content.take(24) else "矢量图"}"}.toTypedArray()) {_,j ->
            if(unchanged(page)) artwork(objects[j].value.operation,objects[j].index)
        }.setNegativeButton("取消",null).show()
    }
    fun tools() {
        val labels=arrayOf("自由画笔","直线","矩形","椭圆","填充当前图层","吸管取色","局部橡皮","整笔橡皮","重做","移动／复制／删除选区","添加文字","导入图片","添加矢量图","编辑文字／矢量对象")
        AlertDialog.Builder(activity).setTitle("绘画工具").setItems(labels) {_,i ->
            if(i<=5) {ink.drawingTool=listOf("freehand","line","rectangle","ellipse","fill","eyedropper")[i];ink.brushEngine=DrawdanceBrush.ENGINE;write()}
            if(i==6 || i==7) {ink.pixelEraser=i==6;setTool(PaperInkView.Mode.ERASE)}
            if(i==8) ink.redo()
            if(i==9) selection()
            if(i==10) artwork("text")
            if(i==11) importImage()
            if(i==12) artwork("svg")
            if(i==13) editObjects()
        }.setNegativeButton("关闭",null).show()
    }
    private fun artwork(type:String,index:Int?=null) {
        val initialPage=ink.page
        val old=index?.let {ink.page.strokes[it]}
        val source=EditText(activity).apply {
            hint=if(type=="text") "输入文字" else "粘贴矢量图内容"
            setText(old?.content.orEmpty());minLines=3;maxLines=10
        }
        val box=box();box.addView(source)
        val size=EditText(activity).apply {hint="字号（1–1000）";setText((old?.fontSize ?: 24f).toString());inputType=8194}
        if(type=="text") box.addView(size)
        val dialog=AlertDialog.Builder(activity).setTitle(if(type=="text") "文字对象" else "矢量图对象")
            .setView(ScrollView(activity).apply {addView(box)}).setNegativeButton("取消",null).setPositiveButton("应用",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val font=size.text.toString().toFloatOrNull()
            if(font==null || !font.isFinite() || font !in 1f..1000f) {size.error="请输入 1–1000";return@setOnClickListener}
            try {
                require(ink.page==initialPage) { "画布已变化，请保留输入内容并重新打开对象编辑" }
                ArtworkContent.validate(type,source.text.toString(),font)
                val op=org.json.JSONObject().put("action",if(index==null) type else "replace_artwork")
                    .put("content",source.text.toString()).put("font_size",font)
                if(index!=null) op.put("indices",org.json.JSONArray(listOf(index))) else op
                    .put("x",ink.page.width*.1f).put("y",ink.page.height*.1f)
                    .put("x2",ink.page.width*.9f).put("y2",ink.page.height*.6f)
                    .put("color",String.format("#%08X",ink.penColor))
                // Parse SVG before closing the editor, so invalid source remains recoverable.
                val next=CanvasEdits.apply(ink.page,org.json.JSONArray().put(op))
                next.strokes.filter {it.operation in ArtworkContent.types}.forEach {ArtworkRenderer.validate(it)}
                if(ink.applyArtwork(org.json.JSONArray().put(op))) {write();dialog.dismiss()}
            } catch(e:Exception) {source.error=e.message ?: "无法应用"}
        }};dialog.show()
    }
    private fun selection() {
        if(!ink.hasSelection) {Toast.makeText(activity,"请先框选完整对象",Toast.LENGTH_SHORT).show();return}
        val initial=ink.page;val initialSelection=ink.context().get("selection").toString()
        val box=box()
        fun number(label:String,value:String)=EditText(activity).apply {hint=label;setText(value);inputType=12290;box.addView(this)}
        val x=number("水平移动","0");val y=number("垂直移动","0");val scale=number("缩放倍数","1");val angle=number("旋转角度","0")
        val mirror=Switch(activity).apply {text="水平镜像"};box.addView(mirror)
        val copy=Switch(activity).apply {text="保留原对象，复制选区"};box.addView(copy)
        val dialog=AlertDialog.Builder(activity).setTitle("变换选区 · 当前图层").setView(ScrollView(activity).apply {addView(box)})
            .setNegativeButton("取消",null).setNeutralButton("删除",null).setPositiveButton("应用",null).create()
        fun fresh()=unchanged(initial) && ink.context().get("selection").toString()==initialSelection
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {if(fresh() && ink.transformSelection(0f,0f,1f,delete=true)) dialog.dismiss()}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if(!fresh()) return@setOnClickListener
                val dx=x.text.toString().toFloatOrNull();val dy=y.text.toString().toFloatOrNull()
                val z=scale.text.toString().toFloatOrNull();val rotation=angle.text.toString().toFloatOrNull()
                if(dx==null || !dx.isFinite()) {x.error="请输入有效数值";return@setOnClickListener}
                if(dy==null || !dy.isFinite()) {y.error="请输入有效数值";return@setOnClickListener}
                if(z==null || !z.isFinite() || z !in .05f..20f) {scale.error="缩放范围 0.05–20";return@setOnClickListener}
                if(rotation==null || !rotation.isFinite()) {angle.error="请输入有效角度";return@setOnClickListener}
                if(ink.transformSelection(dx,dy,z,copy.isChecked,rotation=rotation,mirror=mirror.isChecked)) dialog.dismiss()
                else x.error="未应用：请检查图层锁定、选区和页面边界"
            }
        };dialog.show()
    }
}
