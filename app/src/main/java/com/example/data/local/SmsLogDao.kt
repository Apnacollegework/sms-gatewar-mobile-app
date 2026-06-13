package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsLogDao {
    @Query("SELECT * FROM sms_logs ORDER BY created_at DESC")
    fun getAllLogs(): Flow<List<SmsLogEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLog(log: SmsLogEntity): Long

    @Update
    suspend fun updateLog(log: SmsLogEntity)

    @Query("SELECT * FROM sms_logs WHERE request_id = :requestId LIMIT 1")
    suspend fun findByRequestId(requestId: String): SmsLogEntity?

    @Query("SELECT COUNT(*) FROM sms_logs WHERE status IN ('sent', 'delivered') AND created_at >= :startOfToday")
    suspend fun getCountSentToday(startOfToday: Long): Int

    @Query("SELECT COUNT(*) FROM sms_logs WHERE status = 'failed' AND created_at >= :startOfToday")
    suspend fun getCountFailedToday(startOfToday: Long): Int

    @Query("DELETE FROM sms_logs")
    suspend fun clearAll()
}
