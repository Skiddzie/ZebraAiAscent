package com.zebra.aidatacapturedemo.salesforce.models

data class CreateResponse(
    val id: String,
    val success: Boolean,
    val errors: List<Any> // Using Any because Salesforce errors can be objects or strings
)