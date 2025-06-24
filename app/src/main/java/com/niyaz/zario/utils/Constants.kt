package com.niyaz.zario.utils

import java.util.concurrent.TimeUnit

/**
 * Central object for holding application-wide constants.
 * Grouped by feature area for better maintainability.
 */
object Constants {


    // --- SharedPreferences ---
    const val PREFS_NAME = "ZarioStudyStatePrefs"
    const val KEY_USER_ID = "user_id"
    const val KEY_STUDY_PHASE = "study_phase"
    const val KEY_STUDY_START_TIMESTAMP = "study_start_timestamp"
    const val KEY_CONDITION = "study_condition"
    const val KEY_TARGET_APP = "target_app_package"
    const val KEY_DAILY_GOAL_MS = "daily_goal_ms"
    const val KEY_POINTS_BALANCE = "points_balance"
    const val KEY_FLEX_POINTS_EARN = "flex_points_earn"
    const val KEY_FLEX_POINTS_LOSE = "flex_points_lose"
    const val KEY_LAST_DAILY_CHECK_TIMESTAMP = "last_daily_check_timestamp"
    const val KEY_LAST_DAY_GOAL_REACHED = "last_day_goal_reached"
    const val KEY_LAST_DAY_POINTS_CHANGE = "last_day_points_change"
    const val KEY_FLEX_STAKES_SET_BY_USER = "flex_stakes_set_by_user"




    // --- Firestore ---
    const val FIRESTORE_COLLECTION_USERS = "users"
    const val FIRESTORE_SUBCOLLECTION_DAILY_USAGE = "daily_app_usage" // Added constant for subcollection
    // User Document Fields
    const val FIRESTORE_FIELD_EMAIL = "email"
    const val FIRESTORE_FIELD_YOB = "yearOfBirth"
    const val FIRESTORE_FIELD_GENDER = "gender"
    const val FIRESTORE_FIELD_REG_TIMESTAMP = "registrationTimestamp"
    const val FIRESTORE_FIELD_STUDY_PHASE = "studyPhase"
    const val FIRESTORE_FIELD_STUDY_CONDITION = "studyCondition"
    const val FIRESTORE_FIELD_STUDY_START_TIMESTAMP = "studyStartTimestamp"
    const val FIRESTORE_FIELD_POINTS_BALANCE = "pointsBalance"
    const val FIRESTORE_FIELD_TARGET_APP = "targetAppPackage"
    const val FIRESTORE_FIELD_DAILY_GOAL = "dailyGoalMs"
    const val FIRESTORE_FIELD_FLEX_EARN = "flexPointsEarn"
    const val FIRESTORE_FIELD_FLEX_LOSE = "flexPointsLose"


    // --- Study Logic ---
    // Durations for testing
    val BASELINE_DURATION_MS: Long = TimeUnit.MINUTES.toMillis(30) // 30-minute baseline for testing.
    //val GOAL_SETTING_TIMEOUT_MS: Long = TimeUnit.DAYS.toMillis(2) // Max time to set goal
    const val GOAL_REDUCTION_FACTOR = 0.80 // Target is 80% of baseline (20% reduction)
    val MINIMUM_GOAL_DURATION_MS: Long = TimeUnit.MINUTES.toMillis(1) // Minimum allowed goal (e.g., 1 minute)


    // Points System
    const val INITIAL_POINTS = 100
    const val MAX_POINTS = 1200
    const val MIN_POINTS = 0
    const val DEFAULT_CONTROL_EARN_POINTS = 10
    const val DEFAULT_CONTROL_LOSE_POINTS = 0 // Explicitly 0 loss for Control
    const val DEFAULT_DEPOSIT_EARN_POINTS = 10
    const val DEFAULT_DEPOSIT_LOSE_POINTS = 40 // Note: This is the *amount* lost (positive value)
    const val FLEX_STAKES_MIN_EARN = 0
    const val FLEX_STAKES_MAX_EARN = 10
    const val FLEX_STAKES_MIN_LOSE = 0
    const val FLEX_STAKES_MAX_LOSE = 40


    // --- Usage Tracking Service ---
    const val USAGE_TRACKING_CHANNEL_ID = "com.niyaz.zario.TRACKING_CHANNEL"
    const val USAGE_TRACKING_FOREGROUND_NOTIF_ID = 1
    const val USAGE_TRACKING_DAILY_FEEDBACK_NOTIF_ID = 2
    const val USAGE_TRACKING_WARN_90_NOTIF_ID = 3
    const val USAGE_TRACKING_LIMIT_100_NOTIF_ID = 4
    // Interval for foreground app checking
    val USAGE_TRACKING_INTERVAL_MS: Long = TimeUnit.MINUTES.toMillis(1) // Check every 1 minute
    val USAGE_TRACKING_MIN_SAVE_DURATION_MS: Long = TimeUnit.SECONDS.toMillis(1) // Min duration to save to DB (e.g., 1 sec)


    // --- Daily Check Worker ---
    const val DAILY_CHECK_WORKER_NAME = "ZarioDailyCheck"
    // Interval for goal checking. For testing, this is set to 30 minutes.
    val DEFAULT_CHECK_WORKER_INTERVAL_MS: Long = TimeUnit.MINUTES.toMillis(30) // Run check worker every 30 minutes.


    // --- Firestore Sync Worker ---
    const val FIRESTORE_SYNC_WORKER_NAME = "ZarioFirestoreSync"
    // Interval for syncing local data to the server.
    val FIRESTORE_SYNC_INTERVAL_MS: Long = TimeUnit.MINUTES.toMillis(5) // Run sync every 5 minutes for testing.
    const val FIRESTORE_SYNC_BATCH_SIZE = 100 // Records per sync batch


    // --- Authentication & Registration Validation ---
    const val MIN_PASSWORD_LENGTH = 8
    const val MIN_BIRTH_YEAR = 1900
    // Implicit MAX_BIRTH_YEAR is current year, handled in validation logic


    // --- Goal Setting UI ---
    const val GOAL_SETTING_APP_ICON_COUNT = 4 // Number of app icons to show
    // Implicit 90% / 100% thresholds used in UsageTrackingService notification logic


    // --- Flexible Deposit Setup UI ---
    const val DEFAULT_FLEX_EARN_SLIDER_VALUE = 5f // Default slider position (matches points)
    const val DEFAULT_FLEX_LOSE_SLIDER_VALUE = 20f // Default slider position (matches points)
    // Slider steps derived from MIN/MAX constants in UI logic


}