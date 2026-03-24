package com.atheer.sdk.network

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * ## AtheerSyncWorker
 * **ملاحظة:** تم إيقاف هذا العامل (Worker) في الإصدار v1.2.0.
 *
 * سابقاً، كان هذا الكلاس مسؤولاً عن مزامنة المعاملات المعلقة.
 * مع الانتقال إلى نظام **الاتصال الفوري الإلزامي (Online-Only)**، لم يعد يتم حفظ المعاملات محلياً،
 * وبالتالي لم تعد هناك حاجة للمزامنة الخلفية للمعاملات.
 */
class AtheerSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("AtheerSyncWorker", "تم استدعاء العامل ولكن المزامنة الخلفية معطلة في هذا الإصدار (Online-Only).")
        return Result.success()
    }
}
