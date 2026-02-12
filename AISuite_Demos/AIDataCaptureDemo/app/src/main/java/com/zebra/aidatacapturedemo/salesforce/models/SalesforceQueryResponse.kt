package com.zebra.aidatacapturedemo.salesforce.models


import com.google.gson.annotations.SerializedName

data class SalesforceQueryResponse(
    @SerializedName("totalSize") val totalSize: Int,
    @SerializedName("done") val done: Boolean,
    @SerializedName("records") val records: List<Map<String, Any>>
)