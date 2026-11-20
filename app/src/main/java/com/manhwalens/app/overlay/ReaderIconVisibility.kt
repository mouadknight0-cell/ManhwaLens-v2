package com.manhwalens.app.overlay

import android.view.View

object ReaderIconVisibility {
    fun opened(icon: View?, width: Int, height: Int, screenWidth: Int, screenHeight: Int) {
        icon?.visibility = if (ReaderContent.hideFloatingIcon(width,height,screenWidth,screenHeight)) View.INVISIBLE else View.VISIBLE
    }
    fun closed(icon: View?) { icon?.visibility = View.VISIBLE }
}
