package com.niyaz.zario.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import com.niyaz.zario.utils.AppInfoHelper.getAppDetails

/**
 * A utility object for retrieving application metadata like name and icon using [PackageManager].
 * Includes an in-memory cache to optimize repeated lookups for the same package name.
 * Provides fallbacks for cases where application info cannot be found.
 */
object AppInfoHelper {

    private const val TAG = "AppInfoHelper"

    /**
     * Internal data class holding the retrieved application details.
     * @property appName The display name of the application.
     * @property icon The application's icon. May be null only if the default fallback icon also fails to load.
     * @warning Caching Drawable instances can potentially lead to memory leaks if not managed carefully,
     *          although using the application context minimizes this risk for standard app icons.
     */
    data class AppDetails(
        val appName: String,
        val icon: Drawable?
    )

    // Simple in-memory cache: Map<PackageName, AppDetails>
    private val appInfoCache = mutableMapOf<String, AppDetails>()

    /**
     * Convenience function to get only the application name for a given package name.
     * Uses the cache via [getAppDetails].
     *
     * @param context The application context.
     * @param packageName The package name of the application.
     * @return The display name of the application, or the package name if lookup fails.
     */
    fun getAppName(context: Context, packageName: String): String {
        return getAppDetails(context, packageName).appName
    }

    /**
     * Gets the [AppDetails] (name and icon) for a given package name.
     * Results are cached in memory to avoid redundant PackageManager lookups.
     * Uses the application context to mitigate potential memory leaks.
     * Provides sensible fallbacks if the application is not found or an error occurs.
     *
     * @param context Context to access PackageManager and Resources. Should ideally be ApplicationContext.
     * @param packageName The package name of the application to look up.
     * @return [AppDetails] containing the application name and icon (or fallbacks).
     */
    fun getAppDetails(context: Context, packageName: String): AppDetails {
        // Return cached result if available
        appInfoCache[packageName]?.let {
            Log.v(TAG, "Cache hit for $packageName") // Verbose logging for cache hit
            return it
        }
        Log.v(TAG, "Cache miss for $packageName. Querying PackageManager.")

        // Use application context to avoid potential leaks
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val defaultIcon = try {
            // Load default system icon using application context's resources
            ResourcesCompat.getDrawable(appContext.resources, android.R.mipmap.sym_def_app_icon, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load default app icon resource", e)
            null // Allow icon to be null if default even fails
        }

        val details = try {
            val appInfo: ApplicationInfo? = try {
                pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA) // Added flag for slightly more info if needed later
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Application package not found: $packageName")
                null // App not found
            }

            val appName = appInfo?.loadLabel(pm)?.toString() ?: packageName // Fallback to package name
            val icon = appInfo?.loadIcon(pm) ?: defaultIcon // Use loaded icon or default

            AppDetails(appName = appName, icon = icon)

        } catch (e: Exception) {
            // Catch any other unexpected errors during lookup
            Log.e(TAG, "Failed to get app details for $packageName: ${e.message}", e)
            // Fallback with package name and default icon
            AppDetails(appName = packageName, icon = defaultIcon)
        }

        // Store result in cache before returning
        appInfoCache[packageName] = details
        return details
    }

    /**
     * Clears the internal cache of application details.
     * May be useful in low memory situations or if application installs/uninstalls occur.
     */
    fun clearCache() {
        Log.d(TAG, "Clearing AppInfoHelper cache.")
        appInfoCache.clear()
    }
}