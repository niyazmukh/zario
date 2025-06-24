package com.niyaz.zario.data.repository

import com.niyaz.zario.StudyPhase
import com.niyaz.zario.data.local.AppUsageBaseline
import com.niyaz.zario.data.local.BaselineUsageRecord
import com.niyaz.zario.data.local.DailyDuration
import com.niyaz.zario.data.local.UsageStatEntity
import com.niyaz.zario.data.model.AppBaselineInfo
import kotlinx.coroutines.flow.Flow

/**
 * Defines the contract for accessing and manipulating all data related to the Zario study.
 * This interface acts as a single source of truth for study data, abstracting the underlying
 * data sources (SharedPreferences via StudyStateManager, Room Database via UsageStatDao,
 * Firebase Firestore, and AppInfoHelper) from the rest of the application,
 * particularly ViewModels and background workers.
 */
interface StudyRepository {

    // --- User Session & Identification ---

    /**
     * Retrieves the currently logged-in user's unique identifier (Firebase Auth UID).
     * Primarily reads from local persistent storage (SharedPreferences).
     * @return The user ID string, or null if no user is logged in or state is cleared.
     */
    fun getUserId(): String?

    /**
     * Saves the user's unique identifier to local persistent storage.
     * Typically called after successful login or registration.
     * @param userId The Firebase Auth UID of the user.
     */
    suspend fun saveUserId(userId: String)

    // --- Study State Management (Local Persistence via StudyStateManager + Firestore Sync) ---

    /** Gets the current [StudyPhase] from local storage. */
    fun getStudyPhase(): StudyPhase

    /**
     * Saves the specified [StudyPhase] to both local storage and Firestore.
     * @param phase The [StudyPhase] to save.
     */
    suspend fun saveStudyPhase(phase: StudyPhase)

    /** Gets the study start timestamp (epoch milliseconds) from local storage. */
    fun getStudyStartTimestamp(): Long

    /**
     * Saves the study start timestamp to both local storage and Firestore.
     * @param timestamp The study start timestamp (epoch milliseconds).
     */
    suspend fun saveStudyStartTimestamp(timestamp: Long)

    /** Gets the participant's assigned intervention condition ([StudyPhase]) from local storage. */
    fun getCondition(): StudyPhase?

    /**
     * Saves the participant's assigned intervention condition to both local storage and Firestore.
     * Ensures only valid intervention phases (CONTROL, DEPOSIT, FLEXIBLE) are saved.
     * @param condition The intervention [StudyPhase] to save.
     */
    suspend fun saveCondition(condition: StudyPhase)

    /** Gets the package name of the selected target application from local storage. */
    fun getTargetApp(): String?

    /**
     * Saves the selected target application's package name to both local storage and Firestore.
     * @param packageName The package name of the target app, or null to clear it.
     */
    suspend fun saveTargetApp(packageName: String?)

    /** Gets the calculated daily usage goal (in milliseconds) for the target app from local storage. */
    fun getDailyGoalMs(): Long?

    /**
     * Saves the calculated daily usage goal to both local storage and Firestore.
     * @param goalMs The daily goal in milliseconds, or null to clear it.
     */
    suspend fun saveDailyGoalMs(goalMs: Long?)

    /** Gets the participant's current points balance from local storage. */
    fun getPointsBalance(): Int

    /**
     * Saves the participant's points balance to both local storage and Firestore.
     * Assumes points have been correctly calculated and coerced by the caller.
     * @param points The new points balance.
     */
    suspend fun savePointsBalance(points: Int)

    /** Gets the chosen point stakes (earn, lose) for the Flexible Deposit condition from local storage. */
    fun getFlexStakes(): Pair<Int?, Int?>

    /**
     * Saves the chosen point stakes (earn, lose) for the Flexible Deposit condition
     * to both local storage and Firestore.
     * @param earn Points to earn on success (typically 0-10).
     * @param lose Points to lose on failure (typically 0-40).
     */
    suspend fun saveFlexStakes(earn: Int, lose: Int)

    /**
     * Gets the flag indicating whether the user has explicitly set their flexible stakes.
     * Reads from local storage.
     * @return True if stakes have been set by the user, false otherwise.
     */
    fun getFlexStakesSetByUser(): Boolean

    /**
     * Saves the flag indicating whether the user has explicitly set their flexible stakes.
     * Persists the flag to local storage. Optionally could update Firestore if needed elsewhere.
     * @param hasSet True to indicate stakes have been set, false otherwise.
     */
    suspend fun saveFlexStakesSetByUser(hasSet: Boolean)

    /**
     * Gets the outcome of the *previous* day's goal check from local storage.
     * Used primarily to display feedback notifications.
     * @return A Triple containing: the timestamp of the check, whether the goal was reached, and the points change applied. Returns nulls if no outcome saved.
     */
    fun getLastDailyOutcome(): Triple<Long?, Boolean?, Int?>

    /**
     * Saves the outcome of the daily goal check to *local storage only*.
     * This state is transient and used for the next day's feedback notification.
     * @param checkTimestamp The timestamp (epoch milliseconds) when the check was performed.
     * @param goalReached True if the goal was met, false otherwise.
     * @param pointsChange The change in points applied based on the outcome.
     */
    suspend fun saveDailyOutcome(checkTimestamp: Long, goalReached: Boolean, pointsChange: Int)

