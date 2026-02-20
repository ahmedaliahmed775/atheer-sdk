package com.atheer.sdk.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * واجهة وصول البيانات (DAO) للمعاملات المالية
 *
 * تُعرِّف هذه الواجهة جميع عمليات قاعدة البيانات المتعلقة بجدول المعاملات.
 * تستخدم Kotlin Coroutines لضمان عدم حجب الخيط الرئيسي أثناء العمليات.
 */
@Dao
interface TransactionDao {

    /**
     * إدراج معاملة جديدة في قاعدة البيانات
     * في حال وجود تعارض يتم استبدال السجل القديم
     *
     * @param transaction كيان المعاملة المراد تخزينه
     * @return المعرف الفريد للسجل المُدرَج
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    /**
     * استرجاع جميع المعاملات غير المتزامنة مع الخادم
     * تُستخدَم هذه الدالة لإرسال المعاملات المعلقة عند استعادة الاتصال بالشبكة
     *
     * @return قائمة بالمعاملات غير المتزامنة
     */
    @Query("SELECT * FROM transactions WHERE is_synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedTransactions(): List<TransactionEntity>

    /**
     * استرجاع جميع المعاملات كـ Flow للمراقبة الفعالة للتغييرات
     * تُستخدَم مع ViewModel لتحديث واجهة المستخدم تلقائياً
     *
     * @return تدفق بيانات يحتوي على قائمة المعاملات
     */
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    /**
     * تحديث حالة المزامنة لمعاملة محددة
     * تُستدعى بعد إرسال المعاملة بنجاح إلى الخادم
     *
     * @param transactionId معرف المعاملة
     * @param isSynced حالة المزامنة الجديدة
     */
    @Query("UPDATE transactions SET is_synced = :isSynced WHERE transaction_id = :transactionId")
    suspend fun updateSyncStatus(transactionId: String, isSynced: Boolean)

    /**
     * حذف جميع المعاملات المتزامنة مع الخادم لتوفير مساحة التخزين
     * يُنصَح باستدعاء هذه الدالة دورياً في عمليات الصيانة
     */
    @Query("DELETE FROM transactions WHERE is_synced = 1")
    suspend fun deleteSyncedTransactions()

    /**
     * البحث عن معاملة بواسطة معرفها الفريد
     *
     * @param transactionId معرف المعاملة
     * @return كيان المعاملة أو null إن لم توجد
     */
    @Query("SELECT * FROM transactions WHERE transaction_id = :transactionId LIMIT 1")
    suspend fun getTransactionById(transactionId: String): TransactionEntity?

    /**
     * حذف معاملة بواسطة معرفها الفريد
     *
     * @param transactionId معرف المعاملة المراد حذفها
     */
    @Query("DELETE FROM transactions WHERE transaction_id = :transactionId")
    suspend fun deleteTransactionById(transactionId: String)
}
