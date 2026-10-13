package jp.mimac.urlsaver.domain

enum class TagColorChoice(
    val storageValue: String,
    val displayName: String,
    val argb: Int?,
) {
    DEFAULT("default", "既定", null),
    SAKURA("sakura", "さくら", 0xFFF5E3EA.toInt()),
    PEACH("peach", "もも", 0xFFF6E3D6.toInt()),
    LEMON("lemon", "れもん", 0xFFF4EDC9.toInt()),
    MINT("mint", "ミント", 0xFFDDEDE3.toInt()),
    SKY("sky", "そら", 0xFFDFEAF4.toInt()),
    LAVENDER("lavender", "ラベンダー", 0xFFE9E2F3.toInt()),
    LILAC("lilac", "ふじ", 0xFFEADDE8.toInt()),
    SAND("sand", "すな", 0xFFEEE5D8.toInt());

    companion object {
        val CUSTOM: List<TagColorChoice> = entries.filterNot { it == DEFAULT }

        fun fromStorageValue(value: String?): TagColorChoice {
            return entries.firstOrNull { it.storageValue == value } ?: DEFAULT
        }
    }
}

@JvmInline
value class TagColorKey(val storageValue: String) {
    companion object {
        fun local(tagId: Long): TagColorKey = TagColorKey("local:$tagId")

        fun shared(authUserId: String?, remoteTagId: String?): TagColorKey {
            val account = authUserId?.takeIf(String::isNotBlank).orEmpty()
            val remote = remoteTagId?.takeIf(String::isNotBlank).orEmpty()
            return TagColorKey("shared:${lengthPrefixed(account)}:${lengthPrefixed(remote)}")
        }

        fun sharedLocalFallback(authUserId: String?, localTagId: Long): TagColorKey {
            val account = authUserId?.takeIf(String::isNotBlank).orEmpty()
            return TagColorKey("shared:${lengthPrefixed(account)}:${lengthPrefixed("local:$localTagId")}")
        }

        fun forTag(tag: TagWithCount): List<TagColorKey> {
            return when (tag.scope) {
                SharedTagScope.LOCAL_ONLY -> listOf(local(tag.id))
                SharedTagScope.SYNCED -> sharedCandidates(tag.authUserId, tag.remoteTagId, tag.id)
            }
        }

        fun forTag(tag: SharedTagRecord): List<TagColorKey> {
            return when (tag.scope) {
                SharedTagScope.LOCAL_ONLY -> listOf(local(tag.id))
                SharedTagScope.SYNCED -> sharedCandidates(tag.authUserId, tag.remoteTagId, tag.id)
            }
        }

        private fun sharedCandidates(
            authUserId: String?,
            remoteTagId: String?,
            localTagId: Long,
        ): List<TagColorKey> {
            val fallback = sharedLocalFallback(authUserId, localTagId)
            return remoteTagId?.takeIf(String::isNotBlank)
                ?.let { listOf(shared(authUserId, it), fallback) }
                ?: listOf(fallback)
        }

        private fun lengthPrefixed(value: String): String = "${value.length}:$value"
    }
}

fun Map<String, TagColorChoice>.choiceFor(keys: List<TagColorKey>): TagColorChoice {
    return keys.firstNotNullOfOrNull { key -> this[key.storageValue] } ?: TagColorChoice.DEFAULT
}
