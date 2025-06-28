package com.niyaz.zario.utils


import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat


object PermissionsUtils {


    /**
     * Checks if the Usage Stats permission (PACKAGE_USAGE_STATS) is granted.
     *
     * @param context The application context.
     * @return True if permission is granted, false otherwise.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOpsManager = ContextCompat.getSystemService(context, AppOpsManager::class.java) ?: return false
        // Mode flags have changed meanings slightly over APIs. AppOpsManager.OPSTR_GET_USAGE_STATS is the key string.
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION") // Use the deprecated checkOpNoThrow for older APIs
            appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }


    /**
     * Creates an Intent to navigate the user to the system settings screen
     * where they can grant the Usage Stats permission.
     *
     * @return An Intent to start the settings activity.
     */
    fun getUsageStatsPermissionIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        // Optional: Add flags if needed, e.g., Intent.FLAG_ACTIVITY_NEW_TASK
    }
}

