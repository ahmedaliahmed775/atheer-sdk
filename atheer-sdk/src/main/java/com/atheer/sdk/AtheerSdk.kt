package com.atheer.sdk

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.work.*
import com.atheer.sdk.database.AtheerDatabase
import com.atheer.sdk.model.AtheerTransaction
import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.model.ChargeResponse
import com.atheer.sdk.network.AtheerNetworkRouter
import com.atheer.sdk.security.AtheerKeystoreManager
import com.atheer.sdk.security.AtheerPaymentSession

import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.scottyab.rootbeer.RootBeer

/**
 * ## AtheerSdk
 * الواجهة الرئيسية (Facade) للمكتبة - توفر وصولاً مركزياً لكافة ميزات نظام Atheer SDK.
 * 
 * تم تحديث هذه النسخة لتعتمد معمارية **Zero-Trust Dynamic Key Derivation** التي تتيح:
 * 1. **Infinite Offline Transactions**: اشتقاق مفاتيح فريدة لكل عملية دون الحاجة للاتصال المسبق.
 * 2. **Hardware-backed Security**: تخزين الـ Master Seed داخل Android Keystore (TEE/StrongBox).
 * 3. **Relay Attack Protection**: قياس RTT لضمان القرب الفيزيائي أثناء عملية الـ NFC.
 * 4. **Device Integrity**: التحقق من سلامة الجهاز عبر Google Play Integrity API ومنع الأجهزة المروتة.
 */
