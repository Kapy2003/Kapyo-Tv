package com.famelack.tv

import com.famelack.tv.data.model.Channel
import com.famelack.tv.data.parser.M3UParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.StringReader

class PerformanceBenchmarkTest {

    @Test
    fun benchmarkStreamingParseSpeedAndMemory() {
        // Generate a realistic 1,000 channel M3U playlist
        val sb = StringBuilder(250 * 1024)
        sb.append("#EXTM3U\n")
        val categories = listOf("News", "Entertainment", "Movies", "Music", "Religious", "Sports", "Kids", "General")
        for (i in 1..1000) {
            val cat = categories[i % categories.size]
            val res = if (i % 2 == 0) "1080p" else "720p"
            sb.append("#EXTINF:-1 tvg-id=\"channel$i.in\" tvg-logo=\"https://cdn.example.com/logo$i.png\" group-title=\"$cat\",Channel $i ($res)\n")
            sb.append("https://stream.example.com/live/ch$i/master.m3u8\n")
        }

        val rawData = sb.toString()

        System.gc()
        Thread.sleep(100)
        val memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        val startTime = System.nanoTime()
        val parser = M3UParser()
        val channels = parser.parse(StringReader(rawData))
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

        val memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memDeltaMb = (memAfter - memBefore) / (1024.0 * 1024.0)

        println("=== PARSER BENCHMARK ===")
        println("Parsed ${channels.size} channels in ${String.format("%.2f", elapsedMs)} ms")
        println("Memory Delta during parse: ${String.format("%.2f", memDeltaMb)} MB")

        assertTrue("Parsing 1000 channels must complete under 500ms on low-end hardware", elapsedMs < 500)
        assertTrue("Channel memory must stay well under 10MB", memDeltaMb < 10)
    }

    @Test
    fun benchmarkCacheReadWriteSpeed() {
        val tempFile = File.createTempFile("channels_bench", ".cache")
        tempFile.deleteOnExit()

        val sampleChannels = (1..850).map { i ->
            Channel(
                id = "channel$i.in",
                name = "Channel $i",
                url = "https://stream.example.com/live/ch$i/master.m3u8",
                logo = "https://cdn.example.com/logo$i.png",
                category = "News",
                resolution = "720p"
            )
        }

        // Measure write speed
        val writeStart = System.nanoTime()
        FileWriter(tempFile).use { writer ->
            for (ch in sampleChannels) {
                writer.write(ch.toCacheLine())
                writer.write("\n")
            }
        }
        val writeMs = (System.nanoTime() - writeStart) / 1_000_000.0

        // Measure read speed (cold start simulation)
        val readStart = System.nanoTime()
        val readChannels = ArrayList<Channel>(850)
        BufferedReader(FileReader(tempFile), 16384).use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotEmpty()) {
                    val ch = Channel.fromCacheLine(line)
                    if (ch != null) readChannels.add(ch)
                }
                line = reader.readLine()
            }
        }
        val readMs = (System.nanoTime() - readStart) / 1_000_000.0
        val fileSizeKb = tempFile.length() / 1024.0

        println("=== DISK CACHE BENCHMARK ===")
        println("Cache file size for 850 channels: ${String.format("%.1f", fileSizeKb)} KB")
        println("Disk write time: ${String.format("%.2f", writeMs)} ms")
        println("Cold-start disk read time: ${String.format("%.2f", readMs)} ms")

        assertTrue("Cold start disk read must be under 100ms", readMs < 100)
        assertTrue("Cache size must be compact (< 150KB)", fileSizeKb < 150)
    }
}

