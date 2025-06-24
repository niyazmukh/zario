package com.niyaz.zario.workers


import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.MainActivity
import com.niyaz.zario.R
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
 * It calculates point changes, updates the points balance, saves the outcome locally,
 * and now also directly triggers the feedback notification to the user.
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
    private val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(applicationContext)
    }


    override suspend fun doWork(): Result {
        val workerStartTimestamp = System.currentTimeMillis()
        Log.i(TAG, "Worker starting execution at $workerStartTimestamp...")

        // Ensure notification channel exists before trying to post a notification
        createNotificationChannel()

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

                if (targetApp == null || dailyGoalMs == null || dailyGoalMs <= 0) {
                    Log.e(TAG, "Cannot perform check for User $userId: Target app or goal is not set correctly. Returning Success.")
                    return@withContext Result.success()
                }

                val (intervalStartMs, intervalEndMs) = getPreviousIntervalTimestamps()
                Log.d(TAG,"Checking usage for previous interval from $intervalStartMs to $intervalEndMs")

                val totalUsageMs = usageStatDao.getTotalDurationForAppInRange(
                    userId = userId,
                    packageName = targetApp,
                    startTime = intervalStartMs,
                    endTime = intervalEndMs
                ) ?: 0L
                Log.i(TAG, "User $userId: Previous interval usage for '$targetApp': ${totalUsageMs / 1000.0} seconds")

                val goalReached = totalUsageMs <= dailyGoalMs
                Log.i(TAG, "User $userId: Interval goal ($dailyGoalMs ms): ${if (goalReached) "MET" else "MISSED"}")

                val pointsChange = calculatePointsChange(condition, goalReached, flexEarn, flexLose)
                val newPoints = (currentPoints + pointsChange).coerceIn(Constants.MIN_POINTS, Constants.MAX_POINTS)
                Log.i(TAG, "User $userId: Condition=$condition, GoalReached=$goalReached -> Points change: $pointsChange, New Balance: $newPoints (Prev: $currentPoints)")

                studyStateManager.saveDailyOutcome(applicationContext, workerStartTimestamp, goalReached, pointsChange)

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

                // --- NEW: Trigger notification directly from the worker ---
                showFeedbackNotification(goalReached, pointsChange, newPoints)

                Log.i(TAG, "Worker finished successfully for User $userId.")
                Result.success()

            } catch (e: Exception) {
                Log.e(TAG, "Worker execution failed with unexpected error.", e)
                Result.retry()
            }
        }
    }

    /**
     * Shows a notification summarizing the result of the interval evaluation.
     */
    private fun showFeedbackNotification(goalReached: Boolean, pointsChange: Int, currentBalance: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        {
            Log.w(TAG, "Cannot show feedback notification: POST_NOTIFICATIONS permission not granted.")
            return
        }

        val title = applicationContext.getString(R.string.notification_daily_feedback_title)
        val pointsUnit = applicationContext.getString(R.string.points_unit)

        val resultMessage = if (goalReached) {
            applicationContext.getString(R.string.notification_daily_feedback_success, pointsChange, pointsUnit)
        } else {
            applicationContext.getString(R.string.notification_daily_feedback_fail, -pointsChange, pointsUnit)
        }
        val fullMessage = resultMessage + " " + applicationContext.getString(R.string.notification_daily_feedback_balance_suffix, currentBalance, pointsUnit)

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            applicationContext,
            Constants.USAGE_TRACKING_DAILY_FEEDBACK_NOTIF_ID, // Use a consistent request code
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

        notificationManager.notify(Constants.USAGE_TRACKING_DAILY_FEEDBACK_NOTIF_ID, notification)
        Log.i(TAG, "Feedback notification has been sent.")
    }

    private fun createNotificationChannel() {
        val channelId = Constants.USAGE_TRACKING_CHANNEL_ID
        val channelName = applicationContext.getString(R.string.notification_channel_name)
        val channelDescription = applicationContext.getString(R.string.notification_channel_description)
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(channelId, channelName, importance).apply {
            description = channelDescription
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

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