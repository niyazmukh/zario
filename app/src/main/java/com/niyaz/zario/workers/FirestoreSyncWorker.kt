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
import java.util.concurrent.TimeUnit

/**
 * A periodic [CoroutineWorker] responsible for synchronizing locally stored application usage data
 * from the Room database to Firebase Firestore.
 *
 * It fetches unsynced records in batches, aggregates them by day and package name,
 * uploads the aggregated data using atomic increments in Firestore Batched Writes,
 * and marks the local records as synced upon successful upload.
 */
class FirestoreSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "FirestoreSyncWorker"

        // Unique Name for the Worker, referenced via Constants
        const val UNIQUE_WORK_NAME = Constants.FIRESTORE_SYNC_WORKER_NAME

        // Configuration: Constraints requiring network connectivity
        val WORKER_CONSTRAINTS: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // Only run when network is available
            .build()

        // Configuration: Default repeat interval (Value taken from Constants for consistency)
        // Note: Actual scheduling happens externally (e.g., in HomeScreen), this defines the intended interval.
        val REPEAT_INTERVAL_HOURS: Long = TimeUnit.MILLISECONDS.toHours(Constants.FIRESTORE_SYNC_INTERVAL_MS)

        // Configuration: Batch size for processing records (Value taken from Constants)
        const val BATCH_SIZE = Constants.FIRESTORE_SYNC_BATCH_SIZE
    }

    // Dependencies (Consider Dependency Injection for better testability)
    private val studyStateManager = StudyStateManager
    private val usageStatDao: UsageStatDao = AppDatabase.getDatabase(appContext).usageStatDao()
    private val firestore: FirebaseFirestore = Firebase.firestore

    /**
     * The main work execution method. Performs the data synchronization logic.
     * Runs primarily on the IO dispatcher.
     * @return [Result.success] if synchronization completed successfully for all available batches
     *         or if the worker prerequisites (like User ID) were not met (non-retriable state).
     *         [Result.retry] if a potentially transient error occurred during processing or upload,
     *         indicating the worker should attempt the sync again later.
     *         [Result.failure] only for severe, non-recoverable errors (should be rare).
     */
    override suspend fun doWork(): Result {
        Log.i(TAG, "Worker starting execution...")

        val userId = studyStateManager.getUserId(applicationContext)
        if (userId == null) {
            Log.w(TAG, "User ID not found in local state. Worker cannot proceed. Returning Success (non-retriable).")
            // Not a worker failure, state issue. Don't retry indefinitely.
            return Result.success()
        }

        Log.i(TAG, "Starting sync process for User ID: $userId")

        return withContext(Dispatchers.IO) {
            try {
                var totalRecordsProcessed = 0
                var batchNumber = 0
                var hasMoreDataToProcess = true

                // Loop to process data in batches until no more unsynced records are found
                while (hasMoreDataToProcess) {
                    batchNumber++
                    Log.d(TAG, "Processing Batch #$batchNumber for User ID: $userId (Batch Size: $BATCH_SIZE)")

                    // 1. Fetch a batch of unsynced records from Room
                    val unsyncedStats = usageStatDao.getUnsyncedUsageStats(userId, BATCH_SIZE)

                    if (unsyncedStats.isEmpty()) {
                        Log.i(TAG, "No more unsynced records found for User ID: $userId. Sync loop finished.")
                        hasMoreDataToProcess = false // Exit the loop
                        continue
                    }

                    Log.d(TAG, "Found ${unsyncedStats.size} unsynced records in Batch #$batchNumber for User ID: $userId.")

                    // 2. Aggregate data by Day -> Package -> Duration
                    val dailyAggregations = aggregateDailyUsage(unsyncedStats)

                    // 3. Upload aggregated data to Firestore using Batched Writes
                    val uploadSuccess = uploadAggregatedDataToFirestore(userId, dailyAggregations)

                    if (uploadSuccess) {
                        // 4. Mark local records as synced *only if* upload was successful
                        val idsToMarkSynced = unsyncedStats.map { it.id }
                        try {
                            val updatedRowCount = usageStatDao.markUsageStatsAsSynced(idsToMarkSynced)
                            totalRecordsProcessed += unsyncedStats.size
                            Log.i(TAG, "Batch #$batchNumber synced successfully for User ID: $userId. Marked $updatedRowCount records locally.")
                        } catch (dbUpdateError: Exception) {
                            // This is problematic: data uploaded but not marked locally.
                            // Log critical error. Manual intervention might be needed.
                            // Return failure to prevent potentially incorrect future syncs.
                            Log.e(TAG, "CRITICAL: Firestore upload succeeded for Batch #$batchNumber (User ID: $userId) but failed to mark records as synced locally!", dbUpdateError)
                            return@withContext Result.failure()
                        }
                    } else {
                        // Upload failed, likely a transient issue (network, Firestore unavailable)
                        Log.e(TAG, "Failed to upload Batch #$batchNumber to Firestore for User ID: $userId. Requesting worker retry.")
                        // Stop processing further batches in this run and trigger retry for the whole task.
                        return@withContext Result.retry()
                    }

                    // Check if the last fetched batch was smaller than the requested size,
                    // indicating we've likely processed all available records.
                    if (unsyncedStats.size < BATCH_SIZE) {
                        hasMoreDataToProcess = false
                        Log.d(TAG,"Last batch fetched was smaller than BATCH_SIZE. Assuming no more data for User ID: $userId.")
                    }
                } // End while loop

                Log.i(TAG, "Sync process finished for User ID: $userId. Total records synced in this run: $totalRecordsProcessed.")
                Result.success()

            } catch (e: Exception) {
                Log.e(TAG, "Sync worker failed with unexpected error for User ID: $userId", e)
                Result.retry() // Retry on any other unexpected failure
            }
        } // End withContext(Dispatchers.IO)
    }

    /**
     * Aggregates a list of raw [UsageStatEntity] records into a nested map structure suitable for Firestore upload.
     * The structure is: `Map<"YYYY-MM-DD", Map<"packageName", totalDurationMs>>`.
     * @param stats The list of [UsageStatEntity] records to aggregate.
     * @return A map where keys are date strings and values are maps of package names to their total duration for that day.
     */
    private fun aggregateDailyUsage(stats: List<UsageStatEntity>): Map<String, Map<String, Long>> {
        // SimpleDateFormat is suitable here as it's used locally within the function execution.
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        // Structure: Map<"YYYY-MM-DD", MutableMap<"packageName", totalDurationMs>>
        val dailyMap = mutableMapOf<String, MutableMap<String, Long>>()

        stats.forEach { stat ->
            // stat.dayTimestamp is the start of the day in milliseconds.
            val dateString = dateFormat.format(Date(stat.dayTimestamp))
            // Get or create the map for the specific day.
            val packageMap = dailyMap.getOrPut(dateString) { mutableMapOf() }
            // Add the duration to the existing total for that package on that day.
            packageMap[stat.packageName] = packageMap.getOrDefault(stat.packageName, 0L) + stat.durationMs
        }
        Log.d(TAG, "Aggregated ${stats.size} records into ${dailyMap.size} daily summaries.")
        return dailyMap
    }

    /**
     * Uploads aggregated daily usage data to Firestore for a specific user using Batched Writes.
     * Uses `FieldValue.increment()` for atomic updates and `SetOptions.merge()` to handle
     * existing documents gracefully.
     * @param userId The ID of the user whose data is being uploaded.
     * @param dailyData The aggregated data map: `Map<"YYYY-MM-DD", Map<"packageName", totalDurationMs>>`.
     * @return `true` if the Firestore batch commit was successful, `false` otherwise.
     */
    private suspend fun uploadAggregatedDataToFirestore(userId: String, dailyData: Map<String, Map<String, Long>>): Boolean {
        if (dailyData.isEmpty()) {
            Log.d(TAG, "No aggregated data to upload for user $userId.")
            return true // Nothing to upload is considered a success
        }

        Log.d(TAG, "Preparing Firestore batch for ${dailyData.size} daily summaries for User ID: $userId.")

        try {
            // Create a new Firestore batch write operation.
            val batch = firestore.batch()

            dailyData.forEach { (dateString, packageDataMap) ->
                // Construct the document reference: /users/{userId}/daily_app_usage/{YYYY-MM-DD}
                // Use constants for collection and subcollection names.
                val dateDocRef = firestore.collection(Constants.FIRESTORE_COLLECTION_USERS)
                    .document(userId)
                    .collection(Constants.FIRESTORE_SUBCOLLECTION_DAILY_USAGE) // Use constant
                    .document(dateString) // Use YYYY-MM-DD as document ID

                // Prepare data for update: Map package names to FieldValue.increment(duration).
                // This ensures that if the worker runs multiple times for the same day (e.g., due to retry),
                // the durations are added correctly rather than overwritten.
                val firestoreUpdates = packageDataMap.mapValues { FieldValue.increment(it.value) }

                // Add a set operation to the batch.
                // SetOptions.merge() ensures that we update existing fields or add new ones
                // without overwriting other potential fields in the document.
                batch.set(dateDocRef, firestoreUpdates, SetOptions.merge())
            }

            // Commit the entire batch atomically.
            batch.commit().await()
            Log.i(TAG, "Firestore batch commit successful for User ID: $userId, covering ${dailyData.size} days.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Firestore batch commit failed for User ID: $userId", e)
            return false // Indicate failure
        }
    }
}