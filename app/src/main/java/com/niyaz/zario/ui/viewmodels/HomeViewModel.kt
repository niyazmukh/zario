package com.niyaz.zario.ui.viewmodels


import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.niyaz.zario.data.model.AppBaselineInfo
import com.niyaz.zario.data.repository.StudyRepository
import com.niyaz.zario.utils.Constants
import com.niyaz.zario.workers.DailyCheckWorker
import com.niyaz.zario.workers.FirestoreSyncWorker
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
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy


// --- CHANGE: Constructor takes Repository, extends ViewModel ---
class HomeViewModel(application: Application, private val repository: StudyRepository) : AndroidViewModel(application) {


    private val TAG = "HomeViewModel" // Add TAG


    // --- State for Current Day Usage ---
    private val _todayUsageMs = MutableStateFlow<Long>(0L)
    val todayUsageMs: StateFlow<Long> = _todayUsageMs.asStateFlow()


    // State for hourly usage data
    private val _hourlyUsageData = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val hourlyUsageData: StateFlow<Map<Int, Long>> = _hourlyUsageData.asStateFlow()


    // --- State for Baseline Analysis & Goal Setting ---
    // AppBaselineInfo data class moved to data.model


    // State for overall Goal Setting process
    enum class GoalSettingUiState { IDLE, LOADING, LOADED, ERROR, SAVING, SAVED }
    private val _goalSettingState = MutableStateFlow(GoalSettingUiState.IDLE)
    val goalSettingState: StateFlow<GoalSettingUiState> = _goalSettingState.asStateFlow()


    // Holds the list of apps with baseline usage for selection
    private val _baselineAppList = MutableStateFlow<List<AppBaselineInfo>>(emptyList())
    val baselineAppList: StateFlow<List<AppBaselineInfo>> = _baselineAppList.asStateFlow()


    // Holds the suggested app (most used)
    private val _suggestedApp = MutableStateFlow<AppBaselineInfo?>(null)
    val suggestedApp: StateFlow<AppBaselineInfo?> = _suggestedApp.asStateFlow()


    // --- Flow Collector Logic ---
    private val targetAppFlow = MutableStateFlow<String?>(null)
    private var usageCollectionJob: Job? = null // Keep track of the collection job


    // Store userId obtained during init
    private var currentUserId: String? = null




    init {
        Log.d(TAG, "ViewModel Initializing")
        viewModelScope.launch { // Use viewModelScope directly
            // Fetch and store userId first
            currentUserId = repository.getUserId() // Get userId from repository
            if (currentUserId == null) {
                Log.e(TAG, "ViewModel initialized but User ID is null. Usage data will not be collected.")
                // Optionally handle this state, e.g., trigger logout or show error
                return@launch // Stop further initialization if no userId
            }
            Log.d(TAG, "ViewModel initialized for user: $currentUserId")


            // Load initial target app
            targetAppFlow.value = repository.getTargetApp() // Use repository
            Log.d(TAG, "Initial target app loaded: ${targetAppFlow.value}")


            // Start collecting usage immediately
            startOrUpdateUsageCollection()

            // --- Enqueue Periodic Workers ---
            enqueuePeriodicWorkers()
        }


        // Observe changes in the target app and restart collection if needed
        viewModelScope.launch {
            targetAppFlow
                .drop(1) // Skip initial value emission as it's handled above
                .distinctUntilChanged()
                .collect { currentTargetApp ->
                    Log.d(TAG, "Target app changed to: $currentTargetApp. Restarting usage collection.")
                    startOrUpdateUsageCollection() // Restart collection when target changes
                }
        }


    } // End Init


