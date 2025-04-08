package com.niyaz.zario.utils // Updated package

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore // Import Firestore
import com.google.firebase.firestore.ktx.firestore // Import KTX
import com.google.firebase.ktx.Firebase // Import KTX
import com.niyaz.zario.StudyPhase // Updated package
import kotlinx.coroutines.tasks.await // Import await() for suspending Task

object StudyStateManager {

    private const val PREFS_NAME = "ZarioStudyStatePrefs"
    private const val KEY_STUDY_PHASE = "study_phase"
    private const val KEY_STUDY_START_TIMESTAMP = "study_start_timestamp"
    // Add keys for other state info as needed (e.g., target app, goal, condition)
    private const val KEY_CONDITION = "study_condition"
    private const val KEY_TARGET_APP = "target_app_package"
    private const val KEY_DAILY_GOAL_MS = "daily_goal_ms"
    private const val KEY_POINTS_BALANCE = "points_balance"
    // Keys for Flexible Deposit stakes
    private const val KEY_FLEX_POINTS_EARN = "flex_points_earn"
    private const val KEY_FLEX_POINTS_LOSE = "flex_points_lose"
    private const val KEY_USER_ID = "user_id" // Add key for user ID


    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- User ID --- (Useful to store locally)
    fun saveUserId(context: Context, userId: String) {
        getPreferences(context).edit().putString(KEY_USER_ID, userId).apply()
    }
    fun getUserId(context: Context): String? {
        return getPreferences(context).getString(KEY_USER_ID, null)
    }

    // --- Study Phase ---
    fun saveStudyPhase(context: Context, phase: StudyPhase) {
        getPreferences(context).edit().putString(KEY_STUDY_PHASE, phase.name).apply()
        Log.d("StudyStateManager", "Saved Study Phase: ${phase.name}")
    }

    fun getStudyPhase(context: Context): StudyPhase {
        val phaseName = getPreferences(context).getString(KEY_STUDY_PHASE, StudyPhase.REGISTERED.name)
        return try {
            StudyPhase.valueOf(phaseName ?: StudyPhase.REGISTERED.name)
        } catch (e: IllegalArgumentException) {
            Log.e("StudyStateManager", "Invalid phase name '$phaseName' found in prefs, defaulting to REGISTERED.")
            StudyPhase.REGISTERED // Default to REGISTERED if saved value is invalid
        }
    }

    // --- Study Start Timestamp ---
    fun saveStudyStartTimestamp(context: Context, timestamp: Long) {
        getPreferences(context).edit().putLong(KEY_STUDY_START_TIMESTAMP, timestamp).apply()
        Log.d("StudyStateManager", "Saved Study Start Timestamp: $timestamp")
    }

    fun getStudyStartTimestamp(context: Context): Long {
        // Return 0 or -1 if not set, indicating baseline hasn't started properly
        return getPreferences(context).getLong(KEY_STUDY_START_TIMESTAMP, 0L)
    }

    // --- TODO: Add Getters/Setters for other state keys as needed ---
    // Example: Target App
    fun saveTargetApp(context: Context, packageName: String) {
        getPreferences(context).edit().putString(KEY_TARGET_APP, packageName).apply()
    }
    fun getTargetApp(context: Context): String? {
        return getPreferences(context).getString(KEY_TARGET_APP, null)
    }

    // Example: Condition (Save as String for clarity)
    fun saveCondition(context: Context, condition: StudyPhase) {
        // Only save valid intervention phases
        if(condition in listOf(StudyPhase.INTERVENTION_CONTROL, StudyPhase.INTERVENTION_DEPOSIT, StudyPhase.INTERVENTION_FLEXIBLE)){
            getPreferences(context).edit().putString(KEY_CONDITION, condition.name).apply()
        } else {
            Log.w("StudyStateManager", "Attempted to save invalid condition: $condition")
        }
    }
    fun getCondition(context: Context): StudyPhase? {
        val conditionName = getPreferences(context).getString(KEY_CONDITION, null)
        return try {
            conditionName?.let { StudyPhase.valueOf(it) }
        } catch (e: IllegalArgumentException) { null }
    }

