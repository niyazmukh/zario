package com.niyaz.zario.workers


import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.StudyPhase
import com.niyaz.zario.data.local.AppDatabase
import com.niyaz.zario.utils.Constants
import com.niyaz.zario.utils.StudyStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar


// Apply changes from <change> to the starter <code>
class DailyCheckWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {


    companion object {
        const val TAG = "DailyCheckWorker"
        // Use constant for worker name
        const val UNIQUE_WORK_NAME = Constants.DAILY_CHECK_WORKER_NAME
    }


    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker starting...")
        val currentTime = System.currentTimeMillis() // Timestamp for this check run




        return withContext(Dispatchers.IO) {
            try {
                val userId = StudyStateManager.getUserId(applicationContext)
                if (userId == null) {
                    Log.e(TAG, "User ID not found. Cannot perform daily check.")
                    return@withContext Result.failure()
                }


                val phase = StudyStateManager.getStudyPhase(applicationContext)
                if (!phase.name.startsWith("INTERVENTION")) {
                    Log.i(TAG, "Not in an intervention phase ($phase). Skipping.")
                    return@withContext Result.success()
                }


                Log.i(TAG, "Running daily check for user $userId in phase $phase")


                // Get state needed for calculation
                val condition = StudyStateManager.getCondition(applicationContext) ?: phase
                val targetApp = StudyStateManager.getTargetApp(applicationContext)
                val dailyGoalMs = StudyStateManager.getDailyGoalMs(applicationContext)
                val currentPoints = StudyStateManager.getPointsBalance(applicationContext)
                // Fetch flexible stakes regardless, will only be used if needed
                val (flexEarn, flexLose) = StudyStateManager.getFlexStakes(applicationContext)


                // --- Validation of required state ---
                if (targetApp == null) {
                    Log.e(TAG, "Target app not set for user $userId. Cannot check goal.")
                    return@withContext Result.failure()
                }
                if (dailyGoalMs == null) {
                    Log.e(TAG, "Daily goal not set for user $userId. Cannot check goal.")
                    return@withContext Result.failure()
                }
                // Use constants for flex stake validation and range check
                if (condition == StudyPhase.INTERVENTION_FLEXIBLE && (flexEarn == null || flexLose == null || flexEarn !in Constants.FLEX_STAKES_MIN_EARN..Constants.FLEX_STAKES_MAX_EARN || flexLose !in Constants.FLEX_STAKES_MIN_LOSE..Constants.FLEX_STAKES_MAX_LOSE)) {
                    Log.e(TAG, "Flexible stakes invalid ($flexEarn, $flexLose) for user $userId. Cannot calculate points.")
                    return@withContext Result.failure()
                }
                // --- End Validation ---


                // 1. Calculate Timestamps for Previous Day
                val (prevDayStart, _) = getPreviousDayTimestamps() // Only need start for daily DAO query


                // 2. Query Database for Previous Day's Usage
                val dao = AppDatabase.getDatabase(applicationContext).usageStatDao()
                // --- Corrected DAO Call: Pass userId, packageName, dayTimestamp ---
                val totalUsageMs = dao.getTotalDurationForAppOnDay(
                    userId = userId, // Pass the userId obtained earlier
                    packageName = targetApp, // Correct position
                    dayTimestamp = prevDayStart // Correct position
                ) ?: 0L
                // --- End Corrected DAO Call ---
                Log.i(TAG, "Previous day ($prevDayStart) total usage for $targetApp: ${totalUsageMs / 1000.0} seconds")


                // 3. Compare Usage to Goal
                val goalReached = totalUsageMs <= dailyGoalMs // Removed !! assertion as validation ensures not null
                Log.i(TAG, "Daily goal ($dailyGoalMs ms): ${if(goalReached) "REACHED" else "MISSED"}")


                // 4. Determine Points Change based on Condition
                // Pass fetched stakes to the calculation function
                val pointsChange = calculatePointsChange(condition, goalReached, flexEarn, flexLose)


                // *** ALIGNMENT CHANGE: Coerce points between MIN_POINTS and MAX_POINTS using Constants ***
                val newPoints = (currentPoints + pointsChange).coerceIn(Constants.MIN_POINTS, Constants.MAX_POINTS) // Use constants for point bounds
                Log.i(TAG, "Condition: $condition, Goal Reached: $goalReached -> Points change: $pointsChange, New Balance: $newPoints (Coerced)")


                // --- ADDED: Save Daily Outcome ---
                StudyStateManager.saveDailyOutcome(applicationContext, currentTime, goalReached, pointsChange)
                // --- End Save Daily Outcome ---


                // 5. Update Points (Local & Firestore) if changed
                if (newPoints != currentPoints) {
                    StudyStateManager.savePointsBalance(applicationContext, newPoints)
                    updatePointsInFirestore(userId, newPoints) // Removed !! assertion as validation ensures not null
                } else {
                    Log.d(TAG,"Points balance unchanged.")
                }


                Log.d(TAG, "Worker finished successfully.")
                Result.success()


            } catch (e: Exception) {
                Log.e(TAG, "Worker failed", e)
                Result.retry()
            }
        } // End withContext
    }




    // Unchanged as per <change> which used /* ... */
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


    private fun calculatePointsChange(
        condition: StudyPhase,
        goalReached: Boolean,
        flexEarn: Int?, // Pass fetched value
        flexLose: Int?  // Pass fetched value
    ): Int {
        // Use Constants for default earn/lose values
        val controlEarn = Constants.DEFAULT_CONTROL_EARN_POINTS
        val depositEarn = Constants.DEFAULT_DEPOSIT_EARN_POINTS
        val depositLoseAmount = Constants.DEFAULT_DEPOSIT_LOSE_POINTS // This is a positive value representing amount to lose


        return when (condition) {
            StudyPhase.INTERVENTION_CONTROL -> if (goalReached) controlEarn else Constants.DEFAULT_CONTROL_LOSE_POINTS // Usually 0 for control loss
            StudyPhase.INTERVENTION_DEPOSIT -> if (goalReached) depositEarn else -depositLoseAmount // Subtract the positive lose amount
            StudyPhase.INTERVENTION_FLEXIBLE -> {
                // Use fetched values, assuming validation passed in doWork
                // Default to min constants if somehow null (should not happen due to validation)
                val earnPoints = flexEarn ?: Constants.FLEX_STAKES_MIN_EARN
                val losePoints = flexLose ?: Constants.FLEX_STAKES_MIN_LOSE
                if (goalReached) earnPoints else -losePoints // Subtract the positive lose amount
            }
            else -> {
                Log.w(TAG, "Calculating points change for unexpected phase: $condition. Returning 0.")
                0
            }
        }
    }


    /**
     * Updates pointsBalance field in the user's Firestore document.
     */
    private suspend fun updatePointsInFirestore(userId: String, newPoints: Int) {
        try {
            val firestore = Firebase.firestore
            // Use constants for collection and field names
            firestore.collection(Constants.FIRESTORE_COLLECTION_USERS).document(userId)
                .update(Constants.FIRESTORE_FIELD_POINTS_BALANCE, newPoints.toLong()) // Firestore typically uses Long for numbers
                .await() // Wait for completion
            Log.i(TAG,"Successfully updated pointsBalance to $newPoints in Firestore for user $userId")
        } catch(e: Exception) {
            Log.e(TAG, "Failed to update pointsBalance in Firestore for user $userId", e)
            // Consider re-throwing or handling more specifically if needed, currently logged only
        }
    }
}

