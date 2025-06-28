package com.niyaz.zario.ui.screens // Ensure correct package


import android.Manifest
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.R
import com.niyaz.zario.StudyPhase
import com.niyaz.zario.data.local.AppDatabase
import com.niyaz.zario.data.model.AppBaselineInfo
import com.niyaz.zario.data.repository.StudyRepository
import com.niyaz.zario.data.repository.StudyRepositoryImpl
import com.niyaz.zario.navigation.Screen
import com.niyaz.zario.services.UsageTrackingService
import com.niyaz.zario.ui.viewmodels.HomeViewModel
import com.niyaz.zario.ui.viewmodels.HomeViewModel.GoalSettingUiState
import com.niyaz.zario.utils.AppInfoHelper
import com.niyaz.zario.utils.Constants
import com.niyaz.zario.utils.PermissionsUtils
import com.niyaz.zario.utils.StudyStateManager
import com.niyaz.zario.workers.DailyCheckWorker
import com.niyaz.zario.workers.FirestoreSyncWorker
import java.util.concurrent.TimeUnit
import kotlin.math.max




@Composable
fun HomeScreen(navController: NavController) { // Inject ViewModel
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current


    // --- Instantiate Repository (Similar to AuthScreens) ---
    // In a larger app, use DI (Hilt/Koin) to provide the repository here.
    val repository: StudyRepository = remember {
        StudyRepositoryImpl(
            context = context.applicationContext,
            usageStatDao = AppDatabase.getDatabase(context.applicationContext).usageStatDao()
        )
    }


    // --- Instantiate ViewModel using the Factory ---
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.provideFactory(repository = repository)
    )
    // --- End ViewModel Instantiation ---




    // --- Collect State from ViewModel ---
    val todayUsageMs by homeViewModel.todayUsageMs.collectAsState()
    val goalSettingState by homeViewModel.goalSettingState.collectAsState()
    val baselineAppList by homeViewModel.baselineAppList.collectAsState()
    val suggestedApp by homeViewModel.suggestedApp.collectAsState()
    val hourlyData by homeViewModel.hourlyUsageData.collectAsState() // Collect hourly data


    // --- Local State (Permissions, Phase, Stakes) ---
    var hasUsageStatsPermission by remember { mutableStateOf(PermissionsUtils.hasUsageStatsPermission(context)) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
            else ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    // Track current phase locally, but rely on ViewModel/StateManager for source of truth
    var currentStudyPhase by remember { mutableStateOf(StudyStateManager.getStudyPhase(context)) }
    // Track flex stakes locally
    var flexibleStakes by remember {
        mutableStateOf(
            if (StudyStateManager.getStudyPhase(context) == StudyPhase.INTERVENTION_FLEXIBLE) {
                StudyStateManager.getFlexStakes(context)
            } else {
                Pair(null, null) // Default to null if not in flex phase initially
            }
        )
    }
    // --- End Local State ---




    // --- Permission Launchers --- (Unchanged)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        Log.d("HomeScreen", "Notification permission granted: $isGranted")
        hasNotificationPermission = isGranted
    }


