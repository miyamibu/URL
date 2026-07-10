package jp.mimac.urlsaver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserLabelDao {
    @Query("SELECT * FROM user_labels ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<UserLabelEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(label: UserLabelEntity): Long

    @Query("DELETE FROM user_labels WHERE id = :labelId")
    suspend fun deleteById(labelId: Long)

    @Query("SELECT * FROM user_labels WHERE lower(name) = lower(:name) LIMIT 1")
    suspend fun findByName(name: String): UserLabelEntity?

    @Query("SELECT * FROM user_labels WHERE id = :labelId LIMIT 1")
    suspend fun findById(labelId: Long): UserLabelEntity?
}
