package com.niyaz.zario.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.niyaz.zario.data.model.AppBaselineInfo
import com.niyaz.zario.data.repository.StudyRepository
import com.niyaz.zario.utils.Constants // Ensure Constants is imported
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.concurrent.TimeUnit
import com.niyaz.zario.data.local.BaselineUsageRecord
import kotlin.math.max

/**
 * ViewModel for the [com.niyaz.zario.ui.screens.HomeScreen].
 *
 * Manages the UI state and business logic related to:
 * - Displaying the current day's usage for the target app during intervention phases.
 * - Orchestrating the baseline analysis and goal-setting process.
 * - Interacting with the [StudyRepository] to fetch and update study data.
 *
 * @property repository The [StudyRepository] instance used for data access.
 */
class HomeViewModel(private val repository: StudyRepository) : ViewModel() {

    private val TAG = "HomeViewModel"

    /** UI state for the Goal Setting phase. */
    enum class GoalSettingUiState { IDLE, LOADING, LOADED, ERROR, SAVING, SAVED }

    // --- StateFlows for UI ---

    /** Stores the current user's ID after initialization. Null if initialization fails or user logs out. */
    private var currentUserId: String? = null

    /** Tracks the package name of the current target application. Null if none selected or not applicable. */
    private val targetAppFlow = MutableStateFlow<String?>(null) // Internal tracker

    /** Job responsible for collecting the usage flow for the current target app. */
    private var usageCollectionJob: Job? = null

    /** StateFlow holding the accumulated usage duration (in milliseconds) for the target app *today*. */
    private val _todayUsageMs = MutableStateFlow(0L)
    val todayUsageMs: StateFlow<Long> = _todayUsageMs.asStateFlow()

    /** StateFlow representing the current state of the goal-setting UI process. */
    private val _goalSettingState = MutableStateFlow(GoalSettingUiState.IDLE)
    val goalSettingState: StateFlow<GoalSettingUiState> = _goalSettingState.asStateFlow()

    /** StateFlow holding the list of apps with calculated baseline usage, for display during goal setting. */
    private val _baselineAppList = MutableStateFlow<List<AppBaselineInfo>>(emptyList())
    val baselineAppList: StateFlow<List<AppBaselineInfo>> = _baselineAppList.asStateFlow()

    /** StateFlow holding the app suggested as the target (typically the most used during baseline). */
    private val _suggestedApp = MutableStateFlow<AppBaselineInfo?>(null)
    val suggestedApp: StateFlow<AppBaselineInfo?> = _suggestedApp.asStateFlow()

    /** StateFlow holding aggregated baseline usage data per hour (0-23), used for the bar chart. */
    private val _hourlyUsageData = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val hourlyUsageData: StateFlow<Map<Int, Long>> = _hourlyUsageData.asStateFlow()


    // --- Initialization ---
    init {
        Log.d(TAG, "ViewModel initializing...")
        viewModelScope.launch { // Use viewModelScope for initialization logic
            try {
                // Fetch and store userId first - critical for subsequent operations
                currentUserId = repository.getUserId()
                if (currentUserId == null) {
                    Log.e(TAG, "ViewModel initialized but User ID is null. Cannot fetch user-specific data.")
                    // Consider triggering a logout or error state if appropriate
                    return@launch // Stop initialization if no user ID
                }
                Log.i(TAG, "ViewModel initialized for User ID: $currentUserId")

                // Load initial target app from repository (might be null)
                targetAppFlow.value = repository.getTargetApp()
                Log.d(TAG, "Initial target app loaded: ${targetAppFlow.value}")

                // Start collecting today's usage data for the target app (if any)
                startOrUpdateUsageCollection()

                // Observe subsequent changes to the target app (e.g., after goal setting)
                observeTargetAppChanges()

            } catch (e: Exception) {
                Log.e(TAG, "Error during ViewModel initialization", e)
                // Handle initialization error appropriately, e.g., set an error state
            }
        }
    }

    /** Observes changes to the internal `targetAppFlow` and restarts usage collection. */
    private fun observeTargetAppChanges() {
        viewModelScope.launch {
            targetAppFlow
                .drop(1) // Skip the initial value emission (handled in init)
                .distinctUntilChanged() // Only react to actual changes
                .collect { currentTargetApp ->
                    Log.i(TAG, "Target app changed to: $currentTargetApp. Restarting usage collection.")
                    startOrUpdateUsageCollection() // Restart collection logic
                }
        }
    }