// --- Schedule Daily AND Sync Workers ---
    LaunchedEffect(currentStudyPhase) { // Trigger based on phase change
        val workManager = WorkManager.getInstance(context)


        // --- Daily Check Worker (Only during Intervention) ---
        if (currentStudyPhase.name.startsWith("INTERVENTION")) { // Condition remains same
            Log.d("HomeScreen", "Intervention phase active. Scheduling DailyCheckWorker.")
            // Use Constant for worker interval
            val dailyCheckRequest = PeriodicWorkRequestBuilder<DailyCheckWorker>(
                Constants.DEFAULT_CHECK_WORKER_INTERVAL_HOURS, // Example: Use a constant if defined (e.g., 24)
                TimeUnit.HOURS
            ).build() // Add constraints if needed (e.g., charging)
            // Use Constant for worker name
            workManager.enqueueUniquePeriodicWork(
                Constants.DAILY_CHECK_WORKER_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Keep existing worker if running
                dailyCheckRequest
            )
            // Use Constant for worker name
            Log.i("HomeScreen", "DailyCheckWorker enqueued/verified with name: ${Constants.DAILY_CHECK_WORKER_NAME}") // Use Constant
        } else { // If not in intervention, cancel DailyCheckWorker
            Log.d("HomeScreen", "Not in intervention phase. Cancelling DailyCheckWorker.")
            // Use Constant for worker name
            workManager.cancelUniqueWork(Constants.DAILY_CHECK_WORKER_NAME) // Use Constant
        }


        // --- Firestore Sync Worker (Runs during Baseline AND Intervention) ---
        // Schedule if user has logged in and tracking is potentially active
        val shouldRunSync = currentStudyPhase == StudyPhase.BASELINE || currentStudyPhase.name.startsWith("INTERVENTION")
        if (shouldRunSync) {
            Log.d("HomeScreen", "Baseline or Intervention phase active. Scheduling FirestoreSyncWorker.")
            val syncRequest = PeriodicWorkRequestBuilder<FirestoreSyncWorker>(
                FirestoreSyncWorker.REPEAT_INTERVAL_HOURS, // Use interval from Worker companion
                TimeUnit.HOURS
            )
                .setConstraints(FirestoreSyncWorker.WORKER_CONSTRAINTS) // Use constraints from Worker companion
                .build()


            workManager.enqueueUniquePeriodicWork(
                FirestoreSyncWorker.UNIQUE_WORK_NAME, // Use unique name from Worker companion
                ExistingPeriodicWorkPolicy.KEEP, // Keep existing worker if already scheduled
                syncRequest
            )
            Log.i("HomeScreen", "FirestoreSyncWorker enqueued/verified with name: ${FirestoreSyncWorker.UNIQUE_WORK_NAME}")
        } else { // Cancel if not in Baseline or Intervention (e.g., Completed, Registered)
            Log.d("HomeScreen", "Not in Baseline or Intervention phase. Cancelling FirestoreSyncWorker.")
            workManager.cancelUniqueWork(FirestoreSyncWorker.UNIQUE_WORK_NAME)
        }
    }


    // --- Re-check permissions, phase, AND stakes on Resume --- (Unchanged)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Log.d("HomeScreen", "ON_RESUME: Re-checking state.")
                hasUsageStatsPermission = PermissionsUtils.hasUsageStatsPermission(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasNotificationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                // Update local phase based on potentially changed persisted state
                currentStudyPhase = StudyStateManager.getStudyPhase(context)
                if (currentStudyPhase == StudyPhase.INTERVENTION_FLEXIBLE) {
                    flexibleStakes = StudyStateManager.getFlexStakes(context)
                }
                // If resumed into Goal Setting phase, ensure baseline data is loaded if needed
                if (currentStudyPhase == StudyPhase.GOAL_SETTING && goalSettingState == GoalSettingUiState.IDLE) {
                    homeViewModel.loadBaselineData()
                }
                // Refresh target app in ViewModel on resume in case it changed externally (less likely but safe)
                if (currentStudyPhase.name.startsWith("INTERVENTION")) {
                    homeViewModel.refreshTargetApp()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }


    // --- Start/Stop Service Effect --- (Unchanged)
    LaunchedEffect(hasUsageStatsPermission, hasNotificationPermission, currentStudyPhase) {
        val shouldBeTracking = hasUsageStatsPermission && hasNotificationPermission &&
                (currentStudyPhase == StudyPhase.BASELINE || currentStudyPhase.name.startsWith("INTERVENTION"))
        val serviceIntent = Intent(context, UsageTrackingService::class.java)
        if (shouldBeTracking) {
            Log.d("HomeScreen", "Conditions met for tracking. Ensuring service is started.")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d("HomeScreen", "Service start command issued.")
            } catch (e: Exception) {
                Log.e("HomeScreen", "Failed to start UsageTrackingService: ${e.message}", e)
            }
        } else {
            Log.d("HomeScreen", "Conditions not met for tracking. Ensuring service is stopped.")
            try {
                context.stopService(serviceIntent)
                Log.d("HomeScreen", "Service stop command issued.")
            } catch (e: Exception) {
                Log.e("HomeScreen", "Error stopping service: ${e.message}")
            }
        }
    }