    private fun startOrUpdateUsageCollection() {
        usageCollectionJob?.cancel() // Cancel previous collection job if running
        val currentTargetApp = targetAppFlow.value
        val userId = currentUserId // Use the stored userId


        // Ensure both userId and targetApp are available
        if (userId != null && currentTargetApp != null) {
            usageCollectionJob = viewModelScope.launch { // Launch new collection in viewModelScope
                val todayStartMs = getStartOfDayTimestamp(System.currentTimeMillis())
                Log.d(TAG, "Collecting today's usage flow for user $userId, app: $currentTargetApp, DayStart: $todayStartMs")


                // Combine repository flow with a ticker to ensure updates even if DB doesn't change frequently
                combine(
                    // Pass userId to the repository method
                    repository.getTodayUsageForAppFlow(userId, currentTargetApp, todayStartMs),
                    tickerFlow(TimeUnit.SECONDS.toMillis(Constants.USAGE_TICKER_INTERVAL_SECONDS)) // Use constant
                ) { usage, _ -> usage } // Combine logic: take the usage value
                    .catch { e -> Log.e(TAG, "Error collecting today's usage flow for user $userId", e) }
                    .distinctUntilChanged() // Only emit if the usage value changes
                    .collect { usage ->
                        val currentUsage = usage ?: 0L
                        if (_todayUsageMs.value != currentUsage) {
                            _todayUsageMs.value = currentUsage
                            // Log only on change to reduce noise
                            Log.d(TAG, "Updated todayUsageMs for user $userId: ${_todayUsageMs.value}")
                        }
                    }
            }
        } else {
            // If target app becomes null or userId is missing, reset usage to 0
            _todayUsageMs.value = 0L
            Log.d(TAG, "Target app ($currentTargetApp) or User ID ($userId) is null. Usage set to 0. Usage collection stopped.")
        }
    }




    // --- Baseline Analysis Logic ---


