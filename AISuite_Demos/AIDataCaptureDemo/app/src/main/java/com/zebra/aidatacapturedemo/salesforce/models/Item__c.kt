package com.zebra.aidatacapturedemo.salesforce.models

import com.google.gson.annotations.SerializedName

data class Item__c(
    @SerializedName("Id") val id: String,
    @SerializedName("Name") val name: String?,
    @SerializedName("PBSI__description__c") val description: String?,
    @SerializedName("PBSI__Default_Location__c") val defaultLocation: String?,
    @SerializedName("PBSI__Item_Group__c") val itemGroup: String?
)