// --- UI ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top, // Changed to Top for Goal Setting flow
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Determine if we need to show prompts or the main content
        val showPermissionPrompts = !hasUsageStatsPermission || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission)
        val showActiveContent = !showPermissionPrompts // Only show main content if permissions are OK


        if (showActiveContent) {
            // --- Display Active Content (Phase Dependent) ---
            // Show Phase always at the top for context
            Text(
                text = stringResource(R.string.home_study_phase_label, currentStudyPhase.name), // Use stringResource
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp) // Add padding below phase
            )


            // Check if Flexible Deposit stakes need setup
            val needsFlexSetup = currentStudyPhase == StudyPhase.INTERVENTION_FLEXIBLE && flexibleStakes.first == null


            if (needsFlexSetup) {
                // --- Show Flexible Deposit Setup UI --- (Composable updated below)
                FlexibleDepositSetupUI { earn, lose ->
                    // Callback when user confirms stakes
                    Log.d("HomeScreen", "Flexible Stakes Confirmed: Earn=$earn, Lose=$lose")
                    // Save stakes locally and to Firestore
                    StudyStateManager.saveFlexStakes(context, earn, lose)
                    val userId = StudyStateManager.getUserId(context)
                    if (userId != null) {
                        val firestore = Firebase.firestore
                        firestore.collection("users").document(userId)
                            .update(mapOf("flexPointsEarn" to earn.toLong(), "flexPointsLose" to lose.toLong())) // Save as Long
                            .addOnSuccessListener { Log.i("HomeScreen", "Firestore flex stakes updated.") }
                            .addOnFailureListener { e -> Log.e("HomeScreen", "Firestore flex stakes update failed.", e)}
                    }
                    // Update local state to hide setup UI and show main intervention UI
                    flexibleStakes = Pair(earn, lose)
                }
            } else {
                // --- Show Main Content based on Phase (when flex setup not needed or not flex condition) ---
                when (currentStudyPhase) {
                    StudyPhase.BASELINE -> { // --- Baseline UI (Text updated) ---
                        val startTime = StudyStateManager.getStudyStartTimestamp(context)
                        // Use Constant for baseline duration
                        val baselineDurationDays = Constants.BASELINE_DURATION_DAYS
                        val baselineDurationMs = TimeUnit.DAYS.toMillis(baselineDurationDays.toLong())


                        // --- Check for Baseline Completion ---
                        LaunchedEffect(startTime) {
                            if (startTime > 0) {
                                val timeElapsedMs = System.currentTimeMillis() - startTime
                                if (timeElapsedMs >= baselineDurationMs) {
                                    Log.i("HomeScreen", "Baseline period complete ($timeElapsedMs ms elapsed). Transitioning to GOAL_SETTING.")
                                    val goalSettingPhase = StudyPhase.GOAL_SETTING
                                    StudyStateManager.saveStudyPhase(context, goalSettingPhase)
                                    val userId = StudyStateManager.getUserId(context)
                                    if (userId != null) {
                                        Firebase.firestore.collection("users").document(userId)
                                            .update("studyPhase", goalSettingPhase.name)
                                            .addOnSuccessListener { Log.i("HomeScreen", "Firestore phase updated to GOAL_SETTING.") }
                                            .addOnFailureListener{ e -> Log.e("HomeScreen", "Firestore phase update failed.", e) }
                                    }
                                    currentStudyPhase = goalSettingPhase // Update local state immediately
                                    // Trigger baseline load in ViewModel now that phase changed
                                    homeViewModel.loadBaselineData()
                                } else {
                                    // Use the *variable* baselineDurationDays which holds the constant
                                    val daysRemaining = baselineDurationDays - TimeUnit.MILLISECONDS.toDays(timeElapsedMs)
                                    Log.d("HomeScreen","Baseline ongoing. Approx ${daysRemaining + 1} days remaining.") // +1 for current day
                                }
                            } else {
                                Log.w("HomeScreen", "Baseline phase active but start timestamp invalid ($startTime).")
                            }
                        }
                        // --- END Check ---


                        // --- Baseline UI Content --- (Text updated)
                        Text(stringResource(R.string.home_baseline_title), style = MaterialTheme.typography.headlineMedium) // Use stringResource
                        Spacer(modifier = Modifier.height(16.dp))
                        val daysElapsed = if (startTime > 0) TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - startTime) else 0
                        // Use the *variable* baselineDurationDays which holds the constant
                        Text(stringResource(R.string.home_baseline_progress, daysElapsed + 1, baselineDurationDays), style = MaterialTheme.typography.bodyLarge) // Use stringResource
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.home_baseline_message), // Use stringResource
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        // --- End Baseline UI ---
                    } // End BASELINE case


                    // --- GOAL_SETTING IMPLEMENTATION (Text updated) ---
                    StudyPhase.GOAL_SETTING -> {
                        // Trigger load if entering this state and it's idle
                        LaunchedEffect(goalSettingState) {
                            if (goalSettingState == GoalSettingUiState.IDLE) {
                                homeViewModel.loadBaselineData()
                            }
                        }


                        // Display UI based on the ViewModel state
                        when (goalSettingState) {
                            GoalSettingUiState.IDLE, GoalSettingUiState.LOADING -> {
                                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.goal_setting_loading_title), style = MaterialTheme.typography.titleLarge) // Use stringResource
                                    Spacer(modifier = Modifier.height(16.dp))
                                    CircularProgressIndicator()
                                }
                            }
                            GoalSettingUiState.ERROR -> {
                                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.goal_setting_error_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error) // Use stringResource
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(stringResource(R.string.goal_setting_error_message), textAlign = TextAlign.Center) // Use stringResource
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(onClick = { homeViewModel.loadBaselineData() }) {
                                        Text(stringResource(R.string.goal_setting_retry_button)) // Use stringResource
                                    }
                                }
                            }
                            GoalSettingUiState.LOADED, GoalSettingUiState.SAVING -> {
                                // *** ADD hourlyUsageData from ViewModel ***
                                val hourlyData by homeViewModel.hourlyUsageData.collectAsState()


                                GoalSettingContent( // Call updated composable below
                                    isLoading = (goalSettingState == GoalSettingUiState.SAVING),
                                    suggestedApp = suggestedApp,
                                    baselineAppList = baselineAppList,
                                    hourlyUsageData = hourlyData, // Pass the data
                                    onConfirmGoal = { selectedApp ->
                                        homeViewModel.confirmGoalSelection(selectedApp)
                                    }
                                )
                            }
                            GoalSettingUiState.SAVED -> {
                                // This state is transient, the phase change should trigger recomposition
                                // to show the intervention UI. Can show a brief message if needed.
                                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.goal_setting_saved_title), style = MaterialTheme.typography.titleLarge) // Use stringResource
                                    // Recomposition will happen automatically due to phase change
                                }
                            }
                        }
                    } // --- END GOAL_SETTING CASE ---




                    StudyPhase.INTERVENTION_CONTROL,
                    StudyPhase.INTERVENTION_DEPOSIT,
                    StudyPhase.INTERVENTION_FLEXIBLE -> { // --- Intervention UI (Text updated) ---
                        // --- Refactored Intervention UI based on Dashboard Sketch ---
                        val condition = StudyStateManager.getCondition(context) ?: currentStudyPhase
                        val points = StudyStateManager.getPointsBalance(context)
                        val targetAppPkg = StudyStateManager.getTargetApp(context)
                        val targetAppName = targetAppPkg?.let { AppInfoHelper.getAppName(context, it) } ?: "Your Target App"
                        val goalMs = StudyStateManager.getDailyGoalMs(context)
                        val (flexEarn, flexLose) = flexibleStakes


                        // *** FIX: Read colors from theme outside Canvas *** (Unchanged)
                        val primaryColor = MaterialTheme.colorScheme.primary
                        val trackColor = MaterialTheme.colorScheme.surfaceVariant // Use theme color for track


                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), // Add horizontal padding
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // 1. Dashboard Title
                            Text(
                                stringResource(R.string.dashboard_title), // Use stringResource
                                style = MaterialTheme.typography.headlineMedium, // Or headlineLarge if preferred
                                modifier = Modifier.padding(bottom = 16.dp)
                            )


                            // 2. Goal and Points Row
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                                horizontalArrangement = Arrangement.SpaceAround, // Space out items
                                verticalAlignment = Alignment.Top // Align tops
                            ) {
                                // Goal Section
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.dashboard_goal_section_title), style = MaterialTheme.typography.titleMedium) // Use stringResource
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (goalMs != null) stringResource(R.string.dashboard_goal_text, targetAppName, formatDuration(goalMs)) else stringResource(R.string.dashboard_goal_not_set), // Use stringResource
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.heightIn(min = 40.dp) // Ensure space
                                    )
                                }


                                // Points Section
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.dashboard_points_section_title), style = MaterialTheme.typography.titleMedium) // Use stringResource
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "$points", // Points value itself is dynamic
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } // End Row Goal/Points


                            // --- Circular Progress for Time Left ---
                            if (goalMs != null && goalMs > 0) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth(0.6f) // Control size relative to screen width
                                        .aspectRatio(1f) // Make it a square for a perfect circle
                                        .padding(bottom = 24.dp)
                                ) {
                                    val remainingMs = (goalMs - todayUsageMs).coerceAtLeast(0L)
                                    // Progress for indicator filling: 0.0 means full time left, 1.0 means no time left
                                    val progressFraction = (todayUsageMs.toFloat() / goalMs.toFloat()).coerceIn(0f, 1f)
                                    val sweepAngle = progressFraction * 360f
                                    val strokeWidth = 12.dp // Define stroke width
                                    // val primaryColor = MaterialTheme.colorScheme.primary // Read color from theme (already defined above)
                                    // val trackColor = MaterialTheme.colorScheme.surfaceVariant // Use theme color for track (already defined above)


                                    // *** REFACTORED: Draw both arcs in one Canvas *** (Unchanged)
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        // Background track
                                        drawArc(
                                            color = trackColor,
                                            startAngle = -90f, // Start at the top
                                            sweepAngle = 360f, // Full circle
                                            useCenter = false,
                                            style = Stroke(width = strokeWidth.toPx())
                                        )
                                        // Foreground progress arc
                                        drawArc(
                                            color = primaryColor,
                                            startAngle = -90f, // Start at the top
                                            sweepAngle = sweepAngle, // Angle based on progress
                                            useCenter = false,
                                            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round) // Round cap for progress
                                        )
                                    }
                                    // *** END REFACTORED Canvas ***


                                    // Text inside (Updated)
                                    Text(
                                        text = stringResource(R.string.dashboard_progress_text, formatDuration(remainingMs)), // Use stringResource
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                // Placeholder if goal isn't set (Updated)
                                Text(stringResource(R.string.dashboard_progress_goal_not_set), style = MaterialTheme.typography.bodyLarge) // Use stringResource
                                Spacer(modifier = Modifier.height(24.dp))
                            } // End Circular Progress Box


                            // --- Daily Commitment Text ---
                            Text(stringResource(R.string.dashboard_commitment_section_title), style = MaterialTheme.typography.titleMedium) // Use stringResource
                            Spacer(modifier = Modifier.height(8.dp))
                            // Use a simple Card for background/border or just Text
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                val commitmentText = when(condition) { // Use resolved stringResource results
                                    StudyPhase.INTERVENTION_CONTROL -> stringResource(R.string.dashboard_commitment_control)
                                    StudyPhase.INTERVENTION_DEPOSIT -> stringResource(R.string.dashboard_commitment_deposit)
                                    StudyPhase.INTERVENTION_FLEXIBLE -> stringResource(R.string.dashboard_commitment_flex, flexEarn ?: "-", flexLose ?: "-")
                                    else -> stringResource(R.string.dashboard_commitment_fallback)
                                }
                                Text(
                                    text = commitmentText, // Use resolved string
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(16.dp).fillMaxWidth() // Padding inside card
                                )
                            } // End Card Commitment
                            Spacer(modifier = Modifier.height(16.dp)) // Space at the very bottom if needed


                        } // End Main Intervention Column
                    } // End INTERVENTION_* case


                    StudyPhase.REGISTERED -> { // --- Registered UI (Text updated) ---
                        Text(stringResource(R.string.home_registered_title), style = MaterialTheme.typography.headlineMedium) // Use stringResource
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.home_registered_message), textAlign = TextAlign.Center) // Use stringResource
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { currentStudyPhase = StudyStateManager.getStudyPhase(context) }) { Text(stringResource(R.string.home_registered_refresh_button)) } // Use stringResource
                    } // End REGISTERED case


                    StudyPhase.COMPLETED -> { // --- Completed UI (Text updated) ---
                        Text(stringResource(R.string.home_completed_title), style = MaterialTheme.typography.headlineMedium) // Use stringResource
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.home_completed_message), textAlign = TextAlign.Center) // Use stringResource
                    } // End COMPLETED case


                } // End of when(currentStudyPhase)


                // --- Logout Button (Positioned at bottom regardless of phase content) ---
                Spacer(modifier = Modifier.weight(1f)) // Pushes button to bottom
                Button(onClick = {
                    Log.d("HomeScreen", "Logout clicked.")
                    // 1. Stop services (if applicable)
                    try { context.stopService(Intent(context, UsageTrackingService::class.java)); Log.d("HomeScreen", "Service stop issued.") }
                    catch (e: Exception) { Log.e("HomeScreen", "Error stopping service.", e) }


                    // 2. Sign out from Firebase *FIRST*
                    Firebase.auth.signOut() // This call is now valid due to the import


                    // 3. Clear local study state *AFTER* Firebase sign out
                    StudyStateManager.clearStudyState(context) // Clear local prefs


                    // 4. Clear relevant ViewModel state
                    // Ensure homeViewModel instance is accessible in this scope
                    homeViewModel.refreshTargetApp() // Example: Clear target app in VM


                    // 5. Navigate *LAST*
                    navController.navigate(Screen.AuthDecision.route) {
                        popUpTo(Screen.Home.route) { inclusive = true } // Clear backstack up to Home
                        launchSingleTop = true
                    }
                }) { Text(stringResource(R.string.home_logout_button)) }
                Spacer(modifier = Modifier.height(16.dp)) // Padding below logout button
                // --- End Logout Button ---


            } // END of else block: if(needsFlexSetup)


        } else { // showPermissionPrompts is true
            // --- Display Permission Prompts --- (Text updated)
            Column( // Use a Column to center the prompts vertically
                modifier = Modifier.fillMaxSize(), // Take full space
                verticalArrangement = Arrangement.Center, // Center vertically
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.home_permission_title), // Use stringResource
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))


                // Notification Permission Prompt
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                    Text(
                        stringResource(R.string.home_permission_notification_message), // Use stringResource
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text(stringResource(R.string.home_permission_notification_button)) // Use stringResource
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }


                // Usage Stats Permission Prompt
                if (!hasUsageStatsPermission) {
                    Text(
                        stringResource(R.string.home_permission_usage_message), // Use stringResource
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        try {
                            val intent = PermissionsUtils.getUsageStatsPermissionIntent()
                            startActivity(context, intent, null)
                        } catch (e: Exception) {
                            Log.e("HomeScreen", "Error opening Usage Access Settings: ${e.message}", e)
                        }
                    }) {
                        Text(stringResource(R.string.home_permission_usage_button)) // Use stringResource
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        hasUsageStatsPermission = PermissionsUtils.hasUsageStatsPermission(context)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            hasNotificationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                        Log.d("HomeScreen", "Manual permission check results - UsageStats: $hasUsageStatsPermission, Notification: $hasNotificationPermission")
                    }) {
                        Text(stringResource(R.string.home_permission_check_button)) // Use stringResource
                    }
                }


                Spacer(modifier = Modifier.height(32.dp))


                // Logout Button (Available even if permissions aren't granted)
                Button(onClick = {
                    Log.d("HomeScreen", "Logout clicked while permissions pending.")
                    StudyStateManager.clearStudyState(context)
                    homeViewModel.refreshTargetApp() // Clear VM state too
                    navController.navigate(Screen.AuthDecision.route) { popUpTo(Screen.Home.route) { inclusive = true }; launchSingleTop = true }
                }) { Text(stringResource(R.string.home_logout_button)) } // Use stringResource
            } // End Column for Prompts
        } // End of else block (showPermissionPrompts)
    } // End of Main Column
}




