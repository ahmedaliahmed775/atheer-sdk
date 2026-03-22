package com.atheer.sdk.network

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.atheer.sdk.AtheerSdk

/**
 * ## AtheerSyncWorker
 * العامل (Worker) المسؤول عن تنفيذ مزامنة المعاملات المعلقة في الخلفية.
 *
 * يعتمد هذا الكلاس على Android WorkManager لضمان تنفيذ عملية المزامنة حتى لو تم إغلاق التطبيق.
 * يتم جدولة المزامنة تلقائياً عند توفر شبكة الإنترنت لضمان إرسال كافة المعاملات التي تمت "دون اتصال" إلى السيرفر.
 *
 * ### آلية المزامنة:
 * 1. استخراج رمز المصادقة (Auth Token) من بيانات الإدخال.
 * 2. استدعاء دالة المزامنة في [AtheerSdk] لإرسال المعاملات غير المتزامنة من قاعدة البيانات المحلية.
 * 3. في حالة فشل الاتصال، يتم جدولة محاولة أخرى (Retry) بناءً على سياسة التراجع الأسي (Exponential Backoff).
 *
 * @param appContext سياق التطبيق.
 * @param workerParams معلمات العامل بما في ذلك البيانات المدخلة (Input Data).
 */
class AtheerSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    /**
     * تنفيذ مهمة المزامنة في سياق Coroutine.
     * @return [Result.success] عند نجاح المزامنة أو عدم وجود بيانات، أو [Result.retry] في حال وجود خطأ في الشبكة.
     */
    override suspend fun doWork(): Result {
        Log.i("AtheerSyncWorker", "بدء محاولة المزامنة التلقائية في الخلفية...")

        return try {
            val sdk = AtheerSdk.getInstance()

            // جلب رمز المصادقة الذي تم تمريره عند جدولة المهمة
            val currentToken = inputData.getString("AUTH_TOKEN") ?: ""

            if (currentToken.isNotEmpty()) {
                sdk.syncPendingTransactions(currentToken) { count ->
                    Log.i("AtheerSyncWorker", "تمت مزامنة $count معاملة بنجاح.")
                }
                Result.success()
            } else {
                Log.e("AtheerSyncWorker", "فشل المزامنة: لا يوجد رمز مصادقة (Token).")
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e("AtheerSyncWorker", "فشل المزامنة في الخلفية: ${e.message}")
            // إعادة المحاولة لاحقاً إذا فشل الطلب بسبب السيرفر أو الشبكة
            Result.retry()
        }
    }
}