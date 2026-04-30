package jp.mimac.urlsaver.data

import android.util.Log
import androidx.room.withTransaction
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.flow.Flow

internal class DefaultUrlRepositoryCollectionsSupport(
    private val database: AppDatabase,
    private val dao: UrlEntryDao,
    private val collectionDao: CollectionDao,
    private val userLabelDao: UserLabelDao,
    private val clock: AppClock,
) {
    fun observeCollections(): Flow<List<CollectionEntity>> = collectionDao.observeCollections()

    fun observeUserLabels(): Flow<List<UserLabelEntity>> = userLabelDao.observeAll()

    suspend fun createCollection(name: String): CreateCollectionResult {
        val normalized = normalizeCollectionName(name)
        if (normalized.isBlank() || normalized.length > MAX_COLLECTION_NAME_LENGTH) {
            return CreateCollectionResult(success = false, invalidName = true)
        }

        return try {
            database.withTransaction {
                val existing = collectionDao.findByNameIgnoreCase(normalized)
                if (existing != null) {
                    CreateCollectionResult(
                        success = false,
                        collectionId = existing.id,
                        duplicateName = true,
                    )
                } else {
                    val now = clock.nowEpochMillis()
                    val nextSortOrder = collectionDao.maxSortOrder() + 1
                    val collectionId = collectionDao.insert(
                        CollectionEntity(
                            name = normalized,
                            sortOrder = nextSortOrder,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    CreateCollectionResult(success = true, collectionId = collectionId)
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "createCollection failed", error)
            CreateCollectionResult(success = false)
        }
    }

    suspend fun reorderCollections(collectionIds: List<Long>): Boolean {
        return try {
            database.withTransaction {
                val customCollections = collectionDao.loadCollections()
                    .filter { it.id != DEFAULT_COLLECTION_ID }
                val customCollectionIds = customCollections.map { it.id }
                if (collectionIds.toSet() != customCollectionIds.toSet()) {
                    return@withTransaction false
                }

                val now = clock.nowEpochMillis()
                collectionIds.forEachIndexed { index, collectionId ->
                    collectionDao.updateSortOrder(
                        collectionId = collectionId,
                        sortOrder = index + 1,
                        updatedAt = now,
                    )
                }
                true
            }
        } catch (error: Throwable) {
            Log.e(TAG, "reorderCollections failed", error)
            false
        }
    }

    suspend fun deleteCollection(collectionId: Long): Boolean {
        return try {
            database.withTransaction {
                val collection = collectionDao.findById(collectionId) ?: return@withTransaction false
                if (collection.id == DEFAULT_COLLECTION_ID || collection.name == DEFAULT_COLLECTION_NAME) {
                    return@withTransaction false
                }

                val defaultCollectionId = resolveCollectionId(requestedCollectionId = null, now = clock.nowEpochMillis())
                dao.moveCollectionEntries(
                    sourceCollectionId = collectionId,
                    targetCollectionId = defaultCollectionId,
                )
                collectionDao.deleteById(collectionId)
                true
            }
        } catch (error: Throwable) {
            Log.e(TAG, "deleteCollection failed", error)
            false
        }
    }

    suspend fun createUserLabel(name: String): Long {
        val normalized = normalizeUserLabelName(name)
        require(normalized.isNotBlank() && normalized.length <= MAX_USER_LABEL_NAME_LENGTH) {
            "User label name must be between 1 and $MAX_USER_LABEL_NAME_LENGTH characters."
        }

        return database.withTransaction {
            userLabelDao.findByName(normalized)?.id
                ?: userLabelDao.insert(
                    UserLabelEntity(
                        name = normalized,
                        createdAt = clock.nowEpochMillis(),
                    ),
                )
        }
    }

    suspend fun deleteUserLabel(labelId: Long) {
        database.withTransaction {
            dao.clearUserLabel(labelId)
            userLabelDao.deleteById(labelId)
        }
    }

    suspend fun assignLabel(entryId: Long, labelId: Long?): Boolean {
        return database.withTransaction {
            val entry = dao.findById(entryId) ?: return@withTransaction false
            if (labelId != null && userLabelDao.findById(labelId) == null) {
                return@withTransaction false
            }
            dao.update(
                entry.copy(
                    userLabelId = labelId,
                    updatedAt = clock.nowEpochMillis(),
                ),
            )
            true
        }
    }

    suspend fun resolveCollectionId(requestedCollectionId: Long?, now: Long): Long {
        if (requestedCollectionId != null && collectionDao.findById(requestedCollectionId) != null) {
            return requestedCollectionId
        }
        collectionDao.findDefaultCollectionId()?.let { return it }
        collectionDao.findByName(DEFAULT_COLLECTION_NAME)?.let { return it.id }
        return collectionDao.insert(
            CollectionEntity(
                name = DEFAULT_COLLECTION_NAME,
                sortOrder = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun normalizeUserLabelName(name: String): String {
        return name.trim().replace(WHITESPACE_REGEX, " ")
    }

    private companion object {
        const val TAG = "DefaultUrlRepository"
        const val MAX_COLLECTION_NAME_LENGTH = 40
        const val MAX_USER_LABEL_NAME_LENGTH = 24
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
