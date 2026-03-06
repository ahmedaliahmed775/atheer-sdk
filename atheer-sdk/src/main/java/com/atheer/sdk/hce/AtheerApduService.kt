package com.atheer.sdk.hce

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.atheer.sdk.security.AtheerKeystoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * خدمة HCE (محاكاة البطاقة المضيفة) لـ Atheer SDK
 *
 * تُمكِّن هذه الخدمة الهاتف من العمل كبطاقة NFC للمدفوعات اللاتلامسية
 * حتى في غياب الاتصال بالإنترنت (الوضع غير المتصل - Offline Mode).
 *
 * آلية العمل:
 * 1. يقترب الهاتف من جهاز نقطة البيع (POS)
 * 2. يُرسِل جهاز POS أمر APDU (SELECT AID) لاختيار التطبيق
 * 3. ترد هذه الخدمة بإشارة الموافقة (9000)
 * 4. يطلب POS بيانات الدفع بأوامر APDU متتالية
 * 5. تُرسِل الخدمة الرمز المميز المشفر بدلاً من بيانات البطاقة الحقيقية
 *
 * بروتوكول APDU (Application Protocol Data Unit):
 * - كل أمر APDU يتبع الهيكل: [CLA][INS][P1][P2][Lc][Data][Le]
 * - كل رد APDU ينتهي بكود الحالة: 9000 = نجاح، 6A82 = ملف غير موجود
 *
 * @see HostApduService الفئة الأصلية من Android
 */
class AtheerApduService : HostApduService() {

    companion object {
        private const val TAG = "AtheerApduService"

        // AID الخاص بـ Atheer SDK (Application Identifier)
        // يتطابق مع AID المُعرَّف في apduservice.xml
        private val ATHEER_AID = byteArrayOf(
            0xA0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x03.toByte(), 0x10.toByte(), 0x10.toByte(), 0x01.toByte()
        )

        // أوامر APDU المعروفة
        // أمر SELECT: يُستخدَم لاختيار التطبيق بواسطة AID
        private const val SELECT_APDU_HEADER = 0x00.toByte()

        // أمر GET DATA: يُستخدَم لطلب بيانات الدفع
        private const val GET_PAYMENT_DATA_INS = 0xCA.toByte()

        // ردود APDU القياسية
        // 9000 = نجاح العملية (Success)
        private val APDU_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())

