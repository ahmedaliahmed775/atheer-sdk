package com.atheer.sdk.model

import com.google.gson.annotations.SerializedName

/**
 * ## EnrollResponse
 * نموذج استجابة تسجيل الجهاز — يدعم بنيتَي JSON المحتملتَين من الخادم.
 * يُستخدَم داخلياً فقط.
 */
internal data class EnrollResponse(
    val data: EnrollData? = null,
    @SerializedName("deviceSeed") val deviceSeed: String? = null
) {
    data class EnrollData(
        @SerializedName("deviceSeed") val deviceSeed: String?
    )

    /** استرجاع الـ seed من أي من البنيتَين المدعومتَين */
    fun getSeed(): String? = data?.deviceSeed?.takeIf { it.isNotBlank() }
        ?: deviceSeed?.takeIf { it.isNotBlank() }
}
