package com.manhwalens.app.names

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameDetectorTest {

    @Test
    fun `korean family name plus given name is detected`() {
        val hits = NameDetector.find("Kim Jiwoo stepped forward.")
        assertEquals(1, hits.size)
        assertEquals("Kim Jiwoo", hits[0].name)
        assertEquals(NameHit.Source.KOREAN_SURNAME, hits[0].source)
    }

    @Test
    fun `hyphenated korean given name is detected`() {
        val hits = NameDetector.find("Park Seo-yeon said nothing.")
        assertEquals(1, hits.size)
        assertEquals("Park Seo-yeon", hits[0].name)
    }

    @Test
    fun `chinese family name plus given name is detected`() {
        val hits = NameDetector.find("Zhang Sanfeng smiled from the doorway.")
        assertEquals(1, hits.size)
        assertEquals("Zhang Sanfeng", hits[0].name)
        assertEquals(NameHit.Source.CHINESE_SURNAME, hits[0].source)
    }

    @Test
    fun `glossary names are found case-insensitively`() {
        val hits = NameDetector.find("lin xiao waited by the gate.", listOf("Lin Xiao"))
        assertEquals(1, hits.size)
        assertEquals(NameHit.Source.GLOSSARY, hits[0].source)
    }

    @Test
    fun `standalone capitalized word without given token is not a name hit`() {
        val hits = NameDetector.find("Park is big. Kim is small.")
        // No given token after the surname -> no surname-driven detection.
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `glossary match overlapping surname detection prefers longer glossary span`() {
        val hits = NameDetector.find("Kim Jiwoo laughed.", listOf("Kim Jiwoo"))
        assertEquals(1, hits.size)
        assertEquals(NameHit.Source.GLOSSARY, hits[0].source)
    }
    @Test fun `uppercase dialogue is not automatically shielded as names`() {
        assertTrue(NameDetector.find("NO WAY! HE WAS HERE. AN OLD MAN.").isEmpty())
        assertTrue(NameDetector.find("LONG AGO").isEmpty())
        assertEquals("KIM JIWOO", NameDetector.find("KIM JIWOO", listOf("Kim Jiwoo")).single().name)
        assertTrue(!NameDetector.safeAutomaticName("NO WAY"))
    }
}
