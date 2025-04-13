package com.niyaz.zario.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for the [UsageStatEntity] table.
 * Provides methods for inserting, updating, and querying application usage statistics
 * stored in the local Room database.
 */
@Dao
interface UsageStatDao {

    // --- Insertion ---

    /**
     * Inserts a single usage statistic record. If a record with the same primary key
     * already exists, it will be replaced.
     * Assumes the [UsageStatEntity.userId] and [UsageStatEntity.isSynced] fields are correctly set by the caller.
     * @param usageStat The [UsageStatEntity] to insert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageStat(usageStat: UsageStatEntity)

    /**
     * Inserts a list of usage statistic records. If records with the same primary keys
     * already exist, they will be replaced.
     * Assumes the [UsageStatEntity.userId] and [UsageStatEntity.isSynced] fields are correctly set by the caller.
     * @param usageStats The list of [UsageStatEntity] objects to insert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllUsageStats(usageStats: List<UsageStatEntity>)

    // --- Querying (User-Specific) ---

    /**
     * Calculates the total usage duration for a specific app on a specific day for a given user.
     * The day is identified by its start timestamp (00:00:00).
     * @param userId The ID of the user whose data to query.
     * @param packageName The package name of the target application.
     * @param dayTimestamp The timestamp (epoch milliseconds) representing the start of the target day.
     * @return The total duration in milliseconds, or null if no usage recorded for that app on that day.
     */
    @Query("SELECT SUM(durationMs) FROM usage_stats WHERE userId = :userId AND packageName = :packageName AND dayTimestamp = :dayTimestamp")
    suspend fun getTotalDurationForAppOnDay(userId: String, packageName: String, dayTimestamp: Long): Long?

    /**
     * Calculates the total usage duration for a specific app within a given time range for a given user.
     * Note: This query checks if the *interval* overlaps the range, not just the event time.
     * @param userId The ID of the user whose data to query.
     * @param packageName The package name of the target application.
     * @param startTime The start timestamp (epoch milliseconds) of the query range (exclusive).
     * @param endTime The end timestamp (epoch milliseconds) of the query range (inclusive).
     * @return The total duration in milliseconds, or null if no usage recorded in that range.
     */
    @Query("SELECT SUM(durationMs) FROM usage_stats WHERE userId = :userId AND packageName = :packageName AND intervalStartTimestamp < :endTime AND intervalEndTimestamp > :startTime")
    suspend fun getTotalDurationForAppInRange(userId: String, packageName: String, startTime: Long, endTime: Long): Long? // Adjusted query slightly for overlap logic

    /**
     * Retrieves all usage statistic records for a specific day for a given user, ordered by time.
     * Emits a new list whenever the underlying data changes.
     * @param userId The ID of the user whose data to query.
     * @param dayTimestamp The timestamp (epoch milliseconds) representing the start of the target day.
     * @return A [Flow] emitting a list of [UsageStatEntity] objects for the specified day.
     */
    @Query("SELECT * FROM usage_stats WHERE userId = :userId AND dayTimestamp = :dayTimestamp ORDER BY intervalStartTimestamp ASC")
    fun getUsageStatsForDayFlow(userId: String, dayTimestamp: Long): Flow<List<UsageStatEntity>>

    /**
     * Retrieves the aggregated daily total usage duration for a specific app for a given user.
     * Emits a new list whenever the underlying data changes.
     * @param userId The ID of the user whose data to query.
     * @param packageName The package name of the target application.
     * @return A [Flow] emitting a list of [DailyDuration] objects, ordered by day descending.
     */
    @Query("SELECT dayTimestamp, SUM(durationMs) as totalDuration FROM usage_stats WHERE userId = :userId AND packageName = :packageName GROUP BY dayTimestamp ORDER BY dayTimestamp DESC")
    fun getAggregatedDailyDurationForAppFlow(userId: String, packageName: String): Flow<List<DailyDuration>>

    /**
     * Retrieves the total usage duration for a specific app for the current day (today) for a given user.
     * Emits a new value whenever the underlying data for today changes.
     * @param userId The ID of the user whose data to query.
     * @param packageName The package name of the target application.
     * @param todayDayTimestamp The timestamp (epoch milliseconds) representing the start of today.
     * @return A [Flow] emitting the total duration in milliseconds (or null if none), automatically updated.
     */
    @Query("SELECT SUM(durationMs) FROM usage_stats WHERE userId = :userId AND packageName = :packageName AND dayTimestamp = :todayDayTimestamp")
    fun getTodayUsageForAppFlow(userId: String, packageName: String, todayDayTimestamp: Long): Flow<Long?>

    // --- Baseline Calculation Queries ---

