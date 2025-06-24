package com.niyaz.zario.ui.screens

// Android & Core Imports
import android.Manifest
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.R
import com.niyaz.zario.StudyPhase
import com.niyaz.zario.data.local.AppDatabase
import com.niyaz.zario.data.repository.StudyRepository
import com.niyaz.zario.data.repository.StudyRepositoryImpl
import com.niyaz.zario.navigation.Screen
import com.niyaz.zario.services.UsageTrackingService
import com.niyaz.zario.ui.viewmodels.HomeViewModel
import com.niyaz.zario.utils.Constants
import com.niyaz.zario.utils.PermissionsUtils
import com.niyaz.zario.utils.StudyStateManager
import com.niyaz.zario.workers.DailyCheckWorker
import com.niyaz.zario.workers.FirestoreSyncWorker
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit


/**
 * The main screen of the application displayed after successful authentication.
 * (KDoc remains the same)
 */
@Composable
fun HomeScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val workManager = WorkManager.getInstance(context)
    val coroutineScope = rememberCoroutineScope()
    val TAG = "HomeScreen" // Logging Tag

    // --- Instantiate Repository and ViewModel ---
    val repository: StudyRepository = remember {
        StudyRepositoryImpl(
            context = context.applicationContext,
            usageStatDao = AppDatabase.getDatabase(context.applicationContext).usageStatDao(),
            firestore = Firebase.firestore
        )
    }
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.provideFactory(repository = repository)
    )

    // --- Collect State from ViewModel ---
    val todayUsageMs by homeViewModel.todayUsageMs.collectAsState()
    val goalSettingState by homeViewModel.goalSettingState.collectAsState()
    val baselineAppList by homeViewModel.baselineAppList.collectAsState()
    val suggestedApp by homeViewModel.suggestedApp.collectAsState()
    val hourlyData by homeViewModel.hourlyUsageData.collectAsState()

    // --- Local State Management ---
    var hasUsageStatsPermission by remember { mutableStateOf(PermissionsUtils.hasUsageStatsPermission(context)) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else { true }
        )
    }
    val permissionsGranted = hasUsageStatsPermission && hasNotificationPermission
    var currentStudyPhase by remember { mutableStateOf(StudyStateManager.getStudyPhase(context)) }
    var flexibleStakes by remember { mutableStateOf(StudyStateManager.getFlexStakes(context)) }
    var flexStakesSetByUser by remember { mutableStateOf(StudyStateManager.getFlexStakesSetByUser(context)) }
    val needsFlexSetup = currentStudyPhase == StudyPhase.INTERVENTION_FLEXIBLE && !flexStakesSetByUser

    // --- Permission Launchers ---
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        Log.d(TAG, "Notification permission result: Granted=$isGranted")
        hasNotificationPermission = isGranted
    }
    val usageStatsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { Log.d(TAG,"Returned from Usage Access Settings screen.") }

    // --- Side Effects --- (Worker Scheduling, Resume Checks, Service Start/Stop - remain unchanged)
    // Effect to Schedule/Cancel Background Workers based on Study Phase
    LaunchedEffect(currentStudyPhase, workManager) {
        Log.d(TAG, "Worker Scheduling Effect: Current Phase = $currentStudyPhase")
        // Daily Check Worker
        if (currentStudyPhase.name.startsWith("INTERVENTION")) {
            val dailyCheckRequest = PeriodicWorkRequestBuilder<DailyCheckWorker>(Constants.DEFAULT_CHECK_WORKER_INTERVAL_MS, TimeUnit.MILLISECONDS).build()
            workManager.enqueueUniquePeriodicWork(Constants.DAILY_CHECK_WORKER_NAME, ExistingPeriodicWorkPolicy.KEEP, dailyCheckRequest)
            Log.i(TAG, "DailyCheckWorker enqueued/verified (Interval: ${Constants.DEFAULT_CHECK_WORKER_INTERVAL_MS}ms)")
        } else {
            workManager.cancelUniqueWork(Constants.DAILY_CHECK_WORKER_NAME)
            Log.i(TAG, "DailyCheckWorker cancelled (Phase: $currentStudyPhase)")
        }
        // Firestore Sync Worker
        val shouldRunSync = currentStudyPhase == StudyPhase.BASELINE || currentStudyPhase.name.startsWith("INTERVENTION")
        if (shouldRunSync) {
            val syncRequest = PeriodicWorkRequestBuilder<FirestoreSyncWorker>(Constants.FIRESTORE_SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .setConstraints(FirestoreSyncWorker.WORKER_CONSTRAINTS).build()
            workManager.enqueueUniquePeriodicWork(Constants.FIRESTORE_SYNC_WORKER_NAME, ExistingPeriodicWorkPolicy.KEEP, syncRequest)
            Log.i(TAG, "FirestoreSyncWorker enqueued/verified (Interval: ${Constants.FIRESTORE_SYNC_INTERVAL_MS}ms)")
        } else {
            workManager.cancelUniqueWork(Constants.FIRESTORE_SYNC_WORKER_NAME)
            Log.i(TAG, "FirestoreSyncWorker cancelled (Phase: $currentStudyPhase)")
        }
    }

    // --- Effect to Re-check Permissions, State (including new flag) on Resume ---
    DisposableEffect(lifecycleOwner, repository, homeViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Log.d(TAG, "Lifecycle ON_RESUME: Re-checking permissions and state.")

                // Re-check permissions
                hasUsageStatsPermission = PermissionsUtils.hasUsageStatsPermission(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasNotificationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }

                // Refresh local state display variable from StudyStateManager
                val refreshedPhase = StudyStateManager.getStudyPhase(context)
                flexibleStakes = StudyStateManager.getFlexStakes(context) // Refresh flex stakes
                flexStakesSetByUser = StudyStateManager.getFlexStakesSetByUser(context) // <<< REFRESH Flag


                // *** FIX: Check for Baseline Completion HERE ***
                if (refreshedPhase == StudyPhase.BASELINE) {
                    val startTime = StudyStateManager.getStudyStartTimestamp(context)
                    val baselineDurationMs = Constants.BASELINE_DURATION_MS
                    val currentTime = System.currentTimeMillis()

                    if (startTime > 0 && currentTime >= (startTime + baselineDurationMs)) {
                        // Baseline duration HAS passed! Trigger transition.
                        Log.i(TAG, "Baseline duration check on RESUME: Duration PASSED ($currentTime >= ${startTime + baselineDurationMs}). Transitioning to GOAL_SETTING.")
                        val goalSettingPhase = StudyPhase.GOAL_SETTING

                        // Update local state variable FIRST for immediate UI effect (if needed, depends on UI structure)
                        currentStudyPhase = goalSettingPhase

                        // Launch coroutine to perform suspend operations (update state + load data)
                        coroutineScope.launch {
                            try {
                                // Persist the phase change locally and remotely
                                repository.saveStudyPhase(goalSettingPhase)
                                Log.i(TAG,"Successfully saved phase change to GOAL_SETTING.")
                                // Trigger loading of baseline data for the Goal Setting screen
                                homeViewModel.loadBaselineData()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving phase change or loading baseline data on resume transition.", e)
                                // Handle error if needed, potentially revert local phase change?
                                // For now, logging the error. UI might be slightly inconsistent if Firestore fails.
                            }
                        }
                        // NOTE: The `currentStudyPhase = goalSettingPhase` above ensures the UI *tries* to switch.
                        // The actual phase used in the `when` block later will be this updated value.
                    } else {
                        // Baseline duration not yet passed
                        Log.d(TAG, "Baseline duration check on RESUME: Duration NOT YET passed ($currentTime < ${startTime + baselineDurationMs}). Staying in BASELINE.")
                        // If phase hasn't changed locally, update `currentStudyPhase`
                        if (currentStudyPhase != refreshedPhase) {
                            currentStudyPhase = refreshedPhase
                        }
                    }
                } else {
                    // Not in baseline phase, just update local state variable if it changed
                    if (currentStudyPhase != refreshedPhase) {
                        Log.i(TAG, "Study phase changed on resume: $currentStudyPhase -> $refreshedPhase")
                        currentStudyPhase = refreshedPhase
                        // If phase changed TO goal setting (e.g., from remote fetch), ensure load triggered
                        if (refreshedPhase == StudyPhase.GOAL_SETTING && goalSettingState == HomeViewModel.GoalSettingUiState.IDLE) {
                            homeViewModel.loadBaselineData()
                        }
                    }
                }
                // *** END FIX ***

                // Refresh target app in ViewModel (remains unchanged)
                homeViewModel.refreshTargetApp()
            } // End ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    } // End DisposableEffect

    // Effect to Start/Stop Usage Tracking Service based on Permissions and Phase
    LaunchedEffect(permissionsGranted, currentStudyPhase) {
        val shouldBeTracking = currentStudyPhase == StudyPhase.BASELINE || currentStudyPhase.name.startsWith("INTERVENTION")
        val serviceIntent = Intent(context, UsageTrackingService::class.java)
        if (shouldBeTracking) {
            Log.i(TAG, "Conditions met (Permissions=true, Phase=$currentStudyPhase). Ensuring UsageTrackingService is started.")
            try {
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) { Log.e(TAG, "Failed to start UsageTrackingService: ${e.message}", e) }
        } else {
            Log.i(TAG, "Conditions not met (Permissions=true, Phase=$currentStudyPhase). Ensuring UsageTrackingService is stopped.")
            try { context.stopService(serviceIntent) }
            catch (e: Exception) { Log.e(TAG, "Error stopping UsageTrackingService: ${e.message}", e) }
        }
    }


    // --- Main UI Structure ---
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Content Area
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- UI ROUTING ---
            if (!permissionsGranted) {
                PermissionPromptUI(
                    hasUsageStats = hasUsageStatsPermission,
                    hasNotifications = hasNotificationPermission,
                    onGrantUsage = {
                        try { usageStatsPermissionLauncher.launch(PermissionsUtils.getUsageStatsPermissionIntent()) }
                        catch (e: Exception) { Log.e(TAG, "Error launching Usage Access Settings intent.", e) }
                    },
                    onGrantNotify = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            } else if (needsFlexSetup) {
                FlexibleDepositSetupUI { earn, lose ->
                    Log.i(TAG, "Flexible Stakes Confirmed via UI: Earn=$earn, Lose=$lose")
                    // *** CORRECTION: Launch suspend function in coroutine scope ***
                    coroutineScope.launch {
                        repository.saveFlexStakes(earn, lose)
                        repository.saveFlexStakesSetByUser(true) // <<< SET Flag to true

                    }
                    // *** END CORRECTION ***
                    flexibleStakes = Pair(earn, lose)
                    flexStakesSetByUser = true // <<< UPDATE Local Flag State

                }
            } else {
                // Permissions ok, route by phase
                Text(
                    text = stringResource(R.string.home_study_phase_label, currentStudyPhase.name),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                when (currentStudyPhase) {
                    StudyPhase.BASELINE -> BaselinePhaseUI()
                    StudyPhase.GOAL_SETTING -> {
                        // --- FIX: Implement UI for all GoalSetting states ---
                        when (goalSettingState) {
                            HomeViewModel.GoalSettingUiState.IDLE,
                            HomeViewModel.GoalSettingUiState.LOADING -> {
                                // Show loading indicator centered
                                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.goal_setting_loading_title), style = MaterialTheme.typography.titleLarge)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    CircularProgressIndicator() // <<< ADDED Loading UI
                                }
                            }
                            HomeViewModel.GoalSettingUiState.ERROR -> {
                                // Show error message and retry button centered
                                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.goal_setting_error_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(stringResource(R.string.goal_setting_error_message), textAlign = TextAlign.Center)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(onClick = { homeViewModel.loadBaselineData() }) { // <<< ADDED Error UI + Retry
                                        Text(stringResource(R.string.goal_setting_retry_button))
                                    }
                                }
                            }
                            HomeViewModel.GoalSettingUiState.LOADED,
                            HomeViewModel.GoalSettingUiState.SAVING -> {
                                // Show the actual Goal Setting content UI
                                GoalSettingPhaseContent( // This was already correct
                                    isLoading = (goalSettingState == HomeViewModel.GoalSettingUiState.SAVING),
                                    suggestedApp = suggestedApp,
                                    baselineAppList = baselineAppList,
                                    hourlyUsageData = hourlyData,
                                    onConfirmGoal = { selectedApp ->
                                        homeViewModel.confirmGoalSelection(selectedApp)
                                    }
                                )
                            }
                            HomeViewModel.GoalSettingUiState.SAVED -> {
                                // Brief "Saved" message - UI will recompose shortly due to phase change
                                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.goal_setting_saved_title), style = MaterialTheme.typography.titleLarge) // <<< ADDED Saved UI
                                }
                            }
                        } // End when(goalSettingState)
                        // --- END FIX ---
                    } // End GOAL_SETTING case

                    StudyPhase.INTERVENTION_CONTROL, StudyPhase.INTERVENTION_DEPOSIT, StudyPhase.INTERVENTION_FLEXIBLE -> {
                        InterventionPhaseUI(
                            currentPhase = currentStudyPhase,
                            points = StudyStateManager.getPointsBalance(context),
                            targetAppPackageName = StudyStateManager.getTargetApp(context),
                            dailyGoalMs = StudyStateManager.getDailyGoalMs(context),
                            todayUsageMs = todayUsageMs,
                            flexStakes = flexibleStakes
                        )
                    }
                    StudyPhase.REGISTERED, StudyPhase.COMPLETED -> {
                        StudyStatusUI(phase = currentStudyPhase)
                    }
                } // End when(currentStudyPhase)
            } // End else
        } // End Content Area Column

        // Logout Button
        Button(
            onClick = {
                Log.i(TAG, "Logout button clicked.")
                coroutineScope.launch { // <<< CORRECTION: Ensure ALL suspend calls are inside launch
                    try {
                        context.stopService(Intent(context, UsageTrackingService::class.java))
                        Log.d(TAG, "UsageTrackingService stop command issued.")
                        workManager.cancelUniqueWork(Constants.DAILY_CHECK_WORKER_NAME)
                        workManager.cancelUniqueWork(Constants.FIRESTORE_SYNC_WORKER_NAME)
                        Log.d(TAG, "Background workers cancelled.")
                        Firebase.auth.signOut()
                        Log.d(TAG, "Firebase sign out successful.")
                        repository.clearStudyState() // <<< CORRECTION: Moved inside launch
                        Log.d(TAG, "Local study state cleared.")
                        homeViewModel.refreshTargetApp()
                        Log.d(TAG, "ViewModel state refreshed.")
                        navController.navigate(Screen.AuthDecision.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during logout process", e)
                    }
                } // <<< CORRECTION: End coroutineScope.launch
            }
        ) {
            Text(stringResource(R.string.home_logout_button))
        } // End Logout Button
    } // End Main Screen Column
}