// --- GoalSettingContent Composable (Ensure hourlyUsageData usage, Text updated) ---
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalSettingContent(
    isLoading: Boolean,
    suggestedApp: AppBaselineInfo?,
    baselineAppList: List<AppBaselineInfo>,
    hourlyUsageData: Map<Int, Long>, // Already receiving the collected state
    onConfirmGoal: (selectedApp: AppBaselineInfo) -> Unit
) {
    var selectedApp by remember { mutableStateOf(suggestedApp) } // Initially select the suggested app


    // Use constant for number of apps to show
    val appsToShow = baselineAppList.take(Constants.GOAL_SETTING_APP_ICON_COUNT) // Use Constant


    // Function to calculate the display goal string (Unchanged logic, updated constants)
    fun calculateGoalString(appInfo: AppBaselineInfo?): String {
        if (appInfo == null) return "N/A"
        val baselineAverageMs = appInfo.averageDailyUsageMs
        // Use constant for reduction factor and minimum goal
        val reductionFactor = Constants.GOAL_REDUCTION_FACTOR // Use Constant
        val dailyGoalMs = (baselineAverageMs * reductionFactor).toLong().coerceAtLeast(Constants.MINIMUM_GOAL_DURATION_MS) // Use Constant
        return formatDuration(dailyGoalMs) // Use helper for formatting
    }


    Column(
        modifier = Modifier
            .fillMaxSize() // Take available space
            .padding(top = 8.dp), // Add some top padding
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.goal_setting_title), style = MaterialTheme.typography.headlineMedium) // Use stringResource
        Spacer(modifier = Modifier.height(8.dp))


        // Suggestion Text - Now incorporates suggested app name and uses stringResource
        val suggestionText = if (suggestedApp != null) {
            stringResource(R.string.goal_setting_suggestion_with_app, suggestedApp.appName) // Use stringResource
        } else {
            stringResource(R.string.goal_setting_suggestion_no_app) // Use stringResource
        }
        Text(
            text = suggestionText, // Use resolved string
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp)) // More space before icons


        // --- App Icon Selection ---
        Text(stringResource(R.string.goal_setting_selection_title), style = MaterialTheme.typography.titleMedium) // Use stringResource
        Spacer(modifier = Modifier.height(12.dp))
        // Using FlowRow for simple wrapping layout of icons
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            maxItemsInEachRow = 4 // Adjust as needed (or make this a constant too if desired)
        ) {
            appsToShow.forEach { appInfo -> // Uses appsToShow list
                AppIconItem( // Use updated composable below
                    appInfo = appInfo,
                    isSelected = selectedApp?.packageName == appInfo.packageName,
                    onClick = { selectedApp = appInfo }
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp)) // More space after icons


        // --- Display Info for Currently Selected App ---
        // Use a Box to handle the case where nothing is selected yet
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            if (selectedApp != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.goal_setting_selected_app_info, selectedApp!!.appName, formatDuration(selectedApp!!.averageDailyUsageMs)), // Use stringResource
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.goal_setting_selected_app_goal, calculateGoalString(selectedApp)), // Use stringResource
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.goal_setting_select_prompt), // Use stringResource
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }


        // --- ADD: Hourly Usage Chart --- (Ensure it uses the parameter, Text updated)
        if (hourlyUsageData.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.goal_setting_chart_title), style = MaterialTheme.typography.titleMedium) // Use stringResource
            Spacer(modifier = Modifier.height(8.dp))
            HourlyUsageBarChart( // Call updated composable below
                usageData = hourlyUsageData, // Pass the received map
                modifier = Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 8.dp)
            )
        }
        // --- End Hourly Usage Chart ---




        Spacer(modifier = Modifier.weight(1f)) // Push confirm button to bottom


        // --- Confirmation Button ---
        Button(
            onClick = { selectedApp?.let { onConfirmGoal(it) } },
            enabled = selectedApp != null && !isLoading, // Enable only if an app is selected and not saving
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp) // Add bottom padding
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                // Use stringResource for button text
                val buttonText = selectedApp?.appName?.let { stringResource(R.string.goal_setting_confirm_button, it) } ?: stringResource(R.string.goal_setting_confirm_button_default)
                Text(buttonText) // Use resolved string
            }
        }
    }
}


