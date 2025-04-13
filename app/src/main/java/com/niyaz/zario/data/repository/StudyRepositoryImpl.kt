package com.niyaz.zario.data.repository

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.StudyPhase
import com.niyaz.zario.data.local.AppUsageBaseline
import com.niyaz.zario.data.local.BaselineUsageRecord
import com.niyaz.zario.data.local.DailyDuration // Keep original import if correct
import com.niyaz.zario.data.local.UsageStatDao
import com.niyaz.zario.data.local.UsageStatEntity
import com.niyaz.zario.data.model.AppBaselineInfo
import com.niyaz.zario.utils.AppInfoHelper
import com.niyaz.zario.utils.Constants // Import Constants
import com.niyaz.zario.utils.StudyStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Concrete implementation of the StudyRepository interface.
 * Manages data access across local (SharedPreferences, Room) and remote (Firestore) sources.
 *
 * @param context Application context, needed for SharedPreferences and AppInfoHelper.
 * @param usageStatDao DAO for accessing Room database (usage stats).
 * @param firestore Instance of FirebaseFirestore.
 */
class StudyRepositoryImpl(
    private val context: Context,
    private val usageStatDao: UsageStatDao,
    private val firestore: FirebaseFirestore = Firebase.firestore
) : StudyRepository {

    private val TAG = "StudyRepositoryImpl"

    // Helper to get current user ID safely
    private fun getCurrentUserId(): String? = StudyStateManager.getUserId(context)

    // Helper to update Firestore field(s) safely
    private suspend fun updateFirestoreField(userId: String, field: String, value: Any?): Boolean {
        return try {
            // Use constant for collection name
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
    private suspend fun updateFirestoreFields(userId: String, data: Map<String, Any?>): Boolean {
        return try {
            // Use constant for collection name
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


    // --- Study State (from StudyStateManager / Firestore) ---

    override fun getUserId(): String? = StudyStateManager.getUserId(context)

    // Saving UserID primarily happens locally during login/register success callbacks
    override suspend fun saveUserId(userId: String) {
        withContext(Dispatchers.IO) {
            StudyStateManager.saveUserId(context, userId)
        }
    }

    override fun getStudyPhase(): StudyPhase = StudyStateManager.getStudyPhase(context)

    override suspend fun saveStudyPhase(phase: StudyPhase) {
        withContext(Dispatchers.IO) {
            StudyStateManager.saveStudyPhase(context, phase)
            // Also update Firestore (uses helper which now uses constant collection name)
            getCurrentUserId()?.let { userId ->
                // Field name remains string literal here as per <change> focus
                updateFirestoreField(userId, Constants.FIRESTORE_FIELD_STUDY_PHASE, phase.name)

            }
        }
    }

    override fun getStudyStartTimestamp(): Long = StudyStateManager.getStudyStartTimestamp(context)

    override suspend fun saveStudyStartTimestamp(timestamp: Long) {
        withContext(Dispatchers.IO) {
            StudyStateManager.saveStudyStartTimestamp(context, timestamp)
            getCurrentUserId()?.let { userId ->
                // Field name remains string literal here
                updateFirestoreField(userId, Constants.FIRESTORE_FIELD_STUDY_START_TIMESTAMP, timestamp)
            }
        }
    }

    override fun getCondition(): StudyPhase? = StudyStateManager.getCondition(context)

    override suspend fun saveCondition(condition: StudyPhase) {
        withContext(Dispatchers.IO) {
            if (condition.name.startsWith("INTERVENTION")) {
                StudyStateManager.saveCondition(context, condition)
                getCurrentUserId()?.let { userId ->
                    // Field name remains string literal here
                    updateFirestoreField(userId, Constants.FIRESTORE_FIELD_STUDY_CONDITION, condition.name)
                }
            } else {
                Log.w(TAG,"Attempted to save invalid condition via repository: $condition")
            }
        }
    }

    override fun getTargetApp(): String? = StudyStateManager.getTargetApp(context)

    override suspend fun saveTargetApp(packageName: String?) {
        withContext(Dispatchers.IO) {
            StudyStateManager.saveTargetApp(context, packageName)
            getCurrentUserId()?.let { userId ->
                // Field name remains string literal here
                updateFirestoreField(userId, Constants.FIRESTORE_FIELD_TARGET_APP, packageName)
            }
        }
    }

    override fun getDailyGoalMs(): Long? = StudyStateManager.getDailyGoalMs(context)

    override suspend fun saveDailyGoalMs(goalMs: Long?) {
        withContext(Dispatchers.IO) {
            StudyStateManager.saveDailyGoalMs(context, goalMs)
            getCurrentUserId()?.let { userId ->
                // Field name remains string literal here
                updateFirestoreField(userId, Constants.FIRESTORE_FIELD_DAILY_GOAL, goalMs)
            }
        }
    }

    override fun getPointsBalance(): Int = StudyStateManager.getPointsBalance(context)

    override suspend fun savePointsBalance(points: Int) {
        withContext(Dispatchers.IO) {
            // Points are coerced in DailyCheckWorker, assume valid here for saving
            StudyStateManager.savePointsBalance(context, points)
            getCurrentUserId()?.let { userId ->
                // Field name remains string literal here
                updateFirestoreField(userId, Constants.FIRESTORE_FIELD_POINTS_BALANCE, points.toLong())
            }
        }
    }

    override fun getFlexStakes(): Pair<Int?, Int?> = StudyStateManager.getFlexStakes(context)

    override suspend fun saveFlexStakes(earn: Int, lose: Int) {
        withContext(Dispatchers.IO) {
            StudyStateManager.saveFlexStakes(context, earn, lose)
            getCurrentUserId()?.let { userId ->
                // Field names remain string literals here
                updateFirestoreFields(userId, mapOf(
                    Constants.FIRESTORE_FIELD_FLEX_EARN to earn.toLong(),
                    Constants.FIRESTORE_FIELD_FLEX_LOSE to lose.toLong()
                ))
            }
        }
    }

    override fun getLastDailyOutcome(): Triple<Long?, Boolean?, Int?> = StudyStateManager.getLastDailyOutcome(context)

    // Saving outcome is purely local state for the next day's notification
    override suspend fun saveDailyOutcome(checkTimestamp: Long, goalReached: Boolean, pointsChange: Int) {
        withContext(Dispatchers.IO) {
            StudyStateManager.saveDailyOutcome(context, checkTimestamp, goalReached, pointsChange)
        }
    }

    // High-level operation remains, implementation details are in StudyStateManager
    override suspend fun fetchAndSaveStateFromFirestore(userId: String): Boolean {
        // This already uses await and handles Firestore access internally
        // It will now benefit from constant collection name if StudyStateManager uses these helpers
        // or if it implements its own Firestore access using constants.
        return StudyStateManager.fetchAndSaveStateFromFirestore(context, userId)
    }

    override suspend fun clearStudyState() {
        withContext(Dispatchers.IO) {
            StudyStateManager.clearStudyState(context)
            // No direct Firestore interaction needed here, as it clears local prefs
        }
    }

    // --- User Profile Data (Firestore) ---
    override suspend fun saveUserProfile(userId: String, userData: Map<String, Any?>): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Use constant for collection name
                firestore.collection(Constants.FIRESTORE_COLLECTION_USERS).document(userId)
                    .set(userData) // Use set for initial creation
                    .await()
                Log.d(TAG, "User profile saved successfully to Firestore for user $userId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save user profile to Firestore for user $userId", e)
                Result.failure(e)
            }
        }
    }

    // --- Usage Stats (Implementations updated to pass userId) ---

    override suspend fun insertUsageStat(usageStat: UsageStatEntity) = withContext(Dispatchers.IO) {
        // Assumes usageStat already contains the correct userId set by the caller (Service)
        usageStatDao.insertUsageStat(usageStat)
    }

    override suspend fun insertAllUsageStats(usageStats: List<UsageStatEntity>) = withContext(Dispatchers.IO) {
        // Assumes usageStats list contains entities with correct userId set by the caller (Service)
        usageStatDao.insertAllUsageStats(usageStats)
    }

    override suspend fun getTotalDurationForAppOnDay(userId: String, packageName: String, dayTimestamp: Long): Long? = withContext(Dispatchers.IO) {
        usageStatDao.getTotalDurationForAppOnDay(userId, packageName, dayTimestamp) // Pass userId
    }

    override suspend fun getTotalDurationForAppInRange(userId: String, packageName: String, startTime: Long, endTime: Long): Long? = withContext(Dispatchers.IO) {
        usageStatDao.getTotalDurationForAppInRange(userId, packageName, startTime, endTime) // Pass userId
    }

    // Flows typically collected where userId is known (ViewModel)
    override fun getUsageStatsForDayFlow(userId: String, dayTimestamp: Long): Flow<List<UsageStatEntity>> {
        return usageStatDao.getUsageStatsForDayFlow(userId, dayTimestamp) // Pass userId
    }

    override fun getAggregatedDailyDurationForAppFlow(userId: String, packageName: String): Flow<List<DailyDuration>> {
        return usageStatDao.getAggregatedDailyDurationForAppFlow(userId, packageName) // Pass userId
    }

    override fun getTodayUsageForAppFlow(userId: String, packageName: String, todayDayTimestamp: Long): Flow<Long?> {
        // Note: This is the crucial one for HomeViewModel's main display
        return usageStatDao.getTodayUsageForAppFlow(userId, packageName, todayDayTimestamp) // Pass userId
    }

    override suspend fun getAggregatedUsageForBaseline(userId: String, startTime: Long, endTime: Long, minTotalDurationMs: Long): List<AppUsageBaseline> = withContext(Dispatchers.IO) {
        usageStatDao.getAggregatedUsageForBaseline(userId, startTime, endTime, minTotalDurationMs) // Pass userId
    }

    override suspend fun getAllUsageRecordsForBaseline(userId: String, startTime: Long, endTime: Long): List<BaselineUsageRecord> = withContext(Dispatchers.IO) {
        usageStatDao.getAllUsageRecordsForBaseline(userId, startTime, endTime) // Pass userId
    }


    // --- App Info (from AppInfoHelper) ---
    override suspend fun getAppDetails(packageName: String): AppBaselineInfo { // Update return type location
        return withContext(Dispatchers.IO) {
            val details = AppInfoHelper.getAppDetails(context, packageName)
            // Now uses the imported AppBaselineInfo
            AppBaselineInfo(
                packageName = packageName,
                appName = details.appName,
                icon = details.icon,
                averageDailyUsageMs = 0L // Still a placeholder here, calculated elsewhere
            )
        }
    }


    // --- Combined Operations ---
    override suspend fun confirmGoalSelection(userId: String, selectedAppPkg: String, calculatedGoalMs: Long): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val condition = StudyStateManager.getCondition(context)
            if (condition == null) {
                return@withContext Result.failure(IllegalStateException("Cannot confirm goal: Condition not set."))
            }

            // 1. Persist locally
            StudyStateManager.saveTargetApp(context, selectedAppPkg)
            StudyStateManager.saveDailyGoalMs(context, calculatedGoalMs)
            StudyStateManager.saveStudyPhase(context, condition) // Transition to the specific intervention phase

            // 2. Update Firestore
            // Use constants for Firestore fields
            val updateData = mapOf(
                Constants.FIRESTORE_FIELD_TARGET_APP to selectedAppPkg,
                Constants.FIRESTORE_FIELD_DAILY_GOAL to calculatedGoalMs,
                Constants.FIRESTORE_FIELD_STUDY_PHASE to condition.name
            )
            // Update Firestore - updateFirestoreFields already logs internally and now uses constant collection name
            val success = updateFirestoreFields(userId, updateData)

            if (success) {
                Result.success(Unit)
            } else {
                // Optional: Consider rolling back local changes if Firestore fails critically?
                // For now, log the inconsistency.
                Log.e(TAG,"Goal selection saved locally but failed to update Firestore!")
                Result.failure(Exception("Failed to update Firestore during goal confirmation."))
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
    ): Result<Unit> = withContext(Dispatchers.IO) { // Perform on IO thread
        try {
            // Step 1: Save profile data to Firestore
            // Uses constant collection name via Constants.kt
            firestore.collection(Constants.FIRESTORE_COLLECTION_USERS).document(userId)
                .set(userProfileData) // Use set for initial creation
                .await() // Wait for Firestore operation completion
            Log.d(TAG, "Firestore document successfully written for new user: $userId")

            // Step 2: Save initial state locally using StudyStateManager
            // Uses constant keys via Constants.kt implicitly within StateManager
            StudyStateManager.saveUserId(context, userId)
            StudyStateManager.saveStudyPhase(context, initialPhase) // Saves the Enum name
            StudyStateManager.saveCondition(context, assignedCondition) // Saves the Enum name
            StudyStateManager.savePointsBalance(context, initialPoints) // Saves the Int
            StudyStateManager.saveStudyStartTimestamp(context, registrationTimestamp) // Saves the Long
            // Ensure other potentially relevant states are cleared/defaulted locally
            StudyStateManager.saveTargetApp(context, null)
            StudyStateManager.saveDailyGoalMs(context, null)
            // Save default flex stakes locally even if not in flex condition initially
            StudyStateManager.saveFlexStakes(context, Constants.FLEX_STAKES_MIN_EARN, Constants.FLEX_STAKES_MIN_LOSE)
            StudyStateManager.saveDailyOutcome(context, 0L, false, 0) // Clear previous day outcome


            Log.d(TAG, "Local state initialized via StudyStateManager for new user: $userId")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize new user $userId (Firestore set or Local save)", e)
            // The calling code (AuthScreen) should handle cleanup like deleting the Auth user if needed.
            Result.failure(e)
        }
    }
    // ... rest of StudyRepositoryImpl ... // This comment is illustrative, no actual code is omitted.
}