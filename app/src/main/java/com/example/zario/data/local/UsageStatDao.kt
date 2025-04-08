package com.example.zario.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow // Use Flow for observable queries

/**
 * Data Access Object (DAO) for the UsageStatEntity table.
 */
@Dao
interface UsageStatDao {

    /**
     * Inserts a single usage stat record. If a conflict occurs (which shouldn't
     * happen with autoGenerate = true unless IDs were manually set), it replaces the old record.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageStat(usageStat: UsageStatEntity)

    /**
     * Inserts a list of usage stat records. This is likely more efficient for batch inserts.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllUsageStats(usageStats: List<UsageStatEntity>)

    /**
     * Gets the total summed duration in milliseconds for a specific package name on a specific day.
     *
     * @param packageName The package name of the target app.
     * @param dayTimestamp The timestamp representing the start of the target day (00:00:00).
     * @return Total duration in milliseconds, or null if no records found.
     */
    @Query("SELECT SUM(durationMs) FROM usage_stats WHERE packageName = :packageName AND dayTimestamp = :dayTimestamp")
    suspend fun getTotalDurationForAppOnDay(packageName: String, dayTimestamp: Long): Long?

    /**
     * Gets the total summed duration for a specific package name within a given time range.
     * Use intervalStartTimestamp for fine-grained queries if needed.
     */
    @Query("SELECT SUM(durationMs) FROM usage_stats WHERE packageName = :packageName AND intervalStartTimestamp >= :startTime AND intervalEndTimestamp <= :endTime")
    suspend fun getTotalDurationForAppInRange(packageName: String, startTime: Long, endTime: Long): Long?


    /**
     * Gets all usage records for a specific day, potentially for display in a history view.
     * Returns a Flow for reactive updates.
     */
    @Query("SELECT * FROM usage_stats WHERE dayTimestamp = :dayTimestamp ORDER BY intervalStartTimestamp ASC")
    fun getUsageStatsForDayFlow(dayTimestamp: Long): Flow<List<UsageStatEntity>>

    /**
     * Gets aggregated daily total durations for a specific app. Returns pairs of (Day Timestamp, Total Duration).
     * Useful for showing historical trends. Returns a Flow for reactive updates.
     */
    @Query("SELECT dayTimestamp, SUM(durationMs) as totalDuration FROM usage_stats WHERE packageName = :packageName GROUP BY dayTimestamp ORDER BY dayTimestamp DESC")
    fun getAggregatedDailyDurationForAppFlow(packageName: String): Flow<List<DailyDuration>>

    // TODO: Add methods for deleting old data if needed (e.g., data older than N days).
    // @Query("DELETE FROM usage_stats WHERE dayTimestamp < :cutoffTimestamp")
    // suspend fun deleteOldStats(cutoffTimestamp: Long)
}

/**
 * Data class specifically for the result of the aggregated daily duration query.
 */
data class DailyDuration(
    val dayTimestamp: Long,
    val totalDuration: Long
)