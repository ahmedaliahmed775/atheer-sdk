package com.atheer.sdk

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.atheer.sdk.database.AtheerDatabase
import com.atheer.sdk.model.AtheerTransaction
import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.model.ChargeResponse
import com.atheer.sdk.network.AtheerNetworkRouter
import com.atheer.sdk.network.AtheerNetworkRouter.NetworkMode
import com.atheer.sdk.security.AtheerKeystoreManager
import com.atheer.sdk.security.AtheerPaymentSession

import kotlinx.coroutines.*
import org.json.JSONObject

import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.scottyab.rootbeer.RootBeer

/**
 * ## AtheerSdk
 * الواجهة الرئيسية (Facade) للمكتبة - توفر وصولاً مركزياً لكافة ميزات نظام Atheer SDK.
 *
 * تم تحديث هذه النسخة لتتضمن:
 * - C-01: توقيع HMAC-SHA256 متوافق مع Backend.
 * - C-03: بروتوكول تسجيل الجهاز (Device Enrollment).
 * - C-04: إرسال جميع الحقول المطلوبة (description, transactionRef, merchantId).
 * - S-02: استخدام HTTPS مع نطاقات بدلاً من IPs.
 * - S-03: استخدام System.currentTimeMillis() كـ timestamp.
 * - B-05: إضافة destroy() لإلغاء CoroutineScope.
 * - A-02/A-06: Builder Pattern + فصل فحص النزاهة عن التهيئة.
 */
class AtheerSdk private constructor(private val config: AtheerSdkConfig) {

    // S-02: استخدام HTTPS مع نطاقات
    private val finalUrl: String = config.baseUrl ?: if (config.isSandbox) SANDBOX_URL else PRODUCTION_URL

    companion object {
        private const val TAG = "AtheerSdk"
        // S-02 Fix: HTTPS مع نطاقات (Domains) بدلاً من HTTP + IPs
        private const val PRODUCTION_URL = "https://api.atheer.com"
        private const val SANDBOX_URL = "https://sandbox.atheer.com"
        private const val CHARGE_PATH = "/api/v1/payments/process"
        private const val ENROLL_PATH = "/api/v1/devices/enroll"

        @Volatile
        private var instance: AtheerSdk? = null

        /**
         * A-02: تهيئة المكتبة باستخدام Builder Pattern (Kotlin DSL).
         *
         * ```kotlin
         * AtheerSdk.init {
         *     context = applicationContext
         *     merchantId = "MERCHANT_001"
         *     apiKey = "YOUR_API_KEY"
         *     isSandbox = true
         * }
         * ```
         *
         * @throws SecurityException إذا كان الجهاز مروتاً.
         */
        fun init(block: AtheerSdkBuilder.() -> Unit) {
            if (instance != null) return
            val builder = AtheerSdkBuilder().apply(block)
            val config = builder.build()
            initWithConfig(config)
        }

        /**
         * تهيئة المكتبة بالطريقة التقليدية (متوافق مع الإصدارات السابقة).
         *
         * @param context سياق التطبيق.
         * @param merchantId معرف التاجر.
         * @param apiKey مفتاح API الخاص بالتاجر.
         * @param isSandbox وضع الاختبار.
         * @param enableApnFallback تفعيل الشبكة الخلوية البديلة.
         * @throws SecurityException إذا كان الجهاز مروتاً.
         */
        fun init(
            context: Context,
            merchantId: String,
            apiKey: String,
            isSandbox: Boolean = true,
            enableApnFallback: Boolean = false
        ) {
            init {
                this.context = context
                this.merchantId = merchantId
                this.apiKey = apiKey
                this.isSandbox = isSandbox
                this.enableApnFallback = enableApnFallback
            }
        }

        /**
         * A-06: التهيئة الداخلية مع فصل فحص النزاهة عن التهيئة الأساسية.
         */
        private fun initWithConfig(config: AtheerSdkConfig) {
            // A-06: الفحوصات المتزامنة (يجب أن تنجح قبل التهيئة)
            performBlockingSecurityChecks(config.context)

            synchronized(this) {
                if (instance == null) {
                    instance = AtheerSdk(config)
                    Log.i(TAG, "تمت تهيئة Atheer SDK بنجاح.")

                    // A-06: الفحوصات غير المتزامنة (لا تمنع التهيئة)
                    performAsyncSecurityChecks(config.context)
                }
            }
        }

        /**
         * A-06: فحوصات أمنية متزامنة (ترفض الأجهزة غير الآمنة فوراً).
         */
        private fun performBlockingSecurityChecks(context: Context) {
            val rootBeer = RootBeer(context)
            if (rootBeer.isRooted) {
                Log.e(TAG, "تم اكتشاف Root! لا يمكن تشغيل SDK على أجهزة غير آمنة.")
                throw SecurityException("Device integrity check failed: Root detected")
            }
        }

        /**
         * A-06: فحوصات أمنية غير متزامنة (تُسجَّل ولا تمنع التهيئة).
         */
        private fun performAsyncSecurityChecks(context: Context) {
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
        }

        fun getInstance(): AtheerSdk = instance ?: throw IllegalStateException("يجب استدعاء AtheerSdk.init() أولاً.")

        /**
         * B-05 Fix: تدمير الـ SDK وإلغاء جميع العمليات الجارية.
         * يُستدعى عند إغلاق التطبيق أو عند الحاجة لإعادة التهيئة.
         */
        fun destroy() {
            instance?.sdkScope?.cancel()
            instance = null
            Log.i(TAG, "تم تدمير Atheer SDK وإلغاء جميع العمليات.")
        }
    }

    internal val keystoreManager = AtheerKeystoreManager(config.context)
    private val networkRouter = AtheerNetworkRouter(config.context, config.networkMode)
    private val database = AtheerDatabase.getInstance(config.context)
    internal val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ==========================================
    // C-03: تسجيل الجهاز (Device Enrollment)
    // ==========================================

    /**
     * التحقق مما إذا كان الجهاز مسجلاً ولديه deviceSeed.
     */
    fun isDeviceEnrolled(): Boolean = keystoreManager.isDeviceEnrolled()

    /**
     * C-03: تسجيل الجهاز مع Backend للحصول على deviceSeed.
     * يجب استدعاؤها مرة واحدة عند أول تشغيل للتطبيق على جهاز جديد.
     *
     * في بيئة التطبيق الموحد (Unified App)، يُستخدم config.phoneNumber تلقائياً كـ deviceId.
     *
     * @param onSuccess يُستدعى عند نجاح التسجيل.
     * @param onError يُستدعى عند فشل التسجيل مع رسالة الخطأ.
     */
    fun enrollDevice(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) = enrollDevice(config.phoneNumber, onSuccess, onError)

    /**
     * C-03: تسجيل الجهاز مع Backend للحصول على deviceSeed.
     * النسخة المُخصَّصة: تقبل معرف جهاز مُخصَّص.
     *
     * @param deviceId معرف الجهاز (في Unified App = رقم الهاتف).
     * @param onSuccess يُستدعى عند نجاح التسجيل.
     * @param onError يُستدعى عند فشل التسجيل مع رسالة الخطأ.
     */
    fun enrollDevice(
        deviceId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (keystoreManager.isDeviceEnrolled()) {
            onSuccess()
            return
        }

        sdkScope.launch {
            try {
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                }.toString()

                val responseJson = networkRouter.executeStandard(
                    "$finalUrl$ENROLL_PATH", body, null, config.apiKey
                )
                val responseObj = JSONObject(responseJson)

                if (responseObj.optBoolean("success", false)) {
                    val deviceSeed = responseObj.optJSONObject("data")?.optString("deviceSeed")
                        ?: throw Exception("لم يتم استلام deviceSeed من الخادم.")
                    keystoreManager.storeEnrolledSeed(deviceSeed)
                    withContext(Dispatchers.Main) { onSuccess() }
                } else {
                    val errorMsg = responseObj.optJSONObject("error")?.optString("message") ?: "فشل التسجيل."
                    withContext(Dispatchers.Main) { onError(errorMsg) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError("خطأ في تسجيل الجهاز: ${e.message}") }
            }
        }
    }

    // ==========================================
    // تجهيز الدفع والمصادقة الحيوية
    // ==========================================

    /**
     * تجهيز عملية الدفع عبر المصادقة الحيوية.
     * في بيئة التطبيق الموحد (Unified App)، يستخدم config.phoneNumber تلقائياً كـ deviceId
     * لضمان أن رقم الهاتف هو المعرف الوحيد في جميع طبقات النظام.
     *
     * S-03 Fix: يستخدم System.currentTimeMillis() كـ timestamp بدلاً من elapsedRealtime().
     *
     * @param activity النشاط الحالي لعرض BiometricPrompt.
     * @param onSuccess يُستدعى عند نجاح المصادقة والجلسة مسلحة.
     * @param onError يُستدعى عند فشل المصادقة.
     */
    fun preparePayment(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) = preparePayment(activity, config.phoneNumber, onSuccess, onError)

    /**
     * تجهيز عملية الدفع عبر المصادقة الحيوية.
     * النسخة المُخصَّصة: تقبل معرف جهاز مُخصَّص.
     *
     * S-03 Fix: يستخدم System.currentTimeMillis() كـ timestamp بدلاً من elapsedRealtime().
     */
    fun preparePayment(
        activity: FragmentActivity,
        deviceId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        // C-03: التحقق من تسجيل الجهاز أولاً
        if (!keystoreManager.isDeviceEnrolled()) {
            onError("الجهاز غير مسجل. استدعِ enrollDevice() أولاً.")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)

                    // 1. الحصول على العداد الرتيب واشتقاق مفتاح LUK
                    val counter = keystoreManager.incrementAndGetCounter()
                    val luk = keystoreManager.deriveLUK(counter)

                    // 2. S-03 Fix: استخدام Unix timestamp (مقاوم لإعادة التشغيل ويمكن التحقق منه في Backend)
                    val timestamp = System.currentTimeMillis()
                    // deviceId = رقم الهاتف في Unified App → يُستخدم كـ customerIdentifier في جوالي
                    val payload = "$deviceId|$counter|$timestamp"

                    // 3. C-01: التوقيع باستخدام HMAC-SHA256 (مطابق لمنطق Backend)
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
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            onError("خطأ في تهيئة المصادقة: ${e.message}")
        }
    }

    // ==========================================
    // معالجة الدفع
    // ==========================================

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
                // C-04 Fix: إرسال جميع الحقول المطلوبة
                val requestBody = JSONObject().apply {
                    put("amount", transaction.amount)
                    put("currency", transaction.currency)
                    put("receiverAccount", transaction.receiverAccount)
                    put("transactionType", transaction.transactionType)
                    put("deviceId", transaction.deviceId)
                    put("counter", transaction.counter)
                    put("timestamp", transaction.timestamp)
                    put("authMethod", transaction.authMethod)
                    put("signature", transaction.signature)
                }.toString()

                networkRouter.executeStandard("$finalUrl$CHARGE_PATH", requestBody, accessToken, config.apiKey)
                withContext(Dispatchers.Main) { onSuccess("تمت المعالجة بنجاح") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e) }
            }
        }
    }

    /**
     * تنفيذ عملية شحن مباشر (Charge) باستخدام الهيكل المبسط.
     * C-04 Fix: يرسل description و transactionRef و merchantId.
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
                put("deviceId", request.deviceId)
                put("counter", request.counter)
                put("timestamp", request.timestamp)
                put("authMethod", request.authMethod)
                put("signature", request.signature)
                // C-04 Fix: إرسال الحقول المفقودة
                request.description?.let { put("description", it) }
                put("transactionRef", request.transactionRef)
                put("merchantId", request.merchantId)
            }.toString()

            val responseJson = networkRouter.executeStandard("$finalUrl$CHARGE_PATH", body, accessToken, config.apiKey)
            val responseObj = JSONObject(responseJson)
            val transactionId = responseObj.optString("transactionId")
                ?: responseObj.optJSONObject("data")?.optString("transactionId") ?: ""

            Result.success(ChargeResponse(transactionId = transactionId, status = "ACCEPTED", message = "نجاح العملية"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * تحليل البيانات المستلمة عبر NFC وتحويلها إلى كائن ChargeRequest جاهز.
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
            ?: throw IllegalArgumentException("بيانات NFC غير صالحة: قيمة Counter غير صحيحة")
        val timestamp = parts[2].toLongOrNull()
            ?: throw IllegalArgumentException("بيانات NFC غير صالحة: قيمة Timestamp غير صحيحة")
        val signature = parts[3]

        if (deviceId.isBlank() || signature.isBlank()) {
            throw IllegalArgumentException("بيانات NFC ناقصة: DeviceID أو Signature فارغ")
        }

        // C-05: استخدام toLong() مع التعامل مع الكسور
        return ChargeRequest(
            amount = amount.toLong(),
            currency = "YER",
            merchantId = config.merchantId,
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

    fun getKeystoreManager() = keystoreManager
}

// ==========================================
// A-02: Builder Pattern (Kotlin DSL) للتهيئة
// ==========================================

/**
 * إعدادات تهيئة Atheer SDK.
 *
 * في بيئة التطبيق الموحد (Unified App):
 *   - phoneNumber = رقم الهاتف المرتبط بهذا المستخدم (العميل أو التاجر)
 *   - merchantId  = معرف وصفي (يمكن ضبطه على نفس رقم الهاتف)
 *   - الـ SDK يستخدم phoneNumber تلقائياً كـ deviceId في enrollDevice وpreparePayment
 */
