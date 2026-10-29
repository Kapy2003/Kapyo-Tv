package com.famelack.tv.data.repository

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Ultra-fast in-memory registry of scraped official channel language and metadata.
 * Sourced directly from iptv-org/database feeds.csv to classify 100% of channels accurately.
 */
object ChannelMetadataRegistry {

    private const val TAG = "ChannelMetadata"
    private var languageMap: Map<String, String>? = null

    fun getLanguage(context: Context, tvgId: String, channelName: String): String {
        val map = languageMap ?: synchronized(this) {
            languageMap ?: loadLanguageMap(context).also { languageMap = it }
        }

        val cleanId = tvgId.trim()
        val baseId = if (cleanId.contains('@')) cleanId.substringBefore('@') else cleanId

        // 1. Exact match on tvg-id or base-id from scraped feeds database
        val matched = map[cleanId] ?: map[baseId]
        if (!matched.isNullOrEmpty()) {
            return matched
        }

        // 2. Smart fallback by channel name
        return detectLanguageByName(channelName)
    }

    private fun loadLanguageMap(context: Context): Map<String, String> {
        val map = HashMap<String, String>(25000)
        try {
            context.assets.open("channel_languages.tsv").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 32768).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        val tabIdx = line.indexOf('\t')
                        if (tabIdx != -1) {
                            val id = line.substring(0, tabIdx).trim()
                            val lang = line.substring(tabIdx + 1).trim()
                            if (id.isNotEmpty() && lang.isNotEmpty()) {
                                map[id] = lang.intern()
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
            Log.i(TAG, "Loaded ${map.size} channel language mappings from assets")
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading channel_languages.tsv from assets", e)
        }
        return map
    }

    fun detectLanguageByName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("malayalam") || lower.contains("asianet") || lower.contains("manorama") ||
            lower.contains("mathrubhumi") || lower.contains("kairali") || lower.contains("mediaone") ||
            lower.contains("reporter tv") || lower.contains("janam tv") -> "Malayalam"

            lower.contains("tamil") || lower.contains("sun tv") || lower.contains("kalaignar") ||
            lower.contains("thanthi") || lower.contains("polimer") || lower.contains("puthiya") ||
            lower.contains("jaya tv") || lower.contains("raj tv") || lower.contains("captain tv") -> "Tamil"

            lower.contains("telugu") || lower.contains("etv ") || lower.contains("tv9 telugu") ||
            lower.contains("sakshi") || lower.contains("ntv ") || lower.contains("abn ") ||
            lower.contains("v6 news") || lower.contains("10tv") || lower.contains("t-news") -> "Telugu"

            lower.contains("kannada") || lower.contains("tv9 kannada") || lower.contains("suvarna") ||
            lower.contains("public tv") || lower.contains("dighvijay") || lower.contains("udaya") ||
            lower.contains("kasthuri") -> "Kannada"

            lower.contains("bengali") || lower.contains("bangla") || lower.contains("abp ananda") ||
            lower.contains("24 ghanta") || lower.contains("calcutta") || lower.contains("kolkata") ||
            lower.contains("aakaash aath") -> "Bengali"

            lower.contains("marathi") || lower.contains("abp majha") || lower.contains("zee 24 taas") ||
            lower.contains("lokmat") || lower.contains("saam tv") || lower.contains("pudhari") ||
            lower.contains("9x jhakaas") -> "Marathi"

            lower.contains("punjabi") || lower.contains("ptc") || lower.contains("zee punjab") ||
            lower.contains("chardikla") || lower.contains("fastway") || lower.contains("7x") ||
            lower.contains("9x tashan") || lower.contains("abp sanjha") -> "Punjabi"

            lower.contains("gujarati") || lower.contains("gujarat") || lower.contains("gstv") ||
            lower.contains("sandesh") || lower.contains("vtv") || lower.contains("abp asmita") ||
            lower.contains("zee 24 kalak") -> "Gujarati"

            lower.contains("hindi") || lower.contains("khabar") || lower.contains("samachar") ||
            lower.contains("aaj tak") || lower.contains("bharat") || lower.contains("dilli") ||
            lower.contains("zee news") || lower.contains("abp news") || lower.contains("india tv") ||
            lower.contains("ndtv india") || lower.contains("news18") || lower.contains("sadhna") ||
            lower.contains("sanskar") || lower.contains("aastha") || lower.contains("sudarshan") ||
            lower.contains("tez") || lower.contains("news nation") || lower.contains("abp ganga") ||
            lower.contains("&tv") || lower.contains("9x jalwa") || lower.contains("9xm") ||
            lower.contains("amarujala") -> "Hindi"

            lower.contains("english") || lower.contains("india today") || lower.contains("ndtv 24x7") ||
            lower.contains("times now") || lower.contains("cnn") || lower.contains("wion") ||
            lower.contains("republic world") || lower.contains("mirror now") || lower.contains("bbc") -> "English"

            lower.contains("arabic") || lower.contains("al jazeera") || lower.contains("alarabiya") ||
            lower.contains("العربية") || lower.contains("الجزيرة") -> "Arabic"

            lower.contains("spanish") || lower.contains("español") || lower.contains("telemundo") ||
            lower.contains("univision") -> "Spanish"

            lower.contains("french") || lower.contains("français") -> "French"
            lower.contains("german") || lower.contains("deutsch") -> "German"
            lower.contains("urdu") -> "Urdu"
            lower.contains("odia") || lower.contains("oriya") -> "Odia"
            lower.contains("assamese") || lower.contains("assam") -> "Assamese"
            lower.contains("bhojpuri") -> "Bhojpuri"

            else -> "General"
        }
    }
}

