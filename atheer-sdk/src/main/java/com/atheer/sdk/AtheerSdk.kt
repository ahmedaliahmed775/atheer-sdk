package com.atheer.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.atheer.sdk.database.AtheerDatabase
import com.atheer.sdk.database.TransactionEntity
import com.atheer.sdk.model.AtheerReadinessReport
import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.model.ChargeResponse
import com.atheer.sdk.model.EnrollResponse
import com.atheer.sdk.network.AtheerNetworkRouter
import com.atheer.sdk.security.AtheerKeystoreManager
import com.atheer.sdk.security.AtheerPaymentSession
import com.google.gson.Gson
import com.scottyab.rootbeer.RootBeer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    /** حجب الأجهزة المكسورة الحماية (Rooted). false = تحذير فقط */
    var blockRootedDevices: Boolean = false
}

/**
 * ## SessionState
 * حالة جلسة الدفع — قابلة للمراقبة عبر [AtheerSdk.sessionState].
 */
enum class SessionState {
    /** الجلسة غير نشطة */
    IDLE,
    /** الجلسة مسلحة — جاهزة للنقر عبر NFC */
    ARMED,
    /** انتهت صلاحية الجلسة (60 ثانية) */
    EXPIRED
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
    private val enableApnFallback: Boolean,
    private val blockRootedDevices: Boolean
) {
    private val finalUrl: String = if (isSandbox) SANDBOX_URL else PRODUCTION_URL
    private val gson = Gson()

    // ==========================================
    // SessionState — مراقبة حالة الجلسة
    // ==========================================

    private val _sessionState = MutableStateFlow(SessionState.IDLE)

    /**
     * تدفق حالة جلسة الدفع — اشترك فيه لعرض عداد تنازلي أو إشعار الانتهاء.
     *
     * ```kotlin
     * sdk.sessionState.collect { state ->
     *     when (state) {
     *         SessionState.ARMED   -> showCountdown(60)
     *         SessionState.EXPIRED -> showMessage("انتهت الجلسة")
     *         SessionState.IDLE    -> resetUI()
     *     }
     * }
     * ```
     */
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var sessionExpirationJob: Job? = null

    companion object {
        private const val TAG = "AtheerSdk"
        private const val PRODUCTION_URL = "http://206.189.137.59:4000"
        private const val SANDBOX_URL = "http://10.0.2.2:4000"
        private const val CHARGE_PATH = "/api/v1/payments/process"
        private const val ENROLL_PATH = "/api/v1/devices/enroll"
        private const val MIN_PAYMENT_INTERVAL_MS = 5_000L   // 5 ثوانٍ بين كل محاولة دفع
        private const val NFC_PAYMENT_TIMESTAMP_MAX_AGE_MS = 120_000L // 2 دقيقة

        @Volatile
        private var instance: AtheerSdk? = null

        /**
         * التهيئة باستخدام التهيئة الموحدة (Builder / DSL)
         */
        fun init(block: AtheerSdkConfig.() -> Unit) {
            val config = AtheerSdkConfig().apply(block)

            requireNotNull(config.context) { "يجب تمرير context صحيح للتهيئة" }
            require(config.phoneNumber.isNotBlank()) { "رقم الهاتف إلزامي لعمليات الدفع والتشفير" }

            // Root Detection
            val rootBeer = RootBeer(config.context)
            if (rootBeer.isRooted) {
                if (config.blockRootedDevices) {
                    throw SecurityException("رُفض التهيئة: الجهاز يبدو أنه مكسور الحماية (Rooted). " +
                            "يشكّل ذلك خطراً أمنياً على بيانات الدفع.")
                } else {
                    Log.w(TAG, "⚠️ تحذير: الجهاز يبدو أنه مكسور الحماية (Rooted). " +
                            "عمليات الدفع قد تكون عرضة للخطر.")
                }
            }

            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = AtheerSdk(
                            context = config.context!!.applicationContext,
                            merchantId = config.merchantId,
                            apiKey = config.apiKey,
                            phoneNumber = config.phoneNumber,
                            isSandbox = config.isSandbox,
                            enableApnFallback = config.enableApnFallback,
                            blockRootedDevices = config.blockRootedDevices
                        )
                        Log.i(TAG, "تمت تهيئة Atheer SDK بنجاح (Unified ID: ${config.phoneNumber}).")
                    }
                }
            }
        }

        fun getInstance(): AtheerSdk = instance
            ?: throw IllegalStateException("يجب استدعاء AtheerSdk.init() أولاً.")
    }

    private val keystoreManager = AtheerKeystoreManager(context)
    private val networkRouter = AtheerNetworkRouter(context, isSandbox = isSandbox)
    private val database = AtheerDatabase.getInstance(context)
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Rate limiting
    private var lastPaymentAttemptMs = 0L

    /**
     * تسجيل الجهاز للحصول على Master Seed وتخزينه.
     * إذا كان الجهاز مسجلاً مسبقاً، يتم إرجاع النجاح فوراً دون طلب شبكة.
     *
     * @param forceRenew إعادة التسجيل حتى لو كان الجهاز مسجلاً (افتراضي: false)
     */
    fun enrollDevice(
        forceRenew: Boolean = false,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        sdkScope.launch {
            // تجنب إعادة التسجيل غير الضرورية
            if (!forceRenew && keystoreManager.isDeviceEnrolled()) {
                Log.i(TAG, "الجهاز مسجل مسبقاً — تم تخطي طلب الشبكة.")
                withContext(Dispatchers.Main) { onSuccess() }
                return@launch
            }

            try {
                val requestBody = gson.toJson(mapOf("deviceId" to phoneNumber))
                val responseJson = networkRouter.executeStandard(
                    "$finalUrl$ENROLL_PATH", requestBody, "", apiKey
                )

                val enrollResponse = gson.fromJson(responseJson, EnrollResponse::class.java)
                val seed = enrollResponse.getSeed()

                if (!seed.isNullOrBlank()) {
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
     * يتضمن Rate Limiting لمنع المحاولات المتكررة خلال فترة قصيرة.
     */
    fun preparePayment(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        // Rate Limiting
        val now = System.currentTimeMillis()
        if (now - lastPaymentAttemptMs < MIN_PAYMENT_INTERVAL_MS) {
            val remaining = (MIN_PAYMENT_INTERVAL_MS - (now - lastPaymentAttemptMs)) / 1000
            onError("يرجى الانتظار $remaining ثوانٍ قبل المحاولة مجدداً")
            return
        }
        lastPaymentAttemptMs = now

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    try {
                        val counter = keystoreManager.incrementAndGetCounter()
                        val timestamp = System.currentTimeMillis()
                        val luk = keystoreManager.deriveLUK(counter)
                        val payload = "$phoneNumber|$counter|$timestamp"
                        val cryptogram = keystoreManager.signWithLUK(payload, luk)

                        AtheerPaymentSession.arm(payload, cryptogram)

                        // تحديث حالة الجلسة + بدء مؤقت الانتهاء
                        _sessionState.value = SessionState.ARMED
                        AtheerPaymentSession.onSessionConsumed = {
                            _sessionState.value = SessionState.IDLE
                            sessionExpirationJob?.cancel()
                        }
                        sessionExpirationJob?.cancel()
                        sessionExpirationJob = sdkScope.launch {
                            delay(AtheerPaymentSession.SESSION_TIMEOUT_MS)
                            if (_sessionState.value == SessionState.ARMED) {
                                _sessionState.value = SessionState.EXPIRED
                            }
                        }

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
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            onError("خطأ في تهيئة المصادقة: ${e.message}")
        }
    }

    /**
     * تحليل بيانات NFC وتجهيز عملية الدفع (يستخدمها التاجر).
     * يتحقق من أن الـ timestamp لا يتجاوز عمره [NFC_PAYMENT_TIMESTAMP_MAX_AGE_MS].
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
        val counter = parts[1].toLongOrNull()
            ?: throw IllegalArgumentException("قيمة Counter غير صحيحة")
        val timestamp = parts[2].toLongOrNull()
            ?: throw IllegalArgumentException("قيمة Timestamp غير صحيحة")
        val signature = parts[3]

        // التحقق من عمر الـ timestamp — يرفض الطلبات الأقدم من دقيقتين
        val ageMs = System.currentTimeMillis() - timestamp
        if (ageMs > NFC_PAYMENT_TIMESTAMP_MAX_AGE_MS) {
            throw IllegalArgumentException("انتهت صلاحية بيانات الدفع (عمر الطلب ${ageMs / 1000}ث > 120ث)")
        }
        if (ageMs < -10_000) {
            throw IllegalArgumentException("Timestamp من المستقبل — رُفض (فارق ${-ageMs / 1000}ث)")
        }

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
     * دالة الدفع (Suspended).
     * تحفظ المعاملة تلقائياً في قاعدة البيانات المحلية عند نجاح العملية.
     */
    suspend fun charge(request: ChargeRequest, accessToken: String): Result<ChargeResponse> {
        if (!networkRouter.isNetworkAvailable()) {
            return Result.failure(Exception("خطأ: لا يوجد اتصال بالإنترنت."))
        }

        return try {
            val body = gson.toJson(request)
            val responseJson = networkRouter.executeStandard(
                "$finalUrl$CHARGE_PATH", body, accessToken, apiKey
            )
            val responseObj = gson.fromJson(responseJson, Map::class.java)
            val transactionId = (responseObj["transactionId"]
                ?: (responseObj["data"] as? Map<*, *>)?.get("transactionId") ?: "").toString()

            val response = ChargeResponse(
                transactionId = transactionId,
                status = "ACCEPTED",
                message = "نجاح العملية"
            )

            // حفظ المعاملة محلياً تلقائياً عند النجاح
            sdkScope.launch {
                runCatching {
                    database.transactionDao().insertTransaction(request.toTransactionEntity(transactionId))
                }.onFailure { e ->
                    Log.w(TAG, "فشل حفظ المعاملة محلياً: ${e.message}")
                }
            }

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * التحقق من جاهزية الجهاز الكاملة لاستخدام SDK.
     * استخدمها قبل عرض واجهة الدفع للمستخدم.
     *
     * @return [AtheerReadinessReport] يحتوي على حالة جميع المتطلبات.
     */
    fun checkReadiness(): AtheerReadinessReport {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
        val isNfcSupported = nfcAdapter != null
        val isNfcEnabled = nfcAdapter?.isEnabled == true
        val isHceSupported = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
        val biometricManager = BiometricManager.from(context)
        val isBiometricAvailable = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
        val isDeviceRooted = try {
            RootBeer(context).isRooted
        } catch (e: Exception) {
            false
        }

        return AtheerReadinessReport(
            isNfcSupported = isNfcSupported,
            isNfcEnabled = isNfcEnabled,
            isHceSupported = isHceSupported,
            isBiometricAvailable = isBiometricAvailable,
            isDeviceEnrolled = keystoreManager.isDeviceEnrolled(),
            isDeviceRooted = isDeviceRooted
        )
    }
}

/**
 * تحويل [ChargeRequest] إلى [TransactionEntity] للحفظ في قاعدة البيانات.
 */
private fun ChargeRequest.toTransactionEntity(transactionId: String) = TransactionEntity(
    transactionId = transactionId,
    amount = amount,
    currency = currency,
    receiverAccount = receiverAccount,
    transactionType = transactionType,
    timestamp = timestamp,
    deviceId = deviceId,
    counter = counter,
    authMethod = authMethod,
    signature = signature,
    isSynced = true // تم إرسالها للخادم بنجاح
)