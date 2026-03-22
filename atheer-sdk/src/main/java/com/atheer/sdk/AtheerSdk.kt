package com.atheer.sdk

import android.content.Context
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
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.atheer.sdk.network.AtheerSyncWorker as AtheerSyncWorker1

/**
 * الواجهة الرئيسية (Facade) لـ Atheer SDK - نسخة مطورة متوافقة مع Atheer Switch V1
 */
class AtheerSdk private constructor(
    private val context: Context,
    private val merchantId: String,
    private val apiKey: String, // مفتاح API المضاف حديثاً للترويسات
    private val isSandbox: Boolean,
    private val enableApnFallback: Boolean
) {
    private val finalUrl: String = if (isSandbox) SANDBOX_URL else PRODUCTION_URL

    companion object {
        private const val TAG = "AtheerSdk"
        
        // روابط المقاصة المالية المحدثة
        private const val PRODUCTION_URL = "https://api.atheer-pay.com"
        private const val SANDBOX_URL = "https://sandbox.atheer-pay.com"
        
        // المسار الجديد لعملية الدفع
        private const val CHARGE_PATH = "/api/v1/payments/process"

        @Volatile
        private var instance: AtheerSdk? = null

        /**
         * تهيئة المكتبة
         * @param apiKey مفتاح التاجر الخاص بـ Atheer Switch
         */
        fun init(context: Context, merchantId: String, apiKey: String, isSandbox: Boolean = true, enableApnFallback: Boolean = false) {
            Log.i(TAG, "بدء تهيئة Atheer SDK...")

            if (instance != null) return
            synchronized(this) {
                if (instance == null) {
                    instance = AtheerSdk(context.applicationContext, merchantId, apiKey, isSandbox, enableApnFallback)
                    Log.i(TAG, "تمت تهيئة Atheer SDK بنجاح.")
                }
            }
        }

        fun getInstance(): AtheerSdk {
            return instance ?: throw IllegalStateException("يجب استدعاء AtheerSdk.init() أولاً.")
        }
    }

    private val keystoreManager = AtheerKeystoreManager()
    private val networkRouter = AtheerNetworkRouter(context)
    private val database = AtheerDatabase.getInstance(context)
    private val tokenManager = AtheerTokenManager(context)
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * معالجة المعاملة التقليدية (مع تحديث المسار والترويسات)
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

                scheduleBackgroundSync(accessToken)

                if (networkRouter.isNetworkAvailable()) {
                    // استخدام الهيكل المسطح والمسار الجديد
                    val requestBody = JSONObject().apply {
                        put("transactionId", transaction.transactionId)
                        put("atheerToken", tokenizedCard)
                        put("nonce", String(sensitiveNonce))
                        put("amount", transaction.amount)
                        put("merchantId", merchantId)
                        put("currency", transaction.currency)
                    }.toString()

                    networkRouter.executeStandard("$finalUrl$CHARGE_PATH", requestBody, accessToken, apiKey)
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
     * تنفيذ شحن مباشر (Flattened Request Structure)
     */
    suspend fun charge(request: ChargeRequest, accessToken: String): Result<ChargeResponse> {
        val sensitiveNonce = keystoreManager.generateNonce().toCharArray()
        return try {
            // تحديث الهيكل ليكون مسطحاً (Flattening) وإضافة الحقول الإلزامية
            val body = JSONObject().apply {
                put("amount", request.amount)
                put("currency", request.currency)
                put("merchantId", request.merchantId)
                put("atheerToken", request.atheerToken)
                put("customerMobile", request.customerMobile) // الحقل الجديد الإلزامي
                put("nonce", String(sensitiveNonce))
                put("description", request.description)
                
                // حقول المحافظ
                put("agentWallet", request.agentWallet)
                put("receiverMobile", request.receiverMobile)
                put("externalRefId", request.externalRefId)
                put("purpose", request.purpose)
                put("providerName", request.providerName)
            }.toString()

            // استخدام المسار الجديد والترويسة الأمنية x-atheer-api-key
            val responseJson = networkRouter.executeStandard("$finalUrl$CHARGE_PATH", body, accessToken, apiKey)
            
            // التعامل مع الرد المسطح أيضاً إذا لزم الأمر
            val responseObj = JSONObject(responseJson)
            val transactionId = responseObj.optString("transactionId") ?: responseObj.optJSONObject("data")?.optString("transactionId") ?: ""
            
            Result.success(ChargeResponse(transactionId = transactionId, status = "ACCEPTED", message = "نجاح العملية"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            sensitiveNonce.fill('0')
        }
    }

    /**
     * مزامنة المعاملات المعلقة
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
                        val decryptedAmount = try {
                            keystoreManager.decrypt(entity.encryptedAmount).toLong()
                        } catch (e: Exception) { 0L }

                        // استخدام الهيكل المسطح في المزامنة أيضاً
                        val requestBody = JSONObject().apply {
                            put("transactionId", entity.transactionId)
                            put("atheerToken", entity.encryptedToken)
                            put("nonce", entity.nonce)
                            put("amount", decryptedAmount)
                            put("merchantId", entity.merchantId)
                            put("currency", entity.currency)
                        }.toString()

                        networkRouter.executeStandard("$finalUrl$CHARGE_PATH", requestBody, accessToken, apiKey)
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
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .addTag("AtheerSyncTag")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork("AtheerBackgroundSync", ExistingWorkPolicy.REPLACE, syncRequest)
    }

    fun getNextOfflineLimit(): Int {
        val prefs = context.getSharedPreferences("AtheerSecurityPrefs", Context.MODE_PRIVATE)
        return prefs.getInt("OFFLINE_LIMIT", Int.MAX_VALUE)
    }

    fun clearOfflineLimit() {
        context.getSharedPreferences("AtheerSecurityPrefs", Context.MODE_PRIVATE).edit().remove("OFFLINE_LIMIT").apply()
    }

    fun getKeystoreManager() = keystoreManager
    fun getTokenManager() = tokenManager
}
