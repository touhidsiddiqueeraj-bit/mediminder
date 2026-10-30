package com.mediminder.app.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mediminder.app.R

/** Plain-language privacy policy. Kept in resources so translators can handle it. */
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "title") {
            androidx.compose.foundation.layout.Row {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Text(
                    stringResource(R.string.privacy_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        item(key = "body") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.privacy_intro), style = MaterialTheme.typography.bodyLarge)
                PrivacySection(stringResource(R.string.privacy_local_title), stringResource(R.string.privacy_local_body))
                PrivacySection(stringResource(R.string.privacy_ocr_title), stringResource(R.string.privacy_ocr_body))
                PrivacySection(stringResource(R.string.privacy_encryption_title), stringResource(R.string.privacy_encryption_body))
                PrivacySection(stringResource(R.string.privacy_network_title), stringResource(R.string.privacy_network_body))
                PrivacySection(stringResource(R.string.privacy_no_tracking_title), stringResource(R.string.privacy_no_tracking_body))
                PrivacySection(stringResource(R.string.privacy_backup_title), stringResource(R.string.privacy_backup_body))
                PrivacySection(stringResource(R.string.privacy_disclaimer_title), stringResource(R.string.privacy_disclaimer_body))
            }
        }
    }
}

@Composable
private fun PrivacySection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