    // --- Usage Collection Logic ---

    /**
     * Starts or restarts the coroutine job that collects the usage duration flow
     * for the current target application from the repository.
     * Cancels any existing collection job. Does nothing if the current user ID or target app is null.
     */
    private fun startOrUpdateUsageCollection() {
        usageCollectionJob?.cancel() // Cancel existing job if any
        val currentTarget = targetAppFlow.value
        val userId = currentUserId // Use the stored userId

        if (userId != null && currentTarget != null) {
            usageCollectionJob = viewModelScope.launch {
                val todayStartMs = getStartOfDayTimestamp(System.currentTimeMillis())
                Log.d(TAG, "Starting usage collection for User $userId, App: $currentTarget, DayStart: $todayStartMs")

                // Combine the database flow with a periodic ticker.
                // The ticker ensures the UI potentially updates even if the underlying DB value
                // doesn't change frequently, providing a sense of live-ness.
                // A simpler alternative might rely solely on Room's flow emissions if precise
                // real-time updates are less critical.
                combine(
                    repository.getTodayUsageForAppFlow(userId, currentTarget, todayStartMs),
                    tickerFlow(TimeUnit.MINUTES.toMillis(1)) // Ticker interval (e.g., 1 minute)
                ) { usage, _ -> usage } // Combine logic: just take the usage value from the DB flow
                    .catch { e -> Log.e(TAG, "Error collecting today's usage flow for User $userId, App $currentTarget", e) }
                    .distinctUntilChanged() // Avoid redundant updates
                    .collect { usage ->
                        val currentUsage = usage ?: 0L
                        if (_todayUsageMs.value != currentUsage) {
                            _todayUsageMs.value = currentUsage
                            // Log only when the value actually changes
                            Log.v(TAG, "Updated todayUsageMs for User $userId, App $currentTarget: $currentUsage ms")
                        }
                    }
            }
        } else {
            _todayUsageMs.value = 0L // Reset usage if no target app or user
            Log.d(TAG, "Usage collection stopped: Target app ($currentTarget) or User ID ($userId) is null.")
        }
    }

    // --- Baseline Analysis Logic ---

