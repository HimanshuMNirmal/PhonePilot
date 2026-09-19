package com.phonepilot.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Resolves human-readable application names (e.g., "Settings", "Camera", "Calculator")
 * to actual Android package names using dynamic [PackageManager] queries and a fallback dictionary.
 */
class AppPackageResolver(
    private val context: Context
) {

    companion object {
        private const val TAG = "PhonePilot"

        // Small static fallback map for common system package aliases
        private val FALLBACK_PACKAGES = mapOf(
            "settings" to "com.android.settings",
            "setting" to "com.android.settings",
            "camera" to "com.android.camera",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "youtube" to "com.google.android.youtube",
            "messages" to "com.google.android.apps.messaging",
            "clock" to "com.google.android.deskclock",
            "calculator" to "com.google.android.calculator",
            "photos" to "com.google.android.apps.photos",
            "phonepilot" to "com.phonepilot"
        )
    }

    /**
     * Resolves an app query string into an installed package name.
     * Returns null if no matching application could be identified.
     */
    fun resolve(appNameOrPackage: String): String? {
        val query = appNameOrPackage.trim()
        if (query.isEmpty()) return null

        // 1. If query is already a full package name format, verify and return
        if (query.contains('.') && query.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))) {
            if (isPackageInstalled(query)) {
                return query
            }
        }

        val lowerQuery = query.lowercase()
        val pm = context.packageManager

        // 2. Query all launchable apps dynamically from PackageManager
        val launchablePackages = queryLaunchableApps(pm)

        // 2a. Exact match on app label
        for ((label, pkg) in launchablePackages) {
            if (label.equals(lowerQuery, ignoreCase = true)) {
                Log.d(TAG, "Resolved '$query' to '$pkg' via exact label match")
                return pkg
            }
        }

        // 2b. Word/prefix match (e.g., "Google Chrome" matching "Chrome")
        for ((label, pkg) in launchablePackages) {
            if (label.contains(lowerQuery, ignoreCase = true) || lowerQuery.contains(label, ignoreCase = true)) {
                Log.d(TAG, "Resolved '$query' to '$pkg' via fuzzy label match ('$label')")
                return pkg
            }
        }

        // 3. Fallback dictionary for common system components
        val fallback = FALLBACK_PACKAGES[lowerQuery]
        if (fallback != null && isPackageInstalled(fallback)) {
            Log.d(TAG, "Resolved '$query' to '$fallback' via fallback dictionary")
            return fallback
        }

        Log.w(TAG, "Unable to resolve app '$query' to an installed package")
        return null
    }

    private fun queryLaunchableApps(pm: PackageManager): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        return try {
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            resolveInfos.mapNotNull { info ->
                val label = info.loadLabel(pm).toString().trim()
                val pkg = info.activityInfo?.packageName
                if (label.isNotEmpty() && !pkg.isNullOrEmpty()) {
                    label.lowercase() to pkg
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query launchable activities: ${e.message}")
            emptyList()
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
