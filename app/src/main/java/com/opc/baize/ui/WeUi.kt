package com.opc.baize.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.opc.baize.paper.CanvasControlStyle

/** One visual vocabulary for the Android host; does not own business state or authentication. */
object WeUi {
    const val PRIMARY="we-primary-action"
    val paper=Color.rgb(246,241,228)
    val ink=Color.rgb(58,53,44)
    val secondary=Color.rgb(105,96,78)
    fun dp(context:Context,value:Int)=(context.resources.displayMetrics.density*value).toInt()
    fun button(context:Context,label:String,call:()->Unit)=Button(context).apply {
        text=label;contentDescription=label;setOnClickListener {call()};style(this)
    }
    fun header(activity:Activity,title:String,back:(()->Unit)?={activity.finish()}):LinearLayout = LinearLayout(activity).apply {
        gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(activity,56)
        if(back!=null) addView(button(activity,"返回",back),LinearLayout.LayoutParams(-2,dp(activity,48)))
        addView(TextView(activity).apply {
            text=title;textSize=20f;setTextColor(ink);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
            setPadding(dp(activity,12),0,dp(activity,12),0)
            accessibilityHeadingCompat()
        },LinearLayout.LayoutParams(0,-2,1f))
    }
    private fun TextView.accessibilityHeadingCompat() {if(android.os.Build.VERSION.SDK_INT>=28) isAccessibilityHeading=true}
    fun editDialog(activity:Activity,title:String,editor:EditText,message:String?=null,reset:(()->Unit)?=null,save:(String)->Unit) {
        style(editor)
        val builder=android.app.AlertDialog.Builder(activity).setTitle(title)
            .setView(ScrollView(activity).apply {addView(editor)}).setNegativeButton("取消",null).setPositiveButton("保存",null)
        if(message!=null) builder.setMessage(message)
        if(reset!=null) builder.setNeutralButton("恢复默认",null)
        val dialog=builder.create()
        dialog.show()
        run {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {save(editor.text.toString());dialog.dismiss()} catch(e:Exception) {editor.error=e.message ?: "输入未保存，请检查后重试"}
            }
            if(reset!=null) dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                try {reset();dialog.dismiss()} catch(e:Exception) {editor.error=e.message ?: "恢复失败，请重试"}
            }
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    fun style(view:View) {
        val d={n:Int->dp(view.context,n)}
        when(view) {
            is CompoundButton -> {view.minHeight=d(48);view.minimumHeight=d(48);view.setTextColor(ink)}
            is Button -> {
                view.isAllCaps=false;view.minHeight=d(48);view.minimumHeight=d(48)
                view.minWidth=d(48);view.minimumWidth=d(48);view.textSize=14f
                view.maxLines=2;view.ellipsize=android.text.TextUtils.TruncateAt.END
                view.setPadding(d(10),d(6),d(10),d(6))
                view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(Color.rgb(128,119,101),ink)))
                view.background=CanvasControlStyle.background(view.context)
                view.tooltipText=view.contentDescription ?: view.text
                if(view.tag==PRIMARY) {
                    val fill=android.graphics.drawable.GradientDrawable().apply {setColor(ink);cornerRadius=d(8).toFloat()}
                    val mask=android.graphics.drawable.GradientDrawable().apply {setColor(Color.WHITE);cornerRadius=d(8).toFloat()}
                    view.background=android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Color.argb(60,255,255,255)),fill,mask)
                    view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(Color.rgb(190,184,170),paper)))
                }
            }
            is EditText -> {
                view.minimumHeight=d(48);view.setTextColor(ink);view.setHintTextColor(secondary)
                view.setPadding(d(10),d(8),d(10),d(8))
            }
            is TextView -> {
                if(view.currentTextColor==Color.rgb(163,154,135)) view.setTextColor(secondary)
                if(view.isClickable) {
                    view.minimumHeight=d(48);view.isFocusable=true
                    view.background=CanvasControlStyle.background(view.context)
                }
            }
            is Spinner -> {view.minimumHeight=d(48);view.setPadding(d(4),0,d(4),0)}
            is SeekBar -> {view.minimumHeight=d(48)}
        }
        if(view is ViewGroup) {
            if(view.isClickable) {view.minimumHeight=d(56);view.isFocusable=true;view.background=CanvasControlStyle.background(view.context)}
            for(i in 0 until view.childCount) style(view.getChildAt(i))
            // Associate visible labels with form controls for TalkBack without exposing field values.
            for(i in 1 until view.childCount) {
                val field=view.getChildAt(i);val label=view.getChildAt(i-1)
                if((field is EditText || field is Spinner || field is SeekBar) && label is TextView && label !is Button && label !is EditText) {
                    if(field.id==View.NO_ID) {field.id=View.generateViewId();field.isSaveEnabled=false}
                    label.labelFor=field.id
                }
            }
        }
    }
}