// --- CORRECTED Composable for Bar Chart (Text updated) ---
@Composable
fun HourlyUsageBarChart(
    usageData: Map<Int, Long>, // Hour (0-23) -> Duration Ms
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    axisLabelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, // Use a less prominent color
    axisLabelFontSize: TextUnit = 10.sp
) {
    if (usageData.isEmpty()) {
        // Optional: Show a placeholder text if no data (Use stringResource)
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.goal_setting_chart_empty), style = MaterialTheme.typography.bodySmall) // Use stringResource
        }
        return
    }


    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current


    // Convert SP to Px for calculations
    val axisLabelFontSizePx = remember(axisLabelFontSize) { with(density) { axisLabelFontSize.toPx() } }


    // Find max usage value to scale bars
    // Ensure maxUsageMs is at least 1 to avoid division by zero or NaN issues
    val maxUsageMs = remember(usageData) { max(1L, usageData.values.maxOrNull() ?: 1L) }




    Canvas(modifier = modifier) {
        val chartHeight = size.height
        val chartWidth = size.width
        val barCount = 24


        // Reserve space for labels at the bottom (calculate based on font size)
        // Measure a sample label ("23h") to estimate height needed
        val sampleLabelLayout = textMeasurer.measure("23h", TextStyle(fontSize = axisLabelFontSize))
        val labelHeight = sampleLabelLayout.size.height * 1.5f // Add some buffer
        val barAreaHeight = (chartHeight - labelHeight).coerceAtLeast(0f) // Ensure non-negative


        // Calculate bar width and spacing based on available width
        val totalSpacing = chartWidth * 0.1f // Example: 10% of width for spacing
        val totalBarWidth = chartWidth - totalSpacing
        val barWidth = (totalBarWidth / barCount).coerceAtLeast(1f) // Ensure at least 1px
        val barSpacing = (totalSpacing / (barCount + 1)).coerceAtLeast(0f)


        // Draw X-axis labels (e.g., 0h, 6h, 12h, 18h, 23h)
        val hoursToLabel = listOf(0, 6, 12, 18, 23)
        hoursToLabel.forEach { hour ->
            val label = "${hour}h"
            // Measure the specific label text
            val textLayoutResult: TextLayoutResult = textMeasurer.measure(
                text = label,
                style = TextStyle(color = axisLabelColor, fontSize = axisLabelFontSize)
            )
            // Calculate position: Start spacing + hour * (bar + spacing) + half bar width - half text width
            val xPos = barSpacing + hour * (barWidth + barSpacing) + (barWidth / 2f) - (textLayoutResult.size.width / 2f)
            // Ensure position is within bounds
            val clampedXPos = xPos.coerceIn(0f, chartWidth - textLayoutResult.size.width)


            // Use DrawScope.drawText
            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(clampedXPos, barAreaHeight + labelHeight * 0.25f) // Position below bars, adjust offset
            )
        }


        // Draw Bars
        for (hour in 0 until barCount) {
            val usageMs = usageData.getOrDefault(hour, 0L)
            // Calculate bar height relative to max usage and available area height
            val barHeight = ((usageMs.toFloat() / maxUsageMs.toFloat()) * barAreaHeight).coerceAtLeast(0f)
            // Calculate bar start position
            val xOffset = barSpacing + hour * (barWidth + barSpacing)


            if (barHeight > 0) { // Only draw non-zero bars
                drawRect(
                    color = barColor,
                    topLeft = Offset(xOffset, barAreaHeight - barHeight), // Draw from bottom (y=barAreaHeight) up
                    size = Size(barWidth, barHeight)
                )
            }
        }
    } // End Canvas
}