    /**
     * Triggers the calculation and loading of baseline data when entering GOAL_SETTING phase.
     */
    fun loadBaselineData() {
        if (_goalSettingState.value != GoalSettingUiState.IDLE && _goalSettingState.value != GoalSettingUiState.ERROR) {
            Log.d(TAG, "Baseline data already loading or loaded. State: ${_goalSettingState.value}")
            return
        }
        Log.d(TAG, "Starting baseline data load...")
        _goalSettingState.value = GoalSettingUiState.LOADING
        val userId = currentUserId // Get stored userId


        if (userId == null) {
            Log.e(TAG, "Cannot load baseline data: User ID is null.")
            _goalSettingState.value = GoalSettingUiState.ERROR // Set error state
            return
        }


        viewModelScope.launch(Dispatchers.IO) { // Use IO dispatcher for repository calls
            try {
                // --- Use Repository ---
                val baselineStartTime = repository.getStudyStartTimestamp()
                if (baselineStartTime <= 0) {
                    Log.e(TAG, "Invalid baseline start time ($baselineStartTime). Cannot load data.")
                    withContext(Dispatchers.Main) { _goalSettingState.value = GoalSettingUiState.ERROR }
                    return@launch
                }


                val baselineDurationMinutes = Constants.BASELINE_DURATION_MINUTES
                val baselineEndTime = baselineStartTime + TimeUnit.MINUTES.toMillis(baselineDurationMinutes)
                val now = System.currentTimeMillis()


                if (now < baselineEndTime) {
                    Log.w(TAG, "Attempted to load baseline data before baseline period ended.")
                    withContext(Dispatchers.Main) { _goalSettingState.value = GoalSettingUiState.IDLE }
                    return@launch
                }


                // --- FIX: Correctly calculate the number of days in the baseline period ---
                val actualBaselineDurationMs = baselineEndTime - baselineStartTime
                val baselineDurationDays = (TimeUnit.MILLISECONDS.toDays(actualBaselineDurationMs)).coerceAtLeast(1)
                // --- End FIX ---


                Log.d(TAG, "Fetching baseline usage for user $userId between $baselineStartTime and $baselineEndTime ($baselineDurationDays days)")
                // --- Use Repository, passing userId ---
                val aggregatedUsage = repository.getAggregatedUsageForBaseline(userId, baselineStartTime, baselineEndTime)
                val hourlyRecords = repository.getAllUsageRecordsForBaseline(userId, baselineStartTime, baselineEndTime)


                // --- Process Hourly Usage (Unchanged logic, just source is different) ---
                val hourlyMap = mutableMapOf<Int, Long>()
                hourlyRecords.forEach { record ->
                    val instant = Instant.ofEpochMilli(record.intervalStartTimestamp)
                    val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                    val hour = dateTime.hour
                    hourlyMap[hour] = hourlyMap.getOrDefault(hour, 0L) + record.durationMs
                }
                withContext(Dispatchers.Main) { _hourlyUsageData.value = hourlyMap.toMap() } // Update on Main thread
                Log.d(TAG, "Processed hourly usage data for user $userId: ${_hourlyUsageData.value.size} hours with data.")


                if (aggregatedUsage.isEmpty()) {
                    Log.w(TAG, "No significant baseline usage data found for user $userId.")
                    withContext(Dispatchers.Main) {
                        _baselineAppList.value = emptyList()
                        _suggestedApp.value = null
                        _hourlyUsageData.value = emptyMap()
                        _goalSettingState.value = GoalSettingUiState.LOADED // Loaded, but empty
                    }
                    return@launch
                }


                // Calculate average daily usage and map to AppBaselineInfo
                val appInfoList = aggregatedUsage.map { baseline ->
                    val averageDailyMs = baseline.totalDurationMs / baselineDurationDays
                    // --- Use Repository to get details (name/icon) ---
                    // Repository call getAppDetails does not need userId
                    val appDetails = repository.getAppDetails(baseline.packageName)
                    AppBaselineInfo(
                        packageName = baseline.packageName,
                        appName = appDetails.appName,
                        averageDailyUsageMs = averageDailyMs, // Now add the calculated average
                        icon = appDetails.icon
                    )
                }


                withContext(Dispatchers.Main) { // Update StateFlows on Main thread
                    _baselineAppList.value = appInfoList
                    _suggestedApp.value = appInfoList.firstOrNull()
                    Log.d(TAG, "Baseline data loaded for user $userId. Suggesting: ${_suggestedApp.value?.appName}. Total apps found: ${appInfoList.size}")
                    _goalSettingState.value = GoalSettingUiState.LOADED
                }


            } catch (e: Exception) {
                Log.e(TAG, "Error loading baseline data for user $userId", e)
                withContext(Dispatchers.Main) {
                    _goalSettingState.value = GoalSettingUiState.ERROR
                    _baselineAppList.value = emptyList()
                    _suggestedApp.value = null
                    _hourlyUsageData.value = emptyMap()
                }
            }
        }
    } // End loadBaselineData




    // --- Goal Setting Confirmation Logic ---


