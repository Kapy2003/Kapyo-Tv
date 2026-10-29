package com.famelack.tv.data.repository

import android.content.Context
import android.util.Log
import com.famelack.tv.KapyoApp
import com.famelack.tv.data.model.Category
import com.famelack.tv.data.model.Channel
import com.famelack.tv.data.model.Country
import com.famelack.tv.data.parser.M3UParser
import com.famelack.tv.data.storage.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.concurrent.TimeUnit

sealed class ChannelState {
    object Loading : ChannelState()
    data class Success(val channels: List<Channel>, val isFromCache: Boolean) : ChannelState()
    data class Error(val message: String, val channels: List<Channel>) : ChannelState()
}

class ChannelRepository(
    private val context: Context,
    private val prefs: PreferencesManager,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    private val _channelState = MutableStateFlow<ChannelState>(ChannelState.Loading)
    val channelState: StateFlow<ChannelState> = _channelState.asStateFlow()

    private val stringPool = HashMap<String, String>()
    private val m3uParser = M3UParser(stringPool) { tvgId, name ->
        ChannelMetadataRegistry.getLanguage(context, tvgId, name)
    }

    // Only ONE country's channels in memory at any time (strict 512 MB constraint)
    private var activeChannels: List<Channel> = emptyList()
    private var activeCountryCode: String = ""

    fun getActiveChannels(): List<Channel> = activeChannels

    private fun getCacheFile(countryCode: String): File {
        return File(context.cacheDir, "channels_${countryCode.lowercase()}.cache")
    }

    suspend fun loadChannels(
        countryCode: String = prefs.selectedCountryCode,
        forceRefresh: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val cleanCountry = countryCode.lowercase()

        if (activeCountryCode != cleanCountry) {
            activeChannels = emptyList()
            activeCountryCode = cleanCountry
            _channelState.value = ChannelState.Loading
        }

        // LRU 5-country disk eviction
        val evictedCountry = prefs.recordCountryAccess(cleanCountry)
        if (evictedCountry != null && evictedCountry != cleanCountry) {
            val oldCache = getCacheFile(evictedCountry)
            if (oldCache.exists()) {
                oldCache.delete()
                Log.i(TAG, "LRU Evicted country cache on disk: $evictedCountry")
            }
        }
        enforceDiskCacheLimits()

        val cacheFile = getCacheFile(cleanCountry)
        val hasCache = cacheFile.exists() && cacheFile.length() > 0

        if (hasCache && !forceRefresh) {
            val cachedChannels = readFromCacheFile(cacheFile)
            if (cachedChannels.isNotEmpty()) {
                activeChannels = cachedChannels
                _channelState.value = ChannelState.Success(cachedChannels, isFromCache = true)
                Log.i(TAG, "Loaded ${cachedChannels.size} channels from cache for $cleanCountry")
            }
        }

        val lastFetch = prefs.getLastFetchTime(cleanCountry)
        val now = System.currentTimeMillis()
        val twelveHoursMs = 12 * 60 * 60 * 1000L
        val shouldRefresh = forceRefresh || !hasCache || (now - lastFetch > twelveHoursMs)

        if (shouldRefresh) {
            refreshFromNetwork(cleanCountry, hasCache)
        }
    }

    private suspend fun refreshFromNetwork(countryCode: String, hadExistingCache: Boolean) = withContext(Dispatchers.IO) {
        val country = Country.findByCode(context, countryCode)
        val url = country.m3uUrl

        val requestBuilder = Request.Builder().url(url)
        val etag = prefs.getEtag(countryCode)
        val lastModified = prefs.getLastModified(countryCode)

        if (!etag.isNullOrEmpty()) {
            requestBuilder.header("If-None-Match", etag)
        }
        if (!lastModified.isNullOrEmpty()) {
            requestBuilder.header("If-Modified-Since", lastModified)
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                if (resp.code == 304) {
                    Log.i(TAG, "Server returned 304 Not Modified for $countryCode")
                    prefs.setLastFetchTime(countryCode, System.currentTimeMillis())
                    return@withContext
                }

                if (!resp.isSuccessful) {
                    val msg = "Network error: HTTP ${resp.code}"
                    Log.e(TAG, msg)
                    if (!hadExistingCache) {
                        _channelState.value = ChannelState.Error(msg, emptyList())
                    }
                    return@withContext
                }

                val body = resp.body ?: run {
                    if (!hadExistingCache) {
                        _channelState.value = ChannelState.Error("Empty response body", emptyList())
                    }
                    return@withContext
                }

                // Scope string pool strictly to the current country to prevent memory bloat
                stringPool.clear()
                val parsedChannels = m3uParser.parse(body.charStream())
                if (parsedChannels.isEmpty()) {
                    if (!hadExistingCache) {
                        _channelState.value = ChannelState.Error("No valid channels found for $countryCode", emptyList())
                    }
                    return@withContext
                }

                val cacheFile = getCacheFile(countryCode)
                val tempFile = File(context.cacheDir, "channels_${countryCode}.cache.tmp")
                writeToCacheFile(tempFile, parsedChannels)
                if (tempFile.exists()) {
                    if (cacheFile.exists()) cacheFile.delete()
                    tempFile.renameTo(cacheFile)
                }

                prefs.setEtag(countryCode, resp.header("ETag"))
                prefs.setLastModified(countryCode, resp.header("Last-Modified"))
                prefs.setLastFetchTime(countryCode, System.currentTimeMillis())

                if (activeCountryCode == countryCode) {
                    activeChannels = parsedChannels
                    _channelState.value = ChannelState.Success(parsedChannels, isFromCache = false)
                }
                Log.i(TAG, "Updated playlist for $countryCode (${parsedChannels.size} channels)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network exception fetching playlist: ${e.message}")
            if (!hadExistingCache) {
                _channelState.value = ChannelState.Error(e.message ?: "Network error", emptyList())
            }
        }
    }

    private fun readFromCacheFile(file: File): List<Channel> {
        val list = ArrayList<Channel>(800)
        try {
            BufferedReader(FileReader(file), 16384).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    if (line.isNotEmpty()) {
                        val ch = Channel.fromCacheLine(line) { str ->
                            stringPool.getOrPut(str) { str }
                        }
                        if (ch != null) {
                            // Backfill language and category if cache has null or obsolete values
                            val rawLang = if (ch.language.isNullOrEmpty() || ch.language == "General") {
                                ChannelMetadataRegistry.getLanguage(context, ch.id, ch.name)
                            } else {
                                ch.language
                            }
                            val validLang = stringPool.getOrPut(rawLang) { rawLang }

                            val rawCat = if (ch.category.equals("Undefined", ignoreCase = true)) {
                                "General"
                            } else {
                                ch.category
                            }
                            val validCat = stringPool.getOrPut(rawCat) { rawCat }

                            val finalCh = if (validLang != ch.language || validCat != ch.category) {
                                ch.copy(language = validLang, category = validCat)
                            } else {
                                ch
                            }
                            list.add(finalCh)
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cache file", e)
        }
        return list
    }

    private fun writeToCacheFile(file: File, channels: List<Channel>) {
        try {
            FileWriter(file).use { writer ->
                for (channel in channels) {
                    writer.write(channel.toCacheLine())
                    writer.write("\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing cache file", e)
        }
    }

    fun clearCache(countryCode: String = prefs.selectedCountryCode) {
        val file = getCacheFile(countryCode)
        if (file.exists()) {
            file.delete()
        }
        prefs.clearCacheMetadata(countryCode)
    }

    /**
     * Completely wipes ALL cached files: all country channels, image disk & memory cache, and string pool.
     */
    fun clearAllCaches() {
        try {
            val files = context.cacheDir.listFiles { _, name ->
                name.startsWith("channels_") && (name.endsWith(".cache") || name.endsWith(".cache.tmp"))
            } ?: emptyArray()

            for (file in files) {
                file.delete()
            }

            prefs.clearAllCacheMetadata()
            stringPool.clear()

            (context.applicationContext as? KapyoApp)?.clearAllImageCaches()
            Log.i(TAG, "Cleared all channel cache files and image caches successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all caches", e)
        }
    }

    /**
     * Enforces strict disk quota: deletes old/orphan country caches beyond the active LRU.
     */
    fun enforceDiskCacheLimits() {
        try {
            val lruSet = prefs.getLruCountries().map { "channels_${it.lowercase()}.cache" }.toSet()
            val files = context.cacheDir.listFiles { _, name ->
                name.startsWith("channels_") && name.endsWith(".cache")
            } ?: emptyArray()

            for (file in files) {
                if (file.name !in lruSet) {
                    file.delete()
                    Log.i(TAG, "Evicted orphan/old cache file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing disk cache limits", e)
        }
    }

    /**
     * Compute language list for the current country.
     * All languages sorted alphabetically A to Z. Total count equals channels.size.
     */
    fun computeLanguages(channels: List<Channel>): List<Category> {
        val langCounts = LinkedHashMap<String, Int>()
        for (ch in channels) {
            val lang = ch.language?.ifEmpty { "General" } ?: "General"
            langCounts[lang] = (langCounts[lang] ?: 0) + 1
        }

        val languages = ArrayList<Category>()
        languages.add(Category(Category.ID_ALL, "All Languages", channels.size, isLanguage = true))

        // Sort languages alphabetically
        val sortedLangs = langCounts.entries.sortedBy { it.key.lowercase() }
        for (entry in sortedLangs) {
            languages.add(Category(entry.key, entry.key, entry.value, isLanguage = true))
        }

        return languages
    }

    /**
     * Compute genres / categories specifically scoped to the selected language.
     */
    fun computeCategories(
        channels: List<Channel>,
        selectedLanguage: String = Category.ID_ALL,
        countryCode: String = prefs.selectedCountryCode
    ): List<Category> {
        val scopedChannels = if (selectedLanguage == Category.ID_ALL) {
            channels
        } else {
            channels.filter { it.language.equals(selectedLanguage, ignoreCase = true) }
        }

        val genreCounts = LinkedHashMap<String, Int>()
        for (ch in scopedChannels) {
            genreCounts[ch.category] = (genreCounts[ch.category] ?: 0) + 1
        }

        val categories = ArrayList<Category>()
        categories.add(Category(Category.ID_ALL, "All Channels", scopedChannels.size))

        val favs = prefs.getFavorites(countryCode)
        val favCount = scopedChannels.count { favs.contains(it.id) }
        categories.add(Category(Category.ID_FAVORITES, "Favorites", favCount))

        val recents = prefs.getRecentChannelIds(countryCode)
        val recentCount = scopedChannels.count { recents.contains(it.id) }
        if (recentCount > 0) {
            categories.add(Category(Category.ID_RECENT, "Recent", recentCount))
        }

        val sortedGenres = genreCounts.entries.sortedByDescending { it.value }
        for (entry in sortedGenres) {
            categories.add(Category(entry.key, entry.key, entry.value))
        }

        return categories
    }

    /**
     * Detect user's country via internet geo-location (IP lookup).
     * Fast, lightweight, zero extra dependencies.
     */
    suspend fun detectGeoCountry(context: Context): Country? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://ipapi.co/country/")
                .header("User-Agent", "KapyoTv/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val code = response.body?.string()?.trim()?.lowercase()
                    if (!code.isNullOrEmpty() && code.length == 2) {
                        val match = Country.findByCode(context, code)
                        if (match.code.lowercase() == code) {
                            return@withContext match
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback to secondary geo endpoint: Cloudflare trace
            try {
                val cfReq = Request.Builder()
                    .url("https://1.1.1.1/cdn-cgi/trace")
                    .header("User-Agent", "KapyoTv/1.0")
                    .build()
                client.newCall(cfReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val locLine = body.lineSequence().firstOrNull { it.startsWith("loc=") }
                        val code = locLine?.substringAfter("loc=")?.trim()?.lowercase()
                        if (!code.isNullOrEmpty() && code.length == 2) {
                            val match = Country.findByCode(context, code)
                            if (match.code.lowercase() == code) {
                                return@withContext match
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }
        null
    }

    companion object {
        private const val TAG = "ChannelRepository"
    }
}
