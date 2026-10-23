package io.github.romanfabio.screentime.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.romanfabio.screentime.data.source.UsageEventSource
import kotlinx.coroutines.flow.MutableStateFlow

data class PermissionState(val usageAccess: Boolean, val batteryExempt: Boolean)

class PermissionStatus(private val context: Context, private val source: UsageEventSource) {

    fun current() = PermissionState(
        usageAccess = source.hasUsageAccess(),
        batteryExempt = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
    )

    fun usageAccessIntent() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** System dialog "Allow app to always run in background?" for this package. */
    // Not distributed on Google Play, where this request is restricted; here it is an explicit, optional user action.
    @SuppressLint("BatteryLife")
    fun batteryExemptionIntent() = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        "package:${context.packageName}".toUri(),
    )

    fun appDetailsIntent() = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:${context.packageName}".toUri(),
    )
}

/** Small UI preferences that must be readable synchronously at startup. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(value) = prefs.edit { putBoolean("onboarding_done", value) }

    /** Show launchers, system plumbing and this app in lists (they are always counted in totals). */
    var showHiddenApps: Boolean
        get() = showHiddenAppsFlow.value
        set(value) {
            prefs.edit { putBoolean("show_hidden_apps", value) }
            showHiddenAppsFlow.value = value
        }

    /** Lets screens react to the toggle without polling. */
    val showHiddenAppsFlow = MutableStateFlow(prefs.getBoolean("show_hidden_apps", false))
}
