package com.zebra.aidatacapturedemo.salesforce.models

import com.google.gson.annotations.SerializedName

data class Product2(
    @SerializedName("Id") val id: String,
    @SerializedName("Name") val name: String?,
    @SerializedName("ProductCode") val productCode: String?,
    @SerializedName("Description") val description: String?,
    @SerializedName("Family") val family: String?,
    @SerializedName("IsActive") val isActive: Boolean?,
    @SerializedName("StockKeepingUnit") val stockKeepingUnit: String?,
    @SerializedName("QuantityUnitOfMeasure") val quantityUnitOfMeasure: String?
)