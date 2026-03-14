package com.atheer.sdk

import android.content.Context
import android.util.Log
import com.atheer.sdk.database.AtheerDatabase
import com.atheer.sdk.database.TransactionEntity
import com.atheer.sdk.model.AtheerTransaction
import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.model.ChargeResponse
import com.atheer.sdk.network.AtheerNetworkRouter
import com.atheer.sdk.security.AtheerKeystoreManager
import com.atheer.sdk.security.AtheerTokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * الواجهة الرئيسية (Facade) لـ Atheer SDK — إدارة مدفوعات SoftPOS والمحافظ الرقمية.
 *
 * هذه الفئة هي نقطة الدخول الوحيدة (Single Point of Entry) للتطبيقات المضيفة (Host Apps)
 * للتفاعل مع مكتبة Atheer. المكتبة تركز حصراً على أمان التشفير، الاتصال اللاتلامسي (NFC)،
 * والمزامنة، وتترك إدارة حسابات المستخدمين (تسجيل الدخول/الأرصدة) للتطبيق المضيف.
 *
 * المهام الرئيسية:
 * - تزويد وإدارة الرموز المميزة غير المتصلة (Offline Token Provisioning).
 * - معالجة عمليات الدفع عبر الشبكة الخلوية أو Wi-Fi.
 * - تخزين المعاملات غير المتصلة محلياً بشكل آمن ومزامنتها لاحقاً.
 *
 * نمط التهيئة:
 * ```kotlin
 * // في Application.onCreate():
 * AtheerSdk.init(context, "MERCHANT_ID_123", "[https://api.atheer.com](https://api.atheer.com)")
 *
 * // استخدام المكتبة:
 * val sdk = AtheerSdk.getInstance()
 * sdk.fetchAndProvisionTokens(accessToken, count = 5, limit = 5000)
 * sdk.charge(chargeRequest, accessToken)
 * ```
 */
