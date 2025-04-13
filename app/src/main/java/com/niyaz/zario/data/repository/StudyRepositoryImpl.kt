package com.niyaz.zario.data.repository

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.StudyPhase
import com.niyaz.zario.data.local.AppUsageBaseline
import com.niyaz.zario.data.local.BaselineUsageRecord
import com.niyaz.zario.data.local.DailyDuration
import com.niyaz.zario.data.local.UsageStatDao
import com.niyaz.zario.data.local.UsageStatEntity
import com.niyaz.zario.data.model.AppBaselineInfo
import com.niyaz.zario.utils.AppInfoHelper
import com.niyaz.zario.utils.Constants
import com.niyaz.zario.utils.StudyStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Concrete implementation of the [StudyRepository] interface.
 * Manages data access and persistence logic, coordinating between local sources
 * (SharedPreferences via [StudyStateManager], Room via [UsageStatDao]) and remote
 * (Firebase Firestore). Also utilizes [AppInfoHelper] for application metadata.
 *
 * @param context Application context, needed for SharedPreferences and AppInfoHelper.
 * @param usageStatDao DAO for accessing the Room database (usage stats).
 * @param firestore Instance of FirebaseFirestore. Defaults to `Firebase.firestore`.
 */
class StudyRepositoryImpl(
    private val context: Context,
    private val usageStatDao: UsageStatDao,
    private val firestore: FirebaseFirestore = Firebase.firestore // Default instance
) : StudyRepository {

    private val TAG = "StudyRepositoryImpl" // Class Tag for Logging

    // Helper to get current user ID safely from local state
    private fun getCurrentUserId(): String? = StudyStateManager.getUserId(context)

    // Helper to update single Firestore field safely
    private suspend fun updateFirestoreField(userId: String, field: String, value: Any?): Boolean {
        return try {
            firestore.collection(Constants.FIRESTORE_COLLECTION_USERS).document(userId)
                .update(field, value)
                .await()
            Log.d(TAG, "Firestore update successful for user $userId: $field = $value")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Firestore update failed for user $userId: $field = $value", e)
            false
        }
    }

    // Helper to update multiple Firestore fields safely
    private suspend fun updateFirestoreFields(userId: String, data: Map<String, Any?>): Boolean {
        return try {
            firestore.collection(Constants.FIRESTORE_COLLECTION_USERS).document(userId)
                .update(data)
                .await()
            Log.d(TAG, "Firestore update successful for user $userId: ${data.keys}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Firestore update failed for user $userId: ${data.keys}", e)
            false
        }
    }

    // --- User Session & Identification ---

    override fun getUserId(): String? = StudyStateManager.getUserId(context)

    override suspend fun saveUserId(userId: String) {
        // SharedPreferences writes are fast but perform on IO dispatcher for consistency
        withContext(Dispatchers.IO) {
            StudyStateManager.saveUserId(context, userId)
        }
    }

    // --- Study State Management ---

    override fun getStudyPhase(): StudyPhase = StudyStateManager.getStudyPhase(context)

    override suspend fun saveStudyPhase(phase: StudyPhase) {
        withContext(Dispatchers.IO) {
            StudyStateManager.saveStudyPhase(context, phase)
            getCurrentUserId()?.let { userId ->
                // Use constant for Firestore field name
                updateFirestoreField(userId, Constants.FIRESTORE_FIELD_STUDY_PHASE, phase.name)
            }
        }
    }

    override fun getStudyStartTimestamp(): Long = StudyStateManager.getStudyStartTimestamp(context)

    override suspend fun saveStudyStartTimestamp(timestamp: Long) {
        withContext(Dispatchers.IO) {
            StudyStateManager.saveStudyStartTimestamp(context, timestamp)
            getCurrentUserId()?.let { userId ->
                // Use constant for Firestore field name
                updateFirestoreField(userId, Constants.FIRESTORE_FIELD_STUDY_START_TIMESTAMP, timestamp)
            }
        }
    }

    override fun getCondition(): StudyPhase? = StudyStateManager.getCondition(context)

    override suspend fun saveCondition(condition: StudyPhase) {
        withContext(Dispatchers.IO) {
            if (condition.name.startsWith("INTERVENTION")) { // Basic check for valid intervention phase
                StudyStateManager.saveCondition(context, condition)
                getCurrentUserId()?.let { userId ->
                    // Use constant for Firestore field name
                    updateFirestoreField(userId, Constants.FIRESTORE_FIELD_STUDY_CONDITION, condition.name)
                }
            } else {
                Log.w(TAG, "Attempted to save non-intervention phase as condition: $condition")
            }
        }
    }

    override fun getTargetApp(): String? = StudyStateManager.getTargetApp(context)

    override suspend fun saveTargetApp(packageName: String?) {
        withContext(Dispatchers.IO) {
            StudyStateManager.saveTargetApp(context, packageName)
            getCurrentUserId()?.let { userId ->
                // Use constant for Firestore field name
                updateFirestoreField(userId, Constants.FIRESTORE_FIELD_TARGET_APP, packageName)
            }
        }
    }

    override fun getDailyGoalMs(): Long? = StudyStateManager.getDailyGoalMs(context)

    override suspend fun saveDailyGoalMs(goalMs: Long?) {
        withContext(Dispatchers.IO) {
            StudyStateManager.saveDailyGoalMs(context, goalMs)
            getCurrentUserId()?.let { userId ->
                // Use constant for Firestore field name
                updateFirestoreField(userId, Constants.FIRESTORE_FIELD_DAILY_GOAL, goalMs)
            }
        }
    }

    override fun getPointsBalance(): Int = StudyStateManager.getPointsBalance(context)

    override suspend fun savePointsBalance(points: Int) {
        withContext(Dispatchers.IO) {
            // Point coercion happens before calling repository (e.g., in DailyCheckWorker)
            StudyStateManager.savePointsBalance(context, points)
            getCurrentUserId()?.let { userId ->
                // Use constant for Firestore field name, save as Long for potential future scaling
                updateFirestoreField(userId, Constants.FIRESTORE_FIELD_POINTS_BALANCE, points.toLong())
            }
        }
    }

    override fun getFlexStakes(): Pair<Int?, Int?> = StudyStateManager.getFlexStakes(context)

    override suspend fun saveFlexStakes(earn: Int, lose: Int) {
        withContext(Dispatchers.IO) {
            StudyStateManager.saveFlexStakes(context, earn, lose)
            getCurrentUserId()?.let { userId ->
                // Use constants for Firestore field names
                updateFirestoreFields(userId, mapOf(
                    Constants.FIRESTORE_FIELD_FLEX_EARN to earn.toLong(), // Save as Long
                    Constants.FIRESTORE_FIELD_FLEX_LOSE to lose.toLong() // Save as Long
                ))
            }
        }
    }

    override fun getLastDailyOutcome(): Triple<Long?, Boolean?, Int?> = StudyStateManager.getLastDailyOutcome(context)

    override suspend fun saveDailyOutcome(checkTimestamp: Long, goalReached: Boolean, pointsChange: Int) {
        // Local state only, no Firestore interaction needed here
        withContext(Dispatchers.IO) {
            StudyStateManager.saveDailyOutcome(context, checkTimestamp, goalReached, pointsChange)
        }
    }

    // Uses StudyStateManager which internally uses constants now
    override suspend fun fetchAndSaveStateFromFirestore(userId: String): Boolean =
        StudyStateManager.fetchAndSaveStateFromFirestore(context, userId) // Already handles await and Firestore access


    override suspend fun clearStudyState() {
        // Local state only
        withContext(Dispatchers.IO) {
            StudyStateManager.clearStudyState(context)
        }
    }

    // --- User Profile Data (Firestore Only) ---

    override suspend fun saveUserProfile(userId: String, userData: Map<String, Any?>): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Use constant for collection name
                firestore.collection(Constants.FIRESTORE_COLLECTION_USERS).document(userId)
                    .set(userData, SetOptions.merge()) // Use set with merge for robust creation/update
                    .await()
                Log.d(TAG, "User profile saved successfully to Firestore for user $userId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save user profile to Firestore for user $userId", e)
                Result.failure(e) // Propagate exception within Result
            }
        }
    }

    // --- Usage Statistics (Room Database) ---

    override suspend fun insertUsageStat(usageStat: UsageStatEntity) {
        withContext(Dispatchers.IO) {
            try {
                // Assumes usageStat contains correct userId & isSynced=false set by caller (Service)
                usageStatDao.insertUsageStat(usageStat)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert usage stat: ${e.message}", e)
            }
        }
    }

    override suspend fun insertAllUsageStats(usageStats: List<UsageStatEntity>) {
        withContext(Dispatchers.IO) {
            try {
                // Assumes usageStats list contains entities with correct userId & isSynced=false set by caller (Service)
                usageStatDao.insertAllUsageStats(usageStats)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert usage stats list: ${e.message}", e)
            }
        }
    }

    // DAO Queries inherently run on IO dispatcher when called from appropriate scope (e.g., worker, viewModelScope)
    // Adding explicit withContext here provides defense-in-depth if called from Main thread unexpectedly.
    override suspend fun getTotalDurationForAppOnDay(userId: String, packageName: String, dayTimestamp: Long): Long? =
        withContext(Dispatchers.IO) {
            usageStatDao.getTotalDurationForAppOnDay(userId, packageName, dayTimestamp)
        }

    override suspend fun getTotalDurationForAppInRange(userId: String, packageName: String, startTime: Long, endTime: Long): Long? =
        withContext(Dispatchers.IO) {
            usageStatDao.getTotalDurationForAppInRange(userId, packageName, startTime, endTime)
        }

    // Flows are collected on the caller's context, no withContext needed here
    override fun getUsageStatsForDayFlow(userId: String, dayTimestamp: Long): Flow<List<UsageStatEntity>> =
        usageStatDao.getUsageStatsForDayFlow(userId, dayTimestamp)

    override fun getAggregatedDailyDurationForAppFlow(userId: String, packageName: String): Flow<List<DailyDuration>> =
        usageStatDao.getAggregatedDailyDurationForAppFlow(userId, packageName)

    override fun getTodayUsageForAppFlow(userId: String, packageName: String, todayDayTimestamp: Long): Flow<Long?> =
        usageStatDao.getTodayUsageForAppFlow(userId, packageName, todayDayTimestamp)

    override suspend fun getAggregatedUsageForBaseline(userId: String, startTime: Long, endTime: Long, minTotalDurationMs: Long): List<AppUsageBaseline> =
        withContext(Dispatchers.IO) {
            // Use constant for default min duration if not overridden
            usageStatDao.getAggregatedUsageForBaseline(userId, startTime, endTime, minTotalDurationMs.coerceAtLeast(Constants.USAGE_TRACKING_MIN_SAVE_DURATION_MS))
        }

    override suspend fun getAllUsageRecordsForBaseline(userId: String, startTime: Long, endTime: Long): List<BaselineUsageRecord> =
        withContext(Dispatchers.IO) {
            usageStatDao.getAllUsageRecordsForBaseline(userId, startTime, endTime)
        }

    // --- Application Information (AppInfoHelper) ---

    override suspend fun getAppDetails(packageName: String): AppBaselineInfo {
        // AppInfoHelper might access PackageManager, better to keep on IO dispatcher
        return withContext(Dispatchers.IO) {
            val details = AppInfoHelper.getAppDetails(context, packageName)
            // Create the DTO. averageDailyUsageMs is calculated elsewhere (ViewModel)
            AppBaselineInfo(
                packageName = packageName,
                appName = details.appName,
                icon = details.icon,
                averageDailyUsageMs = 0L // Placeholder, repo doesn't calculate this
            )
        }
    }

    // --- Combined High-Level Operations ---

    override suspend fun confirmGoalSelection(userId: String, selectedAppPkg: String, calculatedGoalMs: Long): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val condition = getCondition() // Get current condition via repository method
            if (condition == null || !condition.name.startsWith("INTERVENTION")) {
                Log.e(TAG,"Cannot confirm goal: Condition ($condition) is not set or not an intervention phase.")
                return@withContext Result.failure(IllegalStateException("Cannot confirm goal: Invalid condition state."))
            }

            // 1. Persist locally first (using repository methods ensures consistency)
            saveTargetApp(selectedAppPkg) // Persists locally + schedules Firestore update via helper
            saveDailyGoalMs(calculatedGoalMs) // Persists locally + schedules Firestore update via helper
            saveStudyPhase(condition) // Transition phase locally + schedules Firestore update via helper

            // 2. Perform a *direct* Firestore update to ensure atomicity for this specific operation
            // This overwrites updates potentially scheduled by the individual save methods above,
            // ensuring these three fields are updated together for goal confirmation.
            val updateData = mapOf(
                Constants.FIRESTORE_FIELD_TARGET_APP to selectedAppPkg,
                Constants.FIRESTORE_FIELD_DAILY_GOAL to calculatedGoalMs,
                Constants.FIRESTORE_FIELD_STUDY_PHASE to condition.name // Use the locally retrieved condition
            )

            // Use the helper function for the atomic update
            val firestoreSuccess = updateFirestoreFields(userId, updateData)

            if (firestoreSuccess) {
                Log.i(TAG,"Goal selection confirmed and persisted for user $userId.")
                Result.success(Unit)
            } else {
                // Local changes were already made. Log inconsistency.
                Log.e(TAG, "Goal selection saved locally but final atomic Firestore update failed for user $userId!")
                // Return failure, caller might need to handle potential inconsistency
                Result.failure(Exception("Failed to reliably update Firestore during goal confirmation."))
            }
        }
    }

    override suspend fun initializeNewUser(
        userId: String,
        userProfileData: Map<String, Any?>,
        initialPhase: StudyPhase,
        assignedCondition: StudyPhase,
        initialPoints: Int,
        registrationTimestamp: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Save profile data to Firestore using the dedicated method
            val profileResult = saveUserProfile(userId, userProfileData)
            if (profileResult.isFailure) {
                // If Firestore save fails, propagate the failure immediately
                throw profileResult.exceptionOrNull() ?: Exception("Failed to save user profile to Firestore.")
            }
            Log.d(TAG, "Firestore document successfully written for new user: $userId")

            // Step 2: Save initial state locally using StudyStateManager via repository methods
            // (This ensures local state matches the profile just saved to Firestore)
            saveUserId(userId)
            saveStudyPhase(initialPhase) // Writes to local state + schedules Firestore update (benign overwrite)
            saveCondition(assignedCondition) // Writes to local state + schedules Firestore update (benign overwrite)
            savePointsBalance(initialPoints) // Writes to local state + schedules Firestore update (benign overwrite)
            saveStudyStartTimestamp(registrationTimestamp) // Writes to local state + schedules Firestore update (benign overwrite)
            // Ensure other potentially relevant states are cleared/defaulted locally
            saveTargetApp(null) // Clears locally + schedules Firestore update (benign overwrite)
            saveDailyGoalMs(null) // Clears locally + schedules Firestore update (benign overwrite)
            // Save default flex stakes locally (Firestore update benign if not flex condition)
            saveFlexStakes(Constants.FLEX_STAKES_MIN_EARN, Constants.FLEX_STAKES_MIN_LOSE)
            // Clear previous day outcome locally (no Firestore interaction)
            saveDailyOutcome(0L, false, 0)

            Log.d(TAG, "Local state initialized via Repository for new user: $userId")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize new user $userId (Firestore or Local save)", e)
            // Let the caller (e.g., AuthScreen) handle cleanup like deleting the Auth user
            Result.failure(e)
        }
    }
}