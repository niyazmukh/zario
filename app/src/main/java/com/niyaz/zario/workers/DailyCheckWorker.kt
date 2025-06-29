package com.niyaz.zario.workers


import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.R
import com.niyaz.zario.data.local.AppDatabase
import com.niyaz.zario.utils.Constants
import com.niyaz.zario.utils.StudyPhase
import com.niyaz.zario.utils.StudyStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.niyaz.zario.MainActivity


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
                val studyStartTimestamp = StudyStateManager.getStudyStartTimestamp(applicationContext)
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


                // 1. Calculate Timestamps for Previous Interval
                val (intervalStart, intervalEnd) = getPreviousEvaluationInterval(currentTime)

                // If the worker runs at the very start of a new interval, there is no
                // "previous" interval to check yet. In this case, we can just succeed.
                if (intervalStart == null || intervalEnd == null) {
                    Log.i(TAG, "Worker ran at the beginning of a new interval. No completed interval to check yet. Skipping.")
                    return@withContext Result.success()
                }

                // --- ADDED: Check if this interval has already been processed ---
                val lastCheckTime = StudyStateManager.getLastIntervalOutcome(applicationContext).first
                if (lastCheckTime != null && lastCheckTime >= intervalEnd) {
                    Log.w(TAG, "This interval (ending at $intervalEnd) has likely already been processed (last check at $lastCheckTime). Skipping to prevent double-counting.")
                    return@withContext Result.success()
                }
                // --- END ADDED CHECK ---

                // *** FIX: Adjust start time to respect the intervention start ***
                // The query should not start before the study/intervention began.
                // This prevents including usage from the baseline period in the first evaluation.
                val actualStartTime = maxOf(intervalStart, studyStartTimestamp)
                Log.d(TAG, "Original interval start: $intervalStart, Study start: $studyStartTimestamp, Using actual start: $actualStartTime")


                // 2. Query Database for Previous Interval's Usage
                val dao = AppDatabase.getDatabase(applicationContext).usageStatDao()
                val totalUsageMs = dao.getTotalDurationForAppInRange(
                    userId = userId,
                    packageName = targetApp,
                    startTime = actualStartTime, // *** USE THE CORRECTED START TIME ***
                    endTime = intervalEnd
                ) ?: 0L
                Log.i(TAG, "Previous interval (from $actualStartTime to $intervalEnd) total usage for $targetApp: ${totalUsageMs / 1000.0} seconds")


                // 3. Compare Usage to Goal
                // The goal is daily, so we must scale it to the interval
                val intervalDurationMinutes = TimeUnit.MILLISECONDS.toMinutes(intervalEnd - actualStartTime)
                val dailyGoalScaledToInterval = (dailyGoalMs.toDouble() / (24 * 60) * intervalDurationMinutes).toLong()
                val goalReached = totalUsageMs <= dailyGoalScaledToInterval
                Log.i(TAG, "Interval Goal ($dailyGoalScaledToInterval ms for $intervalDurationMinutes mins): ${if(goalReached) "REACHED" else "MISSED"}")


                // 4. Determine Points Change based on Condition
                // Pass fetched stakes to the calculation function
                val pointsChange = calculatePointsChange(condition, goalReached, flexEarn, flexLose)


                // *** ALIGNMENT CHANGE: Coerce points between MIN_POINTS and MAX_POINTS using Constants ***
                val newPoints = (currentPoints + pointsChange).coerceIn(Constants.MIN_POINTS, Constants.MAX_POINTS) // Use constants for point bounds
                Log.i(TAG, "Condition: $condition, Goal Reached: $goalReached -> Points change: $pointsChange, New Balance: $newPoints (Coerced)")


                // --- ALIGNMENT CHANGE: Save outcome with the END timestamp of the interval ---
                StudyStateManager.saveIntervalOutcome(applicationContext, intervalEnd, goalReached, pointsChange)
                // --- End Save Daily Outcome ---


                // 5. Update Points (Local & Firestore) if changed
                if (newPoints != currentPoints) {
                    StudyStateManager.savePointsBalance(applicationContext, newPoints)
                    updatePointsInFirestore(userId, newPoints) // Removed !! assertion as validation ensures not null
                } else {
                    Log.d(TAG,"Points balance unchanged.")
                }

                // --- NEW: Show notification about the outcome ---
                showPointsNotification(pointsChange, goalReached, newPoints)


                Log.d(TAG, "Worker finished successfully.")
                Result.success()


            } catch (e: Exception) {
                Log.e(TAG, "Worker failed", e)
                Result.retry()
            }
        } // End withContext
    }


    /**
     * Shows a notification to the user about the result of the last evaluation interval.
     */
    private fun showPointsNotification(pointsChange: Int, goalReached: Boolean, newPoints: Int) {
        val notificationManager = NotificationManagerCompat.from(applicationContext)
        createNotificationChannel(notificationManager)

        val title = applicationContext.getString(R.string.notification_daily_feedback_title)
        val pointsUnit = applicationContext.getString(R.string.points_unit)

        val message = if (goalReached) {
            applicationContext.getString(R.string.notification_daily_feedback_success, pointsChange, pointsUnit)
        } else {
            // Show loss as a positive number
            applicationContext.getString(R.string.notification_daily_feedback_fail, -pointsChange, pointsUnit)
        }
        val fullMessage = message + " " + applicationContext.getString(R.string.notification_daily_feedback_balance_suffix, newPoints, pointsUnit)

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            applicationContext,
            Constants.USAGE_TRACKING_DAILY_FEEDBACK_NOTIF_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, Constants.USAGE_TRACKING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracking_notification)
            .setContentTitle(title)
            .setContentText(fullMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullMessage))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        {
            Log.w(TAG, "Cannot show points notification: POST_NOTIFICATIONS permission not granted.")
            return
        }

        notificationManager.notify(Constants.USAGE_TRACKING_DAILY_FEEDBACK_NOTIF_ID, notification)
        Log.i(TAG, "Showing points feedback notification. Points change: $pointsChange")
    }

    /**
     * Creates the notification channel for study updates, required for Android O and above.
     */
    private fun createNotificationChannel(notificationManager: NotificationManagerCompat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = applicationContext.getString(R.string.notification_channel_name)
            val channelDescription = applicationContext.getString(R.string.notification_channel_description)
            val channel = NotificationChannel(
                Constants.USAGE_TRACKING_CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = channelDescription
            }
            notificationManager.createNotificationChannel(channel)
        }
    }


    /**
     * Calculates the start and end timestamps for the most recently completed evaluation interval.
     * This ensures the worker evaluates fixed, non-overlapping time blocks.
     *
     * @param currentTime The current time used to determine which interval is the "previous" one.
     * @return A Pair of (startTime, endTime) or (null, null) if no complete interval has passed.
     */
    private fun getPreviousEvaluationInterval(currentTime: Long): Pair<Long?, Long?> {
        val intervalMinutes = Constants.DAILY_CHECK_INTERVAL_MINUTES
        if (intervalMinutes <= 0) {
            Log.e(TAG, "Daily check interval is zero or negative. Cannot determine interval.")
            return Pair(null, null)
        }
        val intervalMillis = TimeUnit.MINUTES.toMillis(intervalMinutes)

        // Calculate the end of the *current* interval slice
        val currentIntervalEnd = (currentTime / intervalMillis + 1) * intervalMillis

        // The previous interval is the one that ended right before the current one started
        val previousIntervalEnd = currentIntervalEnd - intervalMillis
        val previousIntervalStart = previousIntervalEnd - intervalMillis

        // Sanity check: If the worker is running very early, the calculated start time
        // might be in the future, which makes no sense. This can happen if the device
        // time is unstable. We also don't evaluate an interval that hasn't fully passed.
        if (previousIntervalStart > currentTime || previousIntervalEnd > currentTime) {
            return Pair(null, null) // No completed interval to evaluate yet
        }

        return Pair(previousIntervalStart, previousIntervalEnd)
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

