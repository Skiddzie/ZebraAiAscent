package com.zebra.aidatacapturedemo.indexcreator
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.zebra.aidatacapturedemo.indexcreator.IndexCreatorViewModel
import com.zebra.aidatacapturedemo.indexcreator.WorkflowStep

// ─────────────────────────────────────────────────────────────────────────────
//  Entry point – wire this into your NavHost or call directly from an Activity
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexCreatorScreen(
    onNavigateUp: () -> Unit = {},
    viewModel: IndexCreatorViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isRunning = state.step !in listOf(
        WorkflowStep.IDLE, WorkflowStep.DONE, WorkflowStep.ERROR
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Index Creator") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.step == WorkflowStep.DONE || state.step == WorkflowStep.ERROR) {
                        TextButton(onClick = { viewModel.reset() }) {
                            Text("Reset")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1 ── Image picker section
            ImagePickerSection(
                selectedImages = state.selectedImages,
                enabled = !isRunning && state.step != WorkflowStep.DONE,
                onImagesSelected = viewModel::onImagesSelected,
                onRemoveImage = viewModel::removeImage
            )

            // 2 ── Index name input
            IndexNameField(
                value = state.indexName,
                enabled = !isRunning && state.step != WorkflowStep.DONE,
                onValueChange = viewModel::onIndexNameChanged
            )

            // 3 ── Error banner
            AnimatedVisibility(visible = state.errorMessage != null) {
                state.errorMessage?.let { ErrorBanner(message = it) }
            }

            // 4 ── Start button / progress / result
            AnimatedContent(
                targetState = state.step,
                label = "workflow_content"
            ) { step ->
                when (step) {
                    WorkflowStep.IDLE -> {
                        Button(
                            onClick = { viewModel.startWorkflow() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.selectedImages.isNotEmpty() && state.indexName.isNotBlank()
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Build Index")
                        }
                    }

                    WorkflowStep.DONE -> {
                        SuccessCard(
                            filePath = state.savedFilePath ?: "",
                            indexName = state.indexName
                        )
                    }

                    WorkflowStep.ERROR -> {
                        // Error is already shown in the banner above; just show retry
                        Button(
                            onClick = { viewModel.startWorkflow() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Retry")
                        }
                    }

                    else -> {
                        // Running — show progress
                        WorkflowProgressCard(state = state)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Sub-components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ImagePickerSection(
    selectedImages: List<Uri>,
    enabled: Boolean,
    onImagesSelected: (List<Uri>) -> Unit,
    onRemoveImage: (Uri) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) onImagesSelected(uris)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Product Images",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        // Pick button
        OutlinedButton(
            onClick = { launcher.launch("image/*") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (selectedImages.isEmpty()) "Select Images from Gallery"
                else "Change Selection (${selectedImages.size} selected)"
            )
        }

        // Thumbnail strip
        if (selectedImages.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(selectedImages, key = { it.toString() }) { uri ->
                    ImageThumbnail(
                        uri = uri,
                        enabled = enabled,
                        onRemove = { onRemoveImage(uri) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageThumbnail(uri: Uri, enabled: Boolean, onRemove: () -> Unit) {
    Box(modifier = Modifier.size(80.dp)) {
        AsyncImage(
            model = uri,
            contentDescription = "Selected image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
        )
        if (enabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun IndexNameField(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Index Name") },
        placeholder = { Text("e.g. product_catalog_2025") },
        supportingText = { Text("Letters, numbers, and underscores only") },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun WorkflowProgressCard(state: IndexCreatorUiState) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.overallProgress,
        animationSpec = tween(durationMillis = 400),
        label = "progress_anim"
    )

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(
                    text = state.statusMessage,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                strokeCap = StrokeCap.Round
            )

            // Step chips
            WorkflowStepRow(currentStep = state.step)

            // Extra detail for embedding generation
            if (state.step == WorkflowStep.GENERATING_EMBEDDINGS && state.selectedImages.isNotEmpty()) {
                Text(
                    "Embeddings: ${state.embeddingsDone} / ${state.selectedImages.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Extra detail while polling
            if (state.step == WorkflowStep.POLLING && state.serverProgress > 0) {
                Text(
                    "Server progress: ${state.serverProgress}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WorkflowStepRow(currentStep: WorkflowStep) {
    val steps = listOf(
        WorkflowStep.GENERATING_EMBEDDINGS to "Embed",
        WorkflowStep.CREATING_JOB to "Job",
        WorkflowStep.UPLOADING_EMBEDDINGS to "Upload",
        WorkflowStep.GENERATING_INDEX to "Generate",
        WorkflowStep.POLLING to "Poll",
        WorkflowStep.DOWNLOADING to "Download"
    )
    val currentIndex = steps.indexOfFirst { it.first == currentStep }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        steps.forEachIndexed { index, (_, label) ->
            val done = index < currentIndex
            val active = index == currentIndex
            val color = when {
                done -> MaterialTheme.colorScheme.primary
                active -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SuccessCard(filePath: String, indexName: String) {
    val clipboard = LocalClipboardManager.current

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Index Ready",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Text(
                "\"$indexName\" has been built and saved to your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Divider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))

            Text(
                "Saved to:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = filePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(filePath)) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy path",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Text(
                "Import this .zip into the AI Data Capture SDK via Product Recognition settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
        }
    }
}
