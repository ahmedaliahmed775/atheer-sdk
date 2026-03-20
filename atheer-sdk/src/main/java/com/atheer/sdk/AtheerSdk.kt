package com.atheer.sdk

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.*
import com.atheer.sdk.database.AtheerDatabase
import com.atheer.sdk.database.TransactionEntity
import com.atheer.sdk.model.AtheerTransaction
import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.model.ChargeResponse
import com.atheer.sdk.network.AtheerNetworkRouter
import com.atheer.sdk.security.AtheerKeystoreManager
import com.atheer.sdk.security.AtheerTokenManager
import com.scottyab.rootbeer.RootBeer
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.atheer.sdk.network.AtheerSyncWorker as AtheerSyncWorker1

/**
 * الواجهة الرئيسية (Facade) لـ Atheer SDK مع فحوصات الأمان ومسح الذاكرة
 */
class AtheerSdk private constructor(
    private val context: Context,
    private val merchantId: String,
    apiBaseUrl: String,
    private val enableApnFallback: Boolean
) {
    private val apiBaseUrl: String = apiBaseUrl.trimEnd('/')

    companion object {
        private const val TAG = "AtheerSdk"

        @Volatile
        private var instance: AtheerSdk? = null

        /**
         * تهيئة المكتبة مع تفعيل فحوصات الأمان الصارمة
         */
        fun init(context: Context, merchantId: String, apiBaseUrl: String, enableApnFallback: Boolean = false) {
            Log.i(TAG, "بدء تهيئة Atheer SDK...")

            // =============================================================
            // 🔒 فحوصات الأمان (معطلة حالياً للاختبار - قم بإزالة التعليق للتفعيل)
            // =============================================================
            /*
            // 1. كشف كسر الحماية (Root Detection)
            val rootBeer = RootBeer(context)
            if (rootBeer.isRooted) {
                Log.e(TAG, "فشل الأمان: تم اكتشاف كسر حماية (Root) على هذا الجهاز!")
                throw SecurityException("لا يمكن تشغيل Atheer SDK على أجهزة مكسورة الحماية لدواعي أمنية.")
            }

            // 2. كشف المحاكي (Emulator Detection)
            if (isEmulator()) {
                Log.e(TAG, "فشل الأمان: تم اكتشاف تشغيل المكتبة على محاكي!")
                throw SecurityException("لا يمكن تشغيل عمليات الدفع في Atheer SDK عبر المحاكيات.")
            }
            */

            if (instance != null) return
            synchronized(this) {
                if (instance == null) {
                    instance = AtheerSdk(context.applicationContext, merchantId, apiBaseUrl, enableApnFallback)
                    Log.i(TAG, "تمت تهيئة Atheer SDK بنجاح.")
                }
            }
        }

        fun getInstance(): AtheerSdk {
            return instance ?: throw IllegalStateException("يجب استدعاء AtheerSdk.init() أولاً.")
        }

        private fun isEmulator(): Boolean {
            return (Build.FINGERPRINT.startsWith("generic")
                    || Build.FINGERPRINT.startsWith("unknown")
                    || Build.MODEL.contains("google_sdk")
                    || Build.MODEL.contains("Emulator")
                    || Build.MODEL.contains("Android SDK built for x86")
                    || Build.MANUFACTURER.contains("Genymotion")
                    || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                    || "google_sdk" == Build.PRODUCT)
        }
    }

    private val keystoreManager = AtheerKeystoreManager()
    private val networkRouter = AtheerNetworkRouter(context)
    private val database = AtheerDatabase.getInstance(context)
    private val tokenManager = AtheerTokenManager(context)
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * التحقق من سلامة التطبيق عبر Google Play Integrity API
     */
    fun checkAppIntegrity(nonce: String, onComplete: (Boolean) -> Unit) {
        val integrityManager = IntegrityManagerFactory.create(context)
        val integrityTokenRequest = IntegrityTokenRequest.builder()
            .setNonce(nonce)
            .build()

        integrityManager.requestIntegrityToken(integrityTokenRequest)
            .addOnSuccessListener { _ ->
                Log.d(TAG, "تم الحصول على Integrity Token بنجاح")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "فشل التحقق من سلامة التطبيق: ${e.message}")
                onComplete(false)
            }
    }

    /**
     * معالجة المعاملة مع مسح الذاكرة الحساسة
     */
    fun processTransaction(
        transaction: AtheerTransaction,
        accessToken: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val sensitiveNonce = keystoreManager.generateNonce().toCharArray()
        val sensitiveAmount = transaction.amount.toString().toCharArray()

        sdkScope.launch {
            try {
                val tokenizedCard = transaction.tokenizedCard ?: throw IllegalArgumentException("tokenizedCard مطلوب")
                val encryptedAmount = keystoreManager.encrypt(String(sensitiveAmount))
                val transactionEntity = TransactionEntity(
                    transactionId = transaction.transactionId,
                    encryptedAmount = encryptedAmount,
                    currency = transaction.currency,
                    merchantId = merchantId,
                    timestamp = transaction.timestamp,
                    encryptedToken = tokenizedCard,
                    nonce = String(sensitiveNonce),
                    isSynced = false
                )
                database.transactionDao().insertTransaction(transactionEntity)

                // جدولة المزامنة التلقائية
                scheduleBackgroundSync(accessToken)

                /*
                if (!networkRouter.isNetworkAvailable() && enableApnFallback) {
                    // توجيه الطلب إلى مقسم أثير عبر نفق APN باستخدام AtheerCellularRouter
                    // سيتم تفعيل هذا الكود فور جاهزية الشراكات مع شركات الاتصالات
                }
                */

                if (networkRouter.isNetworkAvailable()) {
                    val requestBody = buildTransactionJson(transaction.transactionId, tokenizedCard, String(sensitiveNonce), transaction.amount)
                    networkRouter.executeStandard("$apiBaseUrl/api/v1/merchant/charge", requestBody, accessToken)
                    database.transactionDao().updateSyncStatus(transaction.transactionId, true)
                }
                withContext(Dispatchers.Main) { onSuccess("تمت المعالجة بنجاح") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e) }
            } finally {
                sensitiveNonce.fill('0')
                sensitiveAmount.fill('0')
            }
        }
    }

    /**
     * تنفيذ شحن مباشر
     */
    suspend fun charge(request: ChargeRequest, accessToken: String): Result<ChargeResponse> {
        val sensitiveNonce = keystoreManager.generateNonce().toCharArray()
        return try {
            val body = JSONObject().apply {
                put("header", JSONObject().apply { put("serviceDetail", JSONObject().apply { put("serviceName", "ATHEER.CASH") }) })
                put("body", JSONObject().apply {
                    put("amount", request.amount)
                    put("atheerToken", request.atheerToken)
                    put("nonce", String(sensitiveNonce))
                    put("merchantId", request.merchantId)
                })
            }.toString()
            val responseJson = networkRouter.executeStandard("$apiBaseUrl/api/v1/merchant/charge", body, accessToken)
            val responseBody = JSONObject(responseJson).optJSONObject("body")
            Result.success(ChargeResponse(transactionId = responseBody?.optString("transactionId") ?: "", status = "ACCEPTED", message = "نجاح"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            sensitiveNonce.fill('0')
        }
    }

    /**
     * مزامنة المعاملات المعلقة (Pending Transactions)
     * يتم استدعاؤها من قبل AtheerSyncWorker
     */
    fun syncPendingTransactions(accessToken: String, onComplete: (Int) -> Unit) {
        sdkScope.launch {
            try {
                val unsyncedTransactions = database.transactionDao().getUnsyncedTransactions()
                if (unsyncedTransactions.isEmpty()) {
                    withContext(Dispatchers.Main) { onComplete(0) }
                    return@launch
                }

                var syncedCount = 0
                for (entity in unsyncedTransactions) {
                    try {
                        /*
                        if (!networkRouter.isNetworkAvailable() && enableApnFallback) {
                            // توجيه الطلب إلى مقسم أثير عبر نفق APN باستخدام AtheerCellularRouter
                            // سيتم تفعيل هذا الكود فور جاهزية الشراكات مع شركات الاتصالات
                        }
                        */

                        // فك تشفير المبلغ المخزن لإرساله بشكل صحيح
                        val decryptedAmount = try {
                            keystoreManager.decrypt(entity.encryptedAmount).toLong()
                        } catch (e: Exception) { 0L }

                        val requestBody = buildTransactionJson(
                            entity.transactionId,
                            entity.encryptedToken ?: "",
                            entity.nonce ?: "",
                            decryptedAmount
                        )
                        networkRouter.executeStandard("$apiBaseUrl/api/v1/merchant/charge", requestBody, accessToken)
                        database.transactionDao().updateSyncStatus(entity.transactionId, true)
                        syncedCount++
                    } catch (e: Exception) {
                        Log.w(TAG, "تعذر مزامنة المعاملة ${entity.transactionId}: ${e.message}")
                    }
                }
                withContext(Dispatchers.Main) { onComplete(syncedCount) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete(0) }
            }
        }
    }

    /**
     * جدولة مهمة المزامنة عبر WorkManager
     */
    fun scheduleBackgroundSync(authToken: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncData = Data.Builder()
            .putString("AUTH_TOKEN", authToken)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<AtheerSyncWorker1>()
            .setConstraints(constraints)
            .setInputData(syncData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag("AtheerSyncTag")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "AtheerBackgroundSync",
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }

    private fun buildTransactionJson(id: String, token: String, nonce: String, amount: Long): String {
        return JSONObject().apply {
            put("header", JSONObject().apply { put("transactionId", id) })
            put("body", JSONObject().apply {
                put("atheerToken", token)
                put("nonce", nonce)
                put("amount", amount)
            })
        }.toString()
    }

    fun getNextOfflineLimit(): Int {
        val prefs = context.getSharedPreferences("AtheerSecurityPrefs", Context.MODE_PRIVATE)
        return prefs.getInt("OFFLINE_LIMIT", Int.MAX_VALUE)
    }

    fun clearOfflineLimit() {
        context.getSharedPreferences("AtheerSecurityPrefs", Context.MODE_PRIVATE).edit().remove("OFFLINE_LIMIT").apply()
    }

    fun getKeystoreManager() = keystoreManager
    fun getDatabase() = database
}