    /**
     * Fetches the complete user study state from Firestore and saves it to local storage (SharedPreferences).
     * Overwrites existing local state. Used typically after login to synchronize state.
     * @param userId The ID of the user whose state to fetch.
     * @return `true` if the state was successfully fetched and saved, `false` otherwise (e.g., user not found, network error).
     */
    suspend fun fetchAndSaveStateFromFirestore(userId: String): Boolean

    /** Clears all locally persisted study state (SharedPreferences). Used on logout. */
    suspend fun clearStudyState()

    // --- User Profile Data (Firestore Only) ---

    /**
     * Saves or overwrites the main user profile document in Firestore.
     * Typically used during registration.
     * @param userId The ID of the user whose profile to save.
     * @param userData A map containing the user profile data fields and values.
     * @return [Result.success] if the operation succeeds, [Result.failure] otherwise.
     */
    suspend fun saveUserProfile(userId: String, userData: Map<String, Any?>): Result<Unit>

    // --- Usage Statistics (Local Room Database via UsageStatDao) ---

    /** Inserts a single [UsageStatEntity] record into the local database. */
    suspend fun insertUsageStat(usageStat: UsageStatEntity)

    /** Inserts a list of [UsageStatEntity] records into the local database. */
    suspend fun insertAllUsageStats(usageStats: List<UsageStatEntity>)

    /** Calculates total usage duration for a specific app on a specific day for the given user. */
    suspend fun getTotalDurationForAppOnDay(userId: String, packageName: String, dayTimestamp: Long): Long?

    /** Calculates total usage duration for a specific app within a time range for the given user. */
    suspend fun getTotalDurationForAppInRange(userId: String, packageName: String, startTime: Long, endTime: Long): Long?

    /** Provides a Flow of all usage records for a specific day for the given user. */
    fun getUsageStatsForDayFlow(userId: String, dayTimestamp: Long): Flow<List<UsageStatEntity>>

    /** Provides a Flow of aggregated daily usage duration for a specific app for the given user. */
    fun getAggregatedDailyDurationForAppFlow(userId: String, packageName: String): Flow<List<DailyDuration>>

    /** Provides a Flow of the total usage duration for a specific app for 'today' for the given user. */
    fun getTodayUsageForAppFlow(userId: String, packageName: String, todayDayTimestamp: Long): Flow<Long?>

    /** Aggregates total usage per app during the baseline period for the given user. */
    suspend fun getAggregatedUsageForBaseline(userId: String, startTime: Long, endTime: Long, minTotalDurationMs: Long = 60000): List<AppUsageBaseline>

    /** Retrieves individual usage records during the baseline period for the given user (for hourly analysis). */
    suspend fun getAllUsageRecordsForBaseline(userId: String, startTime: Long, endTime: Long): List<BaselineUsageRecord>

    // Note: Methods specific to the sync worker (getUnsynced, markSynced) are typically accessed
    // directly via the DAO within the worker itself and may not need to be exposed in the repository interface,
    // unless other parts of the app need this level of control.

    // --- Application Information (via AppInfoHelper) ---

    /**
     * Retrieves application details (name, icon) for a given package name.
     * Does not include usage data.
     * @param packageName The package name of the application.
     * @return An [AppBaselineInfo] object containing the app name and icon. The `averageDailyUsageMs` field will be 0 as it's not calculated here.
     */
    suspend fun getAppDetails(packageName: String): AppBaselineInfo // Consider renaming if used outside baseline context? Maybe AppDisplayInfo?

    // --- Combined High-Level Operations ---

    /**
     * Handles the process of confirming the user's target app selection and goal.
     * Calculates the goal based on the selected app's baseline, persists the target app
     * and goal locally and remotely (Firestore), and updates the study phase.
     *
     * @param userId The ID of the user confirming the goal.
     * @param selectedAppPkg The package name of the app selected by the user.
     * @param calculatedGoalMs The pre-calculated daily usage goal in milliseconds.
     * @return [Result.success] if the operation completes successfully, [Result.failure] otherwise.
     */
    suspend fun confirmGoalSelection(userId: String, selectedAppPkg: String, calculatedGoalMs: Long): Result<Unit>

    /**
     * Initializes a new user after successful registration.
     * Creates the user's profile document in Firestore and saves the initial study state
     * (phase, condition, points, timestamps) locally via StudyStateManager.
     *
     * @param userId The new user's Firebase Auth UID.
     * @param userProfileData Map containing initial profile data fields for Firestore.
     * @param initialPhase The starting study phase enum value for local state.
     * @param assignedCondition The randomly assigned intervention condition enum value for local state.
     * @param initialPoints The starting points balance for local state.
     * @param registrationTimestamp The timestamp of registration/study start for local state.
     * @return [Result.success] if both Firestore and local state initialization succeed, [Result.failure] otherwise.
     */
    suspend fun initializeNewUser(
        userId: String,
        userProfileData: Map<String, Any?>,
        initialPhase: StudyPhase,
        assignedCondition: StudyPhase,
        initialPoints: Int,
        registrationTimestamp: Long
    ): Result<Unit>
}