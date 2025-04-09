package com.niyaz.zario.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.StudyPhase
import com.niyaz.zario.data.local.AppDatabase
import com.niyaz.zario.utils.StudyStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class DailyCheckWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "DailyCheckWorker"
        const val UNIQUE_WORK_NAME = "ZarioDailyCheck" // Unique name for the periodic work
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker starting...")

        // Run calculations in IO context for DB/Network access
        return withContext(Dispatchers.IO) {
            try {
                val userId = StudyStateManager.getUserId(applicationContext)
                if (userId == null) {
                    Log.e(TAG, "User ID not found in state. Cannot perform daily check. Stopping work.")
                    // Maybe retry later if userId might become available? For now, fail.
                    return@withContext Result.failure()
                }

                val phase = StudyStateManager.getStudyPhase(applicationContext)
                // Only run the check logic if in an active intervention phase
                if (!phase.name.startsWith("INTERVENTION")) {
                    Log.i(TAG, "Not in an intervention phase ($phase). Skipping daily check.")
                    return@withContext Result.success() // Success as no work needed
                }

                Log.i(TAG, "Running daily check for user $userId in phase $phase")

                // Get necessary state
                val condition = StudyStateManager.getCondition(applicationContext) ?: phase // Fallback to phase if condition key missing
                val targetApp = StudyStateManager.getTargetApp(applicationContext)
                val dailyGoalMs = StudyStateManager.getDailyGoalMs(applicationContext)
                val currentPoints = StudyStateManager.getPointsBalance(applicationContext)

                if (targetApp == null || dailyGoalMs == null) {
                    Log.e(TAG, "Target app or daily goal not set for user $userId. Cannot perform check.")
                    return@withContext Result.failure() // Fail as critical data missing
                }

                // 1. Calculate Timestamps for Previous Day
                val (prevDayStart, prevDayEnd) = getPreviousDayTimestamps()
                Log.d(TAG, "Checking usage for $targetApp between $prevDayStart and $prevDayEnd")

                // 2. Query Database for Previous Day's Usage
                val dao = AppDatabase.getDatabase(applicationContext).usageStatDao()
                // Use the dayTimestamp for efficient querying
                val totalUsageMs = dao.getTotalDurationForAppOnDay(targetApp, prevDayStart) ?: 0L
                Log.i(TAG, "Previous day's total usage for $targetApp: ${totalUsageMs / 1000.0} seconds")

                // 3. Compare Usage to Goal
                val goalReached = totalUsageMs <= dailyGoalMs
                Log.i(TAG, "Daily goal ($dailyGoalMs ms): ${if(goalReached) "REACHED" else "MISSED"}")

                // 4. Determine Points Change based on Condition
                val pointsChange = calculatePointsChange(condition, goalReached)
                val newPoints = (currentPoints + pointsChange).coerceAtLeast(0) // Ensure points don't go below 0
                Log.i(TAG, "Condition: $condition, Goal Reached: $goalReached -> Points change: $pointsChange, New Balance: $newPoints")


                // 5. Update Points (Local & Firestore) if changed
                if (newPoints != currentPoints) {
                    StudyStateManager.savePointsBalance(applicationContext, newPoints)
                    updatePointsInFirestore(userId, newPoints) // Separate suspend function
                } else {
                    Log.d(TAG,"Points balance unchanged.")
                }

                // TODO: Implement FR-050 Daily Feedback Notification logic here or trigger another worker/service.

                Log.d(TAG, "Worker finished successfully.")
                Result.success()

            } catch (e: Exception) {
                Log.e(TAG, "Worker failed", e)
                Result.retry() // Retry if a transient error occurred
            }
        } // End withContext(Dispatchers.IO)
    }

    private fun getPreviousDayTimestamps(): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        // Move to yesterday
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        // Set to start of yesterday
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTimestamp = calendar.timeInMillis
        // Set to end of yesterday
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val endTimestamp = calendar.timeInMillis
        return Pair(startTimestamp, endTimestamp)
    }

    private fun calculatePointsChange(condition: StudyPhase, goalReached: Boolean): Int {
        // TODO: Fetch flexible stakes if condition is FLEXIBLE_DEPOSIT
        // val (earn, lose) = StudyStateManager.getFlexStakes(applicationContext)
        return when (condition) {
            StudyPhase.INTERVENTION_CONTROL -> if (goalReached) 10 else 0
            StudyPhase.INTERVENTION_DEPOSIT -> if (goalReached) 10 else -40
            StudyPhase.INTERVENTION_FLEXIBLE -> {
                val (earn, lose) = StudyStateManager.getFlexStakes(applicationContext)
                if (goalReached) (earn ?: 10) else -(lose ?: 40) // Use defaults if not set? Or handle error?
            }
            else -> 0 // Should not happen if check is done correctly
        }
    }

    private suspend fun updatePointsInFirestore(userId: String, newPoints: Int) {
        try {
            val firestore = Firebase.firestore
            firestore.collection("users").document(userId)
                .update("pointsBalance", newPoints)
                .await() // Wait for completion
            Log.i(TAG,"Successfully updated pointsBalance to $newPoints in Firestore for user $userId")
        } catch(e: Exception) {
            Log.e(TAG, "Failed to update pointsBalance in Firestore for user $userId", e)
            // Consider retry logic or marking local state as dirty if needed
        }
    }
}