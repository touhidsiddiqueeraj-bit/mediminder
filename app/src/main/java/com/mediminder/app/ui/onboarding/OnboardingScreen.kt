package com.mediminder.app.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mediminder.app.R
import com.mediminder.app.ui.components.BigButton
import com.mediminder.app.ui.components.BigOutlinedButton
import com.mediminder.app.ui.components.PermissionStatusCard
import com.mediminder.app.ui.settings.openAppNotificationSettings
import com.mediminder.app.ui.settings.openExactAlarmSettings
import com.mediminder.app.ui.settings.requestIgnoreBatteryOptimizations
import kotlinx.coroutines.launch

/**
 * A short, skippable walkthrough (4 pages, big icons, big text) including the
 * guided permission setup that keeps reminders reliable.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pageCount = 4
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun nextPage() {
        scope.launch {
            if (pagerState.currentPage < pageCount - 1) {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDone) { Text(stringResource(R.string.onboarding_skip)) }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> HowItWorksPage()
                2 -> PermissionsPage()
                else -> DonePage()
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pageCount) { index ->
                val selected = pagerState.currentPage == index
                Surface(
                    shape = CircleShape,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    modifier = Modifier
                        .padding(4.dp)
                        .size(if (selected) 16.dp else 12.dp),
                ) {}
            }
        }

        BigButton(
            text = if (pagerState.currentPage == pageCount - 1) {
                stringResource(R.string.onboarding_start)
            } else {
                stringResource(R.string.next)
            },
            onClick = {
                if (pagerState.currentPage == pageCount - 1) onDone() else nextPage()
            },
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun OnboardingPage(
    icon: ImageVector,
    title: String,
    message: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.size(24.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WelcomePage() {
    OnboardingPage(
        icon = Icons.Filled.Alarm,
        title = stringResource(R.string.onboarding_welcome_title),
        message = stringResource(R.string.onboarding_welcome_message),
    )
}

@Composable
private fun HowItWorksPage() {
    OnboardingPage(
        icon = Icons.Filled.PhotoCamera,
        title = stringResource(R.string.onboarding_how_title),
        message = stringResource(R.string.onboarding_how_message),
    )
}

@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableStateOf(0) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshTrigger++ }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshTrigger++ }

    val notificationsGranted = if (android.os.Build.VERSION.SDK_INT >= 33) {
        remember(refreshTrigger) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
    } else {
        true
    }
    val exactGranted = if (android.os.Build.VERSION.SDK_INT >= 31) {
        remember(refreshTrigger) {
            (context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager)
                .canScheduleExactAlarms()
        }
    } else {
        true
    }
    val batteryGranted = remember(refreshTrigger) {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_permissions_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.onboarding_permissions_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PermissionStatusCard(
            icon = Icons.Filled.NotificationsActive,
            title = stringResource(R.string.perm_notifications_title),
            granted = notificationsGranted,
            grantedText = stringResource(R.string.perm_notifications_ok),
            notGrantedText = stringResource(R.string.perm_notifications_missing),
            actionLabel = if (android.os.Build.VERSION.SDK_INT >= 33) {
                stringResource(R.string.perm_allow)
            } else {
                stringResource(R.string.perm_open_settings)
            },
            onAction = {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openAppNotificationSettings(context)
                }
            },
        )
        PermissionStatusCard(
            icon = Icons.Filled.Alarm,
            title = stringResource(R.string.perm_exact_alarms_title),
            granted = exactGranted,
            grantedText = stringResource(R.string.perm_exact_alarms_ok),
            notGrantedText = stringResource(R.string.perm_exact_alarms_missing),
            actionLabel = stringResource(R.string.perm_allow),
            onAction = { openExactAlarmSettings(context) },
        )
        PermissionStatusCard(
            icon = Icons.Filled.BatteryFull,
            title = stringResource(R.string.perm_battery_title),
            granted = batteryGranted,
            grantedText = stringResource(R.string.perm_battery_ok),
            notGrantedText = stringResource(R.string.perm_battery_missing),
            actionLabel = stringResource(R.string.perm_allow),
            onAction = { requestIgnoreBatteryOptimizations(context) },
        )
    }
}

@Composable
private fun DonePage() {
    OnboardingPage(
        icon = Icons.Filled.RecordVoiceOver,
        title = stringResource(R.string.onboarding_done_title),
        message = stringResource(R.string.onboarding_done_message),
    )
}
