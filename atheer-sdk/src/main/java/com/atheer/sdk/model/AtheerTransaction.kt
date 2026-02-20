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
 */
data class AtheerTransaction(
    val transactionId: String,
    val amount: Long,
    val currency: String,
    val merchantId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tokenizedCard: String? = null,
    val nonce: String? = null
)
