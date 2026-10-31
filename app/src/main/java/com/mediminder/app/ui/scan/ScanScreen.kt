package com.mediminder.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CameraEnhance
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.mediminder.app.R
import com.mediminder.app.domain.model.DrugNameType
import com.mediminder.app.domain.model.LabelField
import com.mediminder.app.domain.model.ScoredDrugName
import com.mediminder.app.ui.addmedication.ScheduleEditor
import com.mediminder.app.ui.components.BigButton
import com.mediminder.app.ui.components.BigOutlinedButton
import com.mediminder.app.ui.components.ConfirmDialog
import com.mediminder.app.ui.components.EmptyStateCard
import com.mediminder.app.ui.components.LabeledField
import com.mediminder.app.ui.components.SectionCard
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.io.File
import java.util.Locale

/**
 * Scan a medicine label: camera -> on-device OCR -> confirm every field
 * next to the photo. Never saves anything without an explicit Save tap.
 */

/** Dictionary score at/above which the name is pre-filled without a warning. */
private const val AUTO_NAME_SCORE = 0.85f

/** Dictionary score at/above which the name is pre-filled but highlighted. */
private const val REVIEW_NAME_SCORE = 0.60f

@Composable
fun ScanScreen(
    onFinished: () -> Unit,
    onEnterManually: (photoUri: String?) -> Unit,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.saved) {
        LaunchedEffect(state.saved) { onFinished() }
    }

    when (state.step) {
        ScanStep.CAMERA -> CameraStep(
            onEnterManually = onEnterManually,
            onPhotoCaptured = viewModel::onPhotoCaptured,
        )
        ScanStep.PROCESSING -> ProcessingStep()
        ScanStep.REVIEW -> ReviewStep(state = state, viewModel = viewModel)
        ScanStep.ERROR -> ErrorStep(
            onRetry = viewModel::reset,
            onEnterManually = { onEnterManually(state.photoUri) },
        )
    }

    if (state.duplicateOf != null) {
        ConfirmDialog(
            title = stringResource(R.string.duplicate_warning_title),
            text = stringResource(R.string.duplicate_warning_text, state.duplicateOf!!),
            confirmLabel = stringResource(R.string.save_anyway),
            onConfirm = viewModel::confirmDuplicate,
            onDismiss = viewModel::dismissDuplicate,
        )
    }
}

// ---------------------------------------------------------------------------
// Camera
// ---------------------------------------------------------------------------

@Composable
private fun CameraStep(
    onPhotoCaptured: (File) -> Unit,
    onEnterManually: (String?) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.scan_instructions),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (hasPermission) {
            val imageCapture = remember {
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                CameraPreview(
                    imageCapture = imageCapture,
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center),
                )
                CaptureButton(
                    imageCapture = imageCapture,
                    onCaptured = onPhotoCaptured,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyStateCard(
                    icon = Icons.Filled.PhotoCamera,
                    title = stringResource(R.string.camera_permission_title),
                    message = stringResource(R.string.camera_permission_message),
                )
                BigButton(
                    text = stringResource(R.string.camera_allow_button),
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    leadingIcon = Icons.Filled.PhotoCamera,
                )
                BigOutlinedButton(
                    text = stringResource(R.string.type_manually_button),
                    onClick = { onEnterManually(null) },
                    leadingIcon = Icons.Filled.Edit,
                )
            }
        }
    }
}

@Composable
private fun CameraPreview(
    imageCapture: ImageCapture,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    AndroidView(factory = { previewView }, modifier = modifier)

    LaunchedEffect(Unit) {
        val provider = suspendCoroutine<ProcessCameraProvider> { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                Runnable { continuation.resume(future.get()) },
                ContextCompat.getMainExecutor(context),
            )
        }
        val preview = Preview.Builder().build().also { preview ->
            preview.setSurfaceProvider(previewView.surfaceProvider)
        }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )
        } catch (e: Exception) {
            // Camera unavailable (e.g. no back camera) — user can still type it in.
        }
    }
}

@Composable
private fun CaptureButton(
    imageCapture: ImageCapture,
    onCaptured: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(
        onClick = {
            val photoDir = File(context.filesDir, "photos").apply { mkdirs() }
            val file = File(photoDir, "label_${System.currentTimeMillis()}.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        onCaptured(file)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        // Keep the user on the camera screen to try again.
                    }
                },
            )
        },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(88.dp),
    ) {
        Icon(
            Icons.Filled.CameraAlt,
            contentDescription = stringResource(R.string.take_photo),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .padding(20.dp)
                .fillMaxSize(),
        )
    }
}

// ---------------------------------------------------------------------------
// Processing
// ---------------------------------------------------------------------------

@Composable
private fun ProcessingStep() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.scan_processing), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.scan_processing_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Review & confirm
// ---------------------------------------------------------------------------

