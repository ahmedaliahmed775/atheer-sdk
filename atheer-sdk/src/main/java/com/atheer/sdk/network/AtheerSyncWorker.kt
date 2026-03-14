package com.atheer.sdk.network

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.atheer.sdk.AtheerSdk

/**
 * العامل (Worker) المسؤول عن تنفيذ مزامنة المعاملات في الخلفية.
 * يتم استدعاؤه تلقائياً بواسطة نظام أندرويد عند توفر شبكة الإنترنت.
 */
class AtheerSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i("AtheerSyncWorker", "بدء محاولة المزامنة التلقائية في الخلفية...")

        return try {
            val sdk = AtheerSdk.getInstance()

            // جلب التوكن الذي تم تمريره عند جدولة المهمة
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
            // إعادة المحاولة لاحقاً إذا فشل الطلب بسبب السيرفر
            Result.retry()
        }
    }
}