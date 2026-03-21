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
 * @param agentWallet معرف محفظة الوكيل (خاص بالمحافظ اليمنية)
 * @param receiverMobile رقم هاتف المستلم (خاص بالمحافظ اليمنية)
 * @param externalRefId رقم المرجع الخاص بنظام المحفظة المضيفة (التتبع الخارجي)
 * @param purpose غرض العملية (مثال: دفع خدمات، تحويل)
 * @param providerName اسم مزود المحفظة (مثل: JAWALI, KURAIMI)
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
    val isSynced: Boolean = false,

    // حقول إضافية لدعم المحافظ اليمنية في قاعدة البيانات
    @ColumnInfo(name = "agent_wallet")
    val agentWallet: String? = null,    // معرف محفظة الوكيل المسؤول عن العملية

    @ColumnInfo(name = "receiver_mobile")
    val receiverMobile: String? = null, // رقم هاتف المستلم النهائي للأموال

    @ColumnInfo(name = "external_ref_id")
    val externalRefId: String? = null,  // المرجع الفريد في نظام المحفظة الخارجي

    @ColumnInfo(name = "purpose")
    val purpose: String? = null,        // الغرض من العملية للامتثال المالي

    @ColumnInfo(name = "provider_name")
    val providerName: String? = null    // اسم مزود المحفظة (مثل: JAWALI, KURAIMI)
)