    /**
     * Initiates the process of loading and analyzing baseline usage data.
     * Sets the [goalSettingState] to [GoalSettingUiState.LOADING].
     * Fetches aggregated and hourly usage data from the repository for the baseline period.
     * Updates [baselineAppList], [suggestedApp], [hourlyUsageData], and sets [goalSettingState]
     * to [GoalSettingUiState.LOADED] on success or [GoalSettingUiState.ERROR] on failure.
     * Does nothing if already loading or loaded.
     */
    fun loadBaselineData() {
        // Prevent concurrent loading attempts
        if (_goalSettingState.value == GoalSettingUiState.LOADING || _goalSettingState.value == GoalSettingUiState.LOADED) {
            Log.d(TAG, "loadBaselineData() called but already loading or loaded. State: ${_goalSettingState.value}")
            return
        }
        Log.i(TAG, "Initiating baseline data load for User ID: $currentUserId")
        _goalSettingState.value = GoalSettingUiState.LOADING
        val userId = currentUserId

        if (userId == null) {
            Log.e(TAG, "Cannot load baseline data: User ID is null.")
            _goalSettingState.value = GoalSettingUiState.ERROR
            return
        }

        viewModelScope.launch(Dispatchers.IO) { // Use IO dispatcher for repository/calculation
            try {
                val baselineStartTime = repository.getStudyStartTimestamp()
                if (baselineStartTime <= 0) {
                    throw IllegalStateException("Invalid baseline start time ($baselineStartTime) found.")
                }

                // Use baseline duration from Constants
                val baselineEndTime = baselineStartTime + Constants.BASELINE_DURATION_MS
                val now = System.currentTimeMillis()

                // Ensure baseline period has actually ended before proceeding
                if (now < baselineEndTime) {
                    // It's technically possible to enter Goal Setting phase slightly early if worker runs late.
                    // Handle this gracefully by setting back to IDLE or potentially showing a 'wait' message.
                    Log.w(TAG, "Attempted to load baseline data before baseline period ended (Now: $now < End: $baselineEndTime). Resetting state.")
                    withContext(Dispatchers.Main) { _goalSettingState.value = GoalSettingUiState.IDLE }
                    return@launch
                }

                Log.d(TAG, "Fetching baseline data for User $userId between $baselineStartTime and $baselineEndTime")
                // Fetch aggregated usage (top apps) and detailed records (for hourly chart)
                val aggregatedUsage = repository.getAggregatedUsageForBaseline(userId, baselineStartTime, baselineEndTime)
                val hourlyRecords = repository.getAllUsageRecordsForBaseline(userId, baselineStartTime, baselineEndTime)

                // --- Process Hourly Usage ---
                val hourlyMap = processHourlyData(hourlyRecords)

                // --- Process Aggregated Usage ---
                if (aggregatedUsage.isEmpty()) {
                    Log.w(TAG, "No significant baseline usage data found for User $userId after aggregation.")
                    // Update state on Main thread for UI consistency
                    withContext(Dispatchers.Main) {
                        _baselineAppList.value = emptyList()
                        _suggestedApp.value = null
                        _hourlyUsageData.value = hourlyMap // Hourly data might still exist
                        _goalSettingState.value = GoalSettingUiState.LOADED // Loaded, but potentially empty list
                    }
                    return@launch
                }

                // Calculate average daily usage and map to AppBaselineInfo DTOs
                val baselineDurationDays = TimeUnit.MILLISECONDS.toDays(Constants.BASELINE_DURATION_MS).toInt().coerceAtLeast(1)
                val appInfoList = aggregatedUsage.mapNotNull { baseline -> // Use mapNotNull to handle potential errors fetching app details
                    try {
                        val averageDailyMs = baseline.totalDurationMs / baselineDurationDays
                        // Get app name/icon details from repository (which uses AppInfoHelper)
                        val appDetails = repository.getAppDetails(baseline.packageName)
                        AppBaselineInfo(
                            packageName = baseline.packageName,
                            appName = appDetails.appName,
                            averageDailyUsageMs = averageDailyMs,
                            icon = appDetails.icon
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing baseline details for package ${baseline.packageName}", e)
                        null // Skip this app if details can't be fetched
                    }
                }

                // --- Update State on Main Thread ---
                withContext(Dispatchers.Main) {
                    _baselineAppList.value = appInfoList
                    _suggestedApp.value = appInfoList.firstOrNull() // Suggest the most used app
                    _hourlyUsageData.value = hourlyMap
                    _goalSettingState.value = GoalSettingUiState.LOADED
                    Log.i(TAG, "Baseline data loaded for User $userId. Suggesting: ${_suggestedApp.value?.appName}. Apps: ${appInfoList.size}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading baseline data for User $userId", e)
                withContext(Dispatchers.Main) { // Ensure state updates are on Main thread
                    _goalSettingState.value = GoalSettingUiState.ERROR
                    _baselineAppList.value = emptyList()
                    _suggestedApp.value = null
                    _hourlyUsageData.value = emptyMap()
                }
            }
        }
    } // End loadBaselineData

    /** Processes raw baseline records into a map of Hour -> Total Duration (ms). */
    private fun processHourlyData(records: List<BaselineUsageRecord>): Map<Int, Long> {
        val hourlyMap = mutableMapOf<Int, Long>()
        records.forEach { record ->
            try {
                val instant = Instant.ofEpochMilli(record.intervalStartTimestamp)
                // Use device's default time zone for grouping by local hour
                val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                val hour = dateTime.hour // Hour: 0-23
                hourlyMap[hour] = hourlyMap.getOrDefault(hour, 0L) + record.durationMs
            } catch (e: Exception) {
                Log.e(TAG, "Error processing hourly data for record starting at ${record.intervalStartTimestamp}", e)
            }
        }
        Log.d(TAG, "Processed ${records.size} records into ${hourlyMap.size} hourly summaries.")
        return hourlyMap.toMap() // Return immutable map
    }

    // --- Goal Setting Confirmation Logic ---

    /**
     * Confirms the user's selection of a target application.
     * Calculates the daily goal based on the baseline average and reduction factor.
     * Calls the repository to persist the target app, goal, and update the study phase.
     * Updates [goalSettingState] to [GoalSettingUiState.SAVING] during the operation,
     * then to [GoalSettingUiState.SAVED] on success or [GoalSettingUiState.ERROR] on failure.
     *
     * @param selectedAppInfo The [AppBaselineInfo] of the application selected by the user.
     */
    fun confirmGoalSelection(selectedAppInfo: AppBaselineInfo?) {
        if (selectedAppInfo == null) {
            Log.e(TAG, "confirmGoalSelection called with null selectedAppInfo.")
            _goalSettingState.value = GoalSettingUiState.ERROR // Indicate error if selection is missing
            return
        }
        // Ensure we are in the correct state to save
        if (_goalSettingState.value != GoalSettingUiState.LOADED) {
            Log.w(TAG, "confirmGoalSelection called in unexpected state: ${_goalSettingState.value}")
            return
        }

        val userId = currentUserId
        if (userId == null) {
            Log.e(TAG, "Cannot confirm goal selection: User ID is null.")
            _goalSettingState.value = GoalSettingUiState.ERROR
            return
        }

        Log.i(TAG, "Confirming goal selection for User $userId: App='${selectedAppInfo.appName}'")
        _goalSettingState.value = GoalSettingUiState.SAVING

        viewModelScope.launch(Dispatchers.IO) { // Use IO for repository operation
            try {
                // Calculate the daily goal (Apply reduction factor, ensure minimum duration)
                val baselineAverageMs = selectedAppInfo.averageDailyUsageMs
                // Use constants for calculation
                val dailyGoalMs = (baselineAverageMs * Constants.GOAL_REDUCTION_FACTOR)
                    .toLong()
                    .coerceAtLeast(Constants.MINIMUM_GOAL_DURATION_MS)

                Log.d(TAG, "User $userId: Baseline Avg=${baselineAverageMs}ms, Calculated Goal=${dailyGoalMs}ms")

                // Call the repository's combined operation to save goal, target, and phase
                val result = repository.confirmGoalSelection(
                    userId = userId,
                    selectedAppPkg = selectedAppInfo.packageName,
                    calculatedGoalMs = dailyGoalMs
                )

                // Update state based on repository result (on Main thread)
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        // Trigger update of the target app flow to start usage collection
                        targetAppFlow.value = selectedAppInfo.packageName
                        _goalSettingState.value = GoalSettingUiState.SAVED
                        Log.i(TAG, "Goal confirmed and saved successfully for User $userId.")
                        // UI should react to phase change driven by repository update
                    } else {
                        _goalSettingState.value = GoalSettingUiState.ERROR
                        Log.e(TAG, "Failed to save goal selection for User $userId.", result.exceptionOrNull())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during goal confirmation process for User $userId", e)
                withContext(Dispatchers.Main) {
                    _goalSettingState.value = GoalSettingUiState.ERROR
                }
            }
        }
    } // End confirmGoalSelection

    // --- Helper Functions ---

    /**
     * Calculates the start of the day (00:00:00.000) in milliseconds for a given timestamp.
     * Uses the device's default time zone.
     * Consider moving to a shared Date/Time utility class if used elsewhere.
     * @param timestamp The timestamp (epoch milliseconds) for which to find the start of the day.
     * @return The timestamp representing the start of the day.
     */
    private fun getStartOfDayTimestamp(timestamp: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Creates a simple Flow that emits Unit periodically. Used to trigger updates
     * in combination with other flows.
     * @param period The emission period in milliseconds.
     * @param initialDelay Optional initial delay before the first emission.
     * @return A Flow emitting Unit at the specified interval.
     */
    private fun tickerFlow(period: Long, initialDelay: Long = 0L) = flow {
        delay(initialDelay)
        while (true) {
            emit(Unit)
            delay(period)
        }
    }

    /**
     * Manually triggers a refresh of the target app state from the repository.
     * Updates the internal `targetAppFlow` if the value has changed,
     * which will consequently restart the usage collection job.
     */
    fun refreshTargetApp() {
        viewModelScope.launch {
            try {
                val refreshedTarget = repository.getTargetApp()
                // Update internal flow only if value differs, triggers collector via distinctUntilChanged
                if (targetAppFlow.value != refreshedTarget) {
                    targetAppFlow.value = refreshedTarget
                    Log.i(TAG, "Target app state manually refreshed: ${targetAppFlow.value}")
                } else {
                    Log.d(TAG, "Target app refresh requested, but value is unchanged: $refreshedTarget")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing target app state", e)
            }
        }
    }

    // --- Factory for ViewModel Instantiation ---
    companion object {
        /**
         * Provides a [ViewModelProvider.Factory] for creating [HomeViewModel] instances.
         * Required because the ViewModel has constructor dependencies ([StudyRepository]).
         * @param repository The [StudyRepository] instance to inject.
         * @return A factory for creating [HomeViewModel].
         */
        fun provideFactory(
            repository: StudyRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                    return HomeViewModel(repository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}