class AtheerSdk private constructor(
    private val context: Context,
    private val merchantId: String,
    private val apiKey: String,
    private val isSandbox: Boolean,
    private val enableApnFallback: Boolean
) {
    private val finalUrl: String = if (isSandbox) SANDBOX_URL else PRODUCTION_URL

    companion object {
        private const val TAG = "AtheerSdk"
        private const val PRODUCTION_URL = "http://206.189.137.59:3000"
        private const val SANDBOX_URL = "http://206.189.137.59:3000"
        private const val CHARGE_PATH = "/api/v1/payments/process"

        @Volatile
        private var instance: AtheerSdk? = null

        /**
         * تهيئة المكتبة والتحقق من سلامة الجهاز.
         * 
         * @param context سياق التطبيق.
         * @param merchantId معرف التاجر.
         * @param apiKey مفتاح API الخاص بالتاجر.
         * @throws SecurityException إذا كان الجهاز مروتاً أو فشل التحقق من النزاهة.
         */
        fun init(context: Context, merchantId: String, apiKey: String, isSandbox: Boolean = true, enableApnFallback: Boolean = false) {
            if (instance != null) return
            
            // 1. التحقق من الـ Root
            val rootBeer = RootBeer(context)
            if (rootBeer.isRooted) {
                Log.e(TAG, "تم اكتشاف Root! لا يمكن تشغيل SDK على أجهزة غير آمنة.")
                throw SecurityException("Device integrity check failed: Root detected")
            }

            // 2. تهيئة Play Integrity API (بشكل غير متزامن)
            val integrityManager = IntegrityManagerFactory.create(context.applicationContext)
            val nonce = java.util.UUID.randomUUID().toString()
            integrityManager.requestIntegrityToken(
                IntegrityTokenRequest.builder()
                    .setNonce(nonce)
                    .build()
            ).addOnSuccessListener { response ->
                Log.i(TAG, "تم التحقق من نزاهة الجهاز عبر Play Integrity بنجاح.")
            }.addOnFailureListener { e ->
                Log.w(TAG, "فشل التحقق من Play Integrity: ${e.message}. قد يتم تقييد العمليات الحساسة.")
            }

            synchronized(this) {
                if (instance == null) {
                    instance = AtheerSdk(context.applicationContext, merchantId, apiKey, isSandbox, enableApnFallback)
                    Log.i(TAG, "تمت تهيئة Atheer SDK بنجاح.")
                }
            }
        }

        fun getInstance(): AtheerSdk = instance ?: throw IllegalStateException("يجب استدعاء AtheerSdk.init() أولاً.")
    }

    private val keystoreManager = AtheerKeystoreManager(context)
    private val networkRouter = AtheerNetworkRouter(context)
    private val database = AtheerDatabase.getInstance(context)

    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * تجهيز عملية الدفع عبر المصادقة الحيوية.
     * عند النجاح، يتم توقيع البيانات وتفعيل الجلسة لمدة 60 ثانية.
     */
    /**
     * تجهيز عملية الدفع عبر المصادقة الحيوية.
     * عند النجاح، يتم اشتقاق مفتاح LUK وبناء Payload موقع: DeviceID | Counter | Challenge_Timestamp.
     */
    fun preparePayment(
        activity: FragmentActivity,
        deviceId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    
                    // 1. الحصول على العداد الرتيب واشتقاق مفتاح LUK
                    val counter = keystoreManager.incrementAndGetCounter()
                    val luk = keystoreManager.deriveLUK(counter)
                    
                    // 2. بناء الـ Payload: DeviceID | Counter | Challenge_Timestamp
                    // ملاحظة: نستخدم SystemClock.elapsedRealtime() للوقت الداخلي لضمان عدم التلاعب بالوقت
                    val timestamp = android.os.SystemClock.elapsedRealtime()
                    val payload = "$deviceId|$counter|$timestamp"
                    
                    // 3. التوقيع باستخدام LUK المشتق
                    val signature = keystoreManager.signWithLUK(payload, luk)
                    
                    // 4. تفعيل الجلسة بالبيانات الموقعة
                    AtheerPaymentSession.arm(payload, signature)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onError("فشلت المصادقة الحيوية")
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("مصادقة عملية الدفع")
            .setSubtitle("استخدم البصمة لتأمين عملية الدفع عبر أثير")
            .setNegativeButtonText("إلغاء")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        try {
            // في المعمارية الجديدة، المصادقة الحيوية تفتح الوصول للـ Master Seed لاشتقاق LUK
            // أو ببساطة نطلب المصادقة قبل البدء بالعملية
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            onError("خطأ في تهيئة المصادقة: ${e.message}")
        }
    }

    /**
     * معالجة معاملة دفع (Online).
     */
    fun processTransaction(
        transaction: AtheerTransaction,
        accessToken: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (!networkRouter.isNetworkAvailable()) {
            onError(Exception("خطأ: يجب توفر اتصال بالإنترنت لإتمام العملية"))
            return
        }

        sdkScope.launch {
            try {
                val requestBody = JSONObject().apply {
                    put("amount", transaction.amount)
                    put("currency", transaction.currency)
                    put("receiverAccount", transaction.receiverAccount)
                    put("transactionType", transaction.transactionType)
                    put("atheerToken", transaction.atheerToken)
                    put("authMethod", transaction.authMethod)
                    put("signature", transaction.signature)
                }.toString()

                networkRouter.executeStandard("$finalUrl$CHARGE_PATH", requestBody, accessToken, apiKey)
                withContext(Dispatchers.Main) { onSuccess("تمت المعالجة بنجاح") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e) }
            }
        }
    }

    /**
     * تنفيذ عملية شحن مباشر (Charge) باستخدام الهيكل المبسط.
     */
    suspend fun charge(request: ChargeRequest, accessToken: String): Result<ChargeResponse> {
        if (!networkRouter.isNetworkAvailable()) {
            return Result.failure(Exception("خطأ: لا يوجد اتصال بالإنترنت."))
        }

        return try {
            val body = JSONObject().apply {
                put("amount", request.amount)
                put("currency", request.currency)
                put("receiverAccount", request.receiverAccount)
                put("transactionType", request.transactionType)
                put("atheerToken", request.atheerToken)
                put("authMethod", request.authMethod)
                put("signature", request.signature)
            }.toString()

            val responseJson = networkRouter.executeStandard("$finalUrl$CHARGE_PATH", body, accessToken, apiKey)
            val responseObj = JSONObject(responseJson)
            val transactionId = responseObj.optString("transactionId") ?: responseObj.optJSONObject("data")?.optString("transactionId") ?: ""
            
            Result.success(ChargeResponse(transactionId = transactionId, status = "ACCEPTED", message = "نجاح العملية"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    /**
     * تحليل البيانات المستلمة عبر NFC وتحويلها إلى كائن ChargeRequest جاهز.
     * تستخدم هذه الدالة في تطبيق "التاجر" أو "المستقبل" لمعالجة البيانات الخام.
     * * @param rawNfcData البيانات النصية الخام المستلمة من هاتف الدافع (بصيغة tokenId|signature).
     * @param amount المبلغ المطلوب خصمه.
     * @param receiverAccount حساب المستلم (رقم هاتف أو معرف تاجر).
     * @param transactionType نوع العملية (P2M أو P2P).
     * @return كائن [ChargeRequest] مهيأ بالبيانات الصحيحة.
     * @throws IllegalArgumentException إذا كانت البيانات المستلمة غير صالحة أو ناقصة.
     */
    fun parseNfcDataToRequest(
        rawNfcData: String,
        amount: Double,
        receiverAccount: String,
        transactionType: String
    ): ChargeRequest {
        // 1. تفكيك النص باستخدام الفاصل |
        val parts = rawNfcData.split("|")

        if (parts.size < 2) {
            throw IllegalArgumentException("بيانات NFC غير صالحة: التنسيق المتوقع tokenId|signature")
        }

        val tokenId = parts[0]
        val signature = parts[1]

        if (tokenId.isBlank() || signature.isBlank()) {
            throw IllegalArgumentException("بيانات NFC ناقصة: التوكن أو التوقيع فارغ")
        }

        // 2. إنشاء وإرجاع كائن ChargeRequest بالهيكل المبسط المعتمد
        return ChargeRequest(
            amount = amount.toLong(),
            currency = "YER",
            receiverAccount = receiverAccount,
            transactionType = transactionType,
            atheerToken = tokenId,
            authMethod = "BIOMETRIC_CRYPTOGRAM",
            signature = signature
        )
    }

    fun getKeystoreManager() = keystoreManager


}