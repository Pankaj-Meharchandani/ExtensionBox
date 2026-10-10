package com.extensionbox.app.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ModuleDataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(data: ModuleDataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(data: List<ModuleDataEntity>)

    @Query("SELECT * FROM module_data WHERE moduleKey = :key ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(key: String): ModuleDataEntity?

    @Query("SELECT * FROM module_data WHERE moduleKey = :key AND timestamp >= :after ORDER BY timestamp ASC")
    fun getHistoryFlow(key: String, after: Long): Flow<List<ModuleDataEntity>>

    @Query("SELECT * FROM module_data WHERE moduleKey = :key AND timestamp >= :after ORDER BY timestamp ASC")
    suspend fun getHistoryList(key: String, after: Long): List<ModuleDataEntity>

    @Query("DELETE FROM module_data WHERE timestamp < :before")
    suspend fun clearOldData(before: Long)

    /**
     * Downsampled history: keeps the first sample of each [bucketMs]-wide time bucket, so a chart over
     * days loads a couple of hundred rows instead of every tick ever stored.
     */
    @Query("""
        SELECT * FROM module_data
        WHERE id IN (
            SELECT MIN(id) FROM module_data
            WHERE moduleKey = :key AND timestamp >= :after
            GROUP BY timestamp / :bucketMs
        )
        ORDER BY timestamp ASC
    """)
    suspend fun getHistoryBucketed(key: String, after: Long, bucketMs: Long): List<ModuleDataEntity>

    /** One page of rows in insertion order for streaming exports; pass the last id of the previous page (0 to start). */
    @Query("SELECT * FROM module_data WHERE moduleKey = :key AND timestamp >= :after AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getHistoryPage(key: String, after: Long, afterId: Long, limit: Int): List<ModuleDataEntity>
}
