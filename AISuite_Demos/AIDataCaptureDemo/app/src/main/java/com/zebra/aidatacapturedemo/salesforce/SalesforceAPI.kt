package com.zebra.aidatacapturedemo.salesforce

import android.util.Log
import com.google.gson.Gson
import com.zebra.aidatacapturedemo.salesforce.models.CreateResponse
import com.zebra.aidatacapturedemo.salesforce.models.Product2
import com.zebra.aidatacapturedemo.salesforce.models.SalesforceQueryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.jvm.java
import kotlin.reflect.KClass


class SalesforceAPI(
    private val instanceUrl: String,  // e.g., "https://yourinstance.salesforce.com"
    private val accessToken: String
) {
    private val client = OkHttpClient()
    private val gson = Gson()

    companion object {
        private const val TAG = "SalesforceAPI"
    }

    /**
     * Query Salesforce using SOQL
     * Example: queryProduct("8008")
     */
    suspend fun queryProduct(sku: String): SalesforceQueryResponse? = withContext(Dispatchers.IO) {
        try {
            // SOQL Query - adjust fields based on your Salesforce schema
            val soqlQuery = """
                SELECT Id, Name, ProductCode, Description, Family, IsActive, 
                       StockKeepingUnit, QuantityUnitOfMeasure
                FROM Product2 
                WHERE ProductCode = '$sku' OR StockKeepingUnit = '$sku'
            """.trimIndent().replace("\n", " ")

            val encodedQuery = java.net.URLEncoder.encode(soqlQuery, "UTF-8")
            val url = "$instanceUrl/services/data/v60.0/query/?q=$encodedQuery"

            Log.d(TAG, "Querying Salesforce: $url")

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                Log.d(TAG, "Success: $responseBody")
                gson.fromJson(responseBody, SalesforceQueryResponse::class.java)
            } else {
                Log.e(TAG, "Error: ${response.code} - $responseBody")
                null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Salesforce: ${e.message}")
            null
        }
    }

    suspend fun createProduct(
        productName: String,
        productCode: String,
        description: String? = null,
        isActive: Boolean = true
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$instanceUrl/services/data/v60.0/sobjects/Product2"

            val fieldMap = mutableMapOf<String, Any>(
                "Name" to productName,
                "ProductCode" to productCode,
                "StockKeepingUnit" to productCode,
                "IsActive" to isActive,
                "ASCENTERP__Default_Unit_Of_Measure__c" to "a0aRL00000JgMUDYA3"
            )

            if (description != null) {
                fieldMap["Description"] = description
            }

            val jsonBody = gson.toJson(fieldMap)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val createResponse = gson.fromJson(responseBody, CreateResponse::class.java)
                Log.d(TAG, "Success! Product created with ID: ${createResponse.id}")
                createResponse.id
            } else {
                Log.e(TAG, "Salesforce Error: ${response.code} - $responseBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Caught error: ${e.message}")
            null
        }
    }

    /**
     * Get product details by SKU
     */
    suspend fun getProductBySKU(sku: String): Product2? {
        val response = queryProduct(sku)
        return response?.records?.firstOrNull()
    }
}