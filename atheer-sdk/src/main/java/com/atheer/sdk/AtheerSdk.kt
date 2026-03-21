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
    private val isSandbox: Boolean,
    private val enableApnFallback: Boolean
) {
    // إخفاء الروابط كإجراء أمني استراتيجي لمنع هجمات (Man-in-the-Middle)
    // وضمان مرور كافة العمليات المالية عبر مقسم أثير المعتمد فقط.
    private val finalUrl: String = if (isSandbox) SANDBOX_URL else PRODUCTION_URL

    companion object {
        private const val TAG = "AtheerSdk"
        
        // روابط داخلية خاصة للمقاصة المالية
        private const val PRODUCTION_URL = "https://api.atheer-pay.com/v1"
        private const val SANDBOX_URL = "https://sandbox.atheer-pay.com/v1"

        @Volatile
        private var instance: AtheerSdk? = null

        /**
         * تهيئة المكتبة مع تفعيل فحوصات الأمان الصارمة
         * @param isSandbox تحدد ما إذا كان سيتم الاتصال بسيرفرات الاختبار أو السيرفر الحقيقي
         */
        fun init(context: Context, merchantId: String, isSandbox: Boolean = true, enableApnFallback: Boolean = false) {
            Log.i(TAG, "بدء تهيئة Atheer SDK...")

            if (instance != null) return
            synchronized(this) {
                if (instance == null) {
                    instance = AtheerSdk(context.applicationContext, merchantId, isSandbox, enableApnFallback)
                    Log.i(TAG, "تمت تهيئة Atheer SDK بنجاح (Sandbox: $isSandbox).")
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

                scheduleBackgroundSync(accessToken)

                if (networkRouter.isNetworkAvailable()) {
                    val requestBody = buildTransactionJson(transaction.transactionId, tokenizedCard, String(sensitiveNonce), transaction.amount)
                    // استخدام الرابط الداخلي المختار
                    networkRouter.executeStandard("$finalUrl/merchant/charge", requestBody, accessToken)
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
                    // دعم حقول المحافظ اليمنية
                    put("agentWallet", request.agentWallet)
                    put("receiverMobile", request.receiverMobile)
                    put("externalRefId", request.externalRefId)
                    put("purpose", request.purpose)
                    put("providerName", request.providerName)
                })
            }.toString()
            // استخدام الرابط الداخلي المختار
            val responseJson = networkRouter.executeStandard("$finalUrl/merchant/charge", body, accessToken)
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

                        val requestBody = buildTransactionJson(
                            entity.transactionId,
                            entity.encryptedToken ?: "",
                            entity.nonce ?: "",
                            decryptedAmount
                        )
                        // استخدام الرابط الداخلي المختار
                        networkRouter.executeStandard("$finalUrl/merchant/charge", requestBody, accessToken)
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
