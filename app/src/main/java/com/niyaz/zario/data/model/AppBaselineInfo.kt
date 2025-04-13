package com.niyaz.zario.data.model // Correct package

import android.graphics.drawable.Drawable

/**
 * Data Transfer Object (DTO) holding processed baseline usage information for a single application.
 * This model is primarily used to populate the UI during the goal-setting phase,
 * allowing the user to view and select their target app.
 *
 * @property packageName The unique package name identifier of the application (e.g., "com.example.app").
 * @property appName The user-friendly display name of the application (e.g., "Example App").
 * @property averageDailyUsageMs The calculated average daily usage duration for this application
 *                               during the baseline period, measured in milliseconds.
 * @property icon The application's icon as a Drawable, suitable for display in the UI.
 *                May be null if the icon cannot be resolved (though a default is typically provided).
 */
data class AppBaselineInfo(
    val packageName: String,
    val appName: String,
    val averageDailyUsageMs: Long,
    val icon: Drawable? // Keep as nullable Drawable, AppInfoHelper handles defaults
)