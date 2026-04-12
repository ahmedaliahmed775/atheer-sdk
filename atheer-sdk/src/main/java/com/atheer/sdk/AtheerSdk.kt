package com.atheer.sdk

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.atheer.sdk.model.AtheerReadinessReport
import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.model.ChargeResponse
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
 * ## AtheerSdk
 * الواجهة الرئيسية للمكتبة بالاعتماد على الهوية الموحدة (phoneNumber / deviceId).
 */
class AtheerSdk private constructor(
    private val context: Context,
    private val merchantId: String,
    private val apiKey: String,
    internal val phoneNumber: String,
    private val isSandbox: Boolean,
    private val enableApnFallback: Boolean,
    private val blockRootedDevices: Boolean,
    private val certificatePins: List<String>
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
        private const val PRODUCTION_URL = "https://api.atheer.com"
        private const val SANDBOX_URL = "http://10.0.2.2:4000"
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
            require(config.merchantId.isNotBlank()) { "merchantId إلزامي لتحديد هوية التاجر" }
            require(config.apiKey.isNotBlank()) { "apiKey إلزامي للمصادقة مع الخادم" }

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
                            blockRootedDevices = config.blockRootedDevices,
                            certificatePins = config.certificatePins
                        )
                        Log.i(TAG, "تمت تهيئة Atheer SDK بنجاح (Unified ID: ${config.phoneNumber}).")
                    }
                }
            }
        }

        fun getInstance(): AtheerSdk = instance
            ?: throw IllegalStateException("يجب استدعاء AtheerSdk.init() أولاً.")

        /**
         * إعادة تعيين الـ SDK وتحرير الموارد.
         * يجب استدعاؤها عند تسجيل خروج المستخدم أو تبديل الحساب.
         */
        fun reset() {
            instance?.sessionExpirationJob?.cancel()
            instance?.sdkScope?.cancel()
            instance = null
        }
    }

    private val keystoreManager = AtheerKeystoreManager(context)
    private val networkRouter = AtheerNetworkRouter(context, isSandbox = isSandbox, certificatePins = certificatePins)
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * مستودع البيانات المركزي — يوفر وصولاً نظيفاً للشبكة والـ Keystore.
     */
    internal val repository: com.atheer.sdk.repository.AtheerRepository =
        com.atheer.sdk.repository.AtheerRepositoryImpl(
            context = context,
            networkRouter = networkRouter,
            keystoreManager = keystoreManager,
            baseUrl = finalUrl,
            gson = gson
        )

    // Rate limiting
    private var lastPaymentAttemptMs = 0L

    /**
     * تسجيل الجهاز للحصول على Master Seed وتخزينه.
     * إذا كان الجهاز مسجلاً مسبقاً، يتم إرجاع النجاح فوراً دون طلب شبكة.
     *
     * @param forceRenew إعادة التسجيل حتى لو كان الجهاز مسجلاً (افتراضي: false)
     * @return [Result.success] عند نجاح التسجيل، [Result.failure] مع رسالة الخطأ عند الفشل.
     */
    suspend fun enrollDevice(forceRenew: Boolean = false): Result<Unit> =
        repository.enrollDevice(phoneNumber, apiKey, forceRenew)

    /**
     * تجهيز عملية الدفع عبر المصادقة الحيوية للعميل.
     * يتضمن Rate Limiting لمنع المحاولات المتكررة خلال فترة قصيرة.
     *
     * @param activity النشاط الحالي المطلوب لعرض مربع حوار المصادقة الحيوية.
     * @return [Result.success] عند نجاح المصادقة وتسليح الجلسة، [Result.failure] عند الفشل.
     */
    suspend fun preparePayment(activity: FragmentActivity): Result<Unit> =
        withContext(Dispatchers.Main) {
            // Rate Limiting
            val now = System.currentTimeMillis()
            if (now - lastPaymentAttemptMs < MIN_PAYMENT_INTERVAL_MS) {
                val remaining = (MIN_PAYMENT_INTERVAL_MS - (now - lastPaymentAttemptMs)) / 1000
                return@withContext Result.failure(
                    IllegalStateException("يرجى الانتظار $remaining ثوانٍ قبل المحاولة مجدداً")
                )
            }
            lastPaymentAttemptMs = now

            suspendCancellableCoroutine { continuation ->
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

                                if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(Result.success(Unit)))
                            } catch (e: Exception) {
                                _sessionState.value = SessionState.IDLE
                                if (continuation.isActive) {
                                    continuation.resumeWith(kotlin.Result.success(Result.failure(e)))
                                }
                            }
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            if (continuation.isActive) {
                                continuation.resumeWith(
                                    kotlin.Result.success(Result.failure(Exception(errString.toString())))
                                )
                            }
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            // onAuthenticationFailed يُستدعى لكل محاولة فاشلة — لا نُنهي الـ coroutine هنا
                            // بل ننتظر onAuthenticationError أو onAuthenticationSucceeded
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
                    if (continuation.isActive) {
                        continuation.resumeWith(kotlin.Result.success(Result.failure(e)))
                    }
                }

                continuation.invokeOnCancellation {
                    biometricPrompt.cancelAuthentication()
                }
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
     * دالة الدفع (Suspended) — تُفوَّض للـ Repository.
     * تُرسِل المعاملة مباشرة للـ Backend وتُعيد النتيجة للمحفظة المضيفة.
     */
    suspend fun charge(request: ChargeRequest, accessToken: String): Result<ChargeResponse> {
        if (!networkRouter.isNetworkAvailable()) {
            return Result.failure(Exception("خطأ: لا يوجد اتصال بالإنترنت."))
        }
        return repository.charge(request, accessToken, apiKey)
    }

    /**
     * التحقق من جاهزية الجهاز الكاملة لاستخدام SDK — تُفوَّض للـ Repository.
     * استخدمها قبل عرض واجهة الدفع للمستخدم.
     *
     * @return [AtheerReadinessReport] يحتوي على حالة جميع المتطلبات.
     */
    fun checkReadiness(): AtheerReadinessReport = repository.checkReadiness()
}
