package com.famelack.tv.data.model

data class Category(
    val id: String,
    val title: String,
    val count: Int = 0,
    val isLanguage: Boolean = false
) {
    companion object {
        const val ID_ALL = "all"
        const val ID_FAVORITES = "favorites"
        const val ID_RECENT = "recent"
        const val PREFIX_LANG = "lang:"
    }
}
