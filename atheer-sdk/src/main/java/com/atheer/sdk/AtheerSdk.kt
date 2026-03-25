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
import com.atheer.sdk.security.AtheerTokenManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ## AtheerSdk
 * الواجهة الرئيسية (Facade) للمكتبة - توفر وصولاً مركزياً لكافة ميزات نظام Atheer SDK.
 * 
 * النسخة المطورة تدعم آلية **Pre-Authorized Biometric Cryptogram** لضمان أعلى مستويات الأمان.
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

        fun init(context: Context, merchantId: String, apiKey: String, isSandbox: Boolean = true, enableApnFallback: Boolean = false) {
            if (instance != null) return
            synchronized(this) {
                if (instance == null) {
                    instance = AtheerSdk(context.applicationContext, merchantId, apiKey, isSandbox, enableApnFallback)
                    Log.i(TAG, "تمت تهيئة Atheer SDK بنجاح.")
                }
            }
        }

        fun getInstance(): AtheerSdk = instance ?: throw IllegalStateException("يجب استدعاء AtheerSdk.init() أولاً.")
    }

    private val keystoreManager = AtheerKeystoreManager()
    private val networkRouter = AtheerNetworkRouter(context)
    private val database = AtheerDatabase.getInstance(context)
    private val tokenManager = AtheerTokenManager(context)
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * تجهيز عملية الدفع عبر المصادقة الحيوية.
     * عند النجاح، يتم توقيع البيانات وتفعيل الجلسة لمدة 60 ثانية.
     */
    fun preparePayment(
        activity: FragmentActivity,
        tokenId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val signature = result.cryptoObject?.signature
                    if (signature != null) {
                        val timestamp = System.currentTimeMillis()
                        val payload = "$tokenId|$timestamp"
                        val cryptogram = keystoreManager.signData(payload, signature)
                        AtheerPaymentSession.arm(tokenId, cryptogram)
                        onSuccess()
                    } else {
                        onError("فشل استرداد كائن التوقيع")
                    }
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
            .build()

        try {
            val signature = keystoreManager.getSignatureInstance()
            biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
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
    fun getTokenManager() = tokenManager

}