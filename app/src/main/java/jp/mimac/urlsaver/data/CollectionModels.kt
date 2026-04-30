package jp.mimac.urlsaver.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "collections",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["sortOrder"]),
    ],
)
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

data class CreateCollectionResult(
    val success: Boolean,
    val collectionId: Long? = null,
    val duplicateName: Boolean = false,
    val invalidName: Boolean = false,
)

fun normalizeCollectionName(raw: String): String = raw.trim()

const val DEFAULT_COLLECTION_NAME = "受信箱"
