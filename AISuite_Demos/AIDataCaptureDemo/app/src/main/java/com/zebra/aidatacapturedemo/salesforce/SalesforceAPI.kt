package com.zebra.aidatacapturedemo.salesforce

import android.util.Log
import com.google.gson.Gson
import com.zebra.aidatacapturedemo.salesforce.models.Product2
import com.zebra.aidatacapturedemo.salesforce.models.SalesforceQueryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException

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

    /**
     * Get product details by SKU
     */
    suspend fun getProductBySKU(sku: String): Product2? {
        val response = queryProduct(sku)
        return response?.records?.firstOrNull()
    }
}