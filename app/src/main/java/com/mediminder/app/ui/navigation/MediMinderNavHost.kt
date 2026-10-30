package com.mediminder.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mediminder.app.R
import com.mediminder.app.ui.addmedication.AddMedicationScreen
import com.mediminder.app.ui.detail.MedicationDetailScreen
import com.mediminder.app.ui.history.HistoryScreen
import com.mediminder.app.ui.onboarding.OnboardingScreen
import com.mediminder.app.ui.privacy.PrivacyPolicyScreen
import com.mediminder.app.ui.scan.ScanScreen
import com.mediminder.app.ui.settings.SettingsScreen
import com.mediminder.app.ui.today.TodayScreen
import kotlinx.coroutines.flow.Flow

/** All routes. Kept as simple strings so navigation stays boring and debuggable. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val TODAY = "today"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val SCAN = "scan"
    const val PRIVACY = "privacy"
    const val ADD_MEDICATION = "add_medication?medicationId={medicationId}&photoUri={photoUri}"
    const val DETAIL = "detail/{medicationId}"

    fun addMedication(medicationId: Long = 0L, photoUri: String? = null): String =
        "add_medication?medicationId=$medicationId&photoUri=${photoUri ?: ""}"

    fun detail(medicationId: Long): String = "detail/$medicationId"
}

@Composable
fun MediMinderNavHost(
    showOnboardingFirst: Boolean,
    reminderOpened: Flow<Long>,
    onOnboardingFinished: () -> Unit,
) {
    val navController = rememberNavController()

    // Opening a reminder notification always lands on Today.
    LaunchedEffect(reminderOpened) {
        reminderOpened.collect {
            navController.navigate(Routes.TODAY) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in setOf(Routes.TODAY, Routes.HISTORY, Routes.SETTINGS)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomDestinations().forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(destination.icon, contentDescription = null)
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (showOnboardingFirst) Routes.ONBOARDING else Routes.TODAY,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onDone = {
                        onOnboardingFinished()
                        navController.navigate(Routes.TODAY) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.TODAY) {
                TodayScreen(
                    onAddManually = { navController.navigate(Routes.addMedication()) },
                    onScan = { navController.navigate(Routes.SCAN) },
                    onOpenMedication = { id -> navController.navigate(Routes.detail(id)) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onOpenPrivacy = { navController.navigate(Routes.PRIVACY) })
            }
            composable(
                route = Routes.ADD_MEDICATION,
                arguments = listOf(
                    navArgument("medicationId") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("photoUri") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                AddMedicationScreen(
                    medicationId = entry.arguments?.getLong("medicationId") ?: 0L,
                    photoUri = entry.arguments?.getString("photoUri")?.takeIf { it.isNotBlank() },
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.SCAN) {
                ScanScreen(
                    onFinished = { navController.popBackStack() },
                    onEnterManually = { photoUri ->
                        navController.navigate(Routes.addMedication(photoUri = photoUri)) {
                            popUpTo(Routes.SCAN) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("medicationId") { type = NavType.LongType }),
            ) { entry ->
                MedicationDetailScreen(
                    medicationId = entry.arguments?.getLong("medicationId") ?: 0L,
                    onEdit = { id -> navController.navigate(Routes.addMedication(medicationId = id)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.PRIVACY) {
                PrivacyPolicyScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private data class BottomDestination(
    val route: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val labelRes: Int,
)

private fun bottomDestinations() = listOf(
    BottomDestination(Routes.TODAY, Icons.Filled.Home, R.string.nav_today),
    BottomDestination(Routes.HISTORY, Icons.Filled.CalendarMonth, R.string.nav_history),
    BottomDestination(Routes.SETTINGS, Icons.Filled.Settings, R.string.nav_settings),
)
