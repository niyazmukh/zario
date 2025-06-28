package com.niyaz.zario.data.local


import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update // Import Update
import kotlinx.coroutines.flow.Flow // Use Flow for observable queries


/**
 * Data Access Object (DAO) for the UsageStatEntity table.
 */
@Dao
interface UsageStatDao {


   // --- Existing methods (No change needed in signature, Room handles mapping) ---
   @Insert(onConflict = OnConflictStrategy.REPLACE)
   suspend fun insertUsageStat(usageStat: UsageStatEntity)


   @Insert(onConflict = OnConflictStrategy.REPLACE)
   suspend fun insertAllUsageStats(usageStats: List<UsageStatEntity>)


   // --- Queries might need userId filter if not implicitly handled by app logic elsewhere ---
   // Note: Added userId filter to most queries to ensure data isolation per user
   @Query("SELECT SUM(durationMs) FROM usage_stats WHERE userId = :userId AND packageName = :packageName AND dayTimestamp = :dayTimestamp")
   suspend fun getTotalDurationForAppOnDay(userId: String, packageName: String, dayTimestamp: Long): Long?


   // Assuming range queries are user-specific too
   @Query("SELECT SUM(durationMs) FROM usage_stats WHERE userId = :userId AND packageName = :packageName AND intervalStartTimestamp >= :startTime AND intervalEndTimestamp <= :endTime")
   suspend fun getTotalDurationForAppInRange(userId: String, packageName: String, startTime: Long, endTime: Long): Long?


   @Query("SELECT * FROM usage_stats WHERE userId = :userId AND dayTimestamp = :dayTimestamp ORDER BY intervalStartTimestamp ASC")
   fun getUsageStatsForDayFlow(userId: String, dayTimestamp: Long): Flow<List<UsageStatEntity>>


   // Aggregated flows often used for user's view, so add userId
   @Query("SELECT dayTimestamp, SUM(durationMs) as totalDuration FROM usage_stats WHERE userId = :userId AND packageName = :packageName GROUP BY dayTimestamp ORDER BY dayTimestamp DESC")
   fun getAggregatedDailyDurationForAppFlow(userId: String, packageName: String): Flow<List<DailyDuration>>


   // Today's usage flow needs userId
   @Query("SELECT SUM(durationMs) FROM usage_stats WHERE userId = :userId AND packageName = :packageName AND dayTimestamp = :todayDayTimestamp")
   fun getTodayUsageForAppFlow(userId: String, packageName: String, todayDayTimestamp: Long): Flow<Long?>


   // --- Baseline Queries likely need userId filter too ---
   @Query("""
       SELECT packageName, SUM(durationMs) as totalDurationMs
       FROM usage_stats
       WHERE userId = :userId AND intervalEndTimestamp > :startTime AND intervalStartTimestamp < :endTime
       GROUP BY packageName
       HAVING SUM(durationMs) >= :minTotalDurationMs
       ORDER BY totalDurationMs DESC
   """)
   suspend fun getAggregatedUsageForBaseline(userId: String, startTime: Long, endTime: Long, minTotalDurationMs: Long = 60000): List<AppUsageBaseline> // Default min 1 minute


   @Query("""
       SELECT packageName, durationMs, intervalStartTimestamp
       FROM usage_stats
       WHERE userId = :userId AND intervalEndTimestamp > :startTime AND intervalStartTimestamp < :endTime
   """)
   suspend fun getAllUsageRecordsForBaseline(userId: String, startTime: Long, endTime: Long): List<BaselineUsageRecord>




   // --- NEW QUERIES/UPDATES FOR SYNC WORKER ---


   /**
    * Gets all usage records for a specific user that have not yet been synced.
    * Optionally limit the number of records fetched per batch.
    * Assumes UsageStatEntity has an 'isSynced' field (e.g., Boolean or Int 0/1)
    * and a primary key 'id'.
    * @param userId The ID of the user whose records to fetch.
    * @param limit Max number of records to return.
    * @return List of unsynced UsageStatEntity objects.
    */
   @Query("SELECT * FROM usage_stats WHERE userId = :userId AND isSynced = 0 ORDER BY intervalStartTimestamp ASC LIMIT :limit")
   suspend fun getUnsyncedUsageStats(userId: String, limit: Int = 100): List<UsageStatEntity> // Default limit 100


   /**
    * Updates a list of UsageStatEntity records, typically to mark them as synced.
    * Room matches entities based on their primary key.
    * @param stats The list of entities to update (should have primary key set).
    * @return The number of rows affected.
    */
   @Update
   suspend fun updateUsageStats(stats: List<UsageStatEntity>): Int


   /**
    * Marks specific usage stats as synced based on their IDs.
    * Use this if updating the entire entity is not desired or efficient.
    * Assumes UsageStatEntity has a primary key 'id'.
    * @param ids List of primary keys (id) of the records to mark as synced.
    */
   @Query("UPDATE usage_stats SET isSynced = 1 WHERE id IN (:ids)")
   suspend fun markUsageStatsAsSynced(ids: List<Long>)


}


// --- Data Classes for Query Results (No changes needed) ---


/** Data class for aggregated baseline usage per app. */
data class AppUsageBaseline(
   val packageName: String,
   val totalDurationMs: Long
)


/** Data class for individual records needed for hourly aggregation. */
data class BaselineUsageRecord(
   val packageName: String,
   val durationMs: Long,
   val intervalStartTimestamp: Long
)




/** Existing Data class */
data class DailyDuration(
   val dayTimestamp: Long,
   val totalDuration: Long
)


// NOTE: The UsageStatEntity definition is assumed to exist elsewhere and contain
// at least the following fields used in the queries:
// - id: Long (Primary Key)
// - userId: String
// - packageName: String
// - durationMs: Long
// - dayTimestamp: Long
// - intervalStartTimestamp: Long
// - intervalEndTimestamp: Long
// - isSynced: Boolean or Int (e.g., 0 for false, 1 for true)

