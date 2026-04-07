package com.atheer.sdk.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ## TransactionEntity
 * كيان قاعدة البيانات الذي يمثل سجل معاملة دفع.
 * تم تبسيط الحقول لتتوافق مع آلية الـ Cryptogram الجديدة.
 */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "transaction_id")
    val transactionId: String,

    @ColumnInfo(name = "amount")
    val amount: Long,

    @ColumnInfo(name = "currency")
    val currency: String,

    @ColumnInfo(name = "receiver_account")
    val receiverAccount: String,

    @ColumnInfo(name = "transaction_type")
    val transactionType: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "counter")
    val counter: Long,

    @ColumnInfo(name = "auth_method")
    val authMethod: String,

    @ColumnInfo(name = "signature")
    val signature: String,

    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = false
)