// --- Placeholder Composables for Extracted UI Sections ---

@Composable
private fun PermissionPromptUI(
    hasUsageStats: Boolean,
    hasNotifications: Boolean,
    onGrantUsage: () -> Unit,
    onGrantNotify: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.home_permission_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (!hasNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Text(
                stringResource(R.string.home_permission_notification_message),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onGrantNotify) {
                Text(stringResource(R.string.home_permission_notification_button))
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (!hasUsageStats) {
            Text(
                stringResource(R.string.home_permission_usage_message),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onGrantUsage) {
                Text(stringResource(R.string.home_permission_usage_button))
            }
            Spacer(modifier = Modifier.height(16.dp))
            // Note: Removed the "Check Permissions Again" button as checks happen on resume
        }
    }
}

@Composable
private fun BaselinePhaseUI() {
    val context = LocalContext.current // Needed for StudyStateManager
    val startTime = StudyStateManager.getStudyStartTimestamp(context)
    val baselineDurationDays = TimeUnit.MILLISECONDS.toDays(Constants.BASELINE_DURATION_MS).toInt()
    val daysElapsed = if (startTime > 0) TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - startTime) else 0
    val daysRemaining = (baselineDurationDays - daysElapsed).coerceAtLeast(0)

    Column(
        modifier = Modifier.fillMaxSize(), // Take full space
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.home_baseline_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.home_baseline_progress, daysElapsed + 1, baselineDurationDays),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_baseline_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        // Optional: Display remaining days/time
        if (daysRemaining > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Approximately $daysRemaining day(s) remaining.", // Consider making this a string resource
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun StudyStatusUI(phase: StudyPhase) {
    Column(
        modifier = Modifier.fillMaxSize(), // Take full space
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val titleRes = if (phase == StudyPhase.REGISTERED) R.string.home_registered_title else R.string.home_completed_title
        val messageRes = if (phase == StudyPhase.REGISTERED) R.string.home_registered_message else R.string.home_completed_message

        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(messageRes),
            textAlign = TextAlign.Center
        )
        // Optionally add a refresh button for REGISTERED state if needed
        // if (phase == StudyPhase.REGISTERED) {
        //     Spacer(modifier = Modifier.height(16.dp))
        //     Button(onClick = { /* TODO: Refresh logic if needed */ }) { Text(stringResource(R.string.home_registered_refresh_button)) }
        // }
    }
}