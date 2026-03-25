package com.zebra.aidatacapturedemo.indexcreator

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─── UI state ─────────────────────────────────────────────────────────────────

enum class WorkflowStep {
    IDLE,
    GENERATING_EMBEDDINGS,
    CREATING_JOB,
    UPLOADING_EMBEDDINGS,
    GENERATING_INDEX,
    POLLING,
    DOWNLOADING,
    DONE,
    ERROR
}

data class IndexCreatorUiState(
    val selectedImages: List<Uri> = emptyList(),
    val indexName: String = "",
    val step: WorkflowStep = WorkflowStep.IDLE,
    /** 0.0 – 1.0 overall progress across all steps */
    val overallProgress: Float = 0f,
    /** Human-readable status message */
    val statusMessage: String = "",
    /** Number of embeddings generated so far */
    val embeddingsDone: Int = 0,
    /** Server-side index generation progress (0–100) */
    val serverProgress: Int = 0,
    /** Absolute path to the saved index zip on success */
    val savedFilePath: String? = null,
    val errorMessage: String? = null
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

class IndexCreatorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = IndexCreatorRepository(application)

    private val _uiState = MutableStateFlow(IndexCreatorUiState())
    val uiState: StateFlow<IndexCreatorUiState> = _uiState

    fun onImagesSelected(uris: List<Uri>) {
        _uiState.update { it.copy(selectedImages = uris, errorMessage = null) }
    }

    fun onIndexNameChanged(name: String) {
        _uiState.update { it.copy(indexName = name) }
    }

    fun removeImage(uri: Uri) {
        _uiState.update { it.copy(selectedImages = it.selectedImages - uri) }
    }

    fun reset() {
        _uiState.value = IndexCreatorUiState()
    }

    fun startWorkflow() {
        val images = _uiState.value.selectedImages
        val indexName = _uiState.value.indexName.trim()

        if (images.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please select at least one image.") }
            return
        }
        if (indexName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter an index name.") }
            return
        }
        if (!indexName.matches(Regex("[A-Za-z0-9_]+"))) {
            _uiState.update { it.copy(errorMessage = "Index name may only contain letters, numbers, and underscores.") }
            return
        }

        viewModelScope.launch {
            runWorkflow(images, indexName)
        }
    }

    private suspend fun runWorkflow(images: List<Uri>, indexName: String) {
        // ── Step 1: Generate embeddings ────────────────────────────────────────
        _uiState.update {
            it.copy(
                step = WorkflowStep.GENERATING_EMBEDDINGS,
                overallProgress = 0.05f,
                statusMessage = "Generating embeddings (0 / ${images.size})…",
                embeddingsDone = 0,
                errorMessage = null
            )
        }

        val embeddings = mutableListOf<Pair<String, EmbeddingResponse>>()
        images.forEachIndexed { index, uri ->
            val label = "product_${index + 1}"
            val result = repository.generateEmbedding(uri, label)
            when (result) {
                is WorkflowResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            step = WorkflowStep.ERROR,
                            errorMessage = "Embedding failed for image ${index + 1}: ${result.message}"
                        )
                    }
                    return
                }
                is WorkflowResult.Success -> embeddings.add(result.data)
            }

            val done = index + 1
            _uiState.update {
                it.copy(
                    embeddingsDone = done,
                    overallProgress = 0.05f + (done.toFloat() / images.size) * 0.35f,
                    statusMessage = "Generating embeddings ($done / ${images.size})…"
                )
            }

            // Throttle to stay under 5 req/sec
            if (index < images.size - 1) {
                delay(IndexCreatorConfig.EMBEDDING_THROTTLE_MS)
            }
        }

        // ── Step 2: Create job ─────────────────────────────────────────────────
        _uiState.update {
            it.copy(
                step = WorkflowStep.CREATING_JOB,
                overallProgress = 0.42f,
                statusMessage = "Creating index job…"
            )
        }

        val jobResult = repository.createJob()
        if (jobResult is WorkflowResult.Failure) {
            _uiState.update {
                it.copy(step = WorkflowStep.ERROR, errorMessage = jobResult.message)
            }
            return
        }
        val jobId = (jobResult as WorkflowResult.Success).data.job_id

        // ── Step 3: Upload embeddings ──────────────────────────────────────────
        _uiState.update {
            it.copy(
                step = WorkflowStep.UPLOADING_EMBEDDINGS,
                overallProgress = 0.50f,
                statusMessage = "Uploading ${embeddings.size} embeddings…"
            )
        }

        val uploadResult = repository.uploadEmbeddings(jobId, embeddings)
        if (uploadResult is WorkflowResult.Failure) {
            _uiState.update {
                it.copy(step = WorkflowStep.ERROR, errorMessage = uploadResult.message)
            }
            return
        }

        // ── Step 4: Trigger generation ─────────────────────────────────────────
        _uiState.update {
            it.copy(
                step = WorkflowStep.GENERATING_INDEX,
                overallProgress = 0.60f,
                statusMessage = "Starting index generation…"
            )
        }

        val genResult = repository.triggerGeneration(jobId, indexName)
        if (genResult is WorkflowResult.Failure) {
            _uiState.update {
                it.copy(step = WorkflowStep.ERROR, errorMessage = genResult.message)
            }
            return
        }

        // ── Step 5: Poll ───────────────────────────────────────────────────────
        _uiState.update {
            it.copy(
                step = WorkflowStep.POLLING,
                overallProgress = 0.65f,
                statusMessage = "Processing index… (this can take up to 60 seconds)"
            )
        }

        val pollResult = repository.pollUntilComplete(jobId) { status ->
            val serverPct = status.index_generation_progress ?: 0
            _uiState.update {
                it.copy(
                    serverProgress = serverPct,
                    overallProgress = 0.65f + (serverPct / 100f) * 0.20f,
                    statusMessage = status.message
                        ?: "Processing… $serverPct%"
                )
            }
        }

        if (pollResult is WorkflowResult.Failure) {
            _uiState.update {
                it.copy(step = WorkflowStep.ERROR, errorMessage = pollResult.message)
            }
            return
        }

        val downloadUrl = (pollResult as WorkflowResult.Success).data.download_url
        if (downloadUrl.isNullOrBlank()) {
            _uiState.update {
                it.copy(step = WorkflowStep.ERROR, errorMessage = "Job completed but no download URL was returned.")
            }
            return
        }

        // ── Step 6: Download ───────────────────────────────────────────────────
        _uiState.update {
            it.copy(
                step = WorkflowStep.DOWNLOADING,
                overallProgress = 0.88f,
                statusMessage = "Downloading index package…"
            )
        }

        val downloadResult = repository.downloadIndex(downloadUrl, indexName)
        if (downloadResult is WorkflowResult.Failure) {
            _uiState.update {
                it.copy(step = WorkflowStep.ERROR, errorMessage = downloadResult.message)
            }
            return
        }

        val file = (downloadResult as WorkflowResult.Success).data
        _uiState.update {
            it.copy(
                step = WorkflowStep.DONE,
                overallProgress = 1f,
                statusMessage = "Index saved successfully!",
                savedFilePath = file.absolutePath
            )
        }
    }
}
