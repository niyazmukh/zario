package com.niyaz.zario.utils


import java.util.concurrent.TimeUnit


/**
 * Central object for holding application-wide constants.
 */
object Constants {


   // --- SharedPreferences Keys (from StudyStateManager) ---
   const val PREFS_NAME = "ZarioStudyStatePrefs"
   const val KEY_STUDY_PHASE = "study_phase"
   const val KEY_STUDY_START_TIMESTAMP = "study_start_timestamp"
   const val KEY_CONDITION = "study_condition"
   const val KEY_TARGET_APP = "target_app_package"
   const val KEY_DAILY_GOAL_MS = "daily_goal_ms"
   const val KEY_POINTS_BALANCE = "points_balance"
   const val KEY_FLEX_POINTS_EARN = "flex_points_earn"
   const val KEY_FLEX_POINTS_LOSE = "flex_points_lose"
   const val KEY_USER_ID = "user_id"
   const val KEY_LAST_DAILY_CHECK_TIMESTAMP = "last_daily_check_timestamp"
   const val KEY_LAST_DAY_GOAL_REACHED = "last_day_goal_reached"
   const val KEY_LAST_DAY_POINTS_CHANGE = "last_day_points_change"


   // --- Firestore ---
   const val FIRESTORE_COLLECTION_USERS = "users"
   const val FIRESTORE_FIELD_EMAIL = "email"
   const val FIRESTORE_FIELD_YOB = "yearOfBirth"
   const val FIRESTORE_FIELD_GENDER = "gender"
   const val FIRESTORE_FIELD_REG_TIMESTAMP = "registrationTimestamp"
   const val FIRESTORE_FIELD_STUDY_PHASE = "studyPhase"
   const val FIRESTORE_FIELD_STUDY_CONDITION = "studyCondition"
   const val FIRESTORE_FIELD_STUDY_START_TIMESTAMP = "studyStartTimestamp"
   const val FIRESTORE_FIELD_POINTS_BALANCE = "pointsBalance"
   const val FIRESTORE_FIELD_TARGET_APP = "targetApp"
   const val FIRESTORE_FIELD_DAILY_GOAL = "dailyGoalMs"
   const val FIRESTORE_FIELD_FLEX_EARN = "flexEarn"
   const val FIRESTORE_FIELD_FLEX_LOSE = "flexLose"


   // --- Notification Channels ---
   const val USAGE_TRACKING_CHANNEL_ID = "usage_tracking_channel"
   const val USAGE_TRACKING_FOREGROUND_NOTIF_ID = 101
   const val USAGE_TRACKING_DAILY_FEEDBACK_NOTIF_ID = 201
   const val USAGE_TRACKING_WARN_90_NOTIF_ID = 202
   const val USAGE_TRACKING_LIMIT_100_NOTIF_ID = 203


   // --- Worker Names ---
   const val DAILY_CHECK_WORKER_NAME = "ZarioDailyCheck"
   const val FIRESTORE_SYNC_WORKER_NAME = "ZarioFirestoreSync"


   // --- Time Durations ---
   val DAILY_CHECK_INTERVAL_MINUTES: Long = 15
   val FIRESTORE_SYNC_INTERVAL_MINUTES: Long = 5
   val BASELINE_DURATION_MINUTES: Long = 10
   val USAGE_TICKER_INTERVAL_SECONDS: Long = 15
   val DAILY_OUTCOME_VALIDITY_MINUTES: Long = 30 // Check if outcome is from the last 30 mins


   // --- Usage Tracking Service ---
   val USAGE_TRACKING_INTERVAL_MS: Long = TimeUnit.MINUTES.toMillis(1) // Check every 1 minute
   const val USAGE_TRACKING_MIN_SAVE_DURATION_MS = 1000L // Min duration to save to DB


   // --- Goal Setting ---
   const val GOAL_REDUCTION_FACTOR = 0.80 // 20% reduction
   const val MINIMUM_GOAL_DURATION_MS = 60000L // 1 minute


   // --- Point System ---
   const val INITIAL_POINTS = 100
   const val MAX_POINTS = 1200
   const val MIN_POINTS = 0
   const val DEFAULT_CONTROL_EARN_POINTS = 10
   const val DEFAULT_CONTROL_LOSE_POINTS = 0
   const val DEFAULT_DEPOSIT_EARN_POINTS = 10
   const val DEFAULT_DEPOSIT_LOSE_POINTS = 20
   const val FLEX_STAKES_MIN_EARN = 0
   const val FLEX_STAKES_MAX_EARN = 10
   const val FLEX_STAKES_MIN_LOSE = 0
   const val FLEX_STAKES_MAX_LOSE = 40
   const val DEFAULT_FLEX_EARN_SLIDER = 5f
   const val DEFAULT_FLEX_LOSE_SLIDER = 20f


   // --- Misc UI ---
   const val GOAL_SETTING_APP_ICON_COUNT = 4 // Number of app icons to show


   // --- Authentication ---
   const val MIN_PASSWORD_LENGTH = 8
   const val MIN_BIRTH_YEAR = 1900


   // Default values for study conditions
   const val MIN_DB_SAVE_DURATION_MS = 1000L


   // --- OLD USAGE TRACKING INTERVAL ---
   // Kept for reference, but new constants above should be used.
   // val USAGE_TRACKING_INTERVAL_MS: Long = TimeUnit.MINUTES.toMillis(1) // Check every 1 minute
   // val USAGE_TRACKING_INTERVAL_MS: Long = TimeUnit.SECONDS.toMillis(15) // TESTING VALUE


   // --- OLD BASELINE DURATION ---
   // Kept for reference, new constants above should be used.
   // const val BASELINE_DURATION_DAYS = 7


   // Firestore Collection/Field Names
   const val FIRESTORE_FIELD_USER_ID = "userId"
   // ... existing code ...


}