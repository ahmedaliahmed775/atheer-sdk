package com.atheer.sdk.hce

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.atheer.sdk.security.AtheerTokenManager
import android.content.Intent
import com.atheer.sdk.AtheerSdk

/**
 * خدمة HCE (محاكاة البطاقة المضيفة) لـ Atheer SDK
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
        
        // كود الخطأ 6A80 لإخبار التاجر برفض العملية (Wrong Data/Amount Rejected)
        private val APDU_REJECTED_AMOUNT = byteArrayOf(0x6A.toByte(), 0x80.toByte())
    }

    private lateinit var tokenManager: AtheerTokenManager

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "جاري تشغيل خدمة HCE لـ Atheer SDK...")
        tokenManager = AtheerTokenManager(applicationContext)
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        val commandHex = commandApdu.toHexString()
        Log.d(TAG, "استلام أمر APDU: $commandHex")

        if (commandApdu.size < 4) {
            return APDU_UNKNOWN_ERROR
        }

        return try {
            when {
                // 1. التحقق من أمر SELECT AID (بداية عملية التلامس)
                isSelectAidCommand(commandApdu) -> {
                    Log.i(TAG, "استلام أمر SELECT AID - الرد بالموافقة")
                    APDU_OK
                }

                // 2. التحقق من أمر طلب بيانات الدفع (الذي يحتوي على المبلغ)
                isGetPaymentDataCommand(commandApdu) -> {
                    // --- تطبيق الجدار الناري للأمان (Security Firewall) ---
                    
                    // استخراج المبلغ من الأمر القادم من جهاز التاجر
                    val requestedAmount = extractAmountFromApdu(commandApdu)
                    
                    // جلب الحد الذي وضعه العميل من الـ SDK
                    val userLimit = AtheerSdk.getInstance().getNextOfflineLimit()

                    Log.d(TAG, "تحقق الأمان: المبلغ المطلوب = $requestedAmount، الحد المسموح = $userLimit")

                    if (requestedAmount > userLimit) {
                        Log.w(TAG, "تم رفض العملية: المبلغ يتجاوز الحد المسموح")
                        
                        // إرسال Broadcast لتنبيه واجهة التطبيق بالرفض
                        sendBroadcast(Intent("com.atheer.sdk.ACTION_PAYMENT_REJECTED").apply {
                            putExtra("amount", requestedAmount)
                            putExtra("limit", userLimit.toDouble())
                        })
                        
                        APDU_REJECTED_AMOUNT
                    } else {
                        // المبلغ مقبول: مسح الحد (لأنه للاستخدام مرة واحدة) وإرسال التوكن
                        Log.i(TAG, "المبلغ مقبول. جاري توليد توكن الدفع...")
                        AtheerSdk.getInstance().clearOfflineLimit()
                        handleGetPaymentData()
                    }
                }

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

    private fun isSelectAidCommand(apdu: ByteArray): Boolean {
        return apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte() && apdu[2] == 0x04.toByte()
    }

    private fun isGetPaymentDataCommand(apdu: ByteArray): Boolean {
        // نعتبر أمر GET DATA (0xCA) هو طلب الدفع الذي يحمل المبلغ
        return apdu[0] == SELECT_APDU_HEADER && apdu[1] == GET_PAYMENT_DATA_INS
    }

    /**
     * استخراج المبلغ من بيانات APDU
     * يفترض هذا المثال أن المبلغ موجود في البيانات الإضافية للأمر (Payload)
     */
    private fun extractAmountFromApdu(apdu: ByteArray): Double {
        return try {
            // منطق استخراج المبلغ: يعتمد على بروتوكول التاجر
            // مثال: إذا كان المبلغ مشفراً في البايتات من 5 إلى 8
            if (apdu.size >= 9) {
                val amountCents = ((apdu[5].toInt() and 0xFF) shl 24) or
                                 ((apdu[6].toInt() and 0xFF) shl 16) or
                                 ((apdu[7].toInt() and 0xFF) shl 8) or
                                 (apdu[8].toInt() and 0xFF)
                amountCents / 100.0
            } else {
                0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }

    private fun handleGetPaymentData(): ByteArray {
        val token = tokenManager.consumeNextToken()
        return if (token != null) {
            val intent = Intent("com.atheer.sdk.ACTION_NFC_TAP_SUCCESS")
            sendBroadcast(intent)

            val tokenBytes = token.toByteArray(Charsets.UTF_8)
            tokenBytes + APDU_OK
        } else {
            APDU_NO_TOKENS
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.i(TAG, "تم إلغاء تفعيل خدمة HCE - السبب: $reason")
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }
}
