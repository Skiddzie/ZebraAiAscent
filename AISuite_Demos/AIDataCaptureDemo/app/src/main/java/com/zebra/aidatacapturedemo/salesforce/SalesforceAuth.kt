package com.zebra.aidatacapturedemo.salesforce

import android.util.Log
import com.google.gson.Gson
import com.zebra.aidatacapturedemo.salesforce.models.AuthResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*

class SalesforceAuth {
    companion object {
        private const val TAG = "SalesforceAuth"

        /**
         * Authenticate using Username-Password flow (for testing)
         * In production, use OAuth 2.0 Web Server flow
         */
        suspend fun authenticate(
            instanceUrl: String,
            clientId: String,
            clientSecret: String,
            username: String,
            password: String,
            securityToken: String
        ): AuthResponse? = withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val tokenUrl = "$instanceUrl/services/oauth2/token"

                Log.d(TAG, "Token URL = $tokenUrl")
                Log.d(TAG, "Username = $username")
                Log.d(TAG, "Security token length = ${securityToken.length}")

                val formBody = FormBody.Builder()
                    .add("grant_type", "password")
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("username", username)
                    .add("password", "$password$securityToken")
                    .build()

                val request = Request.Builder()
                    .url(tokenUrl)
                    .post(formBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    Log.d(TAG, "Authentication successful")
                    Gson().fromJson(responseBody, AuthResponse::class.java)
                } else {
                    Log.e(TAG, "Auth failed: ${response.code} - $responseBody")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auth error: ${e.message}")
                null
            }
        }
    }
}