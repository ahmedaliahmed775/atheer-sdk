package com.atheer.sdk.hce

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.atheer.sdk.security.AtheerTokenManager
import android.content.Intent
import com.atheer.sdk.AtheerSdk
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * خدمة HCE (محاكاة البطاقة المضيفة) لـ Atheer SDK مع تطبيق Memory Wiping
 */
class AtheerApduService : HostApduService() {

    companion object {
        private const val TAG = "AtheerApduService"

        private const val SELECT_APDU_HEADER = 0x00.toByte()
        private const val GET_PAYMENT_DATA_INS = 0xCA.toByte()

        private val APDU_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val APDU_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val APDU_NO_TOKENS = byteArrayOf(0x6A.toByte(), 0x83.toByte())
        private val APDU_UNKNOWN_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        private val APDU_REJECTED_AMOUNT = byteArrayOf(0x6A.toByte(), 0x80.toByte())
    }

    private lateinit var tokenManager: AtheerTokenManager
    
    // مصفوفات لتخزين البيانات الحساسة بشكل مؤقت لمسحها لاحقاً
    private var sensitiveTokenBuffer: ByteArray? = null
    private var sensitiveAmountBuffer: DoubleArray = DoubleArray(1)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "جاري تشغيل خدمة HCE لـ Atheer SDK...")
        tokenManager = AtheerTokenManager(applicationContext)
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        return try {
            when {
                isSelectAidCommand(commandApdu) -> {
                    Log.i(TAG, "استلام أمر SELECT AID")
                    APDU_OK
                }

                isGetPaymentDataCommand(commandApdu) -> {
                    // استخراج المبلغ وحفظه في مصفوفة قابلة للمسح
                    sensitiveAmountBuffer[0] = extractAmountFromApdu(commandApdu)
                    val userLimit = AtheerSdk.getInstance().getNextOfflineLimit()

                    if (sensitiveAmountBuffer[0] > userLimit) {
                        Log.w(TAG, "تم رفض العملية: المبلغ يتجاوز الحد")
                        sendBroadcast(Intent("com.atheer.sdk.ACTION_PAYMENT_REJECTED").apply {
                            putExtra("amount", sensitiveAmountBuffer[0])
                            putExtra("limit", userLimit.toDouble())
                        })
                        APDU_REJECTED_AMOUNT
                    } else {
                        AtheerSdk.getInstance().clearOfflineLimit()
                        handleGetPaymentData()
                    }
                }

                else -> APDU_FILE_NOT_FOUND
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ APDU: ${e.message}")
            APDU_UNKNOWN_ERROR
        } finally {
            // مسح الذاكرة الحساسة فور انتهاء معالجة الأمر
            clearSensitiveData()
        }
    }

    /**
     * مسح البيانات الحساسة من الذاكرة (Memory Wiping)
     */
    private fun clearSensitiveData() {
        sensitiveTokenBuffer?.fill(0)
        sensitiveTokenBuffer = null
        sensitiveAmountBuffer.fill(0.0)
    }

    private fun handleGetPaymentData(): ByteArray {
        val tokenStr = tokenManager.consumeNextToken()
        return if (tokenStr != null) {
            sendBroadcast(Intent("com.atheer.sdk.ACTION_NFC_TAP_SUCCESS"))
            
            // تحويل الرمز لمصفوفة بايتات للتعامل المباشر
            sensitiveTokenBuffer = tokenStr.toByteArray(Charsets.UTF_8)
            val response = sensitiveTokenBuffer!! + APDU_OK
            
            // مسح السلسلة النصية الأصلية (قدر الإمكان في JVM)
            response
        } else {
            APDU_NO_TOKENS
        }
    }

    private fun isSelectAidCommand(apdu: ByteArray) = 
        apdu.size >= 2 && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte()

    private fun isGetPaymentDataCommand(apdu: ByteArray) = 
        apdu.size >= 2 && apdu[0] == SELECT_APDU_HEADER && apdu[1] == GET_PAYMENT_DATA_INS

    private fun extractAmountFromApdu(apdu: ByteArray): Double {
        if (apdu.size < 9) return 0.0
        val buffer = ByteBuffer.wrap(apdu, 5, 4).order(ByteOrder.BIG_ENDIAN)
        return buffer.int / 100.0
    }

    override fun onDeactivated(reason: Int) {
        Log.i(TAG, "إلغاء تفعيل HCE - مسح نهائي للذاكرة")
        clearSensitiveData()
    }
}
