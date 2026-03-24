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
 * هذه النسخة (v1.2.0) تعتمد نظام **الاتصال الفوري الإلزامي (Online-Only)** لضمان أمان العمليات والتحقق اللحظي من المقسم.
 * تدير المكتبة عمليات الدفع عبر NFC، التشفير، والاتصال المباشر مع Atheer Switch.
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
     * **ملاحظة:** تتطلب هذه الدالة اتصالاً فورياً بالإنترنت. لن يتم تخزين المعاملة محلياً.
     * يتم إرسال البيانات مباشرة إلى المقسم للتحقق والخصم اللحظي.
     *
     * @param transaction كائن المعاملة الذي يحتوي على بيانات البطاقة والمبلغ.
     * @param accessToken رمز الوصول (Bearer Token) الخاص بالمستخدم الحالي.
     * @param onSuccess دالة استدعاء راجعة عند نجاح العملية وتأكيدها من السيرفر.
     * @param onError دالة استدعاء راجعة عند حدوث خطأ أو عدم توفر اتصال.
     */
    fun processTransaction(
        transaction: AtheerTransaction,
        accessToken: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        // 1. فحص إلزامي للشبكة قبل البدء
        if (!networkRouter.isNetworkAvailable()) {
            onError(Exception("خطأ: يجب توفر اتصال بالإنترنت لإتمام العملية"))
            return
        }

        val sensitiveNonce = keystoreManager.generateNonce().toCharArray()
        val sensitiveAmount = transaction.amount.toString().toCharArray()

        sdkScope.launch {
            try {
                val tokenizedCard = transaction.tokenizedCard ?: throw IllegalArgumentException("tokenizedCard مطلوب")
                
                // تم إزالة الحفظ المحلي وجدولة المزامنة لضمان التدفق الفوري (Online-Only)

                // 2. إرسال الطلب مباشرة للمقسم
                val requestBody = JSONObject().apply {
                    put("transactionId", transaction.transactionId)
                    put("atheerToken", tokenizedCard)
                    put("nonce", String(sensitiveNonce))
                    put("amount", transaction.amount)
                    put("merchantId", merchantId)
                    put("currency", transaction.currency)
                }.toString()

                // التنفيذ والانتظار لاستجابة السيرفر
                val response = networkRouter.executeStandard("$finalUrl$CHARGE_PATH", requestBody, accessToken, apiKey)
                
                // إذا لم يرمِ executeStandard استثناءً، فهذا يعني نجاح العملية (HTTP 200)
                withContext(Dispatchers.Main) { 
                    onSuccess("تمت المعالجة والخصم بنجاح من المقسم") 
                }
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
     * تتطلب اتصالاً فورياً بالإنترنت.
     *
     * @param request كائن الطلب الذي يحتوي على كافة بيانات الدفع بما في ذلك [customerMobile].
     * @param accessToken رمز الوصول الخاص بالمستخدم.
     * @return [Result] يحتوي على [ChargeResponse] في حالة النجاح أو خطأ في حالة الفشل.
     */
    suspend fun charge(request: ChargeRequest, accessToken: String): Result<ChargeResponse> {
        if (!networkRouter.isNetworkAvailable()) {
            return Result.failure(Exception("خطأ: لا يوجد اتصال بالإنترنت."))
        }

        val sensitiveNonce = keystoreManager.generateNonce().toCharArray()
        return try {
            val body = JSONObject().apply {
                put("amount", request.amount)
                put("currency", request.currency)
                put("merchantId", request.merchantId)
                put("atheerToken", request.atheerToken)
                put("customerMobile", request.customerMobile)
                put("nonce", String(sensitiveNonce))
                put("description", request.description)
                put("agentWallet", request.agentWallet)
                put("receiverMobile", request.receiverMobile)
                put("externalRefId", request.externalRefId)
                put("purpose", request.purpose)
                put("providerName", request.providerName)
            }.toString()

            val responseJson = networkRouter.executeStandard("$finalUrl$CHARGE_PATH", body, accessToken, apiKey)
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
     * @deprecated تم إيقاف المزامنة المحلية في الإصدار v1.2.0 لصالح نظام الاتصال الفوري الإلزامي.
     */
    @Deprecated("استخدم processTransaction للتعامل الفوري مع المقسم", ReplaceWith(""))
    fun syncPendingTransactions(accessToken: String, onComplete: (Int) -> Unit) {
        onComplete(0)
    }

    /**
     * @deprecated تم إيقاف المزامنة الخلفية في الإصدار v1.2.0.
     */
    @Deprecated("تم إيقاف المزامنة الخلفية لصالح نظام Online-Only")
    fun scheduleBackgroundSync(authToken: String) {
        // لم يعد هناك حاجة لجدولة المزامنة
        WorkManager.getInstance(context).cancelUniqueWork("AtheerBackgroundSync")
    }

    fun getKeystoreManager() = keystoreManager
    fun getTokenManager() = tokenManager
}
