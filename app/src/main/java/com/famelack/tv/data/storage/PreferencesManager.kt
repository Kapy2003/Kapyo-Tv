package com.famelack.tv.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.famelack.tv.data.model.Country

/**
 * Lightweight local preferences manager using SharedPreferences.
 * - Persists selected country (defaulted to device locale or SIM region).
 * - Persists per-country favorites.
 * - Tracks LRU accessed countries for aggressive 5-country disk cache eviction.
 */
class PreferencesManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isCountryUserSelected: Boolean
        get() = prefs.getBoolean(KEY_IS_COUNTRY_USER_SELECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_COUNTRY_USER_SELECTED, value).apply()

    var selectedCountryCode: String
        get() {
            val saved = prefs.getString(KEY_COUNTRY_CODE, null)
            if (saved != null) return saved
            val detected = Country.detectDefaultCountry(context).code
            prefs.edit().putString(KEY_COUNTRY_CODE, detected).apply()
            return detected
        }
        set(value) = prefs.edit().putString(KEY_COUNTRY_CODE, value.lowercase()).apply()

    var selectedCountryName: String
        get() {
            val saved = prefs.getString(KEY_COUNTRY_NAME, null)
            if (saved != null) return saved
            return Country.findByCode(context, selectedCountryCode).name
        }
        set(value) = prefs.edit().putString(KEY_COUNTRY_NAME, value).apply()

    var maxResolution: String
        get() = prefs.getString(KEY_MAX_RES, "720p") ?: "720p"
        set(value) = prefs.edit().putString(KEY_MAX_RES, value).apply()

    // ==================== Per-Country Favorites ====================

    fun getFavorites(countryCode: String = selectedCountryCode): Set<String> {
        val key = "${KEY_FAVORITES_PREFIX}_${countryCode.lowercase()}"
        return prefs.getStringSet(key, emptySet()) ?: emptySet()
    }

    fun isFavorite(countryCode: String = selectedCountryCode, channelId: String): Boolean {
        return getFavorites(countryCode).contains(channelId)
    }

    fun toggleFavorite(countryCode: String = selectedCountryCode, channelId: String): Boolean {
        val key = "${KEY_FAVORITES_PREFIX}_${countryCode.lowercase()}"
        val current = HashSet(getFavorites(countryCode))
        val newState: Boolean
        if (current.contains(channelId)) {
            current.remove(channelId)
            newState = false
        } else {
            current.add(channelId)
            newState = true
        }
        prefs.edit().putStringSet(key, current).apply()
        return newState
    }

    // ==================== Recently Watched ====================

    fun getRecentChannelIds(countryCode: String = selectedCountryCode): List<String> {
        val key = "${KEY_RECENTS_PREFIX}_${countryCode.lowercase()}"
        val raw = prefs.getString(key, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(',').filter { it.isNotEmpty() }
    }

    fun addRecentChannelId(countryCode: String = selectedCountryCode, channelId: String) {
        val key = "${KEY_RECENTS_PREFIX}_${countryCode.lowercase()}"
        val list = getRecentChannelIds(countryCode).toMutableList()
        list.remove(channelId)
        list.add(0, channelId)
        val trimmed = if (list.size > 20) list.subList(0, 20) else list
        prefs.edit().putString(key, trimmed.joinToString(",")).apply()
    }

    // ==================== HTTP Conditional Headers ====================

    fun getEtag(countryCode: String): String? {
        return prefs.getString("${KEY_ETAG_PREFIX}_${countryCode.lowercase()}", null)
    }

    fun setEtag(countryCode: String, etag: String?) {
        prefs.edit().putString("${KEY_ETAG_PREFIX}_${countryCode.lowercase()}", etag).apply()
    }

    fun getLastModified(countryCode: String): String? {
        return prefs.getString("${KEY_LAST_MOD_PREFIX}_${countryCode.lowercase()}", null)
    }

    fun setLastModified(countryCode: String, lastModified: String?) {
        prefs.edit().putString("${KEY_LAST_MOD_PREFIX}_${countryCode.lowercase()}", lastModified).apply()
    }

    fun getLastFetchTime(countryCode: String): Long {
        return prefs.getLong("${KEY_FETCH_TIME_PREFIX}_${countryCode.lowercase()}", 0L)
    }

    fun setLastFetchTime(countryCode: String, time: Long) {
        prefs.edit().putLong("${KEY_FETCH_TIME_PREFIX}_${countryCode.lowercase()}", time).apply()
    }

    // ==================== LRU Cache Management (Max 5 Countries) ====================

    fun getLruCountries(): List<String> {
        val raw = prefs.getString(KEY_LRU_COUNTRIES, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(',').filter { it.isNotEmpty() }
    }

    fun recordCountryAccess(countryCode: String): String? {
        val clean = countryCode.lowercase()
        val list = getLruCountries().toMutableList()
        list.remove(clean)
        list.add(0, clean)

        var evictedCountry: String? = null
        if (list.size > 5) {
            evictedCountry = list.removeAt(list.size - 1)
        }

        prefs.edit().putString(KEY_LRU_COUNTRIES, list.joinToString(",")).apply()
        return evictedCountry
    }

    fun clearCacheMetadata(countryCode: String) {
        val clean = countryCode.lowercase()
        prefs.edit()
            .remove("${KEY_ETAG_PREFIX}_$clean")
            .remove("${KEY_LAST_MOD_PREFIX}_$clean")
            .remove("${KEY_FETCH_TIME_PREFIX}_$clean")
            .apply()

        val list = getLruCountries().toMutableList()
        list.remove(clean)
        prefs.edit().putString(KEY_LRU_COUNTRIES, list.joinToString(",")).apply()
    }

    fun clearAllCacheMetadata() {
        val editor = prefs.edit()
        val allKeys = prefs.all.keys
        for (key in allKeys) {
            if (key.startsWith(KEY_ETAG_PREFIX) ||
                key.startsWith(KEY_LAST_MOD_PREFIX) ||
                key.startsWith(KEY_FETCH_TIME_PREFIX)
            ) {
                editor.remove(key)
            }
        }
        editor.remove(KEY_LRU_COUNTRIES)
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "kapyo_tv_prefs"
        private const val KEY_COUNTRY_CODE = "selected_country_code"
        private const val KEY_COUNTRY_NAME = "selected_country_name"
        private const val KEY_MAX_RES = "max_resolution"
        private const val KEY_FAVORITES_PREFIX = "fav"
        private const val KEY_RECENTS_PREFIX = "recents"
        private const val KEY_ETAG_PREFIX = "etag"
        private const val KEY_LAST_MOD_PREFIX = "last_mod"
        private const val KEY_FETCH_TIME_PREFIX = "fetch_time"
        private const val KEY_LRU_COUNTRIES = "lru_countries"
        private const val KEY_IS_COUNTRY_USER_SELECTED = "is_country_user_selected"
    }
}
