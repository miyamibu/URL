package jp.mimac.urlsaver.ui.theme

enum class AppThemeMode(val label: String, val storageValue: String) {
    SYSTEM("システム", "system"),
    LIGHT("ライト", "light"),
    DARK("ダーク", "dark");

    companion object {
        fun fromStorageValue(value: String?): AppThemeMode {
            return entries.firstOrNull { it.storageValue == value } ?: SYSTEM
        }
    }
}