// --- NEW Helper Composable for App Icon Display (Content Description updated) ---
@Composable
fun AppIconItem(
    appInfo: AppBaselineInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    iconSize: Dp = 64.dp // Make icon size configurable
) {
    Box(
        modifier = Modifier
            .size(iconSize)
            .clip(CircleShape) // Clip to circle
            .background(Color.LightGray) // Placeholder background
            .border(
                BorderStroke(
                    width = if (isSelected) 3.dp else 0.dp, // Thicker border when selected
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                ),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            // Use Coil or Accompanist Drawable Painter
            painter = rememberAsyncImagePainter(model = appInfo.icon),
            contentDescription = stringResource(R.string.goal_setting_app_icon_description, appInfo.appName), // Use stringResource
            modifier = Modifier.padding(4.dp) // Padding inside the circle for the icon itself
                .fillMaxSize() // Let the image fill the Box
        )
    }
}


// --- NEW Helper Composable for App Item Display --- (Unchanged from original)
@Composable
fun AppInfoCard(
    appInfo: AppBaselineInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // TODO: Add Icon if needed: Image(painter = rememberDrawablePainter(appInfo.icon), contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp)) // Placeholder if no icon
            Column(modifier = Modifier.weight(1f)) {
                Text(appInfo.appName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text("Avg. Daily Use: ${formatDuration(appInfo.averageDailyUsageMs)}", style = MaterialTheme.typography.bodyMedium)
            }
            // Optional: RadioButton or Checkbox indicator for selection
        }
    }
}


