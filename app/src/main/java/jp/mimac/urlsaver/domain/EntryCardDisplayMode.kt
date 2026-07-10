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
