package com.atheer.sdk.model

/**
 * ## AtheerTransaction
 * نموذج بيانات يمثل عملية دفع مكتملة أو في طور المعالجة.
 */
data class AtheerTransaction(
    val transactionId: String,
    val amount: Long,
    val currency: String,
    val receiverAccount: String,
    val transactionType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceId: String,
    val counter: Long,
    val authMethod: String = "BIOMETRIC_CRYPTOGRAM",
    val signature: String
)