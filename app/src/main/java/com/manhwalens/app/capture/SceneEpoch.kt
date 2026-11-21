package com.manhwalens.app.capture

/** Short publication sections only: never run OCR, translation or IO under this lock. */
internal class SceneEpoch {
    @Volatile var current=0L
        private set
    @Synchronized fun advance(clear:()->Unit):Long { current++;clear();return current }
    @Synchronized fun commit(expected:Long, publish:()->Unit):Boolean {
        if(expected!=current)return false
        publish();return true
    }
}