    /**
     * Called when the user confirms their target app selection.
     * Uses the repository to calculate the goal, persist the data, and transition the phase.
     *
     * @param selectedAppInfo The AppBaselineInfo object for the app chosen by the user.
     */
    fun confirmGoalSelection(selectedAppInfo: AppBaselineInfo?) {
        if (selectedAppInfo == null) {
            Log.e(TAG, "Confirm button clicked but selectedAppInfo is null.")
            // Optionally update state to show an error message in UI
            return
        }
        if (_goalSettingState.value != GoalSettingUiState.LOADED) {
            Log.w(TAG, "Confirm button clicked in unexpected state: ${_goalSettingState.value}")
            return
        }


        val userId = currentUserId // Use stored userId for logging/potential use
        Log.d(TAG, "Confirming goal selection for user $userId: ${selectedAppInfo.appName}")
        _goalSettingState.value = GoalSettingUiState.SAVING


        viewModelScope.launch(Dispatchers.IO) { // Use IO for repository operation
            try {
                // Calculate 20% reduction goal (FR015)
                val baselineAverageMs = selectedAppInfo.averageDailyUsageMs
                val reductionFactor = 0.80
                val dailyGoalMs = (baselineAverageMs * reductionFactor).toLong().coerceAtLeast(60000L)
                Log.d(TAG, "Baseline Avg: $baselineAverageMs ms, Calculated Goal: $dailyGoalMs ms")


                // --- Use Repository's combined operation ---
                // Check again just before critical operation
                val confirmedUserId = currentUserId
                if (confirmedUserId == null) {
                    throw IllegalStateException("User ID not found for goal confirmation.")
                }
                val result = repository.confirmGoalSelection(
                    userId = confirmedUserId, // Pass confirmed userId
                    selectedAppPkg = selectedAppInfo.packageName,
                    calculatedGoalMs = dailyGoalMs
                )


                if (result.isSuccess) {
                    // Refresh the target app flow for today's usage tracking
                    // This will trigger startOrUpdateUsageCollection via the flow collector
                    targetAppFlow.value = selectedAppInfo.packageName


                    Log.i(TAG, "Goal confirmed and saved via repository for user $confirmedUserId. Target: ${selectedAppInfo.packageName}, Goal: $dailyGoalMs ms.")
                    withContext(Dispatchers.Main) {
                        _goalSettingState.value = GoalSettingUiState.SAVED
                    }
                } else {
                    throw result.exceptionOrNull() ?: Exception("Unknown error saving goal selection.")
                }


            } catch (e: Exception) {
                Log.e(TAG, "Error saving goal selection for user $userId", e)
                withContext(Dispatchers.Main) {
                    _goalSettingState.value = GoalSettingUiState.ERROR
                    // Consider adding a specific error message StateFlow for the UI
                }
            }
        }
    } // End confirmGoalSelection




    // --- Helper Functions ---
    private fun getStartOfDayTimestamp(timestamp: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }


    private fun tickerFlow(period: Long) = flow {
        while (true) {
            emit(Unit)
            delay(period)
        }
    }


    /** Refreshes the target app state from the repository. */
    fun refreshTargetApp() {
        // No userId needed here as it just reads local state via repo/stateManager
        viewModelScope.launch { // No specific dispatcher needed for reading from repo here
            val currentTarget = targetAppFlow.value
            val refreshedTarget = repository.getTargetApp()
            // Update the flow only if the value has actually changed
            if (currentTarget != refreshedTarget) {
                targetAppFlow.value = refreshedTarget
                Log.d(TAG, "Target app manually refreshed: ${targetAppFlow.value}")
            } else {
                Log.d(TAG, "Target app refresh requested but value hasn't changed: $refreshedTarget")
            }
        }
    }


    // --- Add Factory for ViewModel Instantiation ---
    companion object {
        fun provideFactory(
            application: Application,
            repository: StudyRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                    return HomeViewModel(application, repository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }


    // --- Worker Scheduling ---
    private fun enqueuePeriodicWorkers() {
        Log.d(TAG, "Enqueuing periodic background workers.")
        val workManager = WorkManager.getInstance(getApplication())


        // --- Daily Check Worker ---
        val dailyCheckRequest = PeriodicWorkRequestBuilder<DailyCheckWorker>(
            Constants.DAILY_CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
        ).build()


        workManager.enqueueUniquePeriodicWork(
            DailyCheckWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Keep existing work if it's already scheduled
            dailyCheckRequest
        )
        Log.d(TAG, "Enqueued ${DailyCheckWorker.UNIQUE_WORK_NAME} to run every ${Constants.DAILY_CHECK_INTERVAL_MINUTES} minutes.")


        // --- Firestore Sync Worker ---
        val firestoreSyncRequest = PeriodicWorkRequestBuilder<FirestoreSyncWorker>(
            Constants.FIRESTORE_SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
        ).setConstraints(FirestoreSyncWorker.WORKER_CONSTRAINTS)
            .build()


        workManager.enqueueUniquePeriodicWork(
            FirestoreSyncWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            firestoreSyncRequest
        )
        Log.d(TAG, "Enqueued ${FirestoreSyncWorker.UNIQUE_WORK_NAME} to run every ${Constants.FIRESTORE_SYNC_INTERVAL_MINUTES} minutes.")
    }
}