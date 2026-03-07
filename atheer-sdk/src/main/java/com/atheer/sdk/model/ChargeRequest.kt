package com.atheer.sdk.model

/**
 * نموذج بيانات طلب إجراء عملية شحن (Charge)
 *
 * يتوافق مع هيكل JSON المتداخل المتوقع من خادم Atheer:
 * ```json
 * {
 *   "header": {
 *     "serviceDetail": { "serviceName": "ATHEER.ECOMMCASHOUT" }
 *   },
 *   "body": {
 *     "amount": 500,
 *     "atheerToken": "...",
 *     "merchantId": "...",
 *     "currency": "SAR"
 *   }
 * }
 * ```
 *
 * @param amount قيمة الشحن بالوحدة الأساسية (هللة/سنت)
 * @param currency رمز العملة وفق معيار ISO 4217 (مثال: SAR, USD)
 * @param merchantId معرف التاجر
 * @param atheerToken الرمز المميز الذي التقطه جهاز POS عبر NFC
 * @param description وصف العملية (اختياري)
 */
data class ChargeRequest(
    val amount: Long,
    val currency: String,
    val merchantId: String,
    val atheerToken: String,
    val description: String? = null
)
