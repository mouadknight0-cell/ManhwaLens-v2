package com.manhwalens.app.overlay

import android.content.Context
import android.widget.ImageView
import com.manhwalens.app.R

internal fun dockButton(ctx:Context,action:FloatingAction,tint:Int,onTap:()->Unit)=ImageView(ctx).apply {
    val (icon,label)=when(action){
        FloatingAction.START -> R.drawable.ic_play to R.string.dock_start
        FloatingAction.STOP -> R.drawable.ic_stop_square to R.string.dock_stop
        FloatingAction.CLOSE -> R.drawable.ic_close to R.string.action_close
    }
    setImageResource(icon);scaleType=ImageView.ScaleType.CENTER;setColorFilter(tint)
    contentDescription=ctx.getString(label);tooltipText=contentDescription
    minimumWidth=(48*resources.displayMetrics.density+.5f).toInt();minimumHeight=minimumWidth
    setOnClickListener { onTap() }
}
