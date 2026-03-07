package com.atheer.sdk.model

/**
 * نموذج بيانات معاملة واحدة في سجل المعاملات
 *
 * @param transactionId معرف المعاملة الفريد
 * @param amount قيمة المعاملة
 * @param currency رمز العملة
 * @param timestamp وقت المعاملة بالميلي ثانية
 * @param status حالة المعاملة (مثال: SUCCESS, PENDING, FAILED)
 */
data class HistoryTransaction(
    val transactionId: String,
    val amount: Double,
    val currency: String,
    val timestamp: Long,
    val status: String
)

/**
 * نموذج بيانات استجابة سجل المعاملات
 *
 * @param transactions قائمة المعاملات
 * @param totalCount إجمالي عدد المعاملات
 */
data class HistoryResponse(
    val transactions: List<HistoryTransaction>,
    val totalCount: Int
)
