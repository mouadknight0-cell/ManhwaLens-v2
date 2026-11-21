package com.manhwalens.app.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.ColorUtils
import com.manhwalens.app.pipeline.*
import kotlin.math.*

/** Recovery is exceptional: the ordinary full-frame path still runs once. All boxes here are OCR-bitmap coordinates. */
internal class ColorRecovery(private val cropFirst:Boolean=com.manhwalens.app.BuildConfig.CROP_FIRST,private val fastFrame:Boolean=com.manhwalens.app.BuildConfig.FAST_FRAME) {
    private data class Vote(val hash:Long,val visual:Long,val regions:List<TextRegion>,val score:Double)
    private class Track(val box:Rect,val votes:ArrayDeque<Vote> = ArrayDeque())
    private val tracks=ArrayDeque<Track>()
    private data class Page(val w:Int,val h:Int,val scene:Long,val boxes:List<TextRegion>,val signatures:List<Long>)
    private var lastPage:Page?=null
    /** Check scene context as well as every crop. A new caption elsewhere must trigger discovery. */
    suspend fun recognize(client:TextRecognitionClient,b:Bitmap,lang:String,keep:(TextRegion)->Boolean,log:(String)->Unit):List<TextRegion> {
        if(cropFirst) return recover(client,b,lang,emptyList(),450,keep,log)
        var scene=17L;for(y in 0..63)for(x in 0..47)scene=31*scene+(b.getPixel((b.width-1)*x/47,(b.height-1)*y/63) and 0xf0f0f0)
        lastPage?.takeIf { it.w==b.width && it.h==b.height && it.scene==scene && it.boxes.all(keep) && it.boxes.indices.all { i->hash(b,box(it.boxes[i]))==it.signatures[i] } }?.let {log("pass=unchanged_page regions=${it.boxes.size}");return it.boxes}
        val first=kotlinx.coroutines.withTimeoutOrNull(12000){client.recognize(b,lang,log,keep)}?:error("OCR timed out")
        val got=recover(client,b,lang,first,6500,keep,log)
        lastPage=if(got.isNotEmpty() && got.size<=32 && got.all { it.left>=0 && it.top>=0 && it.right<=b.width && it.bottom<=b.height && it.width>0 && it.height>0 && acceptedFirst(it) }) Page(b.width,b.height,scene,got,got.map { hash(b,box(it)) }) else null
        return got
    }
    private fun acceptedFirst(r:TextRegion):Boolean {
        if(score(r)>=.77 && !(r.confidence<.52f))return true
        if(!fastFrame)return false
        val words=r.text.trim().split(Regex("\\s+"));if(words.size<2 || r.confidence.isFinite() && r.confidence<.5f)return false
        return com.manhwalens.app.phrasebook.PhraseLibrary.index?.lookup(r.text)?.stage=="exact" ||
            (r.confidence>=.5f && ReadingEnglish.score(r.text,emptyList())==1.0)
    }
    private fun overlap(a:Rect,b:Rect):Double {val i=max(0,min(a.right,b.right)-max(a.left,b.left))*max(0,min(a.bottom,b.bottom)-max(a.top,b.top));return i.toDouble()/max(1,a.width()*a.height()+b.width()*b.height()-i)}
    private fun box(r:TextRegion)=Rect(r.left,r.top,r.right,r.bottom)
    private fun gray(c:Int)=(Color.red(c)*54+Color.green(c)*183+Color.blue(c)*19) shr 8
    private val digitNoise=Regex("[A-Za-z]\\d|\\d[A-Za-z]")
    private fun score(r:TextRegion):Double {
        val repaired=TranslationInput.prepare(r.text,emptyList());val lexical=ReadingEnglish.score(repaired,emptyList())
        val digits=digitNoise.containsMatchIn(repaired)
        val noise=repaired.count { !it.isLetterOrDigit() && !it.isWhitespace() && it !in "'’.,!?…-" }.toDouble()/max(1,repaired.length)
        return .46*(r.confidence.takeIf(Float::isFinite)?.toDouble()?:.35)+.54*lexical-noise-(if(digits).22 else 0.0)
    }
    /** Flood-fill quantized colour components, rank contrast-bearing interiors rather than entire black pages. */
    private fun propose(b:Bitmap):List<Rect>{
        val step=max(5,max(b.width,b.height)/144);val w=b.width/step;val h=b.height/step;if(w<3||h<3)return emptyList()
        val samples=ContrastSamples(b,step);val pix=samples.corners;val tiles=samples.tiles
        val seen=BooleanArray(pix.size);val q=IntArray(pix.size);val hits=mutableListOf<Pair<Rect,Int>>()
        for(i in pix.indices){if(seen[i])continue;val c=pix[i];if(gray(c) in 153..219 && maxOf(Color.red(c),Color.green(c),Color.blue(c))-minOf(Color.red(c),Color.green(c),Color.blue(c))<55)continue
            var n=1;var head=0;q[0]=i;seen[i]=true;var l=w;var r=0;var t=h;var bot=0;var edges=0
            fun visit(j:Int){val v=pix[j];if(maxOf(abs(Color.red(c)-Color.red(v)),abs(Color.green(c)-Color.green(v)),abs(Color.blue(c)-Color.blue(v)))>34){edges++;return};if(!seen[j]){seen[j]=true;q[n++]=j}}
            while(head<n){val j=q[head++];val x=j%w;val y=j/w;l=min(l,x);r=max(r,x);t=min(t,y);bot=max(bot,y);if(x>0)visit(j-1);if(x<w-1)visit(j+1);if(y>0)visit(j-w);if(y<h-1)visit(j+w)}
            val area=(r-l+1)*(bot-t+1);if(n>=18 && area<=w*h*.60 && r-l>=4 && bot-t>=2 && n>=area*.28 && edges>12)hits.add(Rect(l*step,t*step,min(b.width,(r+1)*step),min(b.height,(bot+1)*step)) to edges*3)
         }
        // A dark full-bleed panel or coloured glyphs on a white page need not have a closed background.
        // Group neighbouring contrast tiles with both light and saturated/dark samples.
        for(i in tiles.indices){if(!tiles[i])continue;tiles[i]=false;q[0]=i;var n=1;var head=0;var l=w;var r=0;var t=h;var bot=0
            while(head<n){val j=q[head++];val x=j%w;val y=j/w;l=min(l,x);r=max(r,x);t=min(t,y);bot=max(bot,y);for(dy in -1..1)for(dx in -2..2){val xx=x+dx;val yy=y+dy;if(xx !in 0 until w || yy !in 0 until h)continue;val k=yy*w+xx;if(tiles[k]){tiles[k]=false;q[n++]=k}}}
            if(n>=8 && r-l>=4 && r-l>bot-t && (r-l+1)*(bot-t+1)<w*h/5)hits.add(Rect(l*step,t*step,(r+1)*step,(bot+1)*step) to n)
        };return hits.sortedByDescending { it.second }.fold(mutableListOf<Rect>()){ selected,(r,_) ->
            if(selected.size<3 && selected.none { it.contains(r) || overlap(it,r)>.45 })selected.add(r);selected }
    }
    private fun hash(b:Bitmap,r:Rect):Long {var v=17L;for(y in 0..15)for(x in 0..23){val p=b.getPixel(r.left+(r.width()-1)*x/23,r.top+(r.height()-1)*y/15);v=v*31+(p and 0xf0f0f0)};return v}
    private fun perceptual(b:Bitmap,r:Rect):Long {var sig=0L;for(y in 0..7)for(x in 0..7){val yy=r.top+(r.height()-1)*(2*y+1)/16
        if(gray(b.getPixel(r.left+(r.width()-1)*x/8,yy))>gray(b.getPixel(r.left+(r.width()-1)*(x+1)/8,yy))+4)sig=sig or (1L shl (y*8+x))};return sig}
    suspend fun recover(client:TextRecognitionClient,b:Bitmap,lang:String,initial:List<TextRegion>,budgetMs:Long=60_000,keep:(TextRegion)->Boolean,log:(String)->Unit):List<TextRegion>{
        val deadline=android.os.SystemClock.elapsedRealtime()+budgetMs
        val weak=initial.filterNot { acceptedFirst(it) }.also {log("first_gate regions=${initial.size} retry=${it.size} policy=${if(fastFrame) "reviewed" else "legacy"}")};if(initial.isNotEmpty() && weak.isEmpty())return initial
        val candidates=(weak.sortedBy { score(it) }.map(::box)+if(initial.size==weak.size)propose(b) else emptyList()).fold(mutableListOf<Rect>()){a,x->if(a.none { overlap(it,x)>.5 })a.add(x);a}.take(4)
        val output=initial.toMutableList()
        for(raw in candidates){if(android.os.SystemClock.elapsedRealtime()>=deadline)break;val outer=Rect(max(0,raw.left-10),max(0,raw.top-10),min(b.width,raw.right+10),min(b.height,raw.bottom+10));val r=if(budgetMs<=450)InkBounds.tighten(b,outer) else outer;if(r.width()<4||r.height()<4||r.width()*r.height()>350000)continue
            val bounded=budgetMs<=450;val regionDeadline=if(bounded)min(deadline,android.os.SystemClock.elapsedRealtime()+200) else deadline
            val fingerprint=hash(b,r);val visual=perceptual(b,r);val tr=tracks.firstOrNull { overlap(it.box,r)>.5 }?:Track(Rect(r)).also { tracks.addLast(it);if(tracks.size>32)tracks.removeFirst() }
            val cached=tr.votes.lastOrNull()?.takeIf { it.hash==fingerprint && tr.box==r && it.score>=.60 };var best=initial.filter { overlap(raw,box(it))>.5 };var value=best.map(::score).average().takeIf(Double::isFinite)?:0.0
            if(cached!=null){best=cached.regions.map {it.copy(ocrPass="cache:${it.ocrPass}")};value=cached.score;log("pass=cache score=$value")} else {
                val colors=IntArray(r.width()*r.height());b.getPixels(colors,0,r.width(),r.left,r.top,r.width(),r.height());val ys by lazy {IntArray(colors.size){gray(colors[it])}};val hist by lazy {IntArray(256).also {h->ys.forEach {h[it]++}}};val mean by lazy {ys.average()};var winner="frame"
                val variants=listOf("original","invert","clahe","otsu","lab");for(pass in variants){val started=android.os.SystemClock.elapsedRealtime();val remaining=regionDeadline-started;if(remaining<=0){log("recovery_budget box=$r retained_score=$value");break};if(pass=="invert" && mean>155)continue
                    val pixels=if(bounded && pass=="original")colors else if(pass=="clahe")LocalContrast.apply(ys,r.width()) else variant(pass,colors,ys,hist);val crop=Bitmap.createBitmap(pixels,r.width(),r.height(),Bitmap.Config.ARGB_8888);val scale=when {bounded && pass=="original"->1;r.height()<32->3;r.height()<=100->2;else->1};val input=if(scale==1)crop else Bitmap.createScaledBitmap(crop,r.width()*scale,r.height()*scale,true)
                    val got=try { kotlinx.coroutines.withTimeoutOrNull((regionDeadline-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1)){client.recognize(input,lang,pass=pass)}.orEmpty().map { it.copy(left=r.left+it.left/scale,right=r.left+it.right/scale,top=r.top+it.top/scale,bottom=r.top+it.bottom/scale,ocrPass=pass) }.filter(keep) }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){log("pass=$pass failure=${e.javaClass.simpleName}");emptyList()}finally{if(input!==crop)input.recycle();crop.recycle()}
                    val s=got.map(::score).average().takeIf(Double::isFinite)?:0.0;log("pass=$pass ms=${android.os.SystemClock.elapsedRealtime()-started} score=$s regions=${got.size} box=$r");if(s>value){value=s;winner=pass;best=got}
                    if(bounded && got.isNotEmpty() && got.all {it.confidence>=.5f && ReadingEnglish.score(it.text,emptyList())>=.5})break
                    // A confident fragment is NOT a successful crop. Early-out only on a
                    // complete reviewed utterance corroborated by the first frame reading.
                    if(s>=.82 && got.size==1 && initial.any { overlap(box(it),box(got.single()))>.65 && it.text.equals(got.single().text,true) } &&
                        com.manhwalens.app.phrasebook.PhraseLibrary.index?.lookup(got.single().text)?.stage=="exact")break
                };log("winner=$winner score=$value box=$r");tr.box.set(r);tr.votes.addLast(Vote(fingerprint,visual,best,value));if(tr.votes.size>4)tr.votes.removeFirst()
                // Only vote for aligned current blocks on perceptually similar crops. Never attach yesterday's coordinates.
                val aligned=tr.votes.filter { v->(v.hash==fingerprint || (visual!=0L && java.lang.Long.bitCount(v.visual xor visual)<=3)) && best.isNotEmpty() && best.size==v.regions.size && best.zip(v.regions).all { (a,b)->overlap(box(a),box(b))>.5 } }
                val voted=aligned.groupBy { it.regions.map { r->r.text.lowercase() } }.values.maxByOrNull { v->v.size+v.maxOf { it.score } }?.maxByOrNull { it.score }
                if(voted!=null && voted.score>=.60){best=best.zip(voted.regions).map { (now,old)->now.copy(text=old.text,confidence=old.confidence) };value=voted.score}
            };if(best.isNotEmpty() && value>=.60){for(hit in best){val same=output.filter { overlap(box(it),box(hit))>.45 };if(same.none { score(it)>score(hit) }){output.removeAll(same);output.add(hit)}}}
        };return output.sortedWith(compareBy({it.top},{it.left}))
    }
    private fun variant(pass:String,c:IntArray,y:IntArray,h:IntArray):IntArray {
        if(pass=="original")return c
        fun percentile(n:Int):Int{var sum=0;for(i in h.indices){sum+=h[i];if(sum>n)return i};return 255}
        val lo=percentile(c.size/100);val hi=percentile(c.size*99/100);var cut=128
        if(pass=="otsu"){var best=-1.0;var sum=0.0;var count=0;val total=y.sum().toDouble();for(i in h.indices){sum+=i*h[i];count+=h[i];if(count==0||count==c.size)continue;val d=sum/count-(total-sum)/(c.size-count);val v=count.toDouble()*(c.size-count)*d*d;if(v>best){best=v;cut=i}}}
        if(pass=="lab")return isolate(c)
        val lowMajority=h.take(cut+1).sum()>c.size/2
        return IntArray(c.size){val v=when(pass){"invert"->255-y[it];"stretch"->((y[it]-lo)*255/max(1,hi-lo)).coerceIn(0,255);else->if((y[it]<=cut)==lowMajority)255 else 0};Color.rgb(v,v,v)}
    }
    private fun isolate(c:IntArray):IntArray {
        val labs=HashMap<Int,DoubleArray>();fun lab(p:Int)=labs.getOrPut(p and 0xf0f0f0){DoubleArray(3).also { ColorUtils.colorToLAB(p,it) }}
        fun dist(a:DoubleArray,b:DoubleArray)=(a[0]-b[0]).pow(2)+(a[1]-b[1]).pow(2)+(a[2]-b[2]).pow(2)
        val sample=c.indices.step(max(1,c.size/768)).map { lab(c[it]) };var a=sample.first();var b=sample.maxBy { dist(it,a) };var majorityA=true
        repeat(4){val u=DoubleArray(3);val v=DoubleArray(3);var n=0;for(p in sample){val near=dist(p,a)<=dist(p,b);if(near)n++;val sums=if(near)u else v;for(k in 0..2)sums[k]+=p[k]};if(n>0)a=DoubleArray(3){u[it]/n};if(n<sample.size)b=DoubleArray(3){v[it]/(sample.size-n)};majorityA=n>=sample.size-n}
        return IntArray(c.size){val p=lab(c[it]);if((dist(p,a)<=dist(p,b))==majorityA)Color.WHITE else Color.BLACK}
    }
}
