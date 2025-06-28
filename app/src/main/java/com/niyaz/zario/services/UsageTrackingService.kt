package com.niyaz.zario.services // Ensure correct package


// --- Android & Core Imports ---
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat


// --- App Specific Imports ---
import com.niyaz.zario.MainActivity
import com.niyaz.zario.R
import com.niyaz.zario.StudyPhase
import com.niyaz.zario.data.local.AppDatabase
import com.niyaz.zario.data.local.UsageStatDao
import com.niyaz.zario.data.local.UsageStatEntity
import com.niyaz.zario.utils.AppInfoHelper
import com.niyaz.zario.utils.Constants // Import Constants
import com.niyaz.zario.utils.StudyStateManager


// --- Coroutine Imports ---
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit


class UsageTrackingService : Service() {


    // --- Coroutine Scope & Dependencies ---
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob) // IO for DB/State access
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var usageStatDao: UsageStatDao
    private lateinit var notificationManager: NotificationManagerCompat


    // --- Tracking State ---
    private var lastQueryEndTime: Long = 0L
    private var appInForegroundAtLastCheck: String? = null
    private var timestampOfLastForegroundEvent: Long = 0L


    // --- State for Intervention Notifications & Daily Tracking ---
    private var targetAppPackage: String? = null
    private var dailyGoalMs: Long? = null
    private var currentDayStartMs: Long = 0L // Start of the current tracking day (midnight)
    private var accumulatedUsageTodayMs: Long = 0L // Accumulated usage for target app today
    private var warnedAt90Percent = false // Flag: Already shown 90% warning today?
    private var warnedAt100Percent = false // Flag: Already shown 100% warning today?
    private var shownDailyFeedback = false // Flag: Shown the initial daily feedback today?


    companion object {
        private const val TAG = "UsageTrackingService"
        // Use constants for channel ID and notification IDs
        private const val NOTIFICATION_CHANNEL_ID = Constants.USAGE_TRACKING_CHANNEL_ID
        private const val FOREGROUND_NOTIFICATION_ID = Constants.USAGE_TRACKING_FOREGROUND_NOTIF_ID
        private const val DAILY_FEEDBACK_NOTIFICATION_ID = Constants.USAGE_TRACKING_DAILY_FEEDBACK_NOTIF_ID
        private const val USAGE_WARNING_90_NOTIFICATION_ID = Constants.USAGE_TRACKING_WARN_90_NOTIF_ID
        private const val USAGE_LIMIT_100_NOTIFICATION_ID = Constants.USAGE_TRACKING_LIMIT_100_NOTIF_ID
        // Use constants for interval and min duration
        private val TRACKING_INTERVAL_MS = Constants.USAGE_TRACKING_INTERVAL_MS
        private const val MIN_DB_SAVE_DURATION_MS = Constants.USAGE_TRACKING_MIN_SAVE_DURATION_MS
    }


    // --- Service Lifecycle ---
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        try {
            usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val database = AppDatabase.getDatabase(applicationContext)
            usageStatDao = database.usageStatDao()
            notificationManager = NotificationManagerCompat.from(this)


            val initTime = System.currentTimeMillis()
            lastQueryEndTime = initTime
            timestampOfLastForegroundEvent = initTime
            appInForegroundAtLastCheck = null // Will determine on first proper query


            // Load initial intervention state and reset daily counters if needed
            loadInterventionState(true) // Pass true to potentially load today's usage from DB


            createNotificationChannel() // Ensure channel exists
            // Use constant for notification ID
            startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
            Log.d(TAG, "Dependencies initialized and service started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error during onCreate initialization: ${e.message}", e)
            stopSelf() // Stop if critical initialization fails
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand received.")
        // Reload intervention state in case goal was just set or changed
        loadInterventionState(false) // Don't reload from DB here, just refresh state vars


        if (serviceScope.isActive && serviceJob.children.count() > 0) {
            Log.w(TAG, "Tracking loop already running. Ignoring redundant start command.")
            return START_STICKY
        }
        serviceScope.launch { trackUsage() }
        return START_STICKY
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        serviceJob.cancel() // Cancel all coroutines
        Log.d(TAG, "Service scope cancelled.")
        // Consider stopping foreground explicitly: stopForeground(STOP_FOREGROUND_REMOVE)
    }


    override fun onBind(intent: Intent?): IBinder? = null // Not binding


    // --- State Loading ---
    /**
     * Loads target app, goal, and current day start time from SharedPreferences.
     * Resets daily accumulated usage and notification flags if the day has changed.
     * Optionally attempts to load today's accumulated usage from the DB on initial load.
     */
    private fun loadInterventionState(loadFromDbOnNewDay: Boolean) {
        Log.d(TAG, "Loading/Refreshing Intervention State. Load from DB on new day: $loadFromDbOnNewDay") // Add logging clarity


        // --- 1. Store Previous State for Comparison ---
        val previousTarget = targetAppPackage
        val previousGoal = dailyGoalMs


        // --- 2. Load Current Persisted State ---
        // Retrieves target app and goal from StudyStateManager (SharedPreferences)
        targetAppPackage = StudyStateManager.getTargetApp(applicationContext)
        dailyGoalMs = StudyStateManager.getDailyGoalMs(applicationContext)


        // --- 3. Check if Target App or Goal Changed Mid-Day ---
        if (targetAppPackage != previousTarget || dailyGoalMs != previousGoal) {
            Log.i(TAG, "Intervention state (target/goal) changed: Target=$targetAppPackage, Goal=$dailyGoalMs ms")
            // Reset daily accumulator if target/goal changes mid-day to avoid mixing data
            accumulatedUsageTodayMs = 0L
            warnedAt90Percent = false
            warnedAt100Percent = false
            // Note: shownDailyFeedback flag is based on the *previous* day's outcome,
            // so it is NOT reset here, only on actual day change.
        }


        // --- 4. Check for Day Rollover ---
        val todayStart = getStartOfDayTimestamp(System.currentTimeMillis())
        if (todayStart != currentDayStartMs) {
            Log.i(TAG,"New day detected. Previous day start: $currentDayStartMs, New day start: $todayStart")


            // --- 4a. Reset Daily Counters and Notification Flags ---
            currentDayStartMs = todayStart
            accumulatedUsageTodayMs = 0L // Reset accumulated usage for the new day
            warnedAt90Percent = false    // Reset 90% warning flag
            warnedAt100Percent = false   // Reset 100% limit flag
            shownDailyFeedback = false   // Reset daily feedback flag (new day, needs new trigger)


            // --- 4b. Optional: Load Initial Usage from DB ---
            // If the service restarted or this is the first check of a new day,
            // try to load any usage that might have been recorded for today
            // before this state load occurred (e.g., between midnight and service start).
            if (loadFromDbOnNewDay && targetAppPackage != null) {
                // Launch background coroutine for DB read
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // --- Get current userId for the DAO call ---
                        val currentUserId = StudyStateManager.getUserId(applicationContext)
                        if (currentUserId == null) {
                            Log.e(TAG, "Cannot load initial usage from DB: User ID is null.")
                            return@launch // Exit coroutine if no user ID
                        }
                        // --- End Get userId ---


                        Log.d(TAG, "Attempting to load initial usage for today ($currentDayStartMs) from DB for $targetAppPackage")
                        // --- Corrected DAO Call: Pass userId, packageName, dayTimestamp ---
                        val usageTodaySoFar = usageStatDao.getTotalDurationForAppOnDay(
                            userId = currentUserId,
                            packageName = targetAppPackage!!, // Now correct position
                            dayTimestamp = currentDayStartMs  // Now correct position
                        ) ?: 0L
                        // --- End Corrected DAO Call ---


                        if (usageTodaySoFar > 0) {
                            Log.d(TAG, "Loaded ${usageTodaySoFar / 1000.0}s of initial usage for today from DB.")
                            // Update the main accumulator on the service's scope
                            withContext(serviceScope.coroutineContext) {
                                accumulatedUsageTodayMs = usageTodaySoFar
                                // Immediately re-check notifications based on potentially loaded value
                                dailyGoalMs?.let { checkUsageLimitNotifications(accumulatedUsageTodayMs, it) }
                            }
                        } else {
                            Log.d(TAG, "No initial usage found in DB for today.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading today's initial usage from DB", e)
                    }
                } // End CoroutineScope launch
            } // End Optional DB Load


        } // End Day Change Check


        // --- 5. Log Final State After Load/Refresh ---
        Log.d(TAG, "State Loaded/Refreshed: Target=$targetAppPackage, Goal=$dailyGoalMs ms, DayStart=$currentDayStartMs, AccumUsage=${accumulatedUsageTodayMs}ms, Warn90=$warnedAt90Percent, Warn100=$warnedAt100Percent, FeedbackShown=$shownDailyFeedback")
    }


    // --- Notification Setup ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            // Use stringResource for channel name and description
            val channelName = getString(R.string.notification_channel_name)
            val channelDescription = getString(R.string.notification_channel_description)
            // Use constant for channel ID
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, importance).apply {
                description = channelDescription
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            Log.d(TAG,"Notification channel created or verified: $NOTIFICATION_CHANNEL_ID")
        }
    }


    /** Creates the persistent notification for the foreground service itself. */
    private fun createForegroundNotification(): Notification {
        val notificationIcon = R.drawable.ic_tracking_notification // Use your drawable icon
        // Use stringResource for title and text
        val title = getString(R.string.notification_foreground_title)
        val text = getString(R.string.notification_foreground_text)


        // Use constant for channel ID
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title) // Use localized title
            .setContentText(text)   // Use localized text
            .setSmallIcon(notificationIcon)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Keep low priority for foreground service itself
            .setOngoing(true) // Makes it non-dismissible
            .build()
    }


    /** Shows the specific intervention-related notifications (Daily Feedback, Warnings). */
    private fun showInterventionNotification(id: Int, title: String, message: String) {
        // Intent to open MainActivity when notification is clicked
        val intent = Intent(this, MainActivity::class.java).apply {
            // Flags bring the existing task to front or start new if needed
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK // Or FLAG_ACTIVITY_SINGLE_TOP? Test needed.
            // TODO: Add extras if needed to navigate to specific Composable in MainActivity
            // putExtra("destination_route", Screen.History.route) // Example
        }
        // Unique request code per notification ID is important for PendingIntent uniqueness
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            id, // Use notification ID as request code
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT // Update current if intent changes
        )


        // Use constant for channel ID
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracking_notification) // Use your drawable icon
            .setContentTitle(title) // Title is passed in, often constructed with stringResource
            .setContentText(message) // Message is passed in, often constructed with stringResource
            .setStyle(NotificationCompat.BigTextStyle().bigText(message)) // Show full message text
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Default priority for user updates
            .setContentIntent(pendingIntent) // Intent to launch on tap
            .setAutoCancel(true) // Dismiss notification when tapped
            .build()


        // Check for notification permission before posting (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        {
            Log.w(TAG, "Cannot show notification ID $id: POST_NOTIFICATIONS permission not granted.")
            // We cannot request permission from a service. The Activity should handle it.
            return
        }


        try {
            notificationManager.notify(id, notification) // Use unique ID
            Log.d(TAG, "Showing notification ID $id: '$title'")
        } catch (e: SecurityException) {
            // This might happen on some devices even if permission seems granted initially
            Log.e(TAG, "SecurityException showing notification ID $id: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification ID $id: ${e.message}", e)
        }
    }




    // --- Tracking & Notification Logic ---
    private suspend fun trackUsage() {
        // Use constant for interval logging
        Log.i(TAG, "Starting usage tracking loop. Interval: ${TRACKING_INTERVAL_MS / 1000} seconds.")
        try {
            while (currentCoroutineContext().isActive) {
                val currentTime = System.currentTimeMillis()
                val intervalStartTime = lastQueryEndTime
                val intervalEndTime = currentTime


                // --- Check for Day Change & Reload State ---
                // Handle potential day rollover before processing the interval
                if (getStartOfDayTimestamp(currentTime) != currentDayStartMs) {
                    Log.d(TAG,"Day change detected during loop.")
                    loadInterventionState(true) // Reload state and reset daily counters
                }


                // Proceed only if interval is valid
                if (intervalEndTime > intervalStartTime) {
                    // Process events and get usage data for the interval
                    val intervalUsageData = processUsageEventsForInterval(intervalStartTime, intervalEndTime)


                    // --- Save Interval Data to DB ---
                    if (intervalUsageData.entities.isNotEmpty()) {
                        insertUsageData(intervalUsageData.entities)
                    }


                    // --- Update Daily Accumulator & Check Notifications ---
                    val phase = StudyStateManager.getStudyPhase(applicationContext)
                    val isInterventionPhase = phase.name.startsWith("INTERVENTION")
                    val targetAppSet = targetAppPackage != null // Renamed for clarity from currentTargetAppPackage check below
                    val goalSet = dailyGoalMs != null


                    if (isInterventionPhase && targetAppSet && goalSet) {
                        // Update today's total usage from the interval data map
                        val usageInInterval = intervalUsageData.usageMap[targetAppPackage!!] ?: 0L // Use non-null asserted target
                        if (usageInInterval > 0) {
                            // Add usage from this interval to today's running total
                            accumulatedUsageTodayMs += usageInInterval
                            Log.d(TAG, "Target app usage in interval: ${usageInInterval / 1000.0}s. Today's total: ${accumulatedUsageTodayMs / 1000.0}s")
                        }


                        // --- Check for Daily Feedback Notification (FR-050) ---
                        // Condition: Target app was foreground AND feedback not shown today
                        if (!shownDailyFeedback && intervalUsageData.foregroundApp == targetAppPackage) {
                            handleDailyFeedbackNotification()
                            shownDailyFeedback = true // Mark as shown for today
                        }


                        // --- Check for Usage Limit Notifications (FR-051, FR-052) ---
                        checkUsageLimitNotifications(accumulatedUsageTodayMs, dailyGoalMs!!) // Use non-null asserted goal


                    } else {
                        // Log only if intervention phase active but target/goal missing (shouldn't normally happen)
                        // Keep original logic here: Log if in intervention phase but target OR goal is missing.
                        if (isInterventionPhase) {
                            Log.w(TAG, "In intervention phase but missing target/goal (TargetSet: $targetAppSet, GoalSet: $goalSet). Skipping notification checks.")
                        }
                    }


                    // Update the end time marker for the next query
                    lastQueryEndTime = intervalEndTime
                } else {
                    Log.w(TAG, "Current time ($intervalEndTime) <= last query end time ($intervalStartTime). Skipping query to avoid issues.")
                    // Advance timestamp to prevent potential infinite loops if clock goes backward slightly
                    lastQueryEndTime = intervalEndTime
                }
                // Wait for the defined interval before the next check
                // Use constant for delay
                delay(TRACKING_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Usage tracking loop cancelled.")
        } catch (e: Exception) {
            Log.e(TAG, "Error within tracking loop: ${e.message}", e)
            // Consider more robust error handling, e.g., stopping service if UsageStatsManager fails repeatedly
        } finally {
            Log.i(TAG, "Usage tracking loop finishing.")
        }
    }


    /** Helper data class to return multiple pieces of info from event processing */
    private data class IntervalProcessingResult(
        val entities: List<UsageStatEntity> = emptyList(),
        val usageMap: Map<String, Long> = emptyMap(), // Map of Pkg -> DurationMs for *this interval*
        val foregroundApp: String? = null // Which app was foreground at the end of processing this interval
    )


    /** Processes events for an interval, returning entities, usage map, and last foreground app. */
    private fun processUsageEventsForInterval(intervalStartTime: Long, intervalEndTime: Long): IntervalProcessingResult {
        // --- Query Events ---
        val usageEvents: UsageEvents? = try { usageStatsManager.queryEvents(intervalStartTime, intervalEndTime) }
        catch (e: SecurityException) { Log.e(TAG, "QueryEvents SecurityException: ${e.message}"); return IntervalProcessingResult() }
        catch (e: Exception) { Log.e(TAG, "QueryEvents Exception: ${e.message}"); return IntervalProcessingResult() }
        if (usageEvents == null) { Log.w(TAG, "queryEvents returned null."); return IntervalProcessingResult() }


        // --- Process Events ---
        val intervalUsageMap = mutableMapOf<String, Long>() // Usage durations calculated *within this interval*
        var currentForegroundApp = appInForegroundAtLastCheck // Start with state from previous interval end
        var lastEventTimestamp = timestampOfLastForegroundEvent // Use marker from previous relevant event


        // Carry over duration for app foreground at start of interval
        if (currentForegroundApp != null && lastEventTimestamp < intervalStartTime) {
            val initialDuration = intervalStartTime - lastEventTimestamp
            if (initialDuration > 0) intervalUsageMap[currentForegroundApp] = intervalUsageMap.getOrDefault(currentForegroundApp, 0L) + initialDuration
            lastEventTimestamp = intervalStartTime // Align marker to interval start
        } else if (lastEventTimestamp < intervalStartTime) {
            lastEventTimestamp = intervalStartTime // Align marker if no app was foreground
        }


        val event = UsageEvents.Event()
        var lastProcessedEventTimestamp = lastEventTimestamp // Track latest event time processed *within this loop*


        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val eventTimestamp = event.timeStamp.coerceAtLeast(intervalStartTime) // Clamp event time to interval start


            // Calculate duration for the *previous* state before processing the current event
            if (currentForegroundApp != null) {
                val segmentStartTime = maxOf(lastEventTimestamp, intervalStartTime) // Start from interval boundary or last event
                if (eventTimestamp > segmentStartTime) {
                    val duration = eventTimestamp - segmentStartTime
                    intervalUsageMap[currentForegroundApp] = intervalUsageMap.getOrDefault(currentForegroundApp, 0L) + duration
                }
            }


            // Update state based on the current event
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    currentForegroundApp = event.packageName
                    lastEventTimestamp = eventTimestamp // Mark the start time
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (event.packageName == currentForegroundApp) {
                        currentForegroundApp = null // App went to background
                    }
                    // Always update lastEventTimestamp even if a different app went BG
                    lastEventTimestamp = maxOf(lastEventTimestamp, eventTimestamp)
                }
                // Other events just update the timestamp marker to keep duration calculation accurate
                else -> {
                    lastEventTimestamp = maxOf(lastEventTimestamp, eventTimestamp)
                }
            }
            lastProcessedEventTimestamp = maxOf(lastProcessedEventTimestamp, eventTimestamp) // Track latest event processed
        }


        // --- Handle App Still in Foreground at End of Interval ---
        val finalSegmentStartTime = maxOf(lastEventTimestamp, intervalStartTime)
        if (currentForegroundApp != null && intervalEndTime > finalSegmentStartTime) {
            val finalDuration = intervalEndTime - finalSegmentStartTime
            if (finalDuration > 0) intervalUsageMap[currentForegroundApp] = intervalUsageMap.getOrDefault(currentForegroundApp, 0L) + finalDuration
            timestampOfLastForegroundEvent = intervalEndTime // Update service state marker
        } else {
            // If no app FG or last event was at/after end time, update marker
            timestampOfLastForegroundEvent = maxOf(lastProcessedEventTimestamp, intervalEndTime)
        }
        // Update service state for next interval
        appInForegroundAtLastCheck = currentForegroundApp


        // --- Convert to Entities (Filter by MIN_DB_SAVE_DURATION_MS) ---
        val entities = mutableListOf<UsageStatEntity>()
        val dayTimestamp = getStartOfDayTimestamp(intervalStartTime)
        // Get current user ID (might need to read from StudyStateManager if not readily available)
        // IMPORTANT: Service runs independently, need reliable way to get current userId.
        // Reading from StudyStateManager (Prefs) is feasible but ensure it's up-to-date.
        val currentUserId = StudyStateManager.getUserId(applicationContext) // Get userId here


        // Check if userId is available before creating entities
        if (currentUserId == null) {
            Log.e(TAG, "Cannot create UsageStatEntity: User ID is null in service.")
            // Return empty result or handle error appropriately
            return IntervalProcessingResult(usageMap = intervalUsageMap, foregroundApp = currentForegroundApp) // Return partial result
        }


        intervalUsageMap.forEach { (pkg, duration) ->
            // Use constant for minimum save duration
            if (duration >= MIN_DB_SAVE_DURATION_MS) { // Only save significant chunks to DB
                // Add userId and set isSynced to false
                entities.add(
                    UsageStatEntity(
                        // id = 0 (auto-generated) // Keep default
                        userId = currentUserId,         // Add the userId
                        packageName = pkg,
                        durationMs = duration,
                        intervalStartTimestamp = intervalStartTime,
                        intervalEndTimestamp = intervalEndTime,
                        dayTimestamp = dayTimestamp,
                        isSynced = false                // Explicitly set to false
                    )
                )
            }
        }
        if (entities.isNotEmpty()) Log.d(TAG,"Prepared ${entities.size} entities for DB insertion from interval.")


        return IntervalProcessingResult(entities, intervalUsageMap, currentForegroundApp)
    }


    /** Inserts usage data into Room DB. */
    private suspend fun insertUsageData(entities: List<UsageStatEntity>) {
        if (entities.isEmpty()) return // Skip if nothing to insert
        try {
            usageStatDao.insertAllUsageStats(entities)
            Log.i(TAG, "Successfully inserted ${entities.size} usage stat records.")
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting usage data into database: ${e.message}", e)
        }
    }


    /** Gets start of day timestamp. */
    private fun getStartOfDayTimestamp(timestamp: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }


    // --- Notification Helper Functions ---


    /** Checks if daily feedback notification needs to be shown. */
    private fun handleDailyFeedbackNotification() {
        // Ensure we have the outcome from the previous day's check
        val (lastCheckTime, lastGoalReached, lastPointsChange) = StudyStateManager.getLastDailyOutcome(applicationContext)
        val todayStart = getStartOfDayTimestamp(System.currentTimeMillis())
        // Check timestamp corresponds to roughly the last 24 hours (covers worker running slightly late/early)
        val validOutcomeTime = lastCheckTime != null && lastCheckTime >= todayStart - TimeUnit.DAYS.toMillis(1) - TimeUnit.HOURS.toMillis(6) // Allow some buffer


        if (validOutcomeTime && lastGoalReached != null && lastPointsChange != null) {
            Log.d(TAG, "Attempting to show daily feedback notification.")
            val title = getString(R.string.notification_daily_feedback_title) // Use stringResource
            val points = lastPointsChange // Use the actual change value
            val pointsUnit = getString(R.string.points_unit) // Get points unit
            val message = if (lastGoalReached) {
                // Use stringResource for success message
                getString(R.string.notification_daily_feedback_success, points, pointsUnit)
            } else {
                // Use stringResource for fail message, display points loss as positive
                getString(R.string.notification_daily_feedback_fail, -points, pointsUnit)
            }
            val currentPoints = StudyStateManager.getPointsBalance(applicationContext)
            // Use stringResource for balance suffix
            val fullMessage = message + " " + getString(R.string.notification_daily_feedback_balance_suffix, currentPoints, pointsUnit)


            // Use constant for notification ID
            showInterventionNotification(DAILY_FEEDBACK_NOTIFICATION_ID, title, fullMessage)
        } else {
            Log.w(TAG,"Skipping daily feedback: No valid outcome data found for previous day (LastCheck: $lastCheckTime, TodayStart: $todayStart).")
        }
    }


    /** Checks accumulated usage against goal and triggers 90%/100% notifications. */
    @SuppressLint("StringFormatMatches")
    private fun checkUsageLimitNotifications(accumulatedMs: Long, goalMs: Long) {
        // Avoid division by zero or issues if goal is invalid
        if (goalMs <= 0) {
            Log.w(TAG, "checkUsageLimitNotifications skipped: Invalid goalMs ($goalMs)")
            return
        }


        // Capture current value and check for null
        val currentTargetAppPackage = targetAppPackage
        if (currentTargetAppPackage == null) {
            Log.e(TAG, "checkUsageLimitNotifications skipped: targetAppPackage is null unexpectedly.")
            return // Use return instead of elvis operator from <change> for clarity
        }


        val usagePercent = (accumulatedMs.toDouble() / goalMs.toDouble()) * 100.0
        // Use AppInfoHelper for consistent app name fetching
        val targetAppName = AppInfoHelper.getAppName(applicationContext, currentTargetAppPackage)
        val pointsUnit = getString(R.string.points_unit) // Get points unit


        // 90% Warning (FR028)
        if (usagePercent >= 90 && !warnedAt90Percent) {
            val title = getString(R.string.notification_warning_90_title) // Use stringResource
            val remainingMs = (goalMs - accumulatedMs).coerceAtLeast(0)
            val remainingMinutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
            val goalMinutesTotal = TimeUnit.MILLISECONDS.toMinutes(goalMs)
            val usedMinutes = TimeUnit.MILLISECONDS.toMinutes(accumulatedMs)
            // Use stringResource for the message
            val message = getString(R.string.notification_warning_90_message, usedMinutes, goalMinutesTotal, targetAppName, remainingMinutes)


            // Use constant for notification ID
            showInterventionNotification(USAGE_WARNING_90_NOTIFICATION_ID, title, message)
            warnedAt90Percent = true // Set flag so it only shows once per day
            Log.i(TAG,"Showing 90% usage warning notification.")
        }


        // 100% Limit Reached (FR029)
        if (usagePercent >= 100 && !warnedAt100Percent) {
            val title = getString(R.string.notification_limit_100_title) // Use stringResource
            val goalMinutes = TimeUnit.MILLISECONDS.toMinutes(goalMs)


            val condition = StudyStateManager.getCondition(applicationContext)
            // Use stringResource for consequence messages
            // Use constant for points value comparison
            val pointConsequence = when(condition) {
                StudyPhase.INTERVENTION_CONTROL -> getString(R.string.notification_limit_100_consequence_control)
                StudyPhase.INTERVENTION_DEPOSIT -> getString(R.string.notification_limit_100_consequence_deposit, Constants.DEFAULT_DEPOSIT_LOSE_POINTS, pointsUnit) // Use Constant
                StudyPhase.INTERVENTION_FLEXIBLE -> {
                    val (_, lose) = StudyStateManager.getFlexStakes(applicationContext)
                    // Use constants for range check
                    if (lose != null && lose > Constants.FLEX_STAKES_MIN_LOSE) getString(R.string.notification_limit_100_consequence_flex_lose, lose, pointsUnit)
                    else if (lose == Constants.FLEX_STAKES_MIN_LOSE) getString(R.string.notification_limit_100_consequence_flex_no_lose) // Check against MIN_LOSE (0)
                    else getString(R.string.notification_limit_100_consequence_fallback) // Fallback if lose is null or < 0 (shouldn't happen)
                }
                else -> getString(R.string.notification_limit_100_consequence_fallback) // Fallback for other phases
            }


            // Construct the final message using stringResource
            val message = getString(R.string.notification_limit_100_message, goalMinutes, targetAppName, pointConsequence)


            // Use constant for notification ID
            showInterventionNotification(USAGE_LIMIT_100_NOTIFICATION_ID, title, message)
            warnedAt100Percent = true // Set flag so it only shows once per day
            Log.i(TAG,"Showing 100% usage limit notification with consequence.")
        }
    }


} // End of UsageTrackingService class