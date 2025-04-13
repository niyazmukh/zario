package com.niyaz.zario.data.model // New package for data models

import android.graphics.drawable.Drawable

/**
 * Data class holding baseline information for an application, suitable for UI display.
 */
data class AppBaselineInfo(
    val packageName: String,
    val appName: String,
    val averageDailyUsageMs: Long,
    val icon: Drawable?
)