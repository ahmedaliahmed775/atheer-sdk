package com.atheer.sdk.model

/**
 * ## ChargeRequest
 * نموذج بيانات طلب إجراء عملية خصم (Charge).
 * تم تبسيط هذا النموذج لدعم آلية المصادقة الحيوية والتوقيع الرقمي.
 */
data class ChargeRequest(
    val amount: Long,
    val currency: String,
    val receiverAccount: String,
    val transactionType: String,
    val atheerToken: String,
    val authMethod: String = "BIOMETRIC_CRYPTOGRAM",
    val signature: String
)