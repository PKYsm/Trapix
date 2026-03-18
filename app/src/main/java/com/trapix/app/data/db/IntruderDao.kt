package com.trapix.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.trapix.app.data.model.IntruderLog

@Dao
interface IntruderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: IntruderLog): Long

    @Delete
    suspend fun delete(log: IntruderLog)

    @Query("DELETE FROM intruder_logs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM intruder_logs ORDER BY timestamp DESC")
    fun getAllLogs(): LiveData<List<IntruderLog>>

    @Query("SELECT * FROM intruder_logs ORDER BY timestamp DESC")
    suspend fun getAllLogsList(): List<IntruderLog>

    @Query("SELECT COUNT(*) FROM intruder_logs")
    fun getCount(): LiveData<Int>

    @Query("DELETE FROM intruder_logs")
    suspend fun deleteAll()

    @Query("UPDATE intruder_logs SET isSavedToGallery = 1 WHERE id = :id")
    suspend fun markSavedToGallery(id: Long)
}
