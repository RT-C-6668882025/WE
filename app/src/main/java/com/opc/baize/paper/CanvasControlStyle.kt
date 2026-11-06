package com.opc.baize.paper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable

/** Shared canvas controls: selected, keyboard focus and touch feedback use one palette. */
object CanvasControlStyle {
    fun background(context: Context): RippleDrawable {
        val density=context.resources.displayMetrics.density
        fun shape(color:Int,focus:Boolean=false)=GradientDrawable().apply {
            setColor(color);cornerRadius=8*density
            if(focus) setStroke((2*density).toInt(),Color.rgb(105,96,78))
        }
        val fill=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected,android.R.attr.state_focused),shape(Color.rgb(216,207,184),true))
            addState(intArrayOf(android.R.attr.state_selected),shape(Color.rgb(216,207,184)))
            addState(intArrayOf(android.R.attr.state_focused),shape(Color.rgb(232,224,204),true))
            addState(intArrayOf(),shape(Color.rgb(246,241,228)))
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(45,58,53,44)),fill,shape(Color.WHITE))
    }
}
