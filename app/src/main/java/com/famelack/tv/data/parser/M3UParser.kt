package com.famelack.tv.data.parser

import com.famelack.tv.data.model.Channel
import java.io.BufferedReader
import java.io.Reader
import java.util.regex.Pattern

/**
 * High-performance, streaming line-by-line M3U playlist parser.
 * - Zero intermediate object graphs.
 * - Resolves language using scraped official feeds registry with smart fallback.
 * - Rescues undefined/general categories into real genres (News, Music, Religious, Movies).
 * - Deduplicates channels by clean name and keeps the highest quality/HTTPS stream.
 */
class M3UParser(
    private val stringPool: MutableMap<String, String> = HashMap(),
    private val languageResolver: ((tvgId: String, name: String) -> String)? = null
) {

    private val attrPattern = Pattern.compile("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")
    private val resolutionPattern = Pattern.compile("\\s*\\((\\d{3,4}p?)\\)", Pattern.CASE_INSENSITIVE)

    fun parse(reader: Reader): List<Channel> {
        val bufferedReader = if (reader is BufferedReader) reader else BufferedReader(reader, 16384)
        val channelMap = LinkedHashMap<String, Channel>()

        var currentExtInf: String? = null

        var line: String? = bufferedReader.readLine()
        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                currentExtInf = trimmed
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && currentExtInf != null) {
                val streamUrl = trimmed
                if (isPlayableUrl(streamUrl)) {
                    val channel = parseEntry(currentExtInf, streamUrl)
                    if (channel != null) {
                        val key = normalizeChannelName(channel.name).lowercase()
                        val existing = channelMap[key]
                        if (existing == null) {
                            channelMap[key] = channel
                        } else {
                            if (shouldReplace(existing, channel)) {
                                channelMap[key] = channel
                            }
                        }
                    }
                }
                currentExtInf = null
            }
            line = bufferedReader.readLine()
        }

        return ArrayList(channelMap.values)
    }

    private fun parseEntry(header: String, url: String): Channel? {
        val commaIndex = header.lastIndexOf(',')
        if (commaIndex == -1) return null

        val rawName = header.substring(commaIndex + 1).trim()
        if (rawName.isEmpty()) return null

        var tvgId = ""
        var tvgLogo: String? = null
        var category = "General"
        var explicitLang: String? = null

        val metaPart = header.substring(0, commaIndex)
        val matcher = attrPattern.matcher(metaPart)
        while (matcher.find()) {
            val key = matcher.group(1)?.lowercase() ?: continue
            val value = matcher.group(2) ?: continue
            when (key) {
                "tvg-id" -> tvgId = value.trim()
                "tvg-logo" -> tvgLogo = value.trim().ifEmpty { null }
                "group-title" -> category = value.trim()
                "tvg-language" -> explicitLang = value.trim().ifEmpty { null }
            }
        }

        val (cleanName, resolution) = extractNameAndResolution(rawName)
        val finalId = if (tvgId.isNotEmpty()) tvgId else cleanName

        val resolvedLang = if (!explicitLang.isNullOrEmpty()) {
            normalizeLanguageCode(explicitLang.split(';').first().trim())
        } else if (languageResolver != null) {
            languageResolver.invoke(tvgId, cleanName)
        } else {
            detectLanguage(cleanName, tvgId, null) ?: "General"
        }

        val normalizedCategory = normalizeCategory(category, cleanName)

        return Channel(
            id = finalId,
            name = cleanName,
            url = url,
            logo = tvgLogo,
            category = getPooled(normalizedCategory),
            resolution = resolution,
            language = getPooled(resolvedLang)
        )
    }

    private fun extractNameAndResolution(rawName: String): Pair<String, String?> {
        val matcher = resolutionPattern.matcher(rawName)
        var resolution: String? = null
        var cleanName = rawName

        if (matcher.find()) {
            resolution = matcher.group(1)?.lowercase()
            cleanName = matcher.replaceFirst("").trim()
        }

        if (resolution != null && !resolution.endsWith("p")) {
            resolution += "p"
        }

        return Pair(cleanName.ifEmpty { rawName }, resolution)
    }

    private fun detectLanguage(name: String, tvgId: String, explicit: String?): String? {
        if (!explicit.isNullOrEmpty()) {
            val first = explicit.split(';').firstOrNull()?.trim() ?: explicit
            return normalizeLanguageCode(first)
        }

        val text = "$name $tvgId".lowercase()

        return when {
            text.contains("malayalam") || text.contains("asianet") || text.contains("manorama") ||
            text.contains("mathrubhumi") || text.contains("kairali") || text.contains("amrita") ||
            text.contains("mediaone") || text.contains("reporter tv") || text.contains("janam tv") -> "Malayalam"

            text.contains("tamil") || text.contains("sun tv") || text.contains("kalaignar") ||
            text.contains("thanthi") || text.contains("polimer") || text.contains("puthiya") ||
            text.contains("jaya tv") || text.contains("raj tv") || text.contains("captain tv") -> "Tamil"

            text.contains("telugu") || text.contains("etv ") || text.contains("tv9 telugu") ||
            text.contains("sakshi") || text.contains("ntv ") || text.contains("abn ") ||
            text.contains("v6 news") || text.contains("t-news") || text.contains("10tv") ||
            text.contains("6tv") || text.contains("bhakthi") || text.contains("mahaa") -> "Telugu"

            text.contains("kannada") || text.contains("tv9 kannada") || text.contains("suvarna") ||
            text.contains("public tv") || text.contains("dighvijay") || text.contains("udaya") ||
            text.contains("kasthuri") -> "Kannada"

            text.contains("bengali") || text.contains("bangla") || text.contains("abp ananda") ||
            text.contains("24 ghanta") || text.contains("calcutta") || text.contains("kolkata") ||
            text.contains("aakaash aath") -> "Bengali"

            text.contains("marathi") || text.contains("abp majha") || text.contains("zee 24 taas") ||
            text.contains("lokmat") || text.contains("saam tv") || text.contains("pudhari") ||
            text.contains("9x jhakaas") -> "Marathi"

            text.contains("punjabi") || text.contains("ptc") || text.contains("zee punjab") ||
            text.contains("chardikla") || text.contains("fastway") || text.contains("7x") ||
            text.contains("9x tashan") || text.contains("abp sanjha") -> "Punjabi"

            text.contains("gujarati") || text.contains("gujarat") || text.contains("gstv") ||
            text.contains("sandesh") || text.contains("vtv") || text.contains("abp asmita") ||
            text.contains("zee 24 kalak") -> "Gujarati"

            text.contains("hindi") || text.contains("khabar") || text.contains("samachar") ||
            text.contains("aaj tak") || text.contains("bharat") || text.contains("dilli") ||
            text.contains("zee news") || text.contains("abp news") || text.contains("india tv") ||
            text.contains("ndtv india") || text.contains("news18") || text.contains("sadhna") ||
            text.contains("sanskar") || text.contains("aastha") || text.contains("sudarshan") ||
            text.contains("tez") || text.contains("news nation") || text.contains("abp ganga") ||
            text.contains("&tv") || text.contains("9x jalwa") || text.contains("9xm") ||
            text.contains("amarujala") -> "Hindi"

            text.contains("english") || text.contains("india today") || text.contains("ndtv 24x7") ||
            text.contains("times now") || text.contains("cnn") || text.contains("wion") ||
            text.contains("republic world") || text.contains("mirror now") || text.contains("bbc") -> "English"

            text.contains("arabic") || text.contains("al jazeera") || text.contains("alarabiya") ||
            text.contains("العربية") || text.contains("الجزيرة") -> "Arabic"

            text.contains("spanish") || text.contains("español") || text.contains("telemundo") ||
            text.contains("univision") -> "Spanish"

            text.contains("french") || text.contains("français") -> "French"
            text.contains("german") || text.contains("deutsch") -> "German"
            text.contains("urdu") -> "Urdu"
            text.contains("odia") || text.contains("oriya") -> "Odia"
            text.contains("assamese") || text.contains("assam") -> "Assamese"
            text.contains("bhojpuri") -> "Bhojpuri"

            else -> null
        }
    }

    private fun normalizeLanguageCode(code: String): String {
        return when (code.lowercase()) {
            "hin", "hindi" -> "Hindi"
            "tam", "tamil" -> "Tamil"
            "tel", "telugu" -> "Telugu"
            "mal", "malayalam" -> "Malayalam"
            "kan", "kannada" -> "Kannada"
            "ben", "bengali", "bangla" -> "Bengali"
            "mar", "marathi" -> "Marathi"
            "pan", "punjabi" -> "Punjabi"
            "guj", "gujarati" -> "Gujarati"
            "eng", "english" -> "English"
            "ara", "arabic" -> "Arabic"
            "spa", "spanish" -> "Spanish"
            "fra", "fre", "french" -> "French"
            "deu", "ger", "german" -> "German"
            "urd", "urdu" -> "Urdu"
            "ori", "ory", "odia", "oriya" -> "Odia"
            "asm", "assamese" -> "Assamese"
            "bho", "bhojpuri" -> "Bhojpuri"
            else -> code.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun normalizeCategory(rawCategory: String, channelName: String): String {
        val firstCat = if (rawCategory.isEmpty() || rawCategory.equals("undefined", ignoreCase = true)) {
            "General"
        } else {
            rawCategory.split(';').firstOrNull()?.trim() ?: "General"
        }

        return when (firstCat.lowercase()) {
            "news", "top-news" -> "News"
            "entertainment", "series", "show", "comedy" -> "Entertainment"
            "movies", "classic" -> "Movies"
            "music" -> "Music"
            "religious", "culture" -> "Religious"
            "sports" -> "Sports"
            "kids", "animation" -> "Kids"
            "documentary", "science" -> "Documentary"
            "business" -> "Business"
            "lifestyle", "cooking", "travel", "weather", "auto" -> "Lifestyle"
            "education" -> "Education"
            "general" -> {
                val nl = channelName.lowercase()
                when {
                    nl.contains("news") || nl.contains("samachar") || nl.contains("khabar") ||
                    nl.contains("times") || nl.contains("media") || nl.contains("express") ||
                    nl.contains("bulletin") -> "News"
                    nl.contains("music") || nl.contains("gana") || nl.contains("sangeet") ||
                    nl.contains("beats") || nl.contains("sound") -> "Music"
                    nl.contains("movie") || nl.contains("cinema") || nl.contains("talkies") ||
                    nl.contains("film") -> "Movies"
                    nl.contains("prarthana") || nl.contains("calvary") || nl.contains("bhakti") ||
                    nl.contains("church") || nl.contains("satsang") || nl.contains("dharma") ||
                    nl.contains("jesus") || nl.contains("shanti") || nl.contains("darshan") -> "Religious"
                    nl.contains("sports") || nl.contains("cricket") -> "Sports"
                    nl.contains("kids") || nl.contains("cartoon") -> "Kids"
                    nl.contains("cook") || nl.contains("food") || nl.contains("rasoi") ||
                    nl.contains("astro") || nl.contains("travel") -> "Lifestyle"
                    else -> "General"
                }
            }
            else -> firstCat.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun getPooled(value: String): String {
        return stringPool.getOrPut(value) { value }
    }

    private fun normalizeChannelName(name: String): String {
        return name.replace(Regex("[^\\p{L}\\p{Nd}]"), "").trim()
    }

    private fun isPlayableUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (lower.contains("youtube.com/watch") || lower.contains("youtu.be/")) return false
        return true
    }

    private fun shouldReplace(current: Channel, candidate: Channel): Boolean {
        val currentIsHttps = current.url.startsWith("https://", ignoreCase = true)
        val candidateIsHttps = candidate.url.startsWith("https://", ignoreCase = true)
        if (!currentIsHttps && candidateIsHttps) return true
        if (currentIsHttps && !candidateIsHttps) return false

        val currentScore = scoreResolution(current.resolution)
        val candidateScore = scoreResolution(candidate.resolution)
        return candidateScore > currentScore
    }

    private fun scoreResolution(resolution: String?): Int {
        return when (resolution?.lowercase()) {
            "720p" -> 5
            "1080p" -> 4
            "576p" -> 3
            "480p" -> 2
            "360p", "240p" -> 1
            else -> 0
        }
    }
}
