package com.manhwalens.app.capture

import kotlin.math.*

/** Phase-only circular correlation of a short row-brightness projection. Reject ambiguous/flat peaks. */
internal object VerticalPhase {
    const val N=48
    private val cosTable=DoubleArray(N*N){cos(2*PI*(it/N)*(it%N)/N)}
    private val sinTable=DoubleArray(N*N){sin(2*PI*(it/N)*(it%N)/N)}
    fun shift(a:DoubleArray,b:DoubleArray):Int? {
        val power=DoubleArray(N);var active=0
        for(k in 1 until N/2){var ar=0.0;var ai=0.0;var br=0.0;var bi=0.0
            for(i in 0 until N){val c=cosTable[k*N+i];val s=sinTable[k*N+i];ar+=a[i]*c;ai-=a[i]*s;br+=b[i]*c;bi-=b[i]*s}
            val re=ar*br+ai*bi;val im=ar*bi-ai*br;val mag=hypot(re,im);if(mag<1)continue;active++
            for(i in 0 until N)power[i]+=(re*cosTable[k*N+i]-im*sinTable[k*N+i])/mag
        }
        if(active<8)return null;val peak=power.indices.maxBy {power[it]};val next=power.indices.filter { it!=peak }.maxOf {power[it]};if(power[peak]<active*.55 || power[peak]-next<active*.25)return null
        return if(peak>N/2)peak-N else peak
    }
}
