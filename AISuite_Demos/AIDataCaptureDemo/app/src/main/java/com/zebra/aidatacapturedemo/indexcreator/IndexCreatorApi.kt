package com.zebra.aidatacapturedemo.indexcreator
// ─── Config ──────────────────────────────────────────────────────────────────

object IndexCreatorConfig {
    // Replace with your key or load from BuildConfig / a local.properties entry
    const val API_KEY = "Y2nZfM7nDbMaFl2ebQSa0NFo7tWnm0Us"
    const val BASE_URL = "https://api.zebra.com/ai-suite/index-creator/image/"
    const val MODEL_NAME = "product-recognition"
    const val MODEL_VERSION = "3.4.1"
    /** ms to wait between embedding calls to stay under 5 req/sec */
    const val EMBEDDING_THROTTLE_MS = 210L
    /** ms between status polls */
    const val POLL_INTERVAL_MS = 7_000L
}

// ─── Request / Response models ────────────────────────────────────────────────

data class EmbeddingData(
    val vector_embedding: List<Double>,
    val embedding_checksum: String,
    val vector_dimension: Int,
    val model_name: String,
    val model_version: String,
    val generated_at: String
)

data class EmbeddingResponse(
    val embedding_id: String,
    val status: String,
    val processing_time_ms: Long,
    val embedding_data: EmbeddingData
)

data class CreateJobRequest(
    val model_name: String = IndexCreatorConfig.MODEL_NAME,
    val model_version: String = IndexCreatorConfig.MODEL_VERSION
)

data class CreateJobResponse(
    val job_id: String,
    val status: String,
    val model_name: String,
    val model_version: String,
    val expires_at: String,
    val processing_time_ms: Long
)

data class EmbeddingWithLabel(
    val label: String,
    val embedding_data: EmbeddingDataUpload
)

data class EmbeddingDataUpload(
    val vector_embedding: List<Double>,
    val embedding_checksum: String
)

data class UploadEmbeddingsRequest(
    val embeddings: List<EmbeddingWithLabel>
)

data class UploadEmbeddingsResponse(
    val job_id: String,
    val batch_id: String,
    val total_embeddings: Int,
    val successful_uploads: Int,
    val failed_uploads: Int,
    val status: String
)

data class GenerateIndexRequest(
    val index_name: String
)

data class GenerateIndexResponse(
    val job_id: String,
    val index_name: String,
    val status: String,
    val poll_uri: String,
    val message: String
)

data class JobStatusResponse(
    val job_id: String,
    val status: String,
    val embeddings_count: Int,
    val progress: Int?,
    val download_url: String?,
    val download_url_expires_at: String?,
    val expires_at: String?,
    val error: String?,
    val index_name: String?,
    val index_generation_status: String,
    val index_generation_progress: Int?,
    val index_generation_completed_at: String?,
    val index_generation_error: String?,
    val message: String?
)