    /**
     * Aggregates the total usage duration per application package during the baseline period for a given user.
     * Filters out apps with less than a minimum total duration.
     * @param userId The ID of the user whose data to query.
     * @param startTime The start timestamp (epoch milliseconds) of the baseline period (exclusive).
     * @param endTime The end timestamp (epoch milliseconds) of the baseline period (inclusive).
     * @param minTotalDurationMs The minimum total duration (in ms) an app must have to be included (default: 60000ms).
     * @return A list of [AppUsageBaseline] objects, ordered by total duration descending.
     */
    @Query("""
        SELECT packageName, SUM(durationMs) as totalDurationMs
        FROM usage_stats
        WHERE userId = :userId AND intervalStartTimestamp < :endTime AND intervalEndTimestamp > :startTime
        GROUP BY packageName
        HAVING SUM(durationMs) >= :minTotalDurationMs
        ORDER BY totalDurationMs DESC
    """)
    suspend fun getAggregatedUsageForBaseline(userId: String, startTime: Long, endTime: Long, minTotalDurationMs: Long = 60000): List<AppUsageBaseline>

    /**
     * Retrieves individual usage records within the baseline period for a given user.
     * Used for calculating hourly usage patterns.
     * @param userId The ID of the user whose data to query.
     * @param startTime The start timestamp (epoch milliseconds) of the baseline period (exclusive).
     * @param endTime The end timestamp (epoch milliseconds) of the baseline period (inclusive).
     * @return A list of [BaselineUsageRecord] objects containing package name, duration, and start time.
     */
    @Query("""
        SELECT packageName, durationMs, intervalStartTimestamp
        FROM usage_stats
        WHERE userId = :userId AND intervalStartTimestamp < :endTime AND intervalEndTimestamp > :startTime
    """)
    suspend fun getAllUsageRecordsForBaseline(userId: String, startTime: Long, endTime: Long): List<BaselineUsageRecord>

    // --- Synchronization Worker Queries/Updates ---

    /**
     * Gets a batch of usage records for a specific user that have not yet been synced to Firestore.
     * Uses the index on `(userId, isSynced)` for efficiency.
     * @param userId The ID of the user whose records to fetch.
     * @param limit The maximum number of records to return in this batch.
     * @return A list of unsynced [UsageStatEntity] objects, ordered by time.
     */
    @Query("SELECT * FROM usage_stats WHERE userId = :userId AND isSynced = 0 ORDER BY intervalStartTimestamp ASC LIMIT :limit")
    suspend fun getUnsyncedUsageStats(userId: String, limit: Int): List<UsageStatEntity> // Removed default limit, should be passed by worker

    /**
     * Updates a list of [UsageStatEntity] records in the database.
     * Room matches entities based on their primary key (`id`). This is typically used
     * by the sync worker to mark records as synced after successful upload.
     * @param stats The list of entities to update (must have `id` and updated `isSynced` status set).
     * @return The number of rows affected by the update.
     */
    @Update
    suspend fun updateUsageStats(stats: List<UsageStatEntity>): Int

    /**
     * Marks specific usage statistic records as synced (`isSynced = true`) based on their primary keys.
     * Use this for more targeted updates if only the sync status needs changing.
     * @param ids A list of primary key `id` values of the records to mark as synced.
     * @return The number of rows affected by the update.
     */
    @Query("UPDATE usage_stats SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markUsageStatsAsSynced(ids: List<Long>): Int

}

// --- Data Classes for Custom Query Results ---

/**
 * Data class holding the aggregated total usage duration for a specific application package,
 * typically used for baseline analysis results.
 * @property packageName The package name of the application.
 * @property totalDurationMs The total aggregated duration in milliseconds.
 */
data class AppUsageBaseline(
    val packageName: String,
    val totalDurationMs: Long
)

/**
 * Data class holding essential fields from individual usage records,
 * used specifically for calculating hourly baseline patterns.
 * @property packageName The package name of the application.
 * @property durationMs The duration of this specific usage interval in milliseconds.
 * @property intervalStartTimestamp The start timestamp (epoch milliseconds) of this usage interval.
 */
data class BaselineUsageRecord(
    val packageName: String,
    val durationMs: Long,
    val intervalStartTimestamp: Long
)

/**
 * Data class holding the aggregated total usage duration for a specific application
 * on a given day. Used by [UsageStatDao.getAggregatedDailyDurationForAppFlow].
 * @property dayTimestamp The timestamp (epoch milliseconds) representing the start of the day.
 * @property totalDuration The total aggregated usage duration for that day in milliseconds.
 */
data class DailyDuration(
    val dayTimestamp: Long,
    val totalDuration: Long
)