    // Example: Points Balance
    fun savePointsBalance(context: Context, points: Int) {
        getPreferences(context).edit().putInt(KEY_POINTS_BALANCE, points).apply()
    }
    fun getPointsBalance(context: Context): Int {
        // Default to initial endowment (PRD) if not set
        return getPreferences(context).getInt(KEY_POINTS_BALANCE, 1200)
    }


    // --- Flexible Stakes --- (Add Save/Get functions if needed)
    fun saveFlexStakes(context: Context, earn: Int, lose: Int) {
        getPreferences(context).edit()
            .putInt(KEY_FLEX_POINTS_EARN, earn)
            .putInt(KEY_FLEX_POINTS_LOSE, lose)
            .apply()
    }
    fun getFlexStakes(context: Context): Pair<Int?, Int?> {
        val prefs = getPreferences(context)
        val earn = if (prefs.contains(KEY_FLEX_POINTS_EARN)) prefs.getInt(KEY_FLEX_POINTS_EARN, 0) else null
        val lose = if (prefs.contains(KEY_FLEX_POINTS_LOSE)) prefs.getInt(KEY_FLEX_POINTS_LOSE, 0) else null
        return Pair(earn, lose)
    }

    /**
     * Fetches user study data from Firestore and saves it to local SharedPreferences.
     * Should be called after successful login. Runs suspend function for await().
     *
     * @param context The application context.
     * @param userId The logged-in user's Firebase UID.
     * @return True if data was fetched and saved successfully, false otherwise.
     */
    suspend fun fetchAndSaveStateFromFirestore(context: Context, userId: String): Boolean {
        Log.d("StudyStateManager", "Attempting to fetch state for user: $userId")
        val firestore: FirebaseFirestore = Firebase.firestore
        val userDocRef = firestore.collection("users").document(userId)

        return try {
            val document = userDocRef.get().await() // Use await() for suspend function
            if (document != null && document.exists()) {
                Log.d("StudyStateManager", "Firestore document found. Data: ${document.data}")
                val editor = getPreferences(context).edit()

                // Save user ID locally
                editor.putString(KEY_USER_ID, userId)

                // Extract and save each field, handling potential nulls or type issues
                val phaseName = document.getString("studyPhase")
                try {
                    val phase = phaseName?.let { StudyPhase.valueOf(it) } ?: StudyPhase.REGISTERED
                    editor.putString(KEY_STUDY_PHASE, phase.name)
                } catch (e: IllegalArgumentException) {
                    Log.e("StudyStateManager", "Invalid phase '$phaseName' in Firestore, defaulting.")
                    editor.putString(KEY_STUDY_PHASE, StudyPhase.REGISTERED.name)
                }

                document.getLong("studyStartTimestamp")?.let { editor.putLong(KEY_STUDY_START_TIMESTAMP, it) }
                document.getString("studyCondition")?.let { editor.putString(KEY_CONDITION, it) } // Save condition name
                document.getLong("pointsBalance")?.let { editor.putInt(KEY_POINTS_BALANCE, it.toInt()) } // Firestore stores Long, Prefs Int
                document.getString("targetAppPackage")?.let { editor.putString(KEY_TARGET_APP, it) }
                document.getLong("dailyGoalMs")?.let { editor.putLong(KEY_DAILY_GOAL_MS, it) }
                document.getLong("flexPointsEarn")?.let { editor.putInt(KEY_FLEX_POINTS_EARN, it.toInt()) }
                document.getLong("flexPointsLose")?.let { editor.putInt(KEY_FLEX_POINTS_LOSE, it.toInt()) }

                editor.apply() // Apply all changes
                Log.i("StudyStateManager", "Successfully fetched and saved state from Firestore.")
                true
            } else {
                Log.w("StudyStateManager", "Firestore document not found for user: $userId")
                // Decide how to handle: Clear local state? Keep local state? Default state?
                // Let's clear local state to ensure consistency if Firestore doc is missing.
                clearStudyState(context) // Clear local state if no Firestore doc found
                false
            }
        } catch (e: Exception) {
            Log.e("StudyStateManager", "Error fetching state from Firestore", e)
            false
        }
    }

    // --- Clear State (e.g., on Logout) ---
    fun clearStudyState(context: Context) {
        getPreferences(context).edit().clear().apply()
        Log.d("StudyStateManager", "Cleared Study State.")
    }
}