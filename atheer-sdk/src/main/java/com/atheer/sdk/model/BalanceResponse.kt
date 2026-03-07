package com.atheer.sdk.model

/**
 * نموذج بيانات استجابة استعلام الرصيد
 *
 * @param balance قيمة الرصيد الحالي
 * @param currency رمز العملة وفق معيار ISO 4217 (مثال: SAR, USD)
 * @param accountId معرف الحساب (اختياري)
 */
data class BalanceResponse(
    val balance: Double,
    val currency: String,
    val accountId: String? = null
)
