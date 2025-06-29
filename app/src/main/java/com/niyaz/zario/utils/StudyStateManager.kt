package com.niyaz.zario.utils


import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.utils.StudyPhase
import kotlinx.coroutines.tasks.await


object StudyStateManager {


    // Use constants for keys
    private const val PREFS_NAME = Constants.PREFS_NAME // Keep local if only used here, or reference Constants
    private const val KEY_STUDY_PHASE = Constants.KEY_STUDY_PHASE
    private const val KEY_STUDY_START_TIMESTAMP = Constants.KEY_STUDY_START_TIMESTAMP
    private const val KEY_CONDITION = Constants.KEY_CONDITION
    private const val KEY_TARGET_APP = Constants.KEY_TARGET_APP
    private const val KEY_DAILY_GOAL_MS = Constants.KEY_DAILY_GOAL_MS
    private const val KEY_POINTS_BALANCE = Constants.KEY_POINTS_BALANCE
    private const val KEY_FLEX_POINTS_EARN = Constants.KEY_FLEX_POINTS_EARN
    private const val KEY_FLEX_POINTS_LOSE = Constants.KEY_FLEX_POINTS_LOSE
    private const val KEY_USER_ID = Constants.KEY_USER_ID
    private const val KEY_LAST_EVAL_TIMESTAMP = Constants.KEY_LAST_EVAL_TIMESTAMP
    private const val KEY_LAST_EVAL_GOAL_REACHED = Constants.KEY_LAST_EVAL_GOAL_REACHED
    private const val KEY_LAST_EVAL_POINTS_CHANGE = Constants.KEY_LAST_EVAL_POINTS_CHANGE


    private const val TAG = "StudyStateManager" // Add TAG


    private fun getPreferences(context: Context): SharedPreferences {
        // Use constant for prefs name
        return context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    }


    // --- Methods using Keys ---
    // (No logic change, just ensure keys used above are from Constants)


    fun saveUserId(context: Context, userId: String) {
        getPreferences(context).edit().putString(KEY_USER_ID, userId).apply()
    }
    fun getUserId(context: Context): String? {
        return getPreferences(context).getString(KEY_USER_ID, null)
    }


    fun saveStudyPhase(context: Context, phase: StudyPhase) {
        getPreferences(context).edit().putString(KEY_STUDY_PHASE, phase.name).apply()
        Log.d(TAG, "Saved Study Phase: ${phase.name}")
    }


    fun getStudyPhase(context: Context): StudyPhase {
        val phaseName = getPreferences(context).getString(KEY_STUDY_PHASE, StudyPhase.REGISTERED.name)
        return try {
            StudyPhase.valueOf(phaseName ?: StudyPhase.REGISTERED.name)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid phase name '$phaseName' found in prefs, defaulting to REGISTERED.")
            StudyPhase.REGISTERED
        }
    }


    fun saveStudyStartTimestamp(context: Context, timestamp: Long) {
        getPreferences(context).edit().putLong(KEY_STUDY_START_TIMESTAMP, timestamp).apply()
        Log.d(TAG, "Saved Study Start Timestamp: $timestamp")
    }


    fun getStudyStartTimestamp(context: Context): Long {
        return getPreferences(context).getLong(KEY_STUDY_START_TIMESTAMP, 0L)
    }


    fun saveTargetApp(context: Context, packageName: String?) {
        getPreferences(context).edit().putString(KEY_TARGET_APP, packageName).apply()
        Log.d(TAG, "Saved Target App: $packageName")
    }
    fun getTargetApp(context: Context): String? {
        return getPreferences(context).getString(KEY_TARGET_APP, null)
    }


    fun saveDailyGoalMs(context: Context, goalMs: Long?) {
        val editor = getPreferences(context).edit()
        if (goalMs != null) {
            editor.putLong(KEY_DAILY_GOAL_MS, goalMs)
        } else {
            editor.remove(KEY_DAILY_GOAL_MS)
        }
        editor.apply()
        Log.d(TAG, "Saved Daily Goal (ms): $goalMs")
    }
    fun getDailyGoalMs(context: Context): Long? {
        val goal = getPreferences(context).getLong(KEY_DAILY_GOAL_MS, -1L)
        return if (goal == -1L) null else goal
    }


    fun saveCondition(context: Context, condition: StudyPhase) {
        if(condition in listOf(StudyPhase.INTERVENTION_CONTROL, StudyPhase.INTERVENTION_DEPOSIT, StudyPhase.INTERVENTION_FLEXIBLE)){
            getPreferences(context).edit().putString(KEY_CONDITION, condition.name).apply()
        } else {
            Log.w(TAG, "Attempted to save invalid condition: $condition")
        }
    }
    fun getCondition(context: Context): StudyPhase? {
        val conditionName = getPreferences(context).getString(KEY_CONDITION, null)
        return try {
            conditionName?.let { StudyPhase.valueOf(it) }
        } catch (e: IllegalArgumentException) { null }
    }


    fun savePointsBalance(context: Context, points: Int) {
        getPreferences(context).edit().putInt(KEY_POINTS_BALANCE, points).apply()
    }
    fun getPointsBalance(context: Context): Int {
        // Use constant for default value
        return getPreferences(context).getInt(KEY_POINTS_BALANCE, Constants.INITIAL_POINTS)
    }


    fun saveFlexStakes(context: Context, earn: Int, lose: Int) {
        getPreferences(context).edit()
            .putInt(KEY_FLEX_POINTS_EARN, earn)
            .putInt(KEY_FLEX_POINTS_LOSE, lose)
            .apply()
    }
    fun getFlexStakes(context: Context): Pair<Int?, Int?> {
        val prefs = getPreferences(context)
        // Use Constants for defaults/range check if needed, though currently returning null if not present
        val earn = if (prefs.contains(KEY_FLEX_POINTS_EARN)) prefs.getInt(KEY_FLEX_POINTS_EARN, Constants.FLEX_STAKES_MIN_EARN) else null
        val lose = if (prefs.contains(KEY_FLEX_POINTS_LOSE)) prefs.getInt(KEY_FLEX_POINTS_LOSE, Constants.FLEX_STAKES_MIN_LOSE) else null
        return Pair(earn, lose)
    }


    fun saveIntervalOutcome(context: Context, checkTimestamp: Long, goalReached: Boolean, pointsChange: Int) {
        getPreferences(context).edit()
            .putLong(KEY_LAST_EVAL_TIMESTAMP, checkTimestamp)
            .putBoolean(KEY_LAST_EVAL_GOAL_REACHED, goalReached)
            .putInt(KEY_LAST_EVAL_POINTS_CHANGE, pointsChange)
            .apply()
        Log.d(TAG, "Saved Interval Outcome: Time=$checkTimestamp, Reached=$goalReached, Change=$pointsChange")
    }


    fun getLastIntervalOutcome(context: Context): Triple<Long?, Boolean?, Int?> {
        val prefs = getPreferences(context)
        val timestamp = if(prefs.contains(KEY_LAST_EVAL_TIMESTAMP)) prefs.getLong(KEY_LAST_EVAL_TIMESTAMP, 0L) else null
        val reached = if(prefs.contains(KEY_LAST_EVAL_GOAL_REACHED)) prefs.getBoolean(KEY_LAST_EVAL_GOAL_REACHED, false) else null
        val change = if(prefs.contains(KEY_LAST_EVAL_POINTS_CHANGE)) prefs.getInt(KEY_LAST_EVAL_POINTS_CHANGE, 0) else null
        return Triple(timestamp, reached, change)
    }


    suspend fun fetchAndSaveStateFromFirestore(context: Context, userId: String): Boolean {
        Log.d(TAG, "Attempting to fetch state for user: $userId")
        val firestore: FirebaseFirestore = Firebase.firestore
        // Use constant for collection name
        val userDocRef = firestore.collection(Constants.FIRESTORE_COLLECTION_USERS).document(userId)


        return try {
            val document = userDocRef.get().await()
            if (document != null && document.exists()) {
                Log.d(TAG, "Firestore document found. Data: ${document.data}")
                val editor = getPreferences(context).edit()


                editor.putString(KEY_USER_ID, userId)


                // Use Constants for Firestore fields
                val phaseName = document.getString(Constants.FIRESTORE_FIELD_STUDY_PHASE)
                try {
                    val phase = phaseName?.let { StudyPhase.valueOf(it) } ?: StudyPhase.REGISTERED
                    editor.putString(KEY_STUDY_PHASE, phase.name)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Invalid phase '$phaseName' in Firestore, defaulting.")
                    editor.putString(KEY_STUDY_PHASE, StudyPhase.REGISTERED.name)
                }


                document.getLong(Constants.FIRESTORE_FIELD_STUDY_START_TIMESTAMP)?.let { editor.putLong(KEY_STUDY_START_TIMESTAMP, it) }
                document.getString(Constants.FIRESTORE_FIELD_STUDY_CONDITION)?.let { editor.putString(KEY_CONDITION, it) }
                document.getLong(Constants.FIRESTORE_FIELD_POINTS_BALANCE)?.let { editor.putInt(KEY_POINTS_BALANCE, it.toInt()) }
                document.getString(Constants.FIRESTORE_FIELD_TARGET_APP)?.let { editor.putString(KEY_TARGET_APP, it) }
                document.getLong(Constants.FIRESTORE_FIELD_DAILY_GOAL)?.let { editor.putLong(KEY_DAILY_GOAL_MS, it) }
                document.getLong(Constants.FIRESTORE_FIELD_FLEX_EARN)?.let { editor.putInt(KEY_FLEX_POINTS_EARN, it.toInt()) }
                document.getLong(Constants.FIRESTORE_FIELD_FLEX_LOSE)?.let { editor.putInt(KEY_FLEX_POINTS_LOSE, it.toInt()) }


                editor.apply()
                Log.i(TAG, "Successfully fetched and saved state from Firestore.")
                true
            } else {
                Log.w(TAG, "Firestore document not found for user: $userId")
                clearStudyState(context)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching state from Firestore", e)
            false
        }
    }


    fun clearStudyState(context: Context) {
        getPreferences(context).edit().clear().apply()
        Log.d(TAG, "Cleared Study State.")
    }
}
