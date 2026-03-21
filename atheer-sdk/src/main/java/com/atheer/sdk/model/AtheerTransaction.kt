package com.atheer.sdk.model

/**
 * نموذج البيانات الذي يمثل عملية دفع واحدة في نظام Atheer SDK
 *
 * @param transactionId معرف فريد للمعاملة
 * @param amount قيمة المعاملة بالوحدة الأساسية (هللة/سنت)
 * @param currency رمز العملة وفق معيار ISO 4217 (مثال: SAR, USD)
 * @param merchantId معرف التاجر
 * @param timestamp وقت إنشاء المعاملة بالميلي ثانية
 * @param tokenizedCard رقم البطاقة المُرمَّز بعد تطبيق آلية Tokenization
 * @param nonce رقم عشوائي لمنع هجمات إعادة التشغيل (Replay Attacks)
 * @param agentWallet معرف محفظة الوكيل (خاص بالمحافظ اليمنية)
 * @param receiverMobile رقم هاتف المستلم (خاص بالمحافظ اليمنية)
 * @param externalRefId رقم المرجع الخاص بنظام المحفظة المضيفة (التتبع الخارجي)
 * @param purpose غرض العملية (مثال: دفع خدمات، تحويل)
 * @param providerName اسم مزود المحفظة (مثل: JAWALI, KURAIMI)
 */
data class AtheerTransaction(
    val transactionId: String,
    val amount: Long,
    val currency: String,
    val merchantId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tokenizedCard: String? = null,
    val nonce: String? = null,
    
    // حقول إضافية لدعم المحافظ اليمنية
    val agentWallet: String? = null,    // معرف محفظة الوكيل المسؤول عن العملية
    val receiverMobile: String? = null, // رقم هاتف المستلم النهائي للأموال
    val externalRefId: String? = null,  // المرجع الفريد في نظام المحفظة الخارجي
    val purpose: String? = null,        // الغرض من العملية للامتثال المالي
    val providerName: String? = null    // اسم مزود المحفظة (JAWALI, KURAIMI)
)
