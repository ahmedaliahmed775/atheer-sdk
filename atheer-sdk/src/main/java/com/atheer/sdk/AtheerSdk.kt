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
 * ## AtheerSdk
 * الواجهة الرئيسية (Facade) للمكتبة - توفر وصولاً مركزياً لكافة ميزات نظام Atheer SDK.
 * 
 * هذه النسخة (v1.1.0) مطورة لتتوافق مع **Atheer Switch V1** وتدعم هيكلية البيانات المسطحة (Flattened Models).
 * تدير المكتبة عمليات الدفع عبر NFC، التشفير، التخزين المحلي، والمزامنة الخلفية.
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

        // روابط مقسم أثير الخاص بك على السيرفر
        private const val PRODUCTION_URL = "http://206.189.137.59:3000"
        private const val SANDBOX_URL = "http://206.189.137.59:3000"

        // المسار المعتمد لعملية الدفع في Atheer Switch V1
        private const val CHARGE_PATH = "/api/v1/payments/process"

        @Volatile
        private var instance: AtheerSdk? = null

        /**
         * تهيئة المكتبة وضبط الإعدادات الأساسية.
         * يجب استدعاء هذه الدالة مرة واحدة فقط عند بدء تشغيل التطبيق (في فئة Application).
         *
         * @param context سياق التطبيق.
         * @param merchantId معرف التاجر المسجل في نظام أثير.
         * @param apiKey مفتاح الأمان (x-atheer-api-key) لتأمين ترويسات الطلبات.
         * @param isSandbox تحديد ما إذا كان التطبيق يعمل في بيئة الاختبار (true) أو الإنتاج (false).
         * @param enableApnFallback تفعيل توجيه البيانات عبر APN مخصص في حالة عدم وجود رصيد إنترنت.
         */
        fun init(context: Context, merchantId: String, apiKey: String, isSandbox: Boolean = true, enableApnFallback: Boolean = false) {
            Log.i(TAG, "بدء تهيئة Atheer SDK على السيرفر: $PRODUCTION_URL")

            if (instance != null) return
            synchronized(this) {
                if (instance == null) {
                    instance = AtheerSdk(context.applicationContext, merchantId, apiKey, isSandbox, enableApnFallback)
                    Log.i(TAG, "تمت تهيئة Atheer SDK بنجاح وتوجيهها للمقسم.")
                }
            }
        }

        /**
         * الحصول على النسخة النشطة (Instance) من المكتبة.
         * @return كائن [AtheerSdk] الوحيد (Singleton).
         * @throws IllegalStateException إذا لم يتم تهيئة المكتبة باستخدام [init].
         */
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
     * معالجة معاملة دفع ناتجة عن مسح بطاقة NFC.
     * تقوم الدالة بتشفير البيانات وتخزينها محلياً قبل محاولة إرسالها للسيرفر.
     *
     * @param transaction كائن المعاملة الذي يحتوي على بيانات البطاقة والمبلغ.
     * @param accessToken رمز الوصول (Bearer Token) الخاص بالمستخدم الحالي.
     * @param onSuccess دالة استدعاء راجعة عند نجاح العملية.
     * @param onError دالة استدعاء راجعة عند حدوث خطأ.
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
     * تنفيذ عملية شحن مباشر (Charge) باستخدام الهيكل المسطح الجديد.
     * هذه الدالة موجهة لعمليات الدفع التي لا تعتمد على NFC المباشر (مثل إدخال التوكن يدوياً).
     *
     * @param request كائن الطلب الذي يحتوي على كافة بيانات الدفع بما في ذلك [customerMobile].
     * @param accessToken رمز الوصول الخاص بالمستخدم.
     * @return [Result] يحتوي على [ChargeResponse] في حالة النجاح أو خطأ في حالة الفشل.
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
     * مزامنة المعاملات المعلقة في قاعدة البيانات المحلية يدوياً.
     * يتم إرسال كافة المعاملات التي تمت في وضع "بدون اتصال" (Offline) إلى السيرفر.
     *
     * @param accessToken رمز الوصول الخاص بالمستخدم.
     * @param onComplete دالة استدعاء راجعة تعيد عدد المعاملات التي تمت مزامنتها بنجاح.
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

    /**
     * جدولة مهمة المزامنة الخلفية باستخدام WorkManager.
     * يتم تنفيذ المهمة فور توفر اتصال بالإنترنت.
     *
     * @param authToken رمز المصادقة اللازم لإرسال الطلبات للسيرفر.
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
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .addTag("AtheerSyncTag")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork("AtheerBackgroundSync", ExistingWorkPolicy.REPLACE, syncRequest)
    }

    /**
     * جلب الحد الأقصى المسموح به للمعاملة القادمة في وضع عدم الاتصال.
     * يتم استرجاع القيمة من الإعدادات الآمنة المحلية.
     * 
     * @return الحد المسموح به كقيمة صحيحة (Int).
     */
    fun getNextOfflineLimit(): Int {
        val prefs = context.getSharedPreferences("AtheerSecurityPrefs", Context.MODE_PRIVATE)
        return prefs.getInt("OFFLINE_LIMIT", Int.MAX_VALUE)
    }

    /**
     * مسح حد العمليات "دون اتصال" المخزن محلياً.
     * تُستدعى هذه الدالة عادةً بعد استهلاك الحد في عملية ناجحة أو عند تحديث البيانات من السيرفر.
     */
    fun clearOfflineLimit() {
        context.getSharedPreferences("AtheerSecurityPrefs", Context.MODE_PRIVATE)
            .edit()
            .remove("OFFLINE_LIMIT")
            .apply()
    }

    /**
     * @return كائن مدير المفاتيح [AtheerKeystoreManager].
     */
    fun getKeystoreManager() = keystoreManager

    /**
     * @return كائن مدير الرموز المميزة [AtheerTokenManager].
     */
    fun getTokenManager() = tokenManager
}
