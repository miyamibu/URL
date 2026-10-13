package jp.mimac.urlsaver.domain

enum class EntryCardDisplayMode {
    RICH,
    COMPACT,
    ;

    fun toggled(): EntryCardDisplayMode {
        return when (this) {
            RICH -> COMPACT
            COMPACT -> RICH
        }
    }
}

enum class EntryListSortKey { ADDED_DATE, TITLE }

data class EntryListSortOrder(
    val key: EntryListSortKey = EntryListSortKey.ADDED_DATE,
    val ascending: Boolean = false,
)

enum class HomeEntryLayout {
    LIST,
    THUMBNAILS,
    ;
    fun toggled(): HomeEntryLayout = if (this == LIST) THUMBNAILS else LIST
}

data class HomeEntryDisplayPreferences(
    val layout: HomeEntryLayout = HomeEntryLayout.LIST,
    val gridShowsInfo: Boolean = true,
)
