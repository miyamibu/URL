package jp.mimac.urlsaver.data

data class SharedTagUrlCountRecord(
    val tagId: Long,
    val tagName: String,
    val urlCount: Int,
)
