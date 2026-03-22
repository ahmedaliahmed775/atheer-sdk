package com.atheer.sdk.model

/**
 * نموذج بيانات طلب إجراء عملية شحن (Charge)
 *
 * تم تحديث الهيكل ليكون مسطحاً (Flattened) بناءً على متطلبات الـ Backend الجديدة.
 *
 * @param amount قيمة الشحن بالوحدة الأساسية (هللة/سنت)
 * @param currency رمز العملة وفق معيار ISO 4217 (مثال: SAR, USD)
 * @param merchantId معرف التاجر
 * @param atheerToken الرمز المميز الذي التقطه جهاز POS عبر NFC
 * @param customerMobile رقم هاتف العميل (إلزامي)
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
    val customerMobile: String,
    val description: String? = null,
    
    // حقول إضافية لدعم المحافظ اليمنية
    val agentWallet: String? = null,
    val receiverMobile: String? = null,
    val externalRefId: String? = null,
    val purpose: String? = null,
    val providerName: String? = null
)
