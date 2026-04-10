package com.atheer.sdk

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.atheer.sdk.database.AtheerDatabase
import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.model.ChargeResponse
import com.atheer.sdk.network.AtheerNetworkRouter
import com.atheer.sdk.security.AtheerKeystoreManager
import com.atheer.sdk.security.AtheerPaymentSession
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * ## AtheerSdkConfig
 * إعدادات التهيئة الموحدة للمكتبة باستخدام DSL
 */
class AtheerSdkConfig {
    var context: Context? = null
    var merchantId: String = ""
    var apiKey: String = ""
    var phoneNumber: String = ""
    var isSandbox: Boolean = true
    var enableApnFallback: Boolean = false
}

/**
 * ## AtheerSdk
 * الواجهة الرئيسية للمكتبة بالاعتماد على الهوية الموحدة (phoneNumber / deviceId).
 */
class AtheerSdk private constructor(
    private val context: Context,
    private val merchantId: String,
    private val apiKey: String,
    val phoneNumber: String,
    private val isSandbox: Boolean,
    private val enableApnFallback: Boolean
) {
    private val finalUrl: String = if (isSandbox) SANDBOX_URL else PRODUCTION_URL

    companion object {
        private const val TAG = "AtheerSdk"
        private const val PRODUCTION_URL = "http://206.189.137.59:4000"
        private const val SANDBOX_URL = "http://10.0.2.2:4000" // Android Emulator → localhost
        private const val CHARGE_PATH = "/api/v1/payments/process"
        private const val ENROLL_PATH = "/api/v1/devices/enroll"

        @Volatile
        private var instance: AtheerSdk? = null

        /**
         * التهيئة باستخدام التهيئة الموحدة (Builder / DSL)
         */
        fun init(block: AtheerSdkConfig.() -> Unit) {
            val config = AtheerSdkConfig().apply(block)
            
            requireNotNull(config.context) { "يجب تمرير context صحيح للتهيئة" }
            require(config.phoneNumber.isNotBlank()) { "رقم الهاتف إلزامي لعمليات الدفع والتشفير" }

            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = AtheerSdk(
                            context = config.context!!.applicationContext,
                            merchantId = config.merchantId,
                            apiKey = config.apiKey,
                            phoneNumber = config.phoneNumber,
                            isSandbox = config.isSandbox,
                            enableApnFallback = config.enableApnFallback
                        )
                        Log.i(TAG, "تمت تهيئة Atheer SDK بنجاح (Unified ID: ${config.phoneNumber}).")
                    }
                }
            }
        }

        fun getInstance(): AtheerSdk = instance ?: throw IllegalStateException("يجب استدعاء AtheerSdk.init() أولاً.")
    }

    private val keystoreManager = AtheerKeystoreManager(context)
    private val networkRouter = AtheerNetworkRouter(context, isSandbox = isSandbox)
    private val database = AtheerDatabase.getInstance(context)
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * تسجيل الجهاز للحصول على Master Seed وتخزينه.
     */
    fun enrollDevice(onSuccess: () -> Unit, onError: (String) -> Unit) {
        sdkScope.launch {
            try {
                val requestBody = JSONObject().apply {
                    put("deviceId", phoneNumber) // الهاتف هو المعرف
                }.toString()

                val responseJson = networkRouter.executeStandard("$finalUrl$ENROLL_PATH", requestBody, "", apiKey)
                val responseObj = JSONObject(responseJson)
                
                val seed = responseObj.optJSONObject("data")?.optString("deviceSeed") ?: responseObj.optString("deviceSeed")
                if (seed.isNotBlank()) {
                    keystoreManager.storeEnrolledSeed(seed)
                    withContext(Dispatchers.Main) { onSuccess() }
                } else {
                    withContext(Dispatchers.Main) { onError("لم يتم العثور على deviceSeed في الرد") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: "فشل التسجيل") }
            }
        }
    }

    /**
     * تجهيز عملية الدفع عبر المصادقة الحيوية للعميل.
     */
    fun preparePayment(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    
                    try {
                        // استخدام العداد من مدير المفاتيح لتوليد مفتاح واستخدامه في التوقيع
                        val counter = keystoreManager.incrementAndGetCounter()
                        val timestamp = System.currentTimeMillis()
                        val luk = keystoreManager.deriveLUK(counter)
                        
                        val payload = "$phoneNumber|$counter|$timestamp"
                        val cryptogram = keystoreManager.signWithLUK(payload, luk)
                        
                        AtheerPaymentSession.arm(phoneNumber, cryptogram)
                        onSuccess()
                    } catch (e: Exception) {
                        onError("خطأ أثناء إنشاء التوقيع: ${e.message}")
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
            .setSubtitle("استخدم البصمة لتأكيد الدفع عبر أثير")
            .setNegativeButtonText("إلغاء")
            .build()

        try {
            // نستخدم المصادقة العادية بدون CryptoObject لأننا نشتق المفتاح بناءً على הSeed والـ Counter
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            onError("خطأ في تهيئة المصادقة: ${e.message}")
        }
    }

    /**
     * تحليل بيانات NFC وتجهيز عملية الدفع (يستخدمها التاجر)
     */
    fun parseNfcDataToRequest(
        rawNfcData: String,
        amount: Double,
        receiverAccount: String,
        transactionType: String
    ): ChargeRequest {
        val parts = rawNfcData.split("|")
        if (parts.size < 4) {
            throw IllegalArgumentException("بيانات NFC غير صالحة: التنسيق المتوقع DeviceID|Counter|Timestamp|Signature")
        }

        val deviceId = parts[0]
        val counter = parts[1].toLongOrNull() ?: throw IllegalArgumentException("قيمة Counter غير صحيحة")
        val timestamp = parts[2].toLongOrNull() ?: throw IllegalArgumentException("قيمة Timestamp غير صحيحة")
        val signature = parts[3]

        return ChargeRequest(
            amount = amount.toLong(),
            currency = "YER",
            merchantId = this.merchantId,
            receiverAccount = receiverAccount,
            transactionRef = java.util.UUID.randomUUID().toString(),
            transactionType = transactionType,
            deviceId = deviceId,
            counter = counter,
            timestamp = timestamp,
            authMethod = "BIOMETRIC_CRYPTOGRAM",
            signature = signature
        )
    }

    /**
     * دالة الدفع المبسطة (Suspended)
     */
    suspend fun charge(request: ChargeRequest, accessToken: String): Result<ChargeResponse> {
        if (!networkRouter.isNetworkAvailable()) {
            return Result.failure(Exception("خطأ: لا يوجد اتصال بالإنترنت."))
        }

        return try {
            val body = JSONObject().apply {
                put("amount", request.amount)
                put("currency", request.currency)
                put("merchantId", request.merchantId)
                put("receiverAccount", request.receiverAccount)
                put("transactionRef", request.transactionRef)
                put("transactionType", request.transactionType)
                put("deviceId", request.deviceId)
                put("counter", request.counter)
                put("timestamp", request.timestamp)
                put("authMethod", request.authMethod)
                put("signature", request.signature)
            }.toString()

            // باستخدام executeStandard بدون توكن كأنها عملية P2M مباشرة من التاجر
            val responseJson = networkRouter.executeStandard("$finalUrl$CHARGE_PATH", body, accessToken, apiKey)
            val responseObj = JSONObject(responseJson)
            val transactionId = responseObj.optString("transactionId") ?: responseObj.optJSONObject("data")?.optString("transactionId") ?: ""

            Result.success(ChargeResponse(transactionId = transactionId, status = "ACCEPTED", message = "نجاح العملية"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}