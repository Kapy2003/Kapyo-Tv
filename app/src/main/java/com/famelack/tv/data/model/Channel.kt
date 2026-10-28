package com.famelack.tv.data.model

/**
 * Compact channel representation supporting Unicode names (Arabic, Hindi, Tamil, Thai, CJK, etc.)
 * String pooling is used for categories and languages to eliminate duplicate heap allocations.
 */
data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String?,
    val category: String,
    val resolution: String? = null,
    val language: String? = null
) {
    /**
     * Compact tab-separated single-line representation for ultra-fast disk caching.
     */
    fun toCacheLine(): String {
        val safeLogo = logo ?: ""
        val safeRes = resolution ?: ""
        val safeLang = language ?: ""
        return "$id\t$name\t$url\t$safeLogo\t$category\t$safeRes\t$safeLang"
    }

    companion object {
        fun fromCacheLine(line: String, stringPool: (String) -> String = { it }): Channel? {
            val parts = line.split('\t')
            if (parts.size < 5) return null
            val id = parts[0]
            val name = parts[1]
            val url = parts[2]
            val logo = parts[3].ifEmpty { null }
            val category = stringPool(parts[4])
            val resolution = if (parts.size > 5 && parts[5].isNotEmpty()) parts[5] else null
            val language = if (parts.size > 6 && parts[6].isNotEmpty()) stringPool(parts[6]) else null
            return Channel(
                id = id,
                name = name,
                url = url,
                logo = logo,
                category = category,
                resolution = resolution,
                language = language
            )
        }
    }
}