// --- NEW Helper Function for Formatting Duration --- (Unchanged from original)
fun formatDuration(millis: Long): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60


    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 && minutes == 0L -> "${hours}h"
        else -> "${minutes}m" // Show minutes only if less than an hour or exactly 0 hours
    }
}




// --- Flexible Deposit Setup UI Composable --- (Text updated, Constants added)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlexibleDepositSetupUI(onConfirm: (earn: Int, lose: Int) -> Unit) {
    // Use constants for slider defaults and ranges/steps
    var earnValue by remember { mutableFloatStateOf(Constants.DEFAULT_FLEX_EARN_SLIDER) } // Use Constant
    var loseValue by remember { mutableFloatStateOf(Constants.DEFAULT_FLEX_LOSE_SLIDER) } // Use Constant


    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.flex_setup_title), style = MaterialTheme.typography.headlineSmall) // Use stringResource
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.flex_setup_message), // Use stringResource
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))


        // Earn Slider
        Text(stringResource(R.string.flex_setup_earn_label, earnValue.toInt())) // Use stringResource
        Slider(
            value = earnValue,
            onValueChange = { earnValue = it },
            // Use Constants for range and steps calculation
            valueRange = Constants.FLEX_STAKES_MIN_EARN.toFloat()..Constants.FLEX_STAKES_MAX_EARN.toFloat(), // Use Constants
            steps = (Constants.FLEX_STAKES_MAX_EARN - Constants.FLEX_STAKES_MIN_EARN - 1).coerceAtLeast(0) // Use Constants, steps = range_size - 1
        )
        Spacer(modifier = Modifier.height(16.dp))


        // Lose Slider
        Text(stringResource(R.string.flex_setup_lose_label, loseValue.toInt())) // Use stringResource
        Slider(
            value = loseValue,
            onValueChange = { loseValue = it },
            // Use Constants for range and steps calculation
            valueRange = Constants.FLEX_STAKES_MIN_LOSE.toFloat()..Constants.FLEX_STAKES_MAX_LOSE.toFloat(), // Use Constants
            steps = (Constants.FLEX_STAKES_MAX_LOSE - Constants.FLEX_STAKES_MIN_LOSE - 1).coerceAtLeast(0) // Use Constants, steps = range_size - 1
        )
        Spacer(modifier = Modifier.height(24.dp))


        Button(onClick = { onConfirm(earnValue.toInt(), loseValue.toInt()) }) {
            Text(stringResource(R.string.flex_setup_confirm_button)) // Use stringResource
        }
    }
}

