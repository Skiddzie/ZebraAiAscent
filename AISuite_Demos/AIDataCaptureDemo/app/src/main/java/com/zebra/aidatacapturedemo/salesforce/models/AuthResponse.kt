package com.zebra.aidatacapturedemo.salesforce.models

import com.google.gson.annotations.SerializedName

data class AuthResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("instance_url") val instanceUrl: String,
    @SerializedName("id") val id: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("issued_at") val issuedAt: String
)