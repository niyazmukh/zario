package com.example.zario.utils

import android.util.Log // Import Android's Log class
import android.content.Context
import android.content.SharedPreferences
import com.example.zario.StudyPhase // Import the enum

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


    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    // --- Clear State (e.g., on Logout) ---
    fun clearStudyState(context: Context) {
        getPreferences(context).edit().clear().apply()
        Log.d("StudyStateManager", "Cleared Study State.")
    }
}