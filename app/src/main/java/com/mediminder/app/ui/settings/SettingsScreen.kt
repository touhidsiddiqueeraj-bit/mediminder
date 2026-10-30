package com.mediminder.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import com.mediminder.app.R
import com.mediminder.app.domain.repository.ThemeMode
import com.mediminder.app.ui.components.BigButton
import com.mediminder.app.ui.components.ConfirmDialog
import com.mediminder.app.ui.components.PermissionStatusCard
import com.mediminder.app.ui.components.SectionCard
import com.mediminder.app.ui.components.SelectionCard
import com.mediminder.app.ui.components.Stepper
import androidx.lifecycle.Lifecycle

@Composable
fun SettingsScreen(
    onOpenPrivacy: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-check permissions every time the user comes back from system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissions() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        }

        // ---- Reminders ----
        item(key = "reminders") {
            SectionCard(title = stringResource(R.string.settings_reminders_title)) {
                Text(stringResource(R.string.setting_snooze_minutes), style = MaterialTheme.typography.titleSmall)
                ChipRow(
                    options = listOf(10, 15, 30),
                    selected = settings.snoozeMinutes,
                    label = { context.getString(R.string.minutes_n, it) },
                    onSelect = viewModel::setSnoozeMinutes,
                )
                Text(
                    stringResource(R.string.setting_snooze_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    stringResource(R.string.setting_re_remind_interval),
                    style = MaterialTheme.typography.titleSmall,
                )
                ChipRow(
                    options = listOf(5, 10, 15),
                    selected = settings.reRemindIntervalMinutes,
                    label = { context.getString(R.string.minutes_n, it) },
                    onSelect = viewModel::setReRemindIntervalMinutes,
                )

                Stepper(
                    value = settings.maxReReminds,
                    onValueChange = viewModel::setMaxReReminds,
                    range = 1..5,
                    label = stringResource(R.string.setting_max_re_reminds),
                )
            }
        }

        // ---- Voice & appearance ----
        item(key = "voice") {
            SectionCard(title = stringResource(R.string.settings_voice_title)) {
                ToggleRow(
                    title = stringResource(R.string.setting_tts_title),
                    subtitle = stringResource(R.string.setting_tts_subtitle),
                    checked = settings.ttsEnabled,
                    onCheckedChange = viewModel::setTtsEnabled,
                )
            }
        }
        item(key = "appearance") {
            SectionCard(title = stringResource(R.string.settings_appearance_title)) {
                SelectionCard(
                    selected = settings.themeMode == ThemeMode.SYSTEM,
                    onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
                    title = stringResource(R.string.theme_system),
                )
                SelectionCard(
                    selected = settings.themeMode == ThemeMode.LIGHT,
                    onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                    title = stringResource(R.string.theme_light),
                )
                SelectionCard(
                    selected = settings.themeMode == ThemeMode.DARK,
                    onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
                    title = stringResource(R.string.theme_dark),
                )
            }
        }

        // ---- Permissions (reliability) ----
        item(key = "permissions") {
            SectionCard(title = stringResource(R.string.settings_permissions_title)) {
                Text(
                    stringResource(R.string.settings_permissions_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PermissionStatusCard(
                    icon = Icons.Filled.NotificationsActive,
                    title = stringResource(R.string.perm_notifications_title),
                    granted = permissions.notifications,
                    grantedText = stringResource(R.string.perm_notifications_ok),
                    notGrantedText = stringResource(R.string.perm_notifications_missing),
                    actionLabel = stringResource(R.string.perm_open_settings),
                    onAction = { openAppNotificationSettings(context) },
                )
                PermissionStatusCard(
                    icon = Icons.Filled.Alarm,
                    title = stringResource(R.string.perm_exact_alarms_title),
                    granted = permissions.exactAlarms,
                    grantedText = stringResource(R.string.perm_exact_alarms_ok),
                    notGrantedText = stringResource(R.string.perm_exact_alarms_missing),
                    actionLabel = stringResource(R.string.perm_allow),
                    onAction = { openExactAlarmSettings(context) },
                )
                PermissionStatusCard(
                    icon = Icons.Filled.BatteryFull,
                    title = stringResource(R.string.perm_battery_title),
                    granted = permissions.batteryOptimizationIgnored,
                    grantedText = stringResource(R.string.perm_battery_ok),
                    notGrantedText = stringResource(R.string.perm_battery_missing),
                    actionLabel = stringResource(R.string.perm_allow),
                    onAction = { requestIgnoreBatteryOptimizations(context) },
                )
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    PermissionStatusCard(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.perm_fullscreen_title),
                        granted = permissions.fullScreenIntent,
                        grantedText = stringResource(R.string.perm_fullscreen_ok),
                        notGrantedText = stringResource(R.string.perm_fullscreen_missing),
                        actionLabel = stringResource(R.string.perm_open_settings),
                        onAction = { openAppSettings(context) },
                    )
                }
            }
        }

        // ---- Privacy ----
        item(key = "privacy") {
            SectionCard(title = stringResource(R.string.settings_privacy_title)) {
                ToggleRow(
                    title = stringResource(R.string.setting_delete_photos_title),
                    subtitle = stringResource(R.string.setting_delete_photos_subtitle),
                    checked = settings.deletePhotosAfterScan,
                    onCheckedChange = viewModel::setDeletePhotosAfterScan,
                )
                BigButton(
                    text = stringResource(R.string.privacy_policy_button),
                    onClick = onOpenPrivacy,
                    leadingIcon = Icons.Filled.Security,
                )
            }
        }

        // ---- Danger zone ----
        item(key = "danger") {
            SectionCard(title = stringResource(R.string.settings_data_title)) {
                BigButton(
                    text = stringResource(R.string.delete_all_data),
                    onClick = viewModel::requestDeleteAll,
                    leadingIcon = Icons.Filled.DeleteForever,
                    enabled = !uiState.deletingAll,
                )
            }
        }

        item(key = "about") {
            SectionCard(title = stringResource(R.string.settings_about_title)) {
                Text(
                    stringResource(R.string.disclaimer_long),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "MediMinder 1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (uiState.confirmDeleteAll) {
        ConfirmDialog(
            title = stringResource(R.string.delete_all_confirm_title),
            text = stringResource(R.string.delete_all_confirm_text),
            confirmLabel = stringResource(R.string.delete_all_confirm_button),
            onConfirm = viewModel::confirmDeleteAll,
            onDismiss = viewModel::cancelDeleteAll,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChipRow(
    options: List<Int>,
    selected: Int,
    label: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}
