package com.famelack.tv

import com.famelack.tv.data.parser.M3UParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class M3UParserTest {

    @Test
    fun testParseValidM3U() {
        val sampleM3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="NDTV24x7.in" tvg-logo="https://example.com/ndtv.png" group-title="News",NDTV 24x7 (1080p)
            https://stream.example.com/ndtv/live.m3u8
            #EXTINF:-1 tvg-id="StarSports.in" tvg-logo="https://example.com/starsports.png" group-title="Sports",Star Sports 1 (720p)
            https://stream.example.com/starsports/live.m3u8
        """.trimIndent()

        val parser = M3UParser()
        val channels = parser.parse(StringReader(sampleM3u))

        assertEquals(2, channels.size)

        val ch1 = channels[0]
        assertEquals("NDTV 24x7", ch1.name)
        assertEquals("NDTV24x7.in", ch1.id)
        assertEquals("https://stream.example.com/ndtv/live.m3u8", ch1.url)
        assertEquals("https://example.com/ndtv.png", ch1.logo)
        assertEquals("News", ch1.category)
        assertEquals("1080p", ch1.resolution)
        assertEquals("English", ch1.language)

        val ch2 = channels[1]
        assertEquals("Star Sports 1", ch2.name)
        assertEquals("Sports", ch2.category)
        assertEquals("720p", ch2.resolution)
    }

    @Test
    fun testLanguageDetection() {
        val multiLangM3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="SunTV" group-title="Entertainment",Sun TV (1080p)
            https://stream.example.com/sun.m3u8
            #EXTINF:-1 tvg-id="ETVTelugu" group-title="News",ETV Andhra Pradesh (720p)
            https://stream.example.com/etv.m3u8
            #EXTINF:-1 tvg-id="AsianetNews" group-title="News",Asianet News (720p)
            https://stream.example.com/asianet.m3u8
            #EXTINF:-1 tvg-id="AajTak" group-title="News",Aaj Tak (1080p)
            https://stream.example.com/aajtak.m3u8
        """.trimIndent()

        val parser = M3UParser()
        val channels = parser.parse(StringReader(multiLangM3u))

        assertEquals(4, channels.size)
        assertEquals("Tamil", channels[0].language)
        assertEquals("Telugu", channels[1].language)
        assertEquals("Malayalam", channels[2].language)
        assertEquals("Hindi", channels[3].language)
    }

    @Test
    fun testDeduplicationPrefersHttpsAndQuality() {
        val duplicateM3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="DDNational" group-title="General",DD National (360p)
            http://stream.example.com/dd_low.m3u8
            #EXTINF:-1 tvg-id="DDNational" group-title="General",DD National (720p)
            https://stream.example.com/dd_hd.m3u8
        """.trimIndent()

        val parser = M3UParser()
        val channels = parser.parse(StringReader(duplicateM3u))

        assertEquals(1, channels.size)
        val winner = channels[0]
        assertEquals("DD National", winner.name)
        assertEquals("https://stream.example.com/dd_hd.m3u8", winner.url)
        assertEquals("720p", winner.resolution)
    }

    @Test
    fun testCategoryNormalization() {
        val sample = """
            #EXTM3U
            #EXTINF:-1 group-title="Animation;Kids",Cartoon Channel
            https://stream.example.com/cartoon.m3u8
            #EXTINF:-1 group-title="undefined",Mystery Channel
            https://stream.example.com/mystery.m3u8
        """.trimIndent()

        val parser = M3UParser()
        val channels = parser.parse(StringReader(sample))

        assertEquals(2, channels.size)
        assertEquals("Kids", channels[0].category)
        assertEquals("General", channels[1].category)
    }

    @Test
    fun testUnicodeChannelNames() {
        val unicodeSample = """
            #EXTM3U
            #EXTINF:-1,العربية الحدث (720p)
            https://stream.example.com/alarabiya.m3u8
            #EXTINF:-1,आज तक (1080p)
            https://stream.example.com/aajtak_hi.m3u8
        """.trimIndent()

        val parser = M3UParser()
        val channels = parser.parse(StringReader(unicodeSample))

        assertEquals(2, channels.size)
        assertEquals("العربية الحدث", channels[0].name)
        assertEquals("आज तक", channels[1].name)
    }
}
