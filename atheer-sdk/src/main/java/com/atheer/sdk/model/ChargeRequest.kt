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
 *     "currency": "SAR",
 *     "agentWallet": "...",
 *     "receiverMobile": "...",
 *     "externalRefId": "...",
 *     "purpose": "...",
 *     "providerName": "..."
 *   }
 * }
 * ```
 *
 * @param amount قيمة الشحن بالوحدة الأساسية (هللة/سنت)
 * @param currency رمز العملة وفق معيار ISO 4217 (مثال: SAR, USD)
 * @param merchantId معرف التاجر
 * @param atheerToken الرمز المميز الذي التقطه جهاز POS عبر NFC
 * @param description وصف العملية (اختياري)
 * @param agentWallet معرف محفظة الوكيل (خاص بالمحافظ اليمنية)
 * @param receiverMobile رقم هاتف المستلم (خاص بالمحافظ اليمنية)
 * @param externalRefId رقم المرجع الخاص بنظام المحفظة المضيفة (التتبع الخارجي)
 * @param purpose غرض العملية (مثال: دفع خدمات، تحويل)
 * @param providerName اسم مزود المحفظة (مثل: JAWALI, KURAIMI)
 */
data class ChargeRequest(
    val amount: Long,
    val currency: String,
    val merchantId: String,
    val atheerToken: String,
    val description: String? = null,
    
    // حقول إضافية لدعم المحافظ اليمنية
    val agentWallet: String? = null,    // معرف محفظة الوكيل المسؤول عن العملية
    val receiverMobile: String? = null, // رقم هاتف المستلم النهائي للأموال
    val externalRefId: String? = null,  // المرجع الفريد في نظام المحفظة الخارجي
    val purpose: String? = null,        // الغرض من العملية للامتثال المالي
    val providerName: String? = null    // اسم المحفظة المزودة للخدمة (JAWALI, KURAIMI, etc.)
)
