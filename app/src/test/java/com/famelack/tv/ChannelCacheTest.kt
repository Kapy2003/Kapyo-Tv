package com.famelack.tv

import com.famelack.tv.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelCacheTest {

    @Test
    fun testCacheLineSerializationRoundTrip() {
        val original = Channel(
            id = "AajTak.in",
            name = "Aaj Tak",
            url = "https://stream.example.com/aajtak.m3u8",
            logo = "https://example.com/logo.png",
            category = "News",
            resolution = "1080p",
            language = "Hindi"
        )

        val cacheLine = original.toCacheLine()
        val restored = Channel.fromCacheLine(cacheLine)

        assertNotNull(restored)
        assertEquals(original.id, restored!!.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.url, restored.url)
        assertEquals(original.logo, restored.logo)
        assertEquals(original.category, restored.category)
        assertEquals(original.resolution, restored.resolution)
        assertEquals(original.language, restored.language)
    }

    @Test
    fun testCacheLineWithNullLogoAndResolution() {
        val original = Channel(
            id = "DDNews",
            name = "DD News",
            url = "https://stream.example.com/ddnews.m3u8",
            logo = null,
            category = "News",
            resolution = null,
            language = null
        )

        val cacheLine = original.toCacheLine()
        val restored = Channel.fromCacheLine(cacheLine)

        assertNotNull(restored)
        assertEquals(original.id, restored!!.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.url, restored.url)
        assertNull(restored.logo)
        assertEquals(original.category, restored.category)
        assertNull(restored.resolution)
        assertNull(restored.language)
    }

    @Test
    fun testLanguageAlphabeticalSortingWithoutEmojis() {
        val channels = listOf(
            Channel("1", "Ch 1", "http://u1", null, "News", null, "Telugu"),
            Channel("2", "Ch 2", "http://u2", null, "News", null, "Bengali"),
            Channel("3", "Ch 3", "http://u3", null, "News", null, "Hindi"),
            Channel("4", "Ch 4", "http://u4", null, "News", null, "Arabic")
        )

        val langCounts = LinkedHashMap<String, Int>()
        for (ch in channels) {
            val lang = ch.language
            if (!lang.isNullOrEmpty()) {
                langCounts[lang] = (langCounts[lang] ?: 0) + 1
            }
        }

        val sortedLangs = langCounts.entries.sortedBy { it.key.lowercase() }.map { it.key }
        assertEquals(listOf("Arabic", "Bengali", "Hindi", "Telugu"), sortedLangs)
    }
}
