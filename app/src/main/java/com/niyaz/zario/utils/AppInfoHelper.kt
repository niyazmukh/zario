package com.niyaz.zario.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.content.res.ResourcesCompat // For loading default icon

object AppInfoHelper {

    private const val TAG = "AppInfoHelper"
    // Cache now includes Drawable?
    private val appInfoCache = mutableMapOf<String, AppDetails>()

    data class AppDetails(
        val appName: String,
        val icon: Drawable? // Store the actual icon
    )

    // Keep getAppName for simplicity if only name is needed elsewhere
    fun getAppName(context: Context, packageName: String): String {
        return getAppDetails(context, packageName).appName
    }

    /**
     * Gets the AppDetails (name, icon) for a given package name.
     * Caches results for efficiency.
     * Includes a fallback default icon.
     *
     * @param context Context to access PackageManager.
     * @param packageName The package name of the application.
     * @return AppDetails containing the name and icon.
     */
    fun getAppDetails(context: Context, packageName: String): AppDetails {
        appInfoCache[packageName]?.let { return it }

        val pm = context.packageManager
        val defaultIcon = ResourcesCompat.getDrawable(context.resources, android.R.mipmap.sym_def_app_icon, null) // Standard default icon

        return try {
            val appInfo: ApplicationInfo? = try {
                pm.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }

            val appName = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: packageName
            val icon = appInfo?.loadIcon(pm) ?: defaultIcon // Use loaded icon or default

            val details = AppDetails(appName = appName, icon = icon)
            appInfoCache[packageName] = details
            details
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app details for $packageName: ${e.message}", e)
            // Fallback with package name and default icon
            AppDetails(appName = packageName, icon = defaultIcon)
        }
    }

    fun clearCache() {
        appInfoCache.clear()
    }
}