package com.niyaz.zario.data.repository


import com.niyaz.zario.StudyPhase
import com.niyaz.zario.data.local.AppUsageBaseline
import com.niyaz.zario.data.local.BaselineUsageRecord
import com.niyaz.zario.data.local.UsageStatEntity
import com.niyaz.zario.data.model.AppBaselineInfo
import kotlinx.coroutines.flow.Flow




/**
 * Interface for accessing and manipulating study-related data.
 * This abstracts the data sources (SharedPreferences, Room, Firestore, AppInfoHelper)
 * from the ViewModels.
 */
interface StudyRepository {


   // --- Study State (from StudyStateManager / Firestore) ---


   fun getUserId(): String?
   suspend fun saveUserId(userId: String) // Added suspend as Firestore might be involved indirectly


   fun getStudyPhase(): StudyPhase
   suspend fun saveStudyPhase(phase: StudyPhase) // Suspend for potential Firestore update


   fun getStudyStartTimestamp(): Long
   suspend fun saveStudyStartTimestamp(timestamp: Long) // Suspend for potential Firestore update


   fun getCondition(): StudyPhase?
   suspend fun saveCondition(condition: StudyPhase) // Suspend for potential Firestore update


   fun getTargetApp(): String?
   suspend fun saveTargetApp(packageName: String?) // Suspend for potential Firestore update


   fun getDailyGoalMs(): Long?
   suspend fun saveDailyGoalMs(goalMs: Long?) // Suspend for potential Firestore update


   fun getPointsBalance(): Int
   suspend fun savePointsBalance(points: Int) // Suspend for potential Firestore update


   fun getFlexStakes(): Pair<Int?, Int?>
   suspend fun saveFlexStakes(earn: Int, lose: Int) // Suspend for potential Firestore update


   fun getLastDailyOutcome(): Triple<Long?, Boolean?, Int?>
   suspend fun saveDailyOutcome(checkTimestamp: Long, goalReached: Boolean, pointsChange: Int) // Suspend for potential Firestore update


   suspend fun fetchAndSaveStateFromFirestore(userId: String): Boolean // Keep this high-level operation
   suspend fun clearStudyState() // Suspend for potential related async operations


   // --- User Profile Data (Firestore) ---
   suspend fun saveUserProfile(userId: String, userData: Map<String, Any?>): Result<Unit> // Result type for better error handling
   // Add methods to get specific profile fields if needed, e.g., getRegistrationTimestamp(userId: String)


   // --- Usage Stats (from Room DAO) ---
   // Signatures updated to include userId where DAO requires it


   suspend fun insertUsageStat(usageStat: UsageStatEntity) // No change needed here
   suspend fun insertAllUsageStats(usageStats: List<UsageStatEntity>) // No change needed here
   suspend fun getTotalDurationForAppOnDay(userId: String, packageName: String, dayTimestamp: Long): Long?
   suspend fun getTotalDurationForAppInRange(userId: String, packageName: String, startTime: Long, endTime: Long): Long?
   fun getUsageStatsForDayFlow(userId: String, dayTimestamp: Long): Flow<List<UsageStatEntity>>
   fun getAggregatedDailyDurationForAppFlow(userId: String, packageName: String): Flow<List<com.niyaz.zario.data.local.DailyDuration>> // Use qualified name
   fun getTodayUsageForAppFlow(userId: String, packageName: String, todayDayTimestamp: Long): Flow<Long?>
   suspend fun getAggregatedUsageForBaseline(userId: String, startTime: Long, endTime: Long, minTotalDurationMs: Long = 60000): List<AppUsageBaseline>
   suspend fun getAllUsageRecordsForBaseline(userId: String, startTime: Long, endTime: Long): List<BaselineUsageRecord>


   // Sync methods are internal to worker or could be exposed if needed, but DAO is sufficient for now
   // suspend fun getUnsyncedUsageStats(userId: String, limit: Int = 100): List<UsageStatEntity> // Example if exposing needed
   // suspend fun markUsageStatsAsSynced(ids: List<Long>) // Example if exposing needed


   // --- App Info (from AppInfoHelper) ---
   // Consider renaming AppBaselineInfo and moving it to a shared data/model package
   suspend fun getAppDetails(packageName: String): AppBaselineInfo


   // --- Combined Operations (Example) ---
   // This combines state saving across local and remote sources
   suspend fun confirmGoalSelection(userId: String, selectedAppPkg: String, calculatedGoalMs: Long): Result<Unit>


   /**
    * Initializes a new user's profile in Firestore and sets initial local state.
    * Handles both remote and local persistence for registration.
    * @param userId The new user's Firebase Auth UID.
    * @param userProfileData Map containing initial profile data for Firestore (YOB, Gender, Email, Timestamps, Phase, Condition, Points etc.).
    * @param initialPhase The starting study phase enum value for local state.
    * @param assignedCondition The randomly assigned intervention condition enum value for local state.
    * @param initialPoints The starting points balance for local state.
    * @param registrationTimestamp The timestamp of registration/study start for local state.
    * @return Result indicating success or failure of the entire initialization process.
    */
   suspend fun initializeNewUser(
      userId: String,
      userProfileData: Map<String, Any?>, // Data destined for Firestore set()
      initialPhase: StudyPhase,          // Enum for local state save
      assignedCondition: StudyPhase,     // Enum for local state save
      initialPoints: Int,                // Int for local state save
      registrationTimestamp: Long        // Long for local state save
   ): Result<Unit>
}