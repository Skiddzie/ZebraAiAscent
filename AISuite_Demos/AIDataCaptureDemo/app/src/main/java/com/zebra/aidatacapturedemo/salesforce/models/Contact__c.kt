package com.zebra.aidatacapturedemo.salesforce.models

import com.google.gson.annotations.SerializedName

data class Contact(
    @SerializedName("Id") val id: String,
    @SerializedName("Name") val name: String?,
    @SerializedName("Email") val email: String?,
    @SerializedName("Phone") val phone: String?,
    @SerializedName("Address") val address: String?
)