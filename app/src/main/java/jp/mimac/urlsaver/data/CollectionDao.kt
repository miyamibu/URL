package jp.mimac.urlsaver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY sortOrder ASC, id ASC")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections ORDER BY sortOrder ASC, id ASC")
    suspend fun loadCollections(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :collectionId LIMIT 1")
    suspend fun findById(collectionId: Long): CollectionEntity?

    @Query("SELECT * FROM collections WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): CollectionEntity?

    @Query("SELECT * FROM collections WHERE lower(name) = lower(:name) LIMIT 1")
    suspend fun findByNameIgnoreCase(name: String): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(collection: CollectionEntity): Long

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM collections")
    suspend fun maxSortOrder(): Int

    @Query("SELECT id FROM collections ORDER BY sortOrder ASC, id ASC LIMIT 1")
    suspend fun findDefaultCollectionId(): Long?

    @Query("UPDATE collections SET sortOrder = :sortOrder, updatedAt = :updatedAt WHERE id = :collectionId")
    suspend fun updateSortOrder(collectionId: Long, sortOrder: Int, updatedAt: Long)

    @Query("DELETE FROM collections WHERE id = :collectionId")
    suspend fun deleteById(collectionId: Long)
}
