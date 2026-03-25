package com.atheer.sdk.hce

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.atheer.sdk.security.AtheerPaymentSession
import com.atheer.sdk.nfc.AtheerFeedbackUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ## AtheerApduService
 * خدمة محاكاة البطاقة المضيفة (HCE) التي تسمح للجهاز بالعمل كبطاقة دفع لا تلامسية.
 *
 * في النسخة المطورة، لا يتم إرسال أي بيانات إلا إذا كانت الجلسة "مسلحة" (Armed) 
 * عبر المصادقة الحيوية المسبقة (Pre-Auth).
 */
class AtheerApduService : HostApduService() {

    companion object {
        private const val TAG = "AtheerApduService"

        private const val SELECT_APDU_HEADER = 0x00.toByte()
        private const val GET_PAYMENT_DATA_INS = 0xCA.toByte()

        private val APDU_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val APDU_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val APDU_SESSION_NOT_ARMED = byteArrayOf(0x69.toByte(), 0x85.toByte()) // Conditions of use not satisfied
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
                    if (!AtheerPaymentSession.isSessionArmed()) {
                        Log.w(TAG, "تم رفض الطلب: الجلسة غير مفعلة (Biometric Pre-Auth required)")
                        APDU_SESSION_NOT_ARMED
                    } else {
                        handleGetPaymentData()
                    }
                }

                else -> APDU_FILE_NOT_FOUND
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ APDU: ${e.message}")
            APDU_UNKNOWN_ERROR
        }
    }

    /**
     * معالجة طلب بيانات الدفع وتجهيز الرد الذي يحتوي على التوكن والتوقيع.
     */
    private fun handleGetPaymentData(): ByteArray {
        val tokenId = AtheerPaymentSession.getTokenId()
        val signature = AtheerPaymentSession.getSignature()

        return if (tokenId != null && signature != null) {
            // التغذية الراجعة فور النجاح
            AtheerFeedbackUtils.playSuccessFeedback(applicationContext)
            
            // دمج التوكن والتوقيع (Cryptogram) ليرسلا للقارئ
            val payload = "${tokenId}|${signature}"
            val response = payload.toByteArray(Charsets.UTF_8) + APDU_OK
            
            // مسح الجلسة فور الاستخدام (One-Tap logic)
            AtheerPaymentSession.clearSession()
            
            Log.i(TAG, "تم إرسال بيانات الدفع بنجاح عبر NFC")
            response
        } else {
            APDU_SESSION_NOT_ARMED
        }
    }

    private fun isSelectAidCommand(apdu: ByteArray) = 
        apdu.size >= 2 && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte()

    private fun isGetPaymentDataCommand(apdu: ByteArray) = 
        apdu.size >= 2 && apdu[0] == SELECT_APDU_HEADER && apdu[1] == GET_PAYMENT_DATA_INS

    override fun onDeactivated(reason: Int) {
        Log.i(TAG, "إلغاء تفعيل HCE")
    }
}