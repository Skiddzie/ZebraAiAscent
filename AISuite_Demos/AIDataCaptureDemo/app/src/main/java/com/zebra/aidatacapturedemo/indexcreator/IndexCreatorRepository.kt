package com.zebra.aidatacapturedemo.indexcreator
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

// ─── Retrofit service interface ───────────────────────────────────────────────

interface IndexCreatorService {

    @Multipart
    @POST("v1/embeddings")
    suspend fun createEmbedding(
        @Header("x-api-key") apiKey: String,
        @Part image: MultipartBody.Part,
        @Query("model_name") modelName: String = IndexCreatorConfig.MODEL_NAME,
        @Query("model_version") modelVersion: String = IndexCreatorConfig.MODEL_VERSION
    ): Response<EmbeddingResponse>

    @POST("v1/index-jobs")
    suspend fun createJob(
        @Header("x-api-key") apiKey: String,
        @Body request: CreateJobRequest
    ): Response<CreateJobResponse>

    @POST("v1/index-jobs/{job_id}/embeddings")
    suspend fun uploadEmbeddings(
        @Header("x-api-key") apiKey: String,
        @Path("job_id") jobId: String,
        @Body request: UploadEmbeddingsRequest
    ): Response<UploadEmbeddingsResponse>

    @POST("v1/index-jobs/{job_id}/generation")
    suspend fun generateIndex(
        @Header("x-api-key") apiKey: String,
        @Path("job_id") jobId: String,
        @Body request: GenerateIndexRequest
    ): Response<GenerateIndexResponse>

    @GET("v1/index-jobs/{job_id}")
    suspend fun getJobStatus(
        @Header("x-api-key") apiKey: String,
        @Path("job_id") jobId: String
    ): Response<JobStatusResponse>
}

// ─── Repository ───────────────────────────────────────────────────────────────

sealed class WorkflowResult<out T> {
    data class Success<T>(val data: T) : WorkflowResult<T>()
    data class Failure(val message: String) : WorkflowResult<Nothing>()
}

class IndexCreatorRepository(private val context: Context) {

    private val service: IndexCreatorService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(IndexCreatorConfig.BASE_URL + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IndexCreatorService::class.java)
    }

    /**
     * Step 1 – Generate embedding for a single image URI.
     * Returns the [EmbeddingResponse] or a [WorkflowResult.Failure].
     */
    suspend fun generateEmbedding(
        uri: Uri,
        label: String
    ): WorkflowResult<Pair<String, EmbeddingResponse>> = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                ?: return@withContext WorkflowResult.Failure("Cannot read image: $uri")

            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val requestBody = bytes.toRequestBody(mimeType.toMediaType())
            val part = MultipartBody.Part.createFormData("image", "image.jpg", requestBody)

            val response = service.createEmbedding(
                apiKey = IndexCreatorConfig.API_KEY,
                image = part
            )
            if (response.isSuccessful && response.body() != null) {
                WorkflowResult.Success(label to response.body()!!)
            } else {
                WorkflowResult.Failure("Embedding failed [${response.code()}]: ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            WorkflowResult.Failure("Embedding exception: ${e.message}")
        }
    }

    /** Step 2 – Create a new index job. */
    suspend fun createJob(): WorkflowResult<CreateJobResponse> = withContext(Dispatchers.IO) {
        try {
            val response = service.createJob(
                apiKey = IndexCreatorConfig.API_KEY,
                request = CreateJobRequest()
            )
            if (response.isSuccessful && response.body() != null) {
                WorkflowResult.Success(response.body()!!)
            } else {
                WorkflowResult.Failure("Create job failed [${response.code()}]: ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            WorkflowResult.Failure("Create job exception: ${e.message}")
        }
    }

    /** Step 3 – Upload all embeddings to the job in one batch (max 1 000). */
    suspend fun uploadEmbeddings(
        jobId: String,
        embeddings: List<Pair<String, EmbeddingResponse>>
    ): WorkflowResult<UploadEmbeddingsResponse> = withContext(Dispatchers.IO) {
        try {
            val payload = UploadEmbeddingsRequest(
                embeddings = embeddings.map { (label, resp) ->
                    EmbeddingWithLabel(
                        label = label,
                        embedding_data = EmbeddingDataUpload(
                            vector_embedding = resp.embedding_data.vector_embedding,
                            embedding_checksum = resp.embedding_data.embedding_checksum
                        )
                    )
                }
            )
            val response = service.uploadEmbeddings(
                apiKey = IndexCreatorConfig.API_KEY,
                jobId = jobId,
                request = payload
            )
            if (response.isSuccessful && response.body() != null) {
                WorkflowResult.Success(response.body()!!)
            } else {
                WorkflowResult.Failure("Upload failed [${response.code()}]: ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            WorkflowResult.Failure("Upload exception: ${e.message}")
        }
    }

    /** Step 4 – Kick off async index generation. */
    suspend fun triggerGeneration(
        jobId: String,
        indexName: String
    ): WorkflowResult<GenerateIndexResponse> = withContext(Dispatchers.IO) {
        try {
            val response = service.generateIndex(
                apiKey = IndexCreatorConfig.API_KEY,
                jobId = jobId,
                request = GenerateIndexRequest(index_name = indexName)
            )
            if (response.isSuccessful && response.body() != null) {
                WorkflowResult.Success(response.body()!!)
            } else {
                WorkflowResult.Failure("Generation trigger failed [${response.code()}]: ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            WorkflowResult.Failure("Generation trigger exception: ${e.message}")
        }
    }

    /**
     * Step 5 – Poll until completed/failed/expired.
     * [onProgress] is called with each [JobStatusResponse] so the UI can update.
     */
    suspend fun pollUntilComplete(
        jobId: String,
        onProgress: (JobStatusResponse) -> Unit
    ): WorkflowResult<JobStatusResponse> = withContext(Dispatchers.IO) {
        val terminalStatuses = setOf("completed", "failed", "expired", "cancelled")
        while (true) {
            try {
                val response = service.getJobStatus(
                    apiKey = IndexCreatorConfig.API_KEY,
                    jobId = jobId
                )
                if (!response.isSuccessful || response.body() == null) {
                    return@withContext WorkflowResult.Failure(
                        "Status check failed [${response.code()}]: ${response.errorBody()?.string()}"
                    )
                }
                val status = response.body()!!
                withContext(Dispatchers.Main) { onProgress(status) }

                if (status.index_generation_status in terminalStatuses) {
                    return@withContext if (status.index_generation_status == "completed") {
                        WorkflowResult.Success(status)
                    } else {
                        WorkflowResult.Failure(
                            status.index_generation_error ?: status.error ?: "Job ended with status: ${status.status}"
                        )
                    }
                }
                delay(IndexCreatorConfig.POLL_INTERVAL_MS)
            } catch (e: Exception) {
                return@withContext WorkflowResult.Failure("Poll exception: ${e.message}")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        WorkflowResult.Failure("Unexpected poll exit")
    }

    /**
     * Step 6 – Download the index zip and save it to the app's external files dir.
     * Returns the [File] on success.
     */
    suspend fun downloadIndex(
        downloadUrl: String,
        indexName: String
    ): WorkflowResult<File> = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "indexes").also { it.mkdirs() }

            val outFile = File(dir, "$indexName.zip")
            URL(downloadUrl).openStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            WorkflowResult.Success(outFile)
        } catch (e: Exception) {
            WorkflowResult.Failure("Download exception: ${e.message}")
        }
    }
}
