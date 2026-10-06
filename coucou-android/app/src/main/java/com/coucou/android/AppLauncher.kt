package com.coucou.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Model of a launchable application.
 */
data class AppEntry(
    val label: String,
    val packageName: String,
    val isSystemApp: Boolean
)

/**
 * Finds and launches installed apps by user-facing name using [PackageManager].
 *
 * Matching is done on the app label (what the user actually types, e.g. "chrome"),
 * case-insensitively, with a package-name fallback so "com.android.chrome" also works.
 */
open class AppLauncher(private val context: Context) {

    private val packageManager: PackageManager by lazy { context.packageManager }

    /**
     * All launchable activities on the device, sorted by label.
     *
     * This can be slow on cold start (it queries every package), so results are cached
     * for [CACHE_TTL_MS].
     */
    fun installedApps(forceRefresh: Boolean = false): List<AppEntry> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cache != null && now - cachedAt < CACHE_TTL_MS) {
            return cache!!
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }

        val apps = resolved.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            AppEntry(
                label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty(),
                packageName = pkg,
                isSystemApp = (info.activityInfo.flags and
                    (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                        android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            )
        }
            .filter { it.label.isNotEmpty() }
            .distinctBy { it.packageName to it.label }
            .sortedBy { it.label.lowercase() }

        cache = apps
        cachedAt = now
        return apps
    }

    /**
     * Best-effort match of free text to an installed app.
     *
     * Matching rule (Sprint 4 BHIM false-match fix):
     *  1. Exact label match (case-insensitive)
     *  2. Package name exact match
     *  3. `startsWith` ONLY if query length >= 3 chars (prevents "Hi" -> "BHIM")
     *  4. Never substring-match arbitrary short queries
     *
     * Ties are broken by shortest label, so "Maps" wins over "Google Maps".
     */
    open fun match(query: String): AppEntry? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            return null
        }
        val apps = installedApps()

        exactLabel(apps, q)?.let { return it }
        apps.firstOrNull { it.packageName.lowercase() == q }?.let { return it }

        // startsWith ONLY if query length >= 3 (prevents "Hi" matching "BHIM")
        if (q.length >= 3) {
            val prefixMatches = apps.filter { it.label.lowercase().startsWith(q) }
            if (prefixMatches.isNotEmpty()) {
                return prefixMatches.minByOrNull { it.label.length }
            }
        }

        // NO contains() or word-contains matching — prevents substring false-positives
        return null
    }

    /**
     * Launches the app matching [query] on the system launcher.
     *
     * @return the launched [AppEntry] on success, or null if nothing matched or the
     *   launch intent could not be resolved.
     */
    open fun launch(query: String): AppEntry? {
        val app = match(query) ?: return null
        val intent = packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            app
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch ${app.packageName}: ${e.message}", e)
            null
        }
    }

    /** True when [packageName] resolves to something launchable. */
    fun isInstalled(packageName: String): Boolean = try {
        packageManager.getLaunchIntentForPackage(packageName) != null
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Brings [packageName] to the foreground; falls back to launching it. */
    fun launchPackage(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch $packageName: ${e.message}", e)
            false
        }
    }

    /**
     * Opens this app's own settings screen, used when overlay permission is missing.
     */
    fun openAppSettings(): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open app settings: ${e.message}", e)
            false
        }
    }

    private fun exactLabel(apps: List<AppEntry>, q: String): AppEntry? =
        apps.firstOrNull { it.label.lowercase() == q }

    companion object {
        private const val TAG = "CoucouAppLauncher"
        private const val CACHE_TTL_MS = 30_000L

        @Volatile
        private var cache: List<AppEntry>? = null

        @Volatile
        private var cachedAt: Long = 0L
    }
}

/**
 * [CommandRouter] stub: launches an installed app by name.
 *
 * This is the Sprint 1 stub referenced on the board — it fulfils "typing 'chrome'
 * opens Chrome" and nothing more. Later sprints add further handlers.
 */
class LaunchAppCommandRouter(private val launcher: AppLauncher) : CommandRouter {

    override val name: String = "LaunchAppCommandRouter"

    override fun route(command: Command): CommandResult {
        val argument = command.argument
        if (argument.isNullOrBlank()) {
            return CommandResult.Unknown(command.raw)
        }

        // A package name is unambiguous; try it before fuzzy label matching.
        if (launcher.isInstalled(argument)) {
            return if (launcher.launchPackage(argument)) {
                CommandResult.Success(argument)
            } else {
                CommandResult.Failed("Could not launch $argument")
            }
        }

        val app = launcher.launch(argument)
            ?: return CommandResult.Unknown(argument)

        return CommandResult.Success(app.label)
    }
}