@Composable
private fun ReviewStep(state: ScanUiState, viewModel: ScanViewModel) {
    val item = state.items.getOrNull(state.currentIndex) ?: return
    val draft = item.draft
    val confidence = draft.confidence
    val hasMore = state.currentIndex < state.items.lastIndex

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.scan_review_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.scan_review_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.items.size > 1) {
                    Text(
                        stringResource(
                            R.string.scan_progress_of,
                            state.currentIndex + 1,
                            state.items.size,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LinearProgressIndicator(
                        progress = {
                            (state.currentIndex + 1).toFloat() / state.items.size
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                    )
                }
            }
        }

        if (state.photoUri != null) {
            item(key = "photo") {
                SectionCard(title = stringResource(R.string.scanned_photo)) {
                    AsyncImage(
                        model = state.photoUri,
                        contentDescription = stringResource(R.string.scanned_photo),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                    )
                }
            }
        }

        if (item.rawNameText.isNotBlank() && item.rawNameText != draft.name) {
            item(key = "read-vs-guess") {
                SectionCard {
                    Text(
                        stringResource(R.string.scan_we_read, item.rawNameText),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.scan_did_you_mean, draft.name),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        item(key = "fields") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledField(
                    label = stringResource(R.string.field_medication_name),
                    value = draft.name,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(name = value) } },
                    confidence = confidence?.get(LabelField.NAME),
                )
                if (item.candidates.isNotEmpty() && item.nameScore < AUTO_NAME_SCORE) {
                    Text(
                        stringResource(R.string.scan_candidates_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    CandidateChips(
                        candidates = item.candidates,
                        rawNameText = item.rawNameText,
                        showRawChip = item.nameScore < REVIEW_NAME_SCORE,
                        onPick = viewModel::applySuggestion,
                    )
                }
                BigOutlinedButton(
                    text = if (state.checkingName) {
                        stringResource(R.string.checking_name)
                    } else {
                        stringResource(R.string.scan_check_name_again)
                    },
                    onClick = viewModel::checkName,
                    enabled = !state.checkingName,
                )
                state.nameSuggestions.forEach { suggestion ->
                    SuggestionButton(suggestion = suggestion, onClick = {
                        viewModel.applySuggestion(suggestion)
                    })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabeledField(
                        label = stringResource(R.string.field_dosage_amount),
                        value = draft.dosageValue,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(dosageValue = value) } },
                        confidence = confidence?.get(LabelField.DOSAGE),
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    LabeledField(
                        label = stringResource(R.string.field_dosage_unit),
                        value = draft.dosageUnit,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(dosageUnit = value) } },
                        confidence = confidence?.get(LabelField.DOSAGE),
                        modifier = Modifier.weight(1f),
                    )
                }

                LabeledField(
                    label = stringResource(R.string.field_instructions),
                    value = draft.instructions,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(instructions = value) } },
                    confidence = confidence?.get(LabelField.INSTRUCTIONS),
                    singleLine = false,
                )

                Text(
                    stringResource(R.string.scan_confirm_schedule),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                ScheduleEditor(draft = draft, onDraftChange = { newDraft ->
                    viewModel.updateDraft { newDraft }
                })
            }
        }

        item(key = "delete_photo") {
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.delete_photo_after_save),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.deletePhotoAfterSave,
                        onCheckedChange = viewModel::setDeletePhotoAfterSave,
                    )
                }
            }
        }

        item(key = "save") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(
                    text = when {
                        state.saving -> stringResource(R.string.saving)
                        hasMore -> stringResource(R.string.scan_save_and_continue)
                        else -> stringResource(R.string.save_medicine)
                    },
                    onClick = viewModel::save,
                    enabled = !state.saving,
                    leadingIcon = Icons.Filled.CameraEnhance,
                )
                if (hasMore) {
                    BigOutlinedButton(
                        text = stringResource(R.string.scan_skip_medicine),
                        onClick = viewModel::skipItem,
                        enabled = !state.saving,
                    )
                }
                Text(
                    stringResource(R.string.disclaimer_short),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One-tap dictionary candidates plus, when the match was weak, the raw OCR text. */
@Composable
private fun CandidateChips(
    candidates: List<ScoredDrugName>,
    rawNameText: String,
    showRawChip: Boolean,
    onPick: (ScoredDrugName) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showRawChip && rawNameText.isNotBlank()) {
            NameChip(
                text = stringResource(R.string.scan_use_read_name, rawNameText),
                onClick = {
                    onPick(
                        ScoredDrugName(name = rawNameText, type = DrugNameType.GENERIC, score = 0f),
                    )
                },
            )
        }
        candidates.forEach { candidate ->
            NameChip(
                text = if (candidate.genericName.isNullOrBlank()) {
                    candidate.name
                } else {
                    stringResource(R.string.scan_candidate_with_generic, candidate.name, candidate.genericName)
                },
                onClick = { onPick(candidate) },
            )
        }
    }
}

@Composable
private fun NameChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { contentDescription = text },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SuggestionButton(suggestion: ScoredDrugName, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            if (suggestion.genericName.isNullOrBlank()) {
                suggestion.name
            } else {
                "${suggestion.name} (${suggestion.genericName})"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

// ---------------------------------------------------------------------------
// Error fallback
// ---------------------------------------------------------------------------

@Composable
private fun ErrorStep(
    onRetry: () -> Unit,
    onEnterManually: (String?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyStateCard(
            icon = Icons.Filled.PhotoCamera,
            title = stringResource(R.string.scan_failed_title),
            message = stringResource(R.string.scan_failed_message),
        )
        BigButton(
            text = stringResource(R.string.scan_try_again),
            onClick = onRetry,
            leadingIcon = Icons.Filled.PhotoCamera,
        )
        BigOutlinedButton(
            text = stringResource(R.string.type_manually_button),
            onClick = { onEnterManually(null) },
            leadingIcon = Icons.Filled.Edit,
        )
    }
}
