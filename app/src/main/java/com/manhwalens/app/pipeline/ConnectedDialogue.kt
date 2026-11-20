package com.manhwalens.app.pipeline

import kotlin.math.abs

/** Require an actual narrow neck and common sampled interior, not mere proximity. */
object ConnectedDialogue {
    fun linked(a:TextRegion,b:TextRegion):Boolean {
        val x=a.surface?:return false;val y=b.surface?:return false
        if(x.mask==null || y.mask==null || a.text.trimEnd().lastOrNull() in listOf('.', '!', '?', '…') || !b.text.trimStart().firstOrNull().let { it==',' || it?.isLowerCase()==true })return false
        val overlap=minOf(x.right,y.right)-maxOf(x.left,y.left)
        return x.color==y.color && y.top-x.bottom in -2..8 && overlap>=minOf(x.width,y.width)*.65
    }
    private val boundary=Regex("[،؛,:;]")
    private val spaces=Regex("\\s+")
    fun split(ar:String,a:String,b:String):Pair<String,String>? {
        fun words(s:String)=s.trim().split(spaces).size
        val want=words(a).toDouble()/(words(a)+words(b));val n=words(ar)
        return boundary.findAll(ar).map { ar.take(it.range.last+1).trim() to ar.drop(it.range.last+1).trim() }.filter { it.first.any(Char::isLetter)&&it.second.any(Char::isLetter) }.map { it to abs(words(it.first).toDouble()/n-want) }.filter { it.second<=.18 }.minByOrNull { it.second }?.first
    }
}
