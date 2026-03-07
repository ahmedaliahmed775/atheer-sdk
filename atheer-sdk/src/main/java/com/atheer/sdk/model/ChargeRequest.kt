package com.atheer.sdk.model

/**
 * نموذج بيانات طلب إجراء عملية شحن
 *
 * @param amount قيمة الشحن بالوحدة الأساسية (هللة/سنت)
 * @param currency رمز العملة وفق معيار ISO 4217 (مثال: SAR, USD)
 * @param merchantId معرف التاجر
 * @param description وصف العملية (اختياري)
 */
data class ChargeRequest(
    val amount: Long,
    val currency: String,
    val merchantId: String,
    val description: String? = null
)