data class AtheerSdkConfig(
    val context: Context,
    val merchantId: String,
    val apiKey: String,
    /** رقم الهاتف المرتبط بهذا المستخدم — المعرف الوحيد في بيئة Unified App.
     *  يُستخدم كـ deviceId في تسجيل الجهاز والتوقيع الرقمي.
     *  يُمرَّر إلى جوالي كـ customerIdentifier (في وضع الدافع) */
    val phoneNumber: String,
    val isSandbox: Boolean = true,
    /** Feature Toggle: وضع الشبكة (PUBLIC_INTERNET أو PRIVATE_APN) */
    val networkMode: NetworkMode = NetworkMode.PUBLIC_INTERNET,
    val sessionTimeoutMs: Long = 60_000L,
    val rttThresholdMs: Long = 50L,
    val baseUrl: String? = null
)

/**
 * Builder لتهيئة Atheer SDK بأسلوب Kotlin DSL.
 *
 * مثال في بيئة التطبيق الموحد:
 * ```kotlin
 * AtheerSdk.init {
 *     context = applicationContext
 *     merchantId = "711222333"   // رقم هاتف المستخدم (عميل أو تاجر)
 *     apiKey = "YOUR_API_KEY"
 *     phoneNumber = "711222333" // رقم الهاتف — المعرف الوحيد
 *     isSandbox = false
 *     networkMode = NetworkMode.PUBLIC_INTERNET
 * }
 * ```
 */
class AtheerSdkBuilder {
    lateinit var context: Context
    lateinit var merchantId: String
    lateinit var apiKey: String
    /** رقم الهاتف — المعرف الفريد الوحيد في بيئة Unified App.
     *  يُستخدم تلقائياً كـ deviceId في enrollDevice() و preparePayment().
     *  يجب أن يكون رقم الهاتف الفعلي للمستخدم بصيغة دولية أو محلية. */
    lateinit var phoneNumber: String
    var isSandbox: Boolean = true
    /** Feature Toggle: وضع الشبكة — PUBLIC_INTERNET (افتراضي) أو PRIVATE_APN */
    var networkMode: NetworkMode = NetworkMode.PUBLIC_INTERNET
    var sessionTimeoutMs: Long = 60_000L
    var rttThresholdMs: Long = 50L
    var baseUrl: String? = null
    @Deprecated("استخدم phoneNumber بدلاً منه لتفعيل APN Fallback")
    var enableApnFallback: Boolean = false

    fun build(): AtheerSdkConfig {
        check(::context.isInitialized) { "context is required" }
        check(::merchantId.isInitialized) { "merchantId is required" }
        check(::apiKey.isInitialized) { "apiKey is required" }
        check(::phoneNumber.isInitialized) { "phoneNumber is required — في Unified App هو المعرف الوحيد لكل كيان" }

        return AtheerSdkConfig(
            context = context.applicationContext,
            merchantId = merchantId,
            apiKey = apiKey,
            phoneNumber = phoneNumber,
            isSandbox = isSandbox,
            networkMode = networkMode,
            sessionTimeoutMs = sessionTimeoutMs,
            rttThresholdMs = rttThresholdMs,
            baseUrl = baseUrl
        )
    }
}