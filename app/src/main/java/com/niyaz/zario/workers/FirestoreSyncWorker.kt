package com.niyaz.zario.workers


import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.data.local.AppDatabase
import com.niyaz.zario.data.local.UsageStatDao
import com.niyaz.zario.data.local.UsageStatEntity
import com.niyaz.zario.utils.Constants
import com.niyaz.zario.utils.StudyStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class FirestoreSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {


    companion object {
        private const val TAG = "FirestoreSyncWorker"
        // Unique Name for the Worker
        const val UNIQUE_WORK_NAME = "ZarioFirestoreSync"
        // Configuration for the worker constraints
        val WORKER_CONSTRAINTS = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // Only run when connected
            .build()
        // How often to run the sync (e.g., every 6 hours)
        val REPEAT_INTERVAL_HOURS = 1L
        // Batch size for processing local records
        const val BATCH_SIZE = 100
    }


    // Get dependencies (DAO, Firestore, StateManager)
    private val usageStatDao: UsageStatDao = AppDatabase.getDatabase(appContext).usageStatDao()
    private val firestore: FirebaseFirestore = Firebase.firestore


    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker starting...")


        val userId = StudyStateManager.getUserId(applicationContext)
        if (userId == null) {
            Log.e(TAG, "User ID not found. Cannot sync data. Stopping worker.")
            // Returning success because the condition preventing work isn't transient
            // We don't want it to keep retrying if the user isn't logged in.
            return Result.success()
        }


        Log.i(TAG, "Starting sync process for user: $userId")


        return withContext(Dispatchers.IO) {
            try {
                var recordsProcessed = 0
                var batchCount = 0
                var hasMoreData = true


                // Loop to process data in batches
                while (hasMoreData) {
                    batchCount++
                    Log.d(TAG, "Processing batch #$batchCount for user $userId")
                    val unsyncedStats = usageStatDao.getUnsyncedUsageStats(userId, BATCH_SIZE)


                    if (unsyncedStats.isEmpty()) {
                        Log.d(TAG, "No more unsynced records found for user $userId.")
                        hasMoreData = false
                        continue // Exit loop
                    }


                    Log.d(TAG, "Found ${unsyncedStats.size} unsynced records in batch #$batchCount.")


                    // Aggregate data by Day -> Package -> Duration
                    val dailyAggregations = aggregateDailyUsage(unsyncedStats)


                    // Upload aggregated data to Firestore
                    val uploadSuccess = uploadAggregatedData(userId, dailyAggregations)


                    if (uploadSuccess) {
                        // Mark local records as synced ONLY if upload was successful
                        val idsToMarkSynced = unsyncedStats.map { it.id }
                        usageStatDao.markUsageStatsAsSynced(idsToMarkSynced)
                        recordsProcessed += unsyncedStats.size
                        Log.i(TAG, "Batch #$batchCount successfully synced. Marked ${idsToMarkSynced.size} records.")
                    } else {
                        Log.e(TAG, "Failed to upload batch #$batchCount to Firestore. Will retry later.")
                        // Stop processing further batches in this run and trigger retry
                        return@withContext Result.retry()
                    }


                    // Check if we fetched less than the batch size, indicating end of data
                    if (unsyncedStats.size < BATCH_SIZE) {
                        hasMoreData = false
                    }
                } // End while loop


                Log.i(TAG, "Sync process finished for user $userId. Total records processed in this run: $recordsProcessed.")
                Result.success()


            } catch (e: Exception) {
                Log.e(TAG, "Error during sync process for user $userId", e)
                Result.retry() // Retry on failure
            }
        } // End withContext
    }


    /**
     * Aggregates a list of raw UsageStatEntity records into a map suitable for Firestore.
     * Structure: Map<"YYYY-MM-DD", Map<"packageName", totalDurationMs>>
     */
    private fun aggregateDailyUsage(stats: List<UsageStatEntity>): Map<String, Map<String, Long>> {
        // Use SimpleDateFormat for date formatting (consider thread safety if needed, but fine within worker)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        // Structure: Map<"YYYY-MM-DD", MutableMap<"packageName", totalDurationMs>>
        val dailyMap = mutableMapOf<String, MutableMap<String, Long>>()


        stats.forEach { stat ->
            // dayTimestamp is the start of the day in millis
            val dateString = dateFormat.format(Date(stat.dayTimestamp))
            val packageMap = dailyMap.getOrPut(dateString) { mutableMapOf() }
            packageMap[stat.packageName] = packageMap.getOrDefault(stat.packageName, 0L) + stat.durationMs
        }
        return dailyMap
    }


    /**
     * Uploads aggregated daily usage data to Firestore for a specific user.
     * Uses batched writes or individual document updates with merge options.
     */
    private suspend fun uploadAggregatedData(userId: String, dailyData: Map<String, Map<String, Long>>): Boolean {
        if (dailyData.isEmpty()) return true // Nothing to upload


        Log.d(TAG, "Uploading ${dailyData.size} days of aggregated data to Firestore for user $userId.")


        try {
            // Use Firestore Batched Write for efficiency
            val batch = firestore.batch()


            dailyData.forEach { (dateString, packageData) ->
                // Document path: /users/{userId}/daily_app_usage/{YYYY-MM-DD}
                val dateDocRef = firestore.collection(Constants.FIRESTORE_COLLECTION_USERS)
                    .document(userId)
                    .collection("daily_app_usage") // Subcollection for daily usage
                    .document(dateString)


                // Prepare data for update: Use FieldValue.increment to add durations atomically
                val updates = packageData.mapValues { FieldValue.increment(it.value) }


                // Set with merge=true adds new fields or increments existing ones
                batch.set(dateDocRef, updates, SetOptions.merge())
            }


            // Commit the batch
            batch.commit().await()
            Log.d(TAG, "Firestore batch commit successful for user $userId.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Firestore batch commit failed for user $userId", e)
            return false
        }
    }
}

