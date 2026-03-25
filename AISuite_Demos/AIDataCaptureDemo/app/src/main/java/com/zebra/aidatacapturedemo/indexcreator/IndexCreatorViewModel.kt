package com.zebra.aidatacapturedemo.indexcreator

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

// ─── UI State ─────────────────────────────────────────────────────────────────

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
    val overallProgress: Float = 0f,
    val statusMessage: String = "",
    val embeddingsDone: Int = 0,
    val serverProgress: Int = 0,
    val savedFilePath: String? = null,
    val errorMessage: String? = null
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

class IndexCreatorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = IndexCreatorRepository(application)

    private val _uiState = MutableStateFlow(IndexCreatorUiState())
    val uiState: StateFlow<IndexCreatorUiState> = _uiState

    // ── Public actions ────────────────────────────────────────────────────────

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

    /**
     * Kicks off the full 6-step workflow.
     * [onIndexReady] is called with the path to the extracted .db file once the
     * index has been downloaded and unzipped, so the caller can copy it into the
     * SDK's cache directory and call applyProductDB().
     */
    fun startWorkflow(onIndexReady: ((String) -> Unit)? = null) {
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
            runWorkflow(images, indexName, onIndexReady)
        }
    }

    // ── Workflow steps ────────────────────────────────────────────────────────

    private suspend fun runWorkflow(
        images: List<Uri>,
        indexName: String,
        onIndexReady: ((String) -> Unit)?
    ) {
        // Step 1 — Generate one embedding per image
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

            when (val result = repository.generateEmbedding(uri, label)) {
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

            // Stay under the 5 req/sec rate limit
            if (index < images.size - 1) {
                delay(IndexCreatorConfig.EMBEDDING_THROTTLE_MS)
            }
        }

        // Step 2 — Create an index job
        _uiState.update {
            it.copy(
                step = WorkflowStep.CREATING_JOB,
                overallProgress = 0.42f,
                statusMessage = "Creating index job…"
            )
        }

        val jobResult = repository.createJob()
        if (jobResult is WorkflowResult.Failure) {
            _uiState.update { it.copy(step = WorkflowStep.ERROR, errorMessage = jobResult.message) }
            return
        }
        val jobId = (jobResult as WorkflowResult.Success).data.job_id

        // Step 3 — Upload all embeddings to the job
        _uiState.update {
            it.copy(
                step = WorkflowStep.UPLOADING_EMBEDDINGS,
                overallProgress = 0.50f,
                statusMessage = "Uploading ${embeddings.size} embeddings…"
            )
        }

        val uploadResult = repository.uploadEmbeddings(jobId, embeddings)
        if (uploadResult is WorkflowResult.Failure) {
            _uiState.update { it.copy(step = WorkflowStep.ERROR, errorMessage = uploadResult.message) }
            return
        }

        // Step 4 — Trigger async index generation
        _uiState.update {
            it.copy(
                step = WorkflowStep.GENERATING_INDEX,
                overallProgress = 0.60f,
                statusMessage = "Starting index generation…"
            )
        }

        val genResult = repository.triggerGeneration(jobId, indexName)
        if (genResult is WorkflowResult.Failure) {
            _uiState.update { it.copy(step = WorkflowStep.ERROR, errorMessage = genResult.message) }
            return
        }

        // Step 5 — Poll until the server finishes
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
                    statusMessage = status.message ?: "Processing… $serverPct%"
                )
            }
        }

        if (pollResult is WorkflowResult.Failure) {
            _uiState.update { it.copy(step = WorkflowStep.ERROR, errorMessage = pollResult.message) }
            return
        }

        val downloadUrl = (pollResult as WorkflowResult.Success).data.download_url
        if (downloadUrl.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    step = WorkflowStep.ERROR,
                    errorMessage = "Job completed but no download URL was returned."
                )
            }
            return
        }

        // Step 6 — Download the index zip
        _uiState.update {
            it.copy(
                step = WorkflowStep.DOWNLOADING,
                overallProgress = 0.88f,
                statusMessage = "Downloading index package…"
            )
        }

        val downloadResult = repository.downloadIndex(downloadUrl, indexName)
        if (downloadResult is WorkflowResult.Failure) {
            _uiState.update { it.copy(step = WorkflowStep.ERROR, errorMessage = downloadResult.message) }
            return
        }

        val zipFile = (downloadResult as WorkflowResult.Success).data

        // Step 7 — Unzip and hand the .db path back to the caller
        _uiState.update {
            it.copy(
                overallProgress = 0.95f,
                statusMessage = "Applying index…"
            )
        }

        val dbPath = unzipAndGetDbPath(zipFile)
        if (dbPath != null) {
            onIndexReady?.invoke(dbPath)
        }

        _uiState.update {
            it.copy(
                step = WorkflowStep.DONE,
                overallProgress = 1f,
                statusMessage = "Index ready!",
                savedFilePath = zipFile.absolutePath
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts all entries from [zipFile] into the same directory,
     * then returns the absolute path of the first .db file found.
     */
    private suspend fun unzipAndGetDbPath(zipFile: File): String? =
        withContext(Dispatchers.IO) {
            try {
                val outDir = zipFile.parentFile!!
                ZipInputStream(zipFile.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(outDir, entry.name)
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                outDir.listFiles()?.firstOrNull { it.extension == "db" }?.absolutePath
            } catch (e: Exception) {
                null
            }
        }
}