package com.atheer.sdk.hce

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.atheer.sdk.security.AtheerPaymentSession
import com.atheer.sdk.security.AtheerTokenManager
import com.atheer.sdk.security.AtheerKeystoreManager
import com.atheer.sdk.nfc.AtheerFeedbackUtils
import java.nio.charset.StandardCharsets

/**
 * ## AtheerApduService
 * خدمة محاكاة البطاقة المضيفة (HCE) التي تسمح للجهاز بالعمل كبطاقة دفع لا تلامسية.
 *
 * التحديث: تدعم الآن جلب التوكنات من المخزن المحلي (AtheerTokenManager) وتشفيرها
 * قبل إرسالها عبر NFC لضمان حماية البيانات أثناء النقل.
 */
class AtheerApduService : HostApduService() {

    private val tokenManager by lazy { AtheerTokenManager(applicationContext) }
    private val keystoreManager by lazy { AtheerKeystoreManager() }

    companion object {
        private const val TAG = "AtheerApduService"

        private const val SELECT_APDU_HEADER = 0x00.toByte()
        private const val GET_PAYMENT_DATA_INS = 0xCA.toByte()

        private val APDU_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val APDU_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val APDU_SESSION_NOT_ARMED = byteArrayOf(0x69.toByte(), 0x85.toByte())
        private val APDU_UNKNOWN_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        return try {
            when {
                isSelectAidCommand(commandApdu) -> {
                    Log.i(TAG, "استلام أمر SELECT AID")
                    APDU_OK
                }

                isGetPaymentDataCommand(commandApdu) -> {
                    handleGetPaymentData()
                }

                else -> APDU_FILE_NOT_FOUND
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ APDU: ${e.message}")
            APDU_UNKNOWN_ERROR
        }
    }

    /**
     * معالجة طلب بيانات الدفع.
     * تبحث أولاً في الجلسة المسلحة (Biometric Auth)، وإذا لم تجدها تبحث عن توكن أوفلاين.
     */
    private fun handleGetPaymentData(): ByteArray {
        // 1. محاولة الحصول على بيانات من الجلسة (المصادقة الحيوية)
        val sessionToken = AtheerPaymentSession.getTokenId()
        val sessionSignature = AtheerPaymentSession.getSignature()

        // تحديد التوكن والتوقيع المستخدم
        val (finalTokenId, finalSignature) = if (sessionToken != null && sessionSignature != null) {
            sessionToken to sessionSignature
        } else {
            // 2. إذا لم تكن هناك جلسة نشطة، نحاول جلب توكن "أوفلاين" غير مستخدم
            Log.i(TAG, "لا توجد جلسة نشطة، جاري البحث عن توكن أوفلاين...")
            val offlineToken = tokenManager.consumeNextToken()
            offlineToken to "OFFLINE_AUTH"
        }

        return if (finalTokenId != null) {
            // التغذية الراجعة فور الاستجابة
            AtheerFeedbackUtils.playSuccessFeedback(applicationContext)
            
            // 3. تجهيز الحمولة وتشفيرها باستخدام Keystore لحمايتها أثناء النقل
            val rawPayload = "$finalTokenId|$finalSignature"
            val encryptedPayload = keystoreManager.encrypt(rawPayload)
            
            val response = encryptedPayload.toByteArray(StandardCharsets.UTF_8) + APDU_OK
            
            // مسح الجلسة لضمان الاستخدام لمرة واحدة
            AtheerPaymentSession.clearSession()
            
            Log.i(TAG, "تم تشفير وإرسال بيانات الدفع بنجاح عبر NFC")
            response
        } else {
            Log.w(TAG, "فشل العملية: لا يوجد توكن متوفر أو الجلسة غير مفعلة")
            APDU_SESSION_NOT_ARMED
        }
    }

    private fun isSelectAidCommand(apdu: ByteArray) = 
        apdu.size >= 2 && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte()

    private fun isGetPaymentDataCommand(apdu: ByteArray) = 
        apdu.size >= 2 && apdu[0] == SELECT_APDU_HEADER && apdu[1] == GET_PAYMENT_DATA_INS

    override fun onDeactivated(reason: Int) {
        Log.i(TAG, "إلغاء تفعيل HCE - السبب: $reason")
    }
}