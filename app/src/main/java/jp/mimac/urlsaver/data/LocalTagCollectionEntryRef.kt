package jp.mimac.urlsaver.data

data class LocalTagCollectionEntryRef(
    val collectionId: Long,
    val entryId: Long,
)

data class LocalTagEntryRef(
    val tagId: Long,
    val entryId: Long,
)
