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


/**
 * A periodic [CoroutineWorker] responsible for checking the participant's target app usage
 * against their set goal for the *previous interval*.
 * For testing, this interval is 30 minutes. For production, it would be daily.
 * It calculates point changes, updates the points balance, and saves the outcome locally.
 */
class DailyCheckWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {


    companion object {
        private const val TAG = "DailyCheckWorker"
        const val UNIQUE_WORK_NAME = Constants.DAILY_CHECK_WORKER_NAME
    }

    private val studyStateManager = StudyStateManager
    private val usageStatDao: UsageStatDao = AppDatabase.getDatabase(appContext).usageStatDao()
    private val firestore: FirebaseFirestore = Firebase.firestore


    override suspend fun doWork(): Result {
        val workerStartTimestamp = System.currentTimeMillis()
        Log.i(TAG, "Worker starting execution at $workerStartTimestamp...")


        return withContext(Dispatchers.IO) {
            try {
                val userId = studyStateManager.getUserId(applicationContext)
                if (userId == null) {
                    Log.w(TAG, "User ID not found in local state. Worker cannot proceed. Returning Success (non-retriable).")
                    return@withContext Result.success()
                }


                val phase = studyStateManager.getStudyPhase(applicationContext)
                if (!phase.name.startsWith("INTERVENTION")) {
                    Log.i(TAG, "Worker run skipped: User $userId is not in an intervention phase (current: $phase). Returning Success.")
                    return@withContext Result.success()
                }


                Log.i(TAG, "Running interval check for User ID: $userId in Phase: $phase")


                val condition = studyStateManager.getCondition(applicationContext) ?: phase
                val targetApp = studyStateManager.getTargetApp(applicationContext)
                val dailyGoalMs = studyStateManager.getDailyGoalMs(applicationContext)
                val currentPoints = studyStateManager.getPointsBalance(applicationContext)
                val (flexEarn, flexLose) = studyStateManager.getFlexStakes(applicationContext)

                if (targetApp == null) {
                    Log.e(TAG, "Cannot perform check for User $userId: Target app is not set. Returning Success.")
                    return@withContext Result.success()
                }
                if (dailyGoalMs == null || dailyGoalMs <= 0) {
                    Log.e(TAG, "Cannot perform check for User $userId: Goal ($dailyGoalMs) is not set or invalid. Returning Success.")
                    return@withContext Result.success()
                }
                if (condition == StudyPhase.INTERVENTION_FLEXIBLE) {
                    if (flexEarn == null || flexLose == null || flexEarn !in Constants.FLEX_STAKES_MIN_EARN..Constants.FLEX_STAKES_MAX_EARN || flexLose !in Constants.FLEX_STAKES_MIN_LOSE..Constants.FLEX_STAKES_MAX_LOSE) {
                        Log.e(TAG, "Cannot perform check for User $userId: Flexible stakes invalid (Earn: $flexEarn, Lose: $flexLose). Returning Success.")
                        return@withContext Result.success()
                    }
                }

                // 1. Calculate Timestamps for the *Previous Interval* based on the worker's own schedule
                val (intervalStartMs, intervalEndMs) = getPreviousIntervalTimestamps()
                Log.d(TAG,"Checking usage for previous interval from $intervalStartMs to $intervalEndMs")

                // 2. Query Database for Previous Interval's Usage
                // MODIFIED to query a time range instead of a specific day
                val totalUsageMs = usageStatDao.getTotalDurationForAppInRange(
                    userId = userId,
                    packageName = targetApp,
                    startTime = intervalStartMs,
                    endTime = intervalEndMs
                ) ?: 0L
                Log.i(TAG, "User $userId: Previous interval usage for '$targetApp': ${totalUsageMs / 1000.0} seconds")

                // 3. Compare Usage to Goal
                // The "daily goal" is now effectively the "per-interval goal" for this testing setup
                val goalReached = totalUsageMs <= dailyGoalMs
                Log.i(TAG, "User $userId: Interval goal ($dailyGoalMs ms): ${if (goalReached) "MET" else "MISSED"}")

                // 4. Determine Points Change
                val pointsChange = calculatePointsChange(condition, goalReached, flexEarn, flexLose)

                // 5. Calculate and Coerce New Points Balance
                val newPoints = (currentPoints + pointsChange).coerceIn(Constants.MIN_POINTS, Constants.MAX_POINTS)
                Log.i(TAG, "User $userId: Condition=$condition, GoalReached=$goalReached -> Points change: $pointsChange, New Balance: $newPoints (Prev: $currentPoints)")

                // 6. Save Outcome Locally (for notification)
                studyStateManager.saveDailyOutcome(applicationContext, workerStartTimestamp, goalReached, pointsChange)

                // 7. Update Points Balance Locally & Remotely (if changed)
                if (newPoints != currentPoints) {
                    Log.i(TAG, "Points balance changed for User $userId. Updating local state and Firestore...")
                    studyStateManager.savePointsBalance(applicationContext, newPoints)
                    val firestoreUpdateSuccess = updatePointsInFirestore(userId, newPoints)
                    if (!firestoreUpdateSuccess) {
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
                Result.retry()
            }
        }
    }

    /**
     * Calculates the start and end timestamps for the previous check interval.
     * The interval's length is defined by DEFAULT_CHECK_WORKER_INTERVAL_MS.
     * @return A Pair where the first element is the start timestamp and the second is the end timestamp.
     */
    private fun getPreviousIntervalTimestamps(): Pair<Long, Long> {
        val endTimestamp = System.currentTimeMillis()
        val startTimestamp = endTimestamp - Constants.DEFAULT_CHECK_WORKER_INTERVAL_MS
        return Pair(startTimestamp, endTimestamp)
    }

    private fun calculatePointsChange(
        condition: StudyPhase,
        goalReached: Boolean,
        flexEarn: Int?,
        flexLose: Int?
    ): Int {
        return when (condition) {
            StudyPhase.INTERVENTION_CONTROL ->
                if (goalReached) Constants.DEFAULT_CONTROL_EARN_POINTS else Constants.DEFAULT_CONTROL_LOSE_POINTS

            StudyPhase.INTERVENTION_DEPOSIT ->
                if (goalReached) Constants.DEFAULT_DEPOSIT_EARN_POINTS else -Constants.DEFAULT_DEPOSIT_LOSE_POINTS

            StudyPhase.INTERVENTION_FLEXIBLE -> {
                val earn = flexEarn ?: Constants.FLEX_STAKES_MIN_EARN
                val lose = flexLose ?: Constants.FLEX_STAKES_MIN_LOSE
                if (goalReached) earn else -lose
            }
            else -> {
                Log.w(TAG, "Calculating points change for unexpected phase: $condition. Returning 0.")
                0
            }
        }
    }

    private suspend fun updatePointsInFirestore(userId: String, newPoints: Int): Boolean {
        return try {
            firestore.collection(Constants.FIRESTORE_COLLECTION_USERS).document(userId)
                .update(Constants.FIRESTORE_FIELD_POINTS_BALANCE, newPoints.toLong())
                .await()
            Log.d(TAG, "Successfully updated pointsBalance to $newPoints in Firestore for user $userId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update pointsBalance in Firestore for user $userId", e)
            false
        }
    }
}