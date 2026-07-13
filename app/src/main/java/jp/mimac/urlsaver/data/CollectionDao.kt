package jp.mimac.urlsaver.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY sortOrder ASC, id ASC")
    suspend fun loadCollections(): List<CollectionEntity>
}
