package com.manhwalens.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationCacheTest {

    @Test
    fun `put and get roundtrip`() {
        val cache = TranslationCache(maxSize = 10)
        cache.put("안녕", "مرحبا")
        assertEquals("مرحبا", cache.get("안녕"))
    }

    @Test
    fun `normalization makes keys equivalent`() {
        val cache = TranslationCache(maxSize = 10)
        cache.put("  Hello   World ", "مرحبا بالعالم")
        assertEquals("مرحبا بالعالم", cache.get("hello world"))
        assertEquals("مرحبا بالعالم", cache.get("HELLO  WORLD"))
    }

    @Test
    fun `least recently used entry is evicted at capacity`() {
        val cache = TranslationCache(maxSize = 2)
        cache.put("one", "1")
        cache.put("two", "2")
        cache.get("one")          // refresh "one"; "two" becomes LRU
        cache.put("three", "3")
        assertEquals("1", cache.get("one"))
        assertNull(cache.get("two"))
        assertEquals("3", cache.get("three"))
    }

    @Test
    fun `clear empties the cache`() {
        val cache = TranslationCache(maxSize = 2)
        cache.put("a", "b")
        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test
    fun `blank keys are ignored`() {
        val cache = TranslationCache(maxSize = 2)
        cache.put("   ", "value")
        assertEquals(0, cache.size())
    }
}
