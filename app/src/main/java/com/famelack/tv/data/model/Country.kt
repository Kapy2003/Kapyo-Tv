package com.famelack.tv.data.model

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

data class Country(
    val code: String,
    val name: String,
    val channelCount: Int = 0
) {
    val m3uUrl: String
        get() = "https://iptv-org.github.io/iptv/countries/${code.lowercase()}.m3u"

    val famelackUrl: String
        get() = "https://famelack.com/tv/${code.lowercase()}"

    companion object {
        val DEFAULT_COUNTRY = Country("in", "India", 837)

        private var cachedList: List<Country>? = null

        /**
         * Lazily loads the clean bundled country index from assets.
         */
        fun getCountryList(context: Context): List<Country> {
            cachedList?.let { return it }

            val list = ArrayList<Country>(35)
            try {
                context.assets.open("countries.tsv").use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            val parts = line.split('\t')
                            if (parts.size >= 2) {
                                val count = if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0
                                list.add(Country(parts[0], parts[1], count))
                            }
                            line = reader.readLine()
                        }
                    }
                }
            } catch (e: Exception) {
                list.add(DEFAULT_COUNTRY)
            }

            cachedList = list
            return list
        }

        /**
         * Detect default country from device locale as initial fallback.
         */
        fun detectDefaultCountry(context: Context): Country {
            val list = getCountryList(context)
            val deviceCountry = Locale.getDefault().country.lowercase()
            return list.firstOrNull { it.code.lowercase() == deviceCountry }
                ?: DEFAULT_COUNTRY
        }

        fun findByCode(context: Context, code: String): Country {
            val clean = code.trim().lowercase()
            return getCountryList(context).firstOrNull { it.code.lowercase() == clean }
                ?: Country(clean, clean.uppercase())
        }
    }
}
