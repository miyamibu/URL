package jp.mimac.urlsaver.domain

enum class HomeBackgroundStyle(val storageValue: String) {
    WARM_BEIGE("current"),
    PASTEL_BLUSH("sakura"),
    PASTEL_LAVENDER("lavender"),
    PASTEL_MINT("mint");

    companion object {
        val DEFAULT: HomeBackgroundStyle = WARM_BEIGE

        fun fromStorageValue(value: String?): HomeBackgroundStyle {
            return entries.firstOrNull { style -> style.storageValue == value } ?: DEFAULT
        }
    }
}
