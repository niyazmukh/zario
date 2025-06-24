package com.niyaz.zario.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.StudyPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Singleton object responsible for managing the persistence of study-related state
 * using Android's SharedPreferences. Acts as the low-level local storage mechanism,
 * primarily accessed via the StudyRepository.
 */
object StudyStateManager {

    // TAG for logging
    private const val TAG = "StudyStateManager"

    private const val KEY_FLEX_STAKES_SET_BY_USER = Constants.KEY_FLEX_STAKES_SET_BY_USER


    // Private helper to get SharedPreferences instance
    private fun getPreferences(context: Context): SharedPreferences {
        // Use constant for prefs name
        return context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- User ID ---

    /**
     * Saves the user's Firebase Auth UID to SharedPreferences.
     * @param context The application context.
     * @param userId The user ID to save.
     */
    fun saveUserId(context: Context, userId: String) {
        getPreferences(context).edit().putString(Constants.KEY_USER_ID, userId).apply()
        Log.d(TAG, "Saved User ID locally.")
    }

    /**
     * Retrieves the user's Firebase Auth UID from SharedPreferences.
     * @param context The application context.
     * @return The stored user ID, or null if not found.
     */
    fun getUserId(context: Context): String? {
        return getPreferences(context).getString(Constants.KEY_USER_ID, null)
    }

    // --- Study Phase ---

    /**
     * Saves the current [StudyPhase] enum name to SharedPreferences.
     * @param context The application context.
     * @param phase The [StudyPhase] to save.
     */
    fun saveStudyPhase(context: Context, phase: StudyPhase) {
        getPreferences(context).edit().putString(Constants.KEY_STUDY_PHASE, phase.name).apply()
        Log.d(TAG, "Saved Study Phase locally: ${phase.name}")
    }

    /**
     * Retrieves the current [StudyPhase] from SharedPreferences.
     * Defaults to [StudyPhase.REGISTERED] if no phase is saved or the saved value is invalid.
     * @param context The application context.
     * @return The current [StudyPhase].
     */
    fun getStudyPhase(context: Context): StudyPhase {
        val phaseName = getPreferences(context).getString(Constants.KEY_STUDY_PHASE, StudyPhase.REGISTERED.name)
        return try {
            StudyPhase.valueOf(phaseName ?: StudyPhase.REGISTERED.name)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid phase name '$phaseName' found in SharedPreferences, defaulting to REGISTERED.", e)
            // Optionally: Clear the invalid key?
            // getPreferences(context).edit().remove(Constants.KEY_STUDY_PHASE).apply()
            StudyPhase.REGISTERED
        }
    }

    // --- Study Start Timestamp ---

    /**
     * Saves the study start timestamp (epoch milliseconds) to SharedPreferences.
     * @param context The application context.
     * @param timestamp The timestamp to save.
     */
    fun saveStudyStartTimestamp(context: Context, timestamp: Long) {
        getPreferences(context).edit().putLong(Constants.KEY_STUDY_START_TIMESTAMP, timestamp).apply()
        Log.d(TAG, "Saved Study Start Timestamp locally: $timestamp")
    }

    /**
     * Retrieves the study start timestamp (epoch milliseconds) from SharedPreferences.
     * Defaults to 0L if not set.
     * @param context The application context.
     * @return The stored timestamp.
     */
    fun getStudyStartTimestamp(context: Context): Long {
        return getPreferences(context).getLong(Constants.KEY_STUDY_START_TIMESTAMP, 0L)
    }

    // --- Target Application ---

    /**
     * Saves the package name of the target application to SharedPreferences.
     * @param context The application context.
     * @param packageName The package name to save, or null to clear it.
     */
    fun saveTargetApp(context: Context, packageName: String?) {
        getPreferences(context).edit().putString(Constants.KEY_TARGET_APP, packageName).apply()
        Log.d(TAG, "Saved Target App locally: $packageName")
    }

    /**
     * Retrieves the package name of the target application from SharedPreferences.
     * @param context The application context.
     * @return The stored package name, or null if not set.
     */
    fun getTargetApp(context: Context): String? {
        return getPreferences(context).getString(Constants.KEY_TARGET_APP, null)
    }

    // --- Daily Goal ---

    /**
     * Saves the daily usage goal (in milliseconds) to SharedPreferences.
     * @param context The application context.
     * @param goalMs The goal in milliseconds to save, or null to clear it.
     */
    fun saveDailyGoalMs(context: Context, goalMs: Long?) {
        val editor = getPreferences(context).edit()
        if (goalMs != null) {
            editor.putLong(Constants.KEY_DAILY_GOAL_MS, goalMs)
        } else {
            editor.remove(Constants.KEY_DAILY_GOAL_MS)
        }
        editor.apply()
        Log.d(TAG, "Saved Daily Goal (ms) locally: $goalMs")
    }

    /**
     * Retrieves the daily usage goal (in milliseconds) from SharedPreferences.
     * @param context The application context.
     * @return The stored goal in milliseconds, or null if not set.
     */
    fun getDailyGoalMs(context: Context): Long? {
        val goal = getPreferences(context).getLong(Constants.KEY_DAILY_GOAL_MS, -1L) // Use -1 as sentinel for not found
        return if (goal == -1L) null else goal
    }

    // --- Intervention Condition ---

    /**
     * Saves the assigned intervention condition ([StudyPhase]) enum name to SharedPreferences.
     * Only saves valid intervention phases (CONTROL, DEPOSIT, FLEXIBLE).
     * @param context The application context.
     * @param condition The intervention [StudyPhase] to save.
     */
    fun saveCondition(context: Context, condition: StudyPhase) {
        // Check if the provided phase is a valid intervention condition
        if (condition in listOf(StudyPhase.INTERVENTION_CONTROL, StudyPhase.INTERVENTION_DEPOSIT, StudyPhase.INTERVENTION_FLEXIBLE)) {
            getPreferences(context).edit().putString(Constants.KEY_CONDITION, condition.name).apply()
            Log.d(TAG, "Saved Condition locally: ${condition.name}")
        } else {
            Log.w(TAG, "Attempted to save invalid condition locally: $condition")
        }
    }

    /**
     * Retrieves the assigned intervention condition ([StudyPhase]) from SharedPreferences.
     * @param context The application context.
     * @return The stored intervention [StudyPhase], or null if not set or invalid.
     */
    fun getCondition(context: Context): StudyPhase? {
        val conditionName = getPreferences(context).getString(Constants.KEY_CONDITION, null)
        return try {
            conditionName?.let { StudyPhase.valueOf(it) }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid condition name '$conditionName' found in SharedPreferences, returning null.", e)
            null
        }
    }

    // --- Points Balance ---

    /**
     * Saves the current points balance to SharedPreferences.
     * @param context The application context.
     * @param points The points balance to save.
     */
    fun savePointsBalance(context: Context, points: Int) {
        getPreferences(context).edit().putInt(Constants.KEY_POINTS_BALANCE, points).apply()
        Log.d(TAG, "Saved Points Balance locally: $points")
    }

    /**
     * Retrieves the current points balance from SharedPreferences.
     * Defaults to [Constants.INITIAL_POINTS] if not set.
     * @param context The application context.
     * @return The stored points balance.
     */
    fun getPointsBalance(context: Context): Int {
        return getPreferences(context).getInt(Constants.KEY_POINTS_BALANCE, Constants.INITIAL_POINTS)
    }

    // --- Flexible Deposit Stakes ---

    /**
     * Saves the chosen flexible deposit stakes (earn/lose points) to SharedPreferences.
     * @param context The application context.
     * @param earn The points to earn on success.
     * @param lose The points to lose on failure.
     */
    fun saveFlexStakes(context: Context, earn: Int, lose: Int) {
        getPreferences(context).edit()
            .putInt(Constants.KEY_FLEX_POINTS_EARN, earn)
            .putInt(Constants.KEY_FLEX_POINTS_LOSE, lose)
            .apply()
        Log.d(TAG, "Saved Flex Stakes locally: Earn=$earn, Lose=$lose")
    }

    /**
     * Retrieves the chosen flexible deposit stakes from SharedPreferences.
     * @param context The application context.
     * @return A Pair containing the earn points (Int?) and lose points (Int?). Returns null for a value if it's not set.
     */
    fun getFlexStakes(context: Context): Pair<Int?, Int?> {
        val prefs = getPreferences(context)
        // Check contains explicitly before getting to return null if truly not set
        val earn = if (prefs.contains(Constants.KEY_FLEX_POINTS_EARN)) prefs.getInt(Constants.KEY_FLEX_POINTS_EARN, Constants.FLEX_STAKES_MIN_EARN) else null
        val lose = if (prefs.contains(Constants.KEY_FLEX_POINTS_LOSE)) prefs.getInt(Constants.KEY_FLEX_POINTS_LOSE, Constants.FLEX_STAKES_MIN_LOSE) else null
        return Pair(earn, lose)
    }

    // --- NEW: Flexible Stakes User Confirmation Flag ---

    /**
     * Saves a flag indicating whether the user has explicitly confirmed their flexible stakes.
     * @param context The application context.
     * @param hasSet True if the user has confirmed, false otherwise (e.g., on initialization).
     */
    fun saveFlexStakesSetByUser(context: Context, hasSet: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_FLEX_STAKES_SET_BY_USER, hasSet).apply()
        Log.d(TAG, "Saved Flex Stakes Set By User flag locally: $hasSet")
    }

