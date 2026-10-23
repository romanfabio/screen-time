package io.github.romanfabio.screentime.data.source

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.romanfabio.screentime.data.db.AppInfoEntity

fun interface AppInfoResolver {
    fun resolve(packageNames: Collection<String>, nowMs: Long): List<AppInfoEntity>
}

/** Resolves labels and system/launcher flags from [PackageManager]. Needs QUERY_ALL_PACKAGES. */
class AppInfoSource(private val context: Context) : AppInfoResolver {

    override fun resolve(packageNames: Collection<String>, nowMs: Long): List<AppInfoEntity> {
        val pm = context.packageManager
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            // Settings declares a "FallbackHome" HOME activity for boot; it is not a launcher the user sees.
            .filterNot { it.activityInfo.name.endsWith("FallbackHome") }
            .map { it.activityInfo.packageName }
            .toSet()
        val withIcon = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .toSet()
        return packageNames.map { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                    info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                AppInfoEntity(
                    packageName = pkg,
                    label = pm.getApplicationLabel(info).toString(),
                    isSystem = system,
                    isLauncher = pkg in launchers,
                    lastSeenMs = nowMs,
                    hasLauncherIcon = pkg in withIcon,
                )
            } catch (_: PackageManager.NameNotFoundException) {
                AppInfoEntity(pkg, pkg, isSystem = false, isLauncher = false, lastSeenMs = nowMs, uninstalled = true)
            }
        }
    }
}
