package com.atheer.sdk.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * كيان قاعدة البيانات الذي يمثل سجل معاملة مشفرة في قاعدة البيانات المحلية
 *
 * يُخزَّن هذا الكيان في جدول "transactions" داخل قاعدة بيانات Room.
 * جميع الحقول الحساسة (مثل رقم البطاقة) مشفرة قبل التخزين باستخدام AtheerKeystoreManager.
 *
 * @param id المفتاح الأساسي للسجل (يُولَّد تلقائياً)
 * @param transactionId معرف المعاملة الفريد
 * @param encryptedAmount القيمة المشفرة للمعاملة
 * @param currency رمز العملة
 * @param merchantId معرف التاجر
 * @param timestamp وقت إجراء المعاملة
 * @param encryptedToken الرمز المميز المشفر للبطاقة
 * @param nonce الرقم العشوائي لمنع هجمات إعادة التشغيل
 * @param isSynced هل تمت مزامنة هذه المعاملة مع الخادم
 */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String,

    @ColumnInfo(name = "encrypted_amount")
    val encryptedAmount: String,

    @ColumnInfo(name = "currency")
    val currency: String,

    @ColumnInfo(name = "merchant_id")
    val merchantId: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "encrypted_token")
    val encryptedToken: String?,

    @ColumnInfo(name = "nonce")
    val nonce: String?,

    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = false
)
