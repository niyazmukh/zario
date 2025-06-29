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
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase


// --- App Specific Imports ---
import com.niyaz.zario.MainActivity
import com.niyaz.zario.R
import com.niyaz.zario.utils.StudyPhase
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


    // --- State for Intervention Notifications & Interval Tracking ---
    private var targetAppPackage: String? = null
    private var dailyGoalMs: Long? = null // This is the goal for a full 24h period
    private var currentIntervalStartMs: Long = 0L // Start of the current tracking interval
    private var accumulatedUsageInIntervalMs: Long = 0L // Accumulated usage for target app in the current interval
    private var warnedAt90Percent = false // Flag: Already shown 90% warning in this interval?
    private var warnedAt100Percent = false // Flag: Already shown 100% warning in this interval?


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
        private val EVALUATION_INTERVAL_MINUTES = Constants.DAILY_CHECK_INTERVAL_MINUTES
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
            loadInterventionState(true) // Pass true to potentially load this interval's usage from DB


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
     * Loads target app, goal, and current interval start time.
     * Resets interval-based accumulated usage and notification flags if the interval has changed.
     * Optionally attempts to load the current interval's accumulated usage from the DB on initial load.
     */
    private fun loadInterventionState(loadFromDbOnNewInterval: Boolean) {
        Log.d(TAG, "Loading/Refreshing Intervention State. Load from DB on new interval: $loadFromDbOnNewInterval") // Add logging clarity


        // --- 1. Store Previous State for Comparison ---
        val previousTarget = targetAppPackage
        val previousGoal = dailyGoalMs
        var isNewGoalJustSet = false // Flag to detect initial goal setting


        // --- 2. Load Current Persisted State ---
        // Retrieves target app and goal from StudyStateManager (SharedPreferences)
        targetAppPackage = StudyStateManager.getTargetApp(applicationContext)
        dailyGoalMs = StudyStateManager.getDailyGoalMs(applicationContext)


        // --- 3. Check if Target App or Goal Changed Mid-Interval ---
        if (targetAppPackage != previousTarget || dailyGoalMs != previousGoal) {
            Log.i(TAG, "Intervention state (target/goal) changed: Target=$targetAppPackage, Goal=$dailyGoalMs ms")
            // This is the key: if the previous target was null, a goal was just set.
            if (previousTarget == null && targetAppPackage != null) {
                isNewGoalJustSet = true
                Log.d(TAG, "This is a new goal setting event.")
            }
            // Reset interval accumulator if target/goal changes mid-interval to avoid mixing data
            accumulatedUsageInIntervalMs = 0L
            warnedAt90Percent = false
            warnedAt100Percent = false
        }


        // --- 4. Check for Interval Rollover ---
        val newIntervalStart = getCurrentIntervalStart(System.currentTimeMillis())
        if (newIntervalStart != currentIntervalStartMs) {
            Log.i(TAG,"New interval detected. Previous interval start: $currentIntervalStartMs, New interval start: $newIntervalStart")


            // --- 4a. Reset Interval-based Counters and Notification Flags ---
            currentIntervalStartMs = newIntervalStart
            accumulatedUsageInIntervalMs = 0L // Reset accumulated usage for the new interval
            warnedAt90Percent = false    // Reset 90% warning flag
            warnedAt100Percent = false   // Reset 100% limit flag


            // --- 4b. Optional: Load Initial Usage from DB ---
            // If the service restarted or this is the first check of a new interval,
            // try to load any usage that might have been recorded for this interval
            // before this state load occurred.
            // DO NOT run this if a goal was just set.
            if (loadFromDbOnNewInterval && targetAppPackage != null && !isNewGoalJustSet) {
                // Launch background coroutine for DB read
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // --- Get current userId for the DAO call ---
                        val currentUserId = StudyStateManager.getUserId(applicationContext)
                        if (currentUserId == null) {
                            Log.e(TAG, "Cannot load initial usage from DB: User ID is null.")
                            return@launch // Exit coroutine if no user ID
                        }


                        val intervalEndTime = currentIntervalStartMs + TimeUnit.MINUTES.toMillis(EVALUATION_INTERVAL_MINUTES)
                        Log.d(TAG, "Attempting to load initial usage for current interval ($currentIntervalStartMs to $intervalEndTime) from DB for $targetAppPackage")


                        // Query the database for usage within the bounds of the new interval
                        val usageInIntervalSoFar = usageStatDao.getTotalDurationForAppInRange(
                            userId = currentUserId,
                            packageName = targetAppPackage!!,
                            startTime = currentIntervalStartMs,
                            endTime = System.currentTimeMillis() // Check from start of interval to now
                        ) ?: 0L


                        if (usageInIntervalSoFar > 0) {
                            Log.d(TAG, "Loaded ${usageInIntervalSoFar / 1000.0}s of initial usage for this interval from DB.")
                            // Update the main accumulator on the service's scope
                            withContext(serviceScope.coroutineContext) {
                                accumulatedUsageInIntervalMs = usageInIntervalSoFar
                                // Immediately re-check notifications based on potentially loaded value
                                checkUsageLimitNotifications()
                            }
                        } else {
                            Log.d(TAG, "No initial usage found in DB for this interval.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading this interval's initial usage from DB", e)
                    }
                } // End CoroutineScope launch
            } // End Optional DB Load


        } // End Interval Change Check


        // --- 5. Log Final State After Load/Refresh ---
        Log.d(TAG, "State Loaded/Refreshed: Target=$targetAppPackage, Goal=$dailyGoalMs ms, IntervalStart=$currentIntervalStartMs, AccumUsage=${accumulatedUsageInIntervalMs}ms, Warn90=$warnedAt90Percent, Warn100=$warnedAt100Percent")
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


                // --- Check for Interval Change & Reload State ---
                // Handle potential interval rollover before processing the usage events
                if (getCurrentIntervalStart(currentTime) != currentIntervalStartMs) {
                    Log.d(TAG,"Interval change detected during loop.")
                    loadInterventionState(true) // Reload state and reset interval counters
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
                        // Update interval's total usage from the interval data map
                        val usageInPollingInterval = intervalUsageData.usageMap[targetAppPackage!!] ?: 0L // Use non-null asserted target
                        // Always update the accumulator, even if adding 0
                        accumulatedUsageInIntervalMs += usageInPollingInterval
                        Log.d(TAG, "Target app usage in check: ${usageInPollingInterval / 1000.0}s. Interval total: ${accumulatedUsageInIntervalMs / 1000.0}s")


                        // Always broadcast the updated usage to provide a regular "heartbeat" for the UI
                        broadcastUsageUpdate(accumulatedUsageInIntervalMs)


                        // --- Check for Usage Limit Notifications (FR-051, FR-052) ---
                        checkUsageLimitNotifications()


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


    /**
     * Calculates the start timestamp of the current evaluation interval based on the current time.
     */
    private fun getCurrentIntervalStart(currentTime: Long): Long {
        val intervalMillis = TimeUnit.MINUTES.toMillis(EVALUATION_INTERVAL_MINUTES)
        if (intervalMillis <= 0) return getStartOfDayTimestamp(currentTime) // Fallback to day if interval is invalid
        return (currentTime / intervalMillis) * intervalMillis
    }


    // --- Notification Helper Functions ---


    /** Checks accumulated usage against goal and triggers 90%/100% notifications. */
    @SuppressLint("StringFormatMatches")
    private fun checkUsageLimitNotifications() {
        // --- ALIGNMENT: The goal must be scaled from a daily value to the interval value ---
        val goalForIntervalMs = dailyGoalMs?.let {
            val intervalRatio = EVALUATION_INTERVAL_MINUTES.toDouble() / TimeUnit.HOURS.toMinutes(24)
            (it * intervalRatio).toLong()
        }

        if (goalForIntervalMs == null || goalForIntervalMs <= 0) {
            Log.w(TAG, "checkUsageLimitNotifications skipped: Invalid goal for interval ($goalForIntervalMs)")
            return
        }

        serviceScope.launch(Dispatchers.Main) { // <<< CHANGE: Switch to Main thread
            // Avoid division by zero or issues if goal is invalid
            if (goalForIntervalMs <= 0) {
                Log.w(TAG, "checkUsageLimitNotifications skipped: Invalid goalMs ($goalForIntervalMs)")
                return@launch
            }

            // Capture current value and check for null
            val currentTargetAppPackage = targetAppPackage
            if (currentTargetAppPackage == null) {
                Log.e(TAG, "checkUsageLimitNotifications skipped: targetAppPackage is null unexpectedly.")
                return@launch
            }

            val usagePercent = (accumulatedUsageInIntervalMs.toDouble() / goalForIntervalMs.toDouble()) * 100.0
            // Use AppInfoHelper for consistent app name fetching on the Main thread
            val targetAppName = AppInfoHelper.getAppName(applicationContext, currentTargetAppPackage)
            val pointsUnit = getString(R.string.points_unit) // Get points unit

            // 90% Warning (FR028)
            if (usagePercent >= 90 && !warnedAt90Percent) {
                warnedAt90Percent = true // Set flag immediately to prevent race conditions
                val title = getString(R.string.notification_warning_90_title)
                val remainingMs = (goalForIntervalMs - accumulatedUsageInIntervalMs).coerceAtLeast(0)
                val remainingMinutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
                val goalMinutesTotal = TimeUnit.MILLISECONDS.toMinutes(goalForIntervalMs)
                val usedMinutes = TimeUnit.MILLISECONDS.toMinutes(accumulatedUsageInIntervalMs)
                val message = getString(R.string.notification_warning_90_message, usedMinutes, goalMinutesTotal, targetAppName, remainingMinutes)

                showInterventionNotification(USAGE_WARNING_90_NOTIFICATION_ID, title, message)
                Log.i(TAG,"Showing 90% usage warning notification.")
            }

            // 100% Limit Reached (FR029)
            if (usagePercent >= 100 && !warnedAt100Percent) {
                warnedAt100Percent = true // Set flag immediately
                val title = getString(R.string.notification_limit_100_title)
                val goalMinutes = TimeUnit.MILLISECONDS.toMinutes(goalForIntervalMs)

                val condition = StudyStateManager.getCondition(applicationContext)
                val pointConsequence = when(condition) {
                    StudyPhase.INTERVENTION_CONTROL -> getString(R.string.notification_limit_100_consequence_control)
                    StudyPhase.INTERVENTION_DEPOSIT -> getString(R.string.notification_limit_100_consequence_deposit, Constants.DEFAULT_DEPOSIT_LOSE_POINTS, pointsUnit)
                    StudyPhase.INTERVENTION_FLEXIBLE -> {
                        val (_, lose) = StudyStateManager.getFlexStakes(applicationContext)
                        if (lose != null && lose > Constants.FLEX_STAKES_MIN_LOSE) getString(R.string.notification_limit_100_consequence_flex_lose, lose, pointsUnit)
                        else if (lose == Constants.FLEX_STAKES_MIN_LOSE) getString(R.string.notification_limit_100_consequence_flex_no_lose)
                        else getString(R.string.notification_limit_100_consequence_fallback)
                    }
                    else -> getString(R.string.notification_limit_100_consequence_fallback)
                }

                val message = getString(R.string.notification_limit_100_message, goalMinutes, targetAppName, pointConsequence)
                showInterventionNotification(USAGE_LIMIT_100_NOTIFICATION_ID, title, message)
                Log.i(TAG,"Showing 100% usage limit notification with consequence.")
            }
        }
    }


    private fun broadcastUsageUpdate(usageMs: Long) {
        val intent = Intent(Constants.ACTION_USAGE_UPDATE).apply {
            putExtra(Constants.EXTRA_USAGE_MS, usageMs)
            // Ensure the broadcast is only sent within the app for security
            `package` = applicationContext.packageName
        }
        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: Usage updated to $usageMs ms")
    }


} // End of UsageTrackingService class