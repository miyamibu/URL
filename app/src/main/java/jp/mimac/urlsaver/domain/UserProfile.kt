package jp.mimac.urlsaver.domain

data class UserProfile(
    val displayName: String = "",
    val avatarBase64: String? = null,
) {
    val trimmedDisplayName: String
        get() = displayName.trim()
}