    /**
     * Retrieves the flag indicating whether the user has explicitly confirmed their flexible stakes.
     * Defaults to false if the flag hasn't been saved yet.
     * @param context The application context.
     * @return True if the user has confirmed stakes, false otherwise.
     */
    fun getFlexStakesSetByUser(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_FLEX_STAKES_SET_BY_USER, false) // Default to false
    }


    // --- Daily Outcome Tracking (Local Only) ---

    /**
     * Saves the outcome of the latest daily goal check to SharedPreferences.
     * This is transient state used for the next day's feedback notification.
     * @param context The application context.
     * @param checkTimestamp The timestamp (epoch milliseconds) when the check was performed.
     * @param goalReached True if the goal was met, false otherwise.
     * @param pointsChange The change in points applied (-ve for loss, +ve for gain, 0 for no change).
     */
    fun saveDailyOutcome(context: Context, checkTimestamp: Long, goalReached: Boolean, pointsChange: Int) {
        getPreferences(context).edit()
            .putLong(Constants.KEY_LAST_DAILY_CHECK_TIMESTAMP, checkTimestamp)
            .putBoolean(Constants.KEY_LAST_DAY_GOAL_REACHED, goalReached)
            .putInt(Constants.KEY_LAST_DAY_POINTS_CHANGE, pointsChange)
            .apply()
        Log.d(TAG, "Saved Daily Outcome locally: Time=$checkTimestamp, Reached=$goalReached, Change=$pointsChange")
    }

    /**
     * Retrieves the outcome of the latest daily goal check from SharedPreferences.
     * @param context The application context.
     * @return A Triple containing:
     *         1. The check timestamp (Long?) or null if not set.
     *         2. Goal reached status (Boolean?) or null if not set.
     *         3. Points change (Int?) or null if not set.
     */
    fun getLastDailyOutcome(context: Context): Triple<Long?, Boolean?, Int?> {
        val prefs = getPreferences(context)
        // Explicitly check if key exists before getting to return null correctly
        val timestamp = if (prefs.contains(Constants.KEY_LAST_DAILY_CHECK_TIMESTAMP)) prefs.getLong(Constants.KEY_LAST_DAILY_CHECK_TIMESTAMP, 0L) else null
        val reached = if (prefs.contains(Constants.KEY_LAST_DAY_GOAL_REACHED)) prefs.getBoolean(Constants.KEY_LAST_DAY_GOAL_REACHED, false) else null
        val change = if (prefs.contains(Constants.KEY_LAST_DAY_POINTS_CHANGE)) prefs.getInt(Constants.KEY_LAST_DAY_POINTS_CHANGE, 0) else null
        return Triple(timestamp, reached, change)
    }

    // --- Firestore Synchronization ---

    /**
     * Fetches the complete user study state from their Firestore document and saves it
     * into local SharedPreferences, overwriting existing local values.
     * Should be called on login or when needing to ensure local state matches remote state.
     * Runs on the IO dispatcher.
     *
     * @param context The application context.
     * @param userId The ID of the user whose state to fetch and save.
     * @return `true` if fetching and saving were successful, `false` otherwise.
     */
    suspend fun fetchAndSaveStateFromFirestore(context: Context, userId: String): Boolean {
        Log.d(TAG, "Attempting to fetch state from Firestore for user: $userId")
        val firestore: FirebaseFirestore = Firebase.firestore
        // Use constant for collection name
        val userDocRef = firestore.collection(Constants.FIRESTORE_COLLECTION_USERS).document(userId)

        return withContext(Dispatchers.IO) { // Perform Firestore and SharedPreferences I/O on IO thread
            try {
                val document = userDocRef.get().await()
                if (document != null && document.exists()) {
                    Log.d(TAG, "Firestore document found for user $userId. Applying to local state...")
                    val editor = getPreferences(context).edit()

                    // Assume flag might not exist in older Firestore docs, default to false if missing
                    val flexSet = document.getBoolean(Constants.KEY_FLEX_STAKES_SET_BY_USER) ?: false
                    editor.putBoolean(KEY_FLEX_STAKES_SET_BY_USER, flexSet)

                    // Apply USER_ID regardless
                    editor.putString(Constants.KEY_USER_ID, userId)

                    // Apply values from Firestore, using constants for field names
                    // Use safe accessors like getString, getLong which return null if field doesn't exist
                    val phaseName = document.getString(Constants.FIRESTORE_FIELD_STUDY_PHASE)
                    try {
                        val phase = phaseName?.let { StudyPhase.valueOf(it) } ?: StudyPhase.REGISTERED
                        editor.putString(Constants.KEY_STUDY_PHASE, phase.name)
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Invalid phase '$phaseName' in Firestore for user $userId, defaulting locally.", e)
                        editor.putString(Constants.KEY_STUDY_PHASE, StudyPhase.REGISTERED.name)
                    }

                    document.getLong(Constants.FIRESTORE_FIELD_STUDY_START_TIMESTAMP)?.let { editor.putLong(Constants.KEY_STUDY_START_TIMESTAMP, it) } ?: editor.remove(Constants.KEY_STUDY_START_TIMESTAMP)
                    document.getString(Constants.FIRESTORE_FIELD_STUDY_CONDITION)?.let { editor.putString(Constants.KEY_CONDITION, it) } ?: editor.remove(Constants.KEY_CONDITION)
                    document.getLong(Constants.FIRESTORE_FIELD_POINTS_BALANCE)?.let { editor.putInt(Constants.KEY_POINTS_BALANCE, it.toInt()) } ?: editor.remove(Constants.KEY_POINTS_BALANCE) // Consider default?
                    document.getString(Constants.FIRESTORE_FIELD_TARGET_APP)?.let { editor.putString(Constants.KEY_TARGET_APP, it) } ?: editor.remove(Constants.KEY_TARGET_APP)
                    document.getLong(Constants.FIRESTORE_FIELD_DAILY_GOAL)?.let { editor.putLong(Constants.KEY_DAILY_GOAL_MS, it) } ?: editor.remove(Constants.KEY_DAILY_GOAL_MS)
                    document.getLong(Constants.FIRESTORE_FIELD_FLEX_EARN)?.let { editor.putInt(Constants.KEY_FLEX_POINTS_EARN, it.toInt()) } ?: editor.remove(Constants.KEY_FLEX_POINTS_EARN)
                    document.getLong(Constants.FIRESTORE_FIELD_FLEX_LOSE)?.let { editor.putInt(Constants.KEY_FLEX_POINTS_LOSE, it.toInt()) } ?: editor.remove(Constants.KEY_FLEX_POINTS_LOSE)

                    // Commit all changes to SharedPreferences
                    val success = editor.commit() // Use commit for synchronous result within the coroutine context

                    if (success) {
                        Log.i(TAG, "Successfully fetched and saved state from Firestore to local SharedPreferences for user $userId.")
                        true
                    } else {
                        Log.e(TAG, "Failed to commit fetched state to SharedPreferences for user $userId.")
                        false
                    }
                } else {
                    Log.w(TAG, "Firestore document not found for user: $userId. Cannot fetch state.")
                    // Optionally clear local state if Firestore doc doesn't exist?
                    // clearStudyState(context)
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching state from Firestore for user $userId", e)
                false // Return false on any exception during fetch/save
            }
        } // End withContext
    }

    // --- Clearing State ---

    /**
     * Clears all key-value pairs stored in the Zario study's SharedPreferences.
     * Typically used during logout.
     * @param context The application context.
     */
    fun clearStudyState(context: Context) {
        getPreferences(context).edit().clear().apply()
        Log.i(TAG, "Cleared all local study state from SharedPreferences.")
    }
}