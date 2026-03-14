package com.atheer.sdk.model

import com.google.gson.annotations.SerializedName

data class TokensResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: TokensData?
)

data class TokensData(
    @SerializedName("tokens") val tokens: List<String>
)