class AtheerSdk private constructor(
    private val context: Context,
    private val merchantId: String,
    apiBaseUrl: String
) {
    private val apiBaseUrl: String = apiBaseUrl.trimEnd('/')

    companion object {
        private const val TAG = "AtheerSdk"

        @Volatile
        private var instance: AtheerSdk? = null

        /**
         * تهيئة مكتبة Atheer SDK بالإعدادات الأساسية.
         *
         * يجب استدعاء هذه الدالة مرة واحدة فقط على مستوى التطبيق (يُفضل في Application class)
         * لضمان جاهزية خدمات التشفير وقواعد البيانات قبل أي استخدام.
         *
         * @param context سياق التطبيق (يُفضل applicationContext لمنع تسرب الذاكرة).
         * @param merchantId المعرف الفريد للتاجر الصادر من نظام Atheer.
         * @param apiBaseUrl الرابط الأساسي لخوادم Atheer API.
         */
        fun init(context: Context, merchantId: String, apiBaseUrl: String) {
            Log.i(TAG, "جاري تهيئة Atheer SDK - معرف التاجر: $merchantId")
            if (instance != null) {
                Log.w(TAG, "تنبيه: تم استدعاء init() مسبقاً. سيتم تجاهل هذا الاستدعاء.")
                return
            }
            synchronized(this) {
                if (instance == null) {
                    instance = AtheerSdk(
                        context = context.applicationContext,
                        merchantId = merchantId,
                        apiBaseUrl = apiBaseUrl
                    )
                    Log.i(TAG, "تمت تهيئة Atheer SDK بنجاح.")
                }
            }
        }

        /**
         * الحصول على النسخة الوحيدة (Singleton) من مكتبة SDK.
         *
         * @return نسخة من [AtheerSdk].
         * @throws IllegalStateException إذا تم الاستدعاء قبل تنفيذ `init()`.
         */
        fun getInstance(): AtheerSdk {
            return instance ?: throw IllegalStateException(
                "لم يتم تهيئة Atheer SDK بعد. يرجى استدعاء AtheerSdk.init() أولاً."
            )
        }

        /**
         * إعادة تعيين النسخة الحالية - **مخصصة لبيئة الاختبارات فقط (Unit Tests).**
         * يجب عدم استخدام هذه الدالة مطلقاً في بيئة الإنتاج.
         */
        internal fun resetForTesting() {
            instance = null
            Log.d(TAG, "تم إعادة تعيين Atheer SDK لأغراض الاختبار.")
        }
    }

    // ==================== المكونات الداخلية ====================
    private val keystoreManager = AtheerKeystoreManager()
    private val networkRouter = AtheerNetworkRouter(context)
    private val database = AtheerDatabase.getInstance(context)
    private val tokenManager = AtheerTokenManager(context)

    // نطاق Coroutines خاص بالمكتبة لضمان عدم تأثر العمليات الخلفية بدورة حياة الـ UI
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ==================== معالجة المعاملات والمزامنة ====================

    /**
     * معالجة معاملة دفع كاملة (للعمليات المجدولة أو التي تتطلب تخزيناً محلياً).
     *
     * الخطوات:
     * 1. توليد Nonce أمني لمنع هجمات إعادة الإرسال (Replay Attacks).
     * 2. تشفير بيانات المعاملة والمبلغ.
     * 3. حفظ المعاملة محلياً في قاعدة البيانات المشفرة (استعداداً للوضع غير المتصل).
     * 4. محاولة الإرسال الفوري للخادم؛ وفي حال الفشل، تبقى المعاملة معلقة للمزامنة لاحقاً.
     *
     * @param transaction كائن يحتوي على تفاصيل المعاملة.
     * @param accessToken رمز المصادقة (Bearer Token).
     * @param onSuccess دالة استدعاء (Callback) تُنفذ عند نجاح الحفظ أو الإرسال.
     * @param onError دالة استدعاء (Callback) تُنفذ عند حدوث خطأ برمجي أو تشفيري.
     */
    fun processTransaction(
        transaction: AtheerTransaction,
        accessToken: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        Log.i(TAG, "بدء معالجة معاملة دفع جديدة - المعرف: ${transaction.transactionId}")
        sdkScope.launch {
            try {
                val nonce = keystoreManager.generateNonce()
                Log.d(TAG, "تم توليد Nonce أمني: ${nonce.take(8)}...")

                val tokenizedCard = transaction.tokenizedCard
                    ?: throw IllegalArgumentException("عذراً، يجب توفير tokenizedCard في المعاملة.")

                // تشفير القيمة المالية وحفظ المعاملة محلياً (Data at Rest Security)
                val encryptedAmount = keystoreManager.encrypt(transaction.amount.toString())
                val transactionEntity = TransactionEntity(
                    transactionId = transaction.transactionId,
                    encryptedAmount = encryptedAmount,
                    currency = transaction.currency,
                    merchantId = merchantId,
                    timestamp = transaction.timestamp,
                    encryptedToken = tokenizedCard,
                    nonce = nonce,
                    isSynced = false
                )

                database.transactionDao().insertTransaction(transactionEntity)
                Log.i(TAG, "تم تأمين وحفظ المعاملة محلياً (Offline Vault).")

                // محاولة الإرسال الفوري إذا توفرت الشبكة
                if (networkRouter.isNetworkAvailable()) {
                    try {
                        val requestBody = buildTransactionJson(
                            transaction.transactionId,
                            tokenizedCard,
                            nonce,
                            transaction.amount
                        )
                        val response = networkRouter.executeStandard(
                            "$apiBaseUrl/api/v1/merchant/charge",
                            requestBody,
                            accessToken
                        )

                        database.transactionDao().updateSyncStatus(transaction.transactionId, true)
                        Log.i(TAG, "تم رفع المعاملة للخادم بنجاح.")

                        withContext(Dispatchers.Main) { onSuccess(response) }
                    } catch (networkError: Exception) {
                        Log.w(TAG, "الشبكة غير مستقرة. تم تعليق المعاملة للمزامنة لاحقاً.")
                        withContext(Dispatchers.Main) {
                            onSuccess("تم حفظ المعاملة محلياً وستتم مزامنتها عند استقرار الشبكة.")
                        }
                    }
                } else {
                    Log.i(TAG, "تم تنفيذ المعاملة في وضع عدم الاتصال (Offline Mode).")
                    withContext(Dispatchers.Main) {
                        onSuccess("تمت العملية بنجاح في الوضع اللاتصالي.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "فشل فادح في معالجة المعاملة: ${e.message}", e)
                withContext(Dispatchers.Main) { onError(e) }
            }
        }
    }

    /**
     * مزامنة المعاملات المعلقة (Pending Transactions) مع خوادم Atheer.
     *
     * تُستدعى هذه الدالة عادةً عند استشعار عودة الاتصال بالإنترنت، وتقوم برفع
     * كافة المعاملات التي تم تنفيذها في الوضع اللاتصالي.
     *
     * @param accessToken رمز المصادقة (Bearer Token).
     * @param onComplete دالة تُرجع عدد المعاملات التي تمت مزامنتها بنجاح.
     */
    fun syncPendingTransactions(accessToken: String, onComplete: (Int) -> Unit) {
        Log.i(TAG, "بدء عملية المزامنة الخلفية للمعاملات المعلقة...")
        sdkScope.launch {
            try {
                val unsyncedTransactions = database.transactionDao().getUnsyncedTransactions()
                if (unsyncedTransactions.isEmpty()) {
                    Log.d(TAG, "لا توجد معاملات معلقة للمزامنة.")
                    withContext(Dispatchers.Main) { onComplete(0) }
                    return@launch
                }

                var syncedCount = 0
                for (entity in unsyncedTransactions) {
                    try {
                        val decryptedAmount = try {
                            keystoreManager.decrypt(entity.encryptedAmount).toLongOrNull() ?: 0L
                        } catch (e: Exception) { 0L }

                        val requestBody = buildTransactionJson(
                            entity.transactionId,
                            entity.encryptedToken ?: "",
                            entity.nonce ?: "",
                            decryptedAmount
                        )
                        
                        networkRouter.executeStandard(
                            "$apiBaseUrl/api/v1/merchant/charge",
                            requestBody,
                            accessToken
                        )
                        
                        database.transactionDao().updateSyncStatus(entity.transactionId, true)
                        syncedCount++
                        Log.d(TAG, "تمت مزامنة المعاملة [${entity.transactionId}] بنجاح.")
                    } catch (e: Exception) {
                        Log.w(TAG, "تعذر مزامنة المعاملة [${entity.transactionId}]: ${e.message}")
                    }
                }

                Log.i(TAG, "اكتملت المزامنة. الإجمالي الناجح: $syncedCount")
                withContext(Dispatchers.Main) { onComplete(syncedCount) }
            } catch (e: Exception) {
                Log.e(TAG, "انهيار في عملية المزامنة: ${e.message}", e)
                withContext(Dispatchers.Main) { onComplete(0) }
            }
        }
    }

    // ==================== عمليات الشحن المباشر (POS) ====================

    /**
     * تنفيذ عملية شحن (خصم) مباشرة من محفظة العميل لصالح التاجر.
     *
     * هذه الدالة تقوم بإرسال الرمز المميز (NFC Token) إلى الخادم للمعالجة الفورية.
     * **التحديث الأمني:** لا يتم رمي استثناء (Exception) في حالات الرفض البنكي
     * (مثل نقص الرصيد)، بل يتم إرجاع حالة "DECLINED" لمعالجتها بسلاسة في واجهة المستخدم.
     *
     * @param request كائن يحتوي على مبلغ الشحن والرمز المُلتقط.
     * @param accessToken رمز المصادقة (Bearer Token).
     * @return Result يحتوي على [ChargeResponse] (سواءً قُبلت العملية أو رُفضت بنكياً)،
     * أو يعيد Failure في حالة أخطاء الشبكة فقط.
     */
    suspend fun charge(request: ChargeRequest, accessToken: String): Result<ChargeResponse> {
        Log.i(TAG, "بدء طلب الشحن المباشر - المبلغ: ${request.amount} ${request.currency}")
        return try {
            val generatedNonce = keystoreManager.generateNonce()

            val serviceDetail = JSONObject().apply { put("serviceName", "ATHEER.ECOMMCASHOUT") }
            val header = JSONObject().apply { put("serviceDetail", serviceDetail) }
            val bodyJson = JSONObject().apply {
                put("amount", request.amount)
                put("atheerToken", request.atheerToken)
                put("nonce", generatedNonce)
                put("merchantId", request.merchantId)
                put("currency", request.currency)
            }
            val body = JSONObject().apply {
                put("header", header)
                put("body", bodyJson)
            }.toString()

            val responseJson = networkRouter.executeStandard(
                "$apiBaseUrl/api/v1/merchant/charge",
                body,
                accessToken
            )
            
            val rootJson = JSONObject(responseJson)
            
            if (rootJson.has("header") && rootJson.has("body")) {
                val headerJson = rootJson.getJSONObject("header").getJSONObject("serviceDetail")
                val responseBody = rootJson.getJSONObject("body")
                val responseCode = headerJson.optString("responseCode", "99")
                
                // 00 تعني قبول العملية
                if (responseCode == "00") {
                    val chargeResponse = ChargeResponse(
                        transactionId = responseBody.optString("transactionId"),
                        status = responseBody.optString("status", "ACCEPTED"),
                        message = headerJson.optString("responseMessage", "تمت العملية بنجاح")
                    )
                    Log.i(TAG, "العملية مقبولة - المعرف: ${chargeResponse.transactionId}")
                    Result.success(chargeResponse)
                } else {
                    // ✅ التعديل الأمني: إرجاع الرفض كاستجابة صالحة (DECLINED) بدلاً من انهيار التطبيق
                    val errorMessage = responseBody.optString("message", "تم رفض العملية من البنك")
                    val chargeResponse = ChargeResponse(
                        transactionId = responseBody.optString("transactionId", ""),
                        status = "DECLINED",
                        message = errorMessage
                    )
                    Log.w(TAG, "العملية مرفوضة (كود $responseCode): $errorMessage")
                    Result.success(chargeResponse)
                }
            } else {
                val message = rootJson.optString("message", "استجابة غير قياسية من الخادم")
                throw Exception(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "فشل في الاتصال أثناء الشحن: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ==================== إدارة رموز الدفع اللاتلامسي (HCE/NFC) ====================

    /**
     * طلب وتخزين حزمة جديدة من الرموز المميزة غير المتصلة (Offline Tokens).
     *
     * تُستخدم هذه الرموز لاحقاً للدفع عبر تقنية NFC (HCE) عندما يكون هاتف العميل
     * غير متصل بالإنترنت.
     *
     * @param authToken رمز المصادقة الخاص بالمستخدم.
     * @param count عدد الرموز المراد توليدها.
     * @param limit السقف الأعلى المسموح به لكل عملية دفع (بالريال).
     * @return Result يحتوي على العدد الإجمالي للرموز المتاحة في الخزنة.
     */
    suspend fun fetchAndProvisionTokens(authToken: String, count: Int, limit: Long): Result<Int> {
        return try {
            val networkResult = networkRouter.fetchOfflineTokens(apiBaseUrl, authToken, count, limit)
            if (networkResult.isSuccess) {
                val tokens = networkResult.getOrThrow()
                provisionOfflineTokens(tokens)
                Result.success(getRemainingTokensCount())
            } else {
                Result.failure(networkResult.exceptionOrNull() ?: Exception("فشل جلب الرموز من السيرفر"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * إدراج رموز مشفرة مباشرة إلى الخزنة المحلية (AtheerTokenManager).
     */
    fun provisionOfflineTokens(tokens: List<String>) {
        Log.i(TAG, "جاري إيداع ${tokens.size} رمز لاتلامسي في الخزنة الآمنة.")
        tokenManager.provisionTokens(tokens)
    }

    /**
     * معرفة الرصيد المتبقي من الرموز اللاتلامسية الصالحة للاستخدام.
     */
    fun getRemainingTokensCount(): Int = tokenManager.getTokensCount()

    // ==================== إعدادات السقف المالي اللاتصالي ====================

    /**
     * تحديد السقف المالي المحلي للعملية اللاتلامسية القادمة.
     */
    fun setNextOfflineLimit(amount: Int) {
        val prefs = context.getSharedPreferences("AtheerSecurityPrefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("OFFLINE_LIMIT", amount).apply()
        Log.d(TAG, "تم تعيين السقف المحلي القادم إلى: $amount")
    }

    /**
     * قراءة السقف المالي المحلي. يعيد [Int.MAX_VALUE] إذا لم يكن هناك سقف محدد.
     */
    fun getNextOfflineLimit(): Int {
        val prefs = context.getSharedPreferences("AtheerSecurityPrefs", Context.MODE_PRIVATE)
        return prefs.getInt("OFFLINE_LIMIT", Int.MAX_VALUE)
    }

    /**
     * مسح قيد السقف المالي للعمليات اللاتلامسية.
     */
    fun clearOfflineLimit() {
        val prefs = context.getSharedPreferences("AtheerSecurityPrefs", Context.MODE_PRIVATE)
        prefs.edit().remove("OFFLINE_LIMIT").apply()
    }

    // ==================== أدوات الوصول المباشر (Getters) ====================

    private fun buildTransactionJson(transactionId: String, token: String, nonce: String, amount: Long): String {
        val header = JSONObject().apply {
            put("serviceName", "ATHEER.NFC.PAYMENT")
            put("transactionId", transactionId)
        }
        val body = JSONObject().apply {
            put("merchantId", merchantId)
            put("atheerToken", token)
            put("nonce", nonce)
            put("amount", amount)
        }
        return JSONObject().apply {
            put("header", header)
            put("body", body)
        }.toString()
    }

    fun getKeystoreManager(): AtheerKeystoreManager = keystoreManager
    fun getNetworkRouter(): AtheerNetworkRouter = networkRouter
    fun getDatabase(): AtheerDatabase = database
}
