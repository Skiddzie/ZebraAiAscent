package com.zebra.aidatacapturedemo.salesforce.models

import com.google.gson.annotations.SerializedName

data class Contact__c(
    @SerializedName("Id") val id: String,
    @SerializedName("Name") val name: String?,
    @SerializedName("PBSI__Email__c") val email: String?,
    @SerializedName("PBSI__Phone__c") val phone: String?,
    @SerializedName("PBSI__Address__c") val address: String?
)