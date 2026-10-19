package com.local.vod.domain.model

/** Las listas de cada perfil; [key] es como las llama el servidor. */
enum class TitleList(val key: String) { LATER("later"), FAVORITES("favorites") }

/** Ids de títulos, el último añadido primero. */
data class TitleLists(
    val later: List<String> = emptyList(),
    val favorites: List<String> = emptyList(),
) {
    fun contains(list: TitleList, titleId: String): Boolean = titleId in ids(list)

    fun ids(list: TitleList): List<String> = when (list) {
        TitleList.LATER -> later
        TitleList.FAVORITES -> favorites
    }
}
