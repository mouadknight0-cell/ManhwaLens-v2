package com.manhwalens.app.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class ProgressiveTranslationTest {
    private fun region(text: String, top: Int = 0) = TextRegion(text, 0, top, 100, top + 30)
    @Test fun deduplicatesAndPreservesCoordinates() = runTest {
        var calls = 0
        val result = progressiveTranslation(listOf(region("Hello"), region("Hello", 50)), translate = {
            calls++; TranslatedBlock(it, it.text, "مرحبا")
        }, onReady = {})
        assertEquals(1, calls)
        assertEquals(listOf(0, 50), result.map { it.region.top })
    }
    @Test fun boundedConcurrencyAndProgressiveResults() = runTest {
        var active = 0
        var maximum = 0
        val sizes = mutableListOf<Int>()
        val result = progressiveTranslation((0..5).map { region("Hello $it") }, translate = {
            active++; maximum = maxOf(maximum, active)
            delay(100); active--
            TranslatedBlock(it, it.text, "مرحبا")
        }, onReady = { sizes.add(it.size) })
        assertEquals(2, maximum)
        assertEquals(6, result.size)
        assertEquals((1..6).toList(), sizes)
    }
    @Test fun oneExceptionCannotCancelFiveSiblingsAndOnlyFailureRetries() = runTest {
        val inputs=(0..5).map { region("bubble $it",it*40) }; val calls=IntArray(6)
        val result=progressiveTranslation(inputs,translate={ r ->
            val i=r.top/40; calls[i]++; if(i==2) error("forced")
            TranslatedBlock(r,r.text,"مرحبا")
        },onReady={})
        assertEquals(6,result.size); assertEquals(5,result.count { it.errorCode==null })
        assertEquals("TR-BLOCK-EXCEPTION",result[2].errorCode); assertArrayEquals(intArrayOf(1,1,2,1,1,1),calls)
    }
    @Test fun cancellationStopsQueuedWork() = runTest {
        var calls = 0
        val job = launch {
            progressiveTranslation((0..9).map { region("text $it") }, translate = {
                calls++; delay(1000); TranslatedBlock(it, it.text, "ok")
            }, onReady = { fail("Cancelled work must not emit") })
        }
        delay(10); job.cancel(); job.join()
        assertEquals(2, calls)
    }
}
