package com.manhwalens.app.overlay

/** Coordinates relative to the visible (inset) display frame. */
object DockPosition {
    fun place(x:Int,y:Int,bw:Int,bh:Int,w:Int,h:Int,sw:Int,sh:Int):Pair<Int,Int>{
        val right=x+bw;val left=x-w;val side=if(x+bw/2>=sw/2)listOf(left,right) else listOf(right,left)
        val beside=side.firstOrNull { it>=0 && it+w<=sw }
        return (beside?:x+(bw-w)/2).coerceIn(0,(sw-w).coerceAtLeast(0)) to
            (if(beside!=null)y+(bh-h)/2 else if(y+bh+h<=sh)y+bh else y-h).coerceIn(0,(sh-h).coerceAtLeast(0))
    }
}
