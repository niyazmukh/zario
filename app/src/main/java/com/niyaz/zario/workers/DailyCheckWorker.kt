package com.niyaz.zario.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.StudyPhase
import com.niyaz.zario.data.local.AppDatabase
import com.niyaz.zario.data.local.UsageStatDao
import com.niyaz.zario.utils.Constants
import com.niyaz.zario.utils.StudyStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * A periodic [CoroutineWorker] responsible for performing the daily check of the participant's
 * target app usage against their set goal for the *previous* day.
 * It calculates point changes based on the participant's condition, updates the points balance
 * locally and in Firestore, and saves the outcome locally for notification purposes.
 */
class DailyCheckWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        // TAG for logging within this worker
        private const val TAG = "DailyCheckWorker"
        // Unique name for this periodic worker, used for enqueuing and cancellation. Referenced via Constants.
        const val UNIQUE_WORK_NAME = Constants.DAILY_CHECK_WORKER_NAME
    }

    // Dependency instances (Consider Dependency Injection for better testability)
    private val studyStateManager = StudyStateManager
    private val usageStatDao: UsageStatDao = AppDatabase.getDatabase(appContext).usageStatDao()
    private val firestore: FirebaseFirestore = Firebase.firestore

    /**
     * The main work execution method. Performs the daily check logic.
     * Runs primarily on the IO dispatcher due to database and network operations.
     * @return [Result.success] if the check completed (even if no points changed or state prevented action),
     *         [Result.retry] if a transient error occurred (like Firestore update failure),
     *         [Result.failure] for unexpected errors (should ideally not happen with proper checks).
     */
    override suspend fun doWork(): Result {
        val workerStartTimestamp = System.currentTimeMillis() // Timestamp for this worker run
        Log.i(TAG, "Worker starting execution at $workerStartTimestamp...")

        return withContext(Dispatchers.IO) {
            try {
                val userId = studyStateManager.getUserId(applicationContext)
                if (userId == null) {
                    Log.w(TAG, "User ID not found in local state. Worker cannot proceed. Returning Success (non-retriable).")
                    // Not a worker failure, state issue. Don't retry indefinitely.
                    return@withContext Result.success()
                }

                val phase = studyStateManager.getStudyPhase(applicationContext)
                // Worker should only run its core logic during intervention phases
                if (!phase.name.startsWith("INTERVENTION")) {
                    Log.i(TAG, "Worker run skipped: User $userId is not in an intervention phase (current: $phase). Returning Success.")
                    // Not a worker failure, expected state. Don't retry.
                    return@withContext Result.success()
                }

                Log.i(TAG, "Running daily check for User ID: $userId in Phase: $phase")

                // --- Gather necessary state ---
                val condition = studyStateManager.getCondition(applicationContext) ?: phase // Fallback to phase if condition somehow missing (shouldn't happen)
                val targetApp = studyStateManager.getTargetApp(applicationContext)
                val dailyGoalMs = studyStateManager.getDailyGoalMs(applicationContext)
                val currentPoints = studyStateManager.getPointsBalance(applicationContext)
                val (flexEarn, flexLose) = studyStateManager.getFlexStakes(applicationContext)

                // --- Validate required state for processing ---
                if (targetApp == null) {
                    Log.e(TAG, "Cannot perform daily check for User $userId: Target app is not set. Returning Success (configuration issue).")
                    return@withContext Result.success() // Needs user action, don't retry worker
                }
                if (dailyGoalMs == null || dailyGoalMs <= 0) { // Also check goal is positive
                    Log.e(TAG, "Cannot perform daily check for User $userId: Daily goal ($dailyGoalMs) is not set or invalid. Returning Success (configuration issue).")
                    return@withContext Result.success() // Needs user action/valid goal, don't retry worker
                }
                if (condition == StudyPhase.INTERVENTION_FLEXIBLE) {
                    // Validate flex stakes only if in the flexible condition
                    if (flexEarn == null || flexLose == null || flexEarn !in Constants.FLEX_STAKES_MIN_EARN..Constants.FLEX_STAKES_MAX_EARN || flexLose !in Constants.FLEX_STAKES_MIN_LOSE..Constants.FLEX_STAKES_MAX_LOSE) {
                        Log.e(TAG, "Cannot perform daily check for User $userId: Flexible stakes invalid (Earn: $flexEarn, Lose: $flexLose). Returning Success (configuration issue).")
                        return@withContext Result.success() // Needs user action (flex setup), don't retry worker
                    }
                }
                // --- End State Validation ---

                // 1. Calculate Timestamps for the *Previous* Day
                val (prevDayStartMs, _) = getPreviousDayTimestamps()
                Log.d(TAG,"Checking usage for previous day starting at: $prevDayStartMs")

                // 2. Query Database for Previous Day's Usage
                // DAO call includes userId check internally
                val totalUsageMs = usageStatDao.getTotalDurationForAppOnDay(
                    userId = userId,
                    packageName = targetApp,
                    dayTimestamp = prevDayStartMs
                ) ?: 0L // Default to 0 if null (no usage recorded)
                Log.i(TAG, "User $userId: Previous day usage for '$targetApp': ${totalUsageMs / 1000.0} seconds")

                // 3. Compare Usage to Goal
                val goalReached = totalUsageMs <= dailyGoalMs
                Log.i(TAG, "User $userId: Daily goal ($dailyGoalMs ms): ${if (goalReached) "MET" else "MISSED"}")

                // 4. Determine Points Change
                val pointsChange = calculatePointsChange(condition, goalReached, flexEarn, flexLose)

                // 5. Calculate and Coerce New Points Balance
                val newPoints = (currentPoints + pointsChange).coerceIn(Constants.MIN_POINTS, Constants.MAX_POINTS)
                Log.i(TAG, "User $userId: Condition=$condition, GoalReached=$goalReached -> Points change: $pointsChange, New Balance: $newPoints (Prev: $currentPoints)")

                // 6. Save Daily Outcome Locally (for next day's notification)
                studyStateManager.saveDailyOutcome(applicationContext, workerStartTimestamp, goalReached, pointsChange)

                // 7. Update Points Balance Locally & Remotely (if changed)
                if (newPoints != currentPoints) {
                    Log.i(TAG, "Points balance changed for User $userId. Updating local state and Firestore...")
                    studyStateManager.savePointsBalance(applicationContext, newPoints)
                    // Attempt Firestore update and check result
                    val firestoreUpdateSuccess = updatePointsInFirestore(userId, newPoints)
                    if (!firestoreUpdateSuccess) {
                        // If Firestore update fails, we want to retry the worker later
                        Log.e(TAG, "Firestore points update failed for User $userId. Requesting worker retry.")
                        return@withContext Result.retry()
                    }
                    Log.i(TAG, "Points balance successfully updated locally and in Firestore for User $userId.")
                } else {
                    Log.d(TAG, "Points balance unchanged for User $userId. No updates needed.")
                }

                Log.i(TAG, "Worker finished successfully for User $userId.")
                Result.success()

            } catch (e: Exception) {
                Log.e(TAG, "Worker execution failed with unexpected error.", e)
                // For unexpected errors, retry might be appropriate
                Result.retry()
            }
        } // End withContext(Dispatchers.IO)
    }

    /**
     * Calculates the start and end timestamps (epoch milliseconds) for the previous calendar day.
     * @return A Pair where the first element is the start timestamp (00:00:00.000) and
     *         the second is the end timestamp (23:59:59.999) of the previous day.
     */
    private fun getPreviousDayTimestamps(): Pair<Long, Long> {
        val calendar = Calendar.getInstance().apply {
            // Move calendar to yesterday
            add(Calendar.DAY_OF_YEAR, -1)
            // Set to the very start of that day
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTimestamp = calendar.timeInMillis

        // Set to the very end of that same day
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val endTimestamp = calendar.timeInMillis

        return Pair(startTimestamp, endTimestamp)
    }

    /**
     * Calculates the points change based on the user's condition, goal outcome, and flexible stakes.
     * Uses constants for default point values.
     * @param condition The user's assigned intervention [StudyPhase].
     * @param goalReached True if the user met their goal, false otherwise.
     * @param flexEarn The points chosen to earn (for Flexible condition, null otherwise).
     * @param flexLose The points chosen to lose (for Flexible condition, null otherwise).
     * @return The calculated change in points (positive for gain, negative for loss, zero for no change).
     */
    private fun calculatePointsChange(
        condition: StudyPhase,
        goalReached: Boolean,
        flexEarn: Int?, // Nullable, only relevant for FLEXIBLE
        flexLose: Int?  // Nullable, only relevant for FLEXIBLE
    ): Int {
        return when (condition) {
            StudyPhase.INTERVENTION_CONTROL ->
                if (goalReached) Constants.DEFAULT_CONTROL_EARN_POINTS else Constants.DEFAULT_CONTROL_LOSE_POINTS // = 0

            StudyPhase.INTERVENTION_DEPOSIT ->
                if (goalReached) Constants.DEFAULT_DEPOSIT_EARN_POINTS else -Constants.DEFAULT_DEPOSIT_LOSE_POINTS // Subtract the loss amount

            StudyPhase.INTERVENTION_FLEXIBLE -> {
                // Validation should have happened in doWork, but check defensively
                val earn = flexEarn ?: Constants.FLEX_STAKES_MIN_EARN
                val lose = flexLose ?: Constants.FLEX_STAKES_MIN_LOSE
                if (goalReached) earn else -lose // Subtract the chosen loss amount
            }
            else -> {
                // Should not happen if called correctly from doWork
                Log.w(TAG, "Calculating points change for unexpected phase: $condition. Returning 0.")
                0
            }
        }
    }

    /**
     * Updates the 'pointsBalance' field in the specified user's Firestore document.
     * Runs on the calling coroutine's context (expected to be IO).
     * @param userId The ID of the user whose points to update.
     * @param newPoints The new points balance to set.
     * @return `true` if the Firestore update was successful, `false` otherwise.
     */
    private suspend fun updatePointsInFirestore(userId: String, newPoints: Int): Boolean {
        return try {
            // Use constants for collection and field names
            firestore.collection(Constants.FIRESTORE_COLLECTION_USERS).document(userId)
                .update(Constants.FIRESTORE_FIELD_POINTS_BALANCE, newPoints.toLong()) // Use Long for Firestore numbers
                .await() // Wait for completion
            Log.d(TAG, "Successfully updated pointsBalance to $newPoints in Firestore for user $userId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update pointsBalance in Firestore for user $userId", e)
            false // Indicate failure
        }
    }
}