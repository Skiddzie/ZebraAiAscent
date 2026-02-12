package com.zebra.aidatacapturedemo.salesforce

import android.util.Log
import com.google.gson.Gson
import com.zebra.aidatacapturedemo.salesforce.models.Contact
import com.zebra.aidatacapturedemo.salesforce.models.CreateResponse
import com.zebra.aidatacapturedemo.salesforce.models.Product2
import com.zebra.aidatacapturedemo.salesforce.models.Item__c
import com.zebra.aidatacapturedemo.salesforce.models.SalesforceQueryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder


class SalesforceAPI(
    private val instanceUrl: String,
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
            val soqlQuery = """
                SELECT Id, Name, ProductCode, Description, Family, IsActive, 
                       StockKeepingUnit, QuantityUnitOfMeasure
                FROM Product2 
                WHERE ProductCode = '$sku' OR StockKeepingUnit = '$sku'
            """.trimIndent().replace("\n", " ")

            val encodedQuery = URLEncoder.encode(soqlQuery, "UTF-8")
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
     * Get Contact from Item by traversing Sales Order Lines → Sales Order → Contact
     * @param itemId The Id of the PBSI__PBSI_Item__c record
     * @return Contact record or null if not found
     */
    suspend fun getContactFromItem(itemId: String): List<Contact> = withContext(Dispatchers.IO) {
        try {
            // Query ALL Sales Order Lines for this Item, and traverse up to Contact
            val query = """SELECT Id, 
        PBSI__Sales_Order__r.PBSI__Contact__c
        FROM PBSI__PBSI_Sales_Order_Line__c
        WHERE PBSI__Item__c = '$itemId'
    """.trimIndent().replace("\n", " ")

            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$instanceUrl/services/data/v60.0/query?q=$encodedQuery"

            Log.d(TAG, "Querying Contacts from Item: $itemId")

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                Log.d(TAG, "Query Success: $responseBody")

                val queryResponse = gson.fromJson(responseBody, SalesforceQueryResponse::class.java)

                if (queryResponse?.records?.isNotEmpty() == true) {
                    val contacts = mutableListOf<Contact>()
                    val seenContactIds = mutableSetOf<String>() // Avoid duplicates

                    queryResponse.records.forEach { orderLineRecord ->
                        val salesOrderData = orderLineRecord as? Map<*, *>
                        val salesOrder = salesOrderData?.get("PBSI__Sales_Order__r") as? Map<*, *>
                        val contactId = salesOrder?.get("PBSI__Contact__c") as? String

                        if (contactId != null && !seenContactIds.contains(contactId)) {
                            seenContactIds.add(contactId)
                            val contact = getContactById(contactId)
                            if (contact != null) {
                                contacts.add(contact)
                                Log.d(TAG, "Found Contact: ${contact.name} (ID: $contactId)")
                            }
                        }
                    }

                    Log.d(TAG, "Total unique contacts found: ${contacts.size}")
                    return@withContext contacts
                } else {
                    Log.w(TAG, "No Sales Order Lines found for Item: $itemId")
                    return@withContext emptyList()
                }
            } else {
                Log.e(TAG, "Query Error: ${response.code} - $responseBody")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception querying Contacts from Item: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get full Contact record by ID
     */
    private suspend fun getContactById(contactId: String): Contact? = withContext(Dispatchers.IO) {
        try {
            val query = """
            SELECT Id, Name, Email, Phone
            FROM Contact
            WHERE Id = '$contactId'
        """.trimIndent().replace("\n", " ")

            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$instanceUrl/services/data/v60.0/query?q=$encodedQuery"

            Log.d(TAG, "Fetching Contact: $contactId")

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val queryResponse = gson.fromJson(responseBody, SalesforceQueryResponse::class.java)
                queryResponse?.records?.firstOrNull()?.let { record ->
                    val contactJson = gson.toJson(record)
                    gson.fromJson(contactJson, Contact::class.java)
                }
            } else {
                Log.e(TAG, "Contact Query Error: ${response.code} - $responseBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching Contact: ${e.message}", e)
            null
        }
    }
    /**
     * Get Item by Name
     */
    suspend fun getItemByName(name: String): Item__c? = withContext(Dispatchers.IO) {
        try {
            val query = """
            SELECT Id, Name, PBSI__Description__c, PBSI__Default_Location__c, PBSI__Item_Group__c
            FROM PBSI__PBSI_Item__c 
            WHERE Name = '$name'
        """.trimIndent().replace("\n", " ")

            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$instanceUrl/services/data/v60.0/query?q=$encodedQuery"

            Log.d(TAG, "Querying Item by Name: $name")

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                Log.d(TAG, "Item Query Success: $responseBody")
                val queryResponse = gson.fromJson(responseBody, SalesforceQueryResponse::class.java)
                queryResponse?.records?.firstOrNull()?.let { record ->
                    val itemJson = gson.toJson(record)
                    gson.fromJson(itemJson, Item__c::class.java)
                }
            } else {
                Log.e(TAG, "Item Query Error: ${response.code} - $responseBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception querying Item: ${e.message}", e)
            null
        }
    }

    /**
     * Create Item
     */
    suspend fun createItem(
        name: String,
        description: String?,
        defaultLocation: String?,
        itemGroup: String?
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$instanceUrl/services/data/v60.0/sobjects/PBSI__PBSI_Item__c"

            val fieldMap = mutableMapOf<String, Any>("Name" to name)

            description?.let { fieldMap["PBSI__Description__c"] = it }
            defaultLocation?.let { fieldMap["PBSI__Default_Location__c"] = it }
            itemGroup?.let { fieldMap["PBSI__Item_Group__c"] = it }

            val jsonBody = gson.toJson(fieldMap)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            Log.d(TAG, "Creating Item with body: $jsonBody")

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val createResponse = gson.fromJson(responseBody, CreateResponse::class.java)
                Log.d(TAG, "Success! Item created with ID: ${createResponse.id}")
                createResponse.id
            } else {
                Log.e(TAG, "Salesforce Error: ${response.code} - $responseBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating Item: ${e.message}", e)
            null
        }
    }

    /**
     * Get product details by SKU
     */
//    suspend fun getProductBySKU(sku: String): Product2? {
//        val response = queryProduct(sku)
//        return response?.records?.firstOrNull()
//    }
}