        // 6A82 = الملف أو التطبيق غير موجود (File Not Found)
        private val APDU_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        // 6F00 = خطأ غير معروف (Unknown Error)
        private val APDU_UNKNOWN_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())
    }

    // مدير مخزن المفاتيح لتشفير بيانات الدفع
    private lateinit var keystoreManager: AtheerKeystoreManager

    // نطاق Coroutines المرتبط بدورة حياة الخدمة
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // بيانات الدفع المُعدَّة للإرسال (الرمز المميز)
    // Volatile لضمان رؤية التغييرات عبر الخيوط المتعددة دون حالات تعارض
    @Volatile
    private var preparedPaymentToken: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "جاري تشغيل خدمة HCE لـ Atheer SDK...")
        keystoreManager = AtheerKeystoreManager()
    }

    /**
     * تهيئة بيانات الدفع قبل الاقتراب من جهاز POS
     *
     * يجب استدعاء هذه الدالة من التطبيق المضيف قبل إجراء عملية الدفع.
     * تقوم بتشفير بيانات البطاقة وإعداد الرمز المميز الجاهز للإرسال عبر NFC.
     *
     * @param cardData بيانات البطاقة (رقم البطاقة أو بيانات المسار)
     * @param amount قيمة المعاملة
     * @param currency رمز العملة
     */
    fun preparePayment(cardData: String, amount: Long, currency: String) {
        Log.d(TAG, "جاري تحضير بيانات الدفع للإرسال عبر NFC...")
        serviceScope.launch {
            // توليد Nonce لمنع هجمات إعادة التشغيل
            val nonce = keystoreManager.generateNonce()
            // تشفير بيانات الدفع مع الـ Nonce كجزء من البيانات
            val paymentData = "$cardData|$amount|$currency|$nonce"
            preparedPaymentToken = keystoreManager.tokenize(paymentData)
            Log.i(TAG, "تم تحضير بيانات الدفع بنجاح - Nonce: ${nonce.take(8)}...")
        }
    }

    /**
     * معالجة أوامر APDU الواردة من جهاز POS
     *
     * هذه الدالة هي القلب النابض للخدمة - تُستدعى تلقائياً من نظام Android
     * عند استلام كل أمر APDU من جهاز القارئ.
     *
     * منطق المعالجة:
     * - إذا كان الأمر SELECT → إرسال موافقة (9000)
     * - إذا كان الأمر GET PAYMENT DATA → إرسال الرمز المميز المشفر + 9000
     * - أي أمر آخر → إرسال خطأ (6A82)
     *
     * @param commandApdu بيانات أمر APDU الوارد من جهاز القارئ
     * @param extras بيانات إضافية (غير مستخدمة حالياً)
     * @return بيانات رد APDU المراد إرسالها إلى جهاز القارئ
     */
    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        Log.d(TAG, "استلام أمر APDU: ${commandApdu.toHexString()}")

        // التحقق من أن البيانات الواردة تحتوي على بيانات كافية
        if (commandApdu.size < 4) {
            Log.w(TAG, "تحذير: أمر APDU غير مكتمل - الطول: ${commandApdu.size}")
            return APDU_UNKNOWN_ERROR
        }

        return try {
            when {
                // التحقق من أمر SELECT AID
                isSelectAidCommand(commandApdu) -> {
                    Log.i(TAG, "استلام أمر SELECT AID - الرد بالموافقة")
                    APDU_OK
                }

                // التحقق من أمر طلب بيانات الدفع
                isGetPaymentDataCommand(commandApdu) -> {
                    handleGetPaymentData()
                }

                // أي أمر غير معروف
                else -> {
                    Log.w(TAG, "أمر APDU غير مدعوم: INS = ${commandApdu[1].toUByte()}")
                    APDU_FILE_NOT_FOUND
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء معالجة أمر APDU: ${e.message}", e)
            APDU_UNKNOWN_ERROR
        }
    }

    /**
     * التحقق من أن الأمر هو SELECT AID
     *
     * بنية أمر SELECT: [00][A4][04][00][Lc][AID Data]
     *
     * @param apdu بيانات الأمر
     * @return true إذا كان أمر SELECT، false في غير ذلك
     */
    private fun isSelectAidCommand(apdu: ByteArray): Boolean {
        // CLA = 00, INS = A4, P1 = 04 (SELECT by AID)
        return apdu[0] == 0x00.toByte() &&
                apdu[1] == 0xA4.toByte() &&
                apdu[2] == 0x04.toByte()
    }

    /**
     * التحقق من أن الأمر هو طلب بيانات الدفع
     *
     * @param apdu بيانات الأمر
     * @return true إذا كان أمر GET PAYMENT DATA
     */
    private fun isGetPaymentDataCommand(apdu: ByteArray): Boolean {
        return apdu[0] == SELECT_APDU_HEADER && apdu[1] == GET_PAYMENT_DATA_INS
    }

    /**
     * معالجة طلب بيانات الدفع وإرسال الرمز المميز
     *
     * @return بيانات الرد تتضمن الرمز المميز المشفر + كود النجاح 9000
     */
    private fun handleGetPaymentData(): ByteArray {
        val token = preparedPaymentToken
        return if (token != null) {
            Log.i(TAG, "إرسال بيانات الدفع المشفرة عبر NFC...")
            val tokenBytes = token.toByteArray(Charsets.UTF_8)
            tokenBytes + APDU_OK
        } else {
            Log.w(TAG, "تحذير: لم يتم تحضير بيانات الدفع بعد")
            APDU_FILE_NOT_FOUND
        }
    }

    /**
     * إشعار بانقطاع الاتصال عن بعد
     *
     * تُستدعى هذه الدالة عندما يبتعد الهاتف عن جهاز القارئ أو
     * عند انقطاع الاتصال لأي سبب آخر.
     *
     * @param reason كود سبب الانقطاع:
     *   - DEACTIVATION_LINK_LOSS: ابتعاد الهاتف عن القارئ
     *   - DEACTIVATION_DESELECTED: اختيار تطبيق آخر
     */
    override fun onDeactivated(reason: Int) {
        val reasonText = when (reason) {
            DEACTIVATION_LINK_LOSS -> "انقطاع الإشارة - ابتعاد الجهاز"
            DEACTIVATION_DESELECTED -> "تم إلغاء تحديد التطبيق"
            else -> "سبب غير معروف: $reason"
        }
        Log.i(TAG, "تم إلغاء تفعيل خدمة HCE - السبب: $reasonText")
        // مسح بيانات الدفع المؤقتة لضمان الأمان
        preparedPaymentToken = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "إيقاف خدمة HCE لـ Atheer SDK")
        // إلغاء جميع Coroutines المرتبطة بالخدمة
        serviceScope.cancel()
        preparedPaymentToken = null
    }

    /**
     * دالة مساعدة لتحويل مصفوفة البايتات إلى تمثيل نصي Hex لأغراض التسجيل
     */
    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }
}
