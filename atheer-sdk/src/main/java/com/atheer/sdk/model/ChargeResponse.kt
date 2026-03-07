package com.atheer.sdk.model

/**
 * نموذج بيانات استجابة عملية الشحن
 *
 * @param transactionId معرف المعاملة المُنشأة
 * @param status حالة المعاملة (مثال: SUCCESS, PENDING, FAILED)
 * @param message رسالة إضافية من الخادم (اختياري)
 */
data class ChargeResponse(
    val transactionId: String,
    val status: String,
    val message: String? = null
)
