package io.github.romanfabio.screentime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.romanfabio.screentime.container
import io.github.romanfabio.screentime.ui.appdetail.AppDetailScreen
import io.github.romanfabio.screentime.ui.dashboard.DashboardScreen
import io.github.romanfabio.screentime.ui.debug.DebugScreen
import io.github.romanfabio.screentime.ui.licenses.LicensesScreen
import io.github.romanfabio.screentime.ui.onboarding.OnboardingScreen
import io.github.romanfabio.screentime.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val DEBUG = "debug"
    const val LICENSES = "licenses"
    const val APP_DETAIL = "app/{packageName}"

    fun appDetail(packageName: String) = "app/$packageName"
}

@Composable
fun ScreenTimeApp() {
    val context = LocalContext.current
    val container = context.container
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    // Onboarding on first run, and again whenever usage access was lost.
    val start = remember {
        if (!container.prefs.onboardingDone || !container.permissions.current().usageAccess) Routes.ONBOARDING
        else Routes.DASHBOARD
    }

    // Keep the database current whenever the app comes to the foreground (the worker covers the rest).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (container.eventSource.hasUsageAccess()) {
            scope.launch(Dispatchers.Default) { runCatching { container.ingest.run() } }
        }
    }

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.DASHBOARD) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                },
            )
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenApp = { navController.navigate(Routes.appDetail(it)) },
            )
        }
        composable(
            route = Routes.APP_DETAIL,
            arguments = listOf(navArgument("packageName") { type = NavType.StringType }),
        ) { entry ->
            AppDetailScreen(
                packageName = entry.arguments?.getString("packageName").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.DEBUG) {
            DebugScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenDebug = { navController.navigate(Routes.DEBUG) },
                onOpenLicenses = { navController.navigate(Routes.LICENSES) },
            )
        }
        composable(Routes.LICENSES) {
            LicensesScreen(onBack = { navController.popBackStack() })
        }
    }
}
