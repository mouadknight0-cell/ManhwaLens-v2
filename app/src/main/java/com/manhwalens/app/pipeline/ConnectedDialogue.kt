package com.manhwalens.app.pipeline

import kotlin.math.abs

/** Require an actual narrow neck and common sampled interior, not mere proximity. */
object ConnectedDialogue {
    fun linked(a:TextRegion,b:TextRegion):Boolean {
        val x=a.surface?:return false;val y=b.surface?:return false
        if(x.mask==null || y.mask==null || a.text.trimEnd().lastOrNull() in listOf('.', '!', '?', '…') || x.mask.kind!=y.mask.kind)return false
        val overlap=minOf(x.right,y.right)-maxOf(x.left,y.left)
        val similar=listOf(0,8,16).all { k->abs(((x.color ushr k) and 255)-((y.color ushr k) and 255))<=12 }
        return similar && y.top-x.bottom in -2..8 && overlap>=minOf(x.width,y.width)*.65
    }
    private val boundary=Regex("[،؛,:;]")
    private val spaces=Regex("\\s+")
    fun split(ar:String,sources:List<String>):List<String>? {
        if(sources.size<2)return null
        var remaining=ar;val out=mutableListOf<String>()
        for(i in 0 until sources.lastIndex){ val (first,rest)=split(remaining,sources[i],sources.drop(i+1).joinToString(" "))?:return null
            out.add(first);remaining=rest };out.add(remaining);return out
    }
    fun split(ar:String,a:String,b:String):Pair<String,String>? {
        fun words(s:String)=s.trim().split(spaces).size
        val want=words(a).toDouble()/(words(a)+words(b));val n=words(ar)
        return boundary.findAll(ar).map { ar.take(it.range.last+1).trim() to ar.drop(it.range.last+1).trim() }.filter { it.first.any(Char::isLetter)&&it.second.any(Char::isLetter) }.map { it to abs(words(it.first).toDouble()/n-want) }.filter { it.second<=.18 }.minByOrNull { it.second }?.first
    }
}
