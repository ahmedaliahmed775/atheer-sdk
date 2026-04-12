package com.atheer.wallet.adapter

import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.atheer.sdk.AtheerSdk
import com.atheer.sdk.AtheerSdkConfig
import com.atheer.sdk.SessionState
import com.atheer.sdk.model.AtheerReadinessReport
import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.model.ChargeResponse
import kotlinx.coroutines.flow.StateFlow

/**
 * ## AtheerWalletAdapter
 * كلاس وسيط (Facade/Adapter) يبسّط تكامل محافظ المدفوعات مع Atheer SDK.
 *
 * يخفي تعقيدات الـ SDK الداخلية (NFC، Keystore، Session Management) ويوفر
 * واجهة موحدة ومبسطة للعمليات الأساسية الأربع:
 * **Initialize → Enroll → Prepare → Process**
 *
 * ### مثال على الاستخدام:
 * ```kotlin
 * // 1. التهيئة (مرة واحدة في Application.onCreate)
 * AtheerWalletAdapter.initialize(
 *     context       = applicationContext,
 *     merchantId    = "MERCHANT_001",
 *     apiKey        = "your-api-key",
 *     phoneNumber   = "71XXXXXXX",
 *     isSandbox     = false,
 *     certificatePins = listOf("sha256/abc...=", "sha256/xyz...=")
 * )
 *
 * // 2. تسجيل الجهاز (مرة واحدة بعد التثبيت)
 * val result = AtheerWalletAdapter.enroll()
 * result.onFailure { showError(it.message) }
 *
 * // 3. تجهيز الدفع (قبل كل عملية — يطلب بصمة المستخدم)
 * val prepareResult = AtheerWalletAdapter.preparePayment(activity)
 * prepareResult.onFailure { showError(it.message) }
 *
 * // 4. معالجة الدفع (في جانب التاجر بعد قراءة NFC)
 * val payResult = AtheerWalletAdapter.processPayment(
 *     rawNfcData      = nfcPayload,
 *     amount          = 5000.0,
 *     receiverAccount = "merchant-phone",
 *     transactionType = "P2M",
 *     accessToken     = userAccessToken
 * )
 * payResult.onSuccess  { showSuccess(it.transactionId) }
 * payResult.onFailure  { showError(it.message) }
 * ```
 *
 * ### مراقبة حالة الجلسة:
 * ```kotlin
 * lifecycleScope.launch {
 *     AtheerWalletAdapter.sessionState.collect { state ->
 *         when (state) {
 *             SessionState.ARMED   -> showTapIndicator()
 *             SessionState.EXPIRED -> showExpiredMessage()
 *             SessionState.IDLE    -> hidePaymentUI()
 *         }
 *     }
 * }
 * ```
 */
object AtheerWalletAdapter {

    private const val TAG = "AtheerWalletAdapter"

    /**
     * تهيئة الـ Adapter وتشغيل Atheer SDK داخله.
     * يجب استدعاؤها مرة واحدة في `Application.onCreate()` قبل أي عملية أخرى.
     *
     * @param context سياق التطبيق (Application Context).
     * @param merchantId معرّف التاجر الصادر من Atheer.
     * @param apiKey مفتاح API للمصادقة.
     * @param phoneNumber رقم هاتف المستخدم — يُستخدم كمعرّف الجهاز.
     * @param isSandbox `true` للتطوير، `false` للإنتاج.
     * @param blockRootedDevices رفض تشغيل SDK على أجهزة مكسورة الحماية.
     * @param certificatePins قائمة SHA-256 hashes لشهادات الخادم (مطلوبة في الإنتاج).
     */
    fun initialize(
        context: Context,
        merchantId: String,
        apiKey: String,
        phoneNumber: String,
        isSandbox: Boolean = true,
        blockRootedDevices: Boolean = false,
        certificatePins: List<String> = emptyList()
    ) {
        AtheerSdk.init {
            this.context = context
            this.merchantId = merchantId
            this.apiKey = apiKey
            this.phoneNumber = phoneNumber
            this.isSandbox = isSandbox
            this.blockRootedDevices = blockRootedDevices
            this.certificatePins = certificatePins
        }
        Log.i(TAG, "✅ AtheerWalletAdapter جاهز (phoneNumber=$phoneNumber, sandbox=$isSandbox).")
    }

    /**
     * تسجيل الجهاز مع Atheer Backend واستلام المفتاح الجذري (deviceSeed).
     * يُنصح باستدعائها مرة واحدة بعد التثبيت الأول.
     *
     * @param forceRenew إعادة التسجيل حتى لو كان الجهاز مسجلاً مسبقاً.
     * @return [Result.success] عند النجاح، [Result.failure] مع رسالة الخطأ.
     */
    suspend fun enroll(forceRenew: Boolean = false): Result<Unit> =
        AtheerSdk.getInstance().enrollDevice(forceRenew)

    /**
     * تجهيز جلسة الدفع عبر المصادقة الحيوية (بصمة الإصبع).
     * عند النجاح، يتم تسليح الجلسة لمدة 60 ثانية تنتهي إذا لم يحدث نقر NFC.
     *
     * @param activity النشاط الحالي لعرض مربع حوار البصمة.
     * @return [Result.success] عند نجاح المصادقة، [Result.failure] عند الرفض أو الخطأ.
     */
    suspend fun preparePayment(activity: FragmentActivity): Result<Unit> =
        AtheerSdk.getInstance().preparePayment(activity)

    /**
     * معالجة بيانات NFC المستقبَلة من جهاز الدفع (جانب التاجر).
     * يُحلِّل حمولة NFC ويُرسل طلب الدفع للخادم.
     *
     * @param rawNfcData حمولة NFC الخام بالتنسيق: `DeviceID|Counter|Timestamp|Signature`
     * @param amount المبلغ المطلوب (بالريال اليمني).
     * @param receiverAccount رقم هاتف التاجر أو رقم الـ POS.
     * @param transactionType نوع العملية (`P2M` أو `P2P`).
     * @param accessToken رمز الوصول للمستخدم.
     * @return [Result.success] مع [ChargeResponse]، أو [Result.failure] مع الخطأ.
     */
    suspend fun processPayment(
        rawNfcData: String,
        amount: Double,
        receiverAccount: String,
        transactionType: String,
        accessToken: String
    ): Result<ChargeResponse> {
        val sdk = AtheerSdk.getInstance()
        return try {
            val chargeRequest = sdk.parseNfcDataToRequest(
                rawNfcData, amount, receiverAccount, transactionType
            )
            sdk.charge(chargeRequest, accessToken)
        } catch (e: Exception) {
            Log.e(TAG, "فشل معالجة الدفع: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * التحقق من جاهزية الجهاز الكاملة قبل عرض واجهة الدفع.
     *
     * @return [AtheerReadinessReport] يحتوي على حالة جميع المتطلبات.
     */
    fun checkReadiness(): AtheerReadinessReport =
        AtheerSdk.getInstance().checkReadiness()

    /**
     * تدفق حالة جلسة الدفع — اشترك فيه لعرض عداد تنازلي أو إشعارات الحالة.
     */
    val sessionState: StateFlow<SessionState>
        get() = AtheerSdk.getInstance().sessionState
}
