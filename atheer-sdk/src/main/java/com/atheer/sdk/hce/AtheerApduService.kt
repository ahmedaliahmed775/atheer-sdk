package com.atheer.sdk.hce

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.nfc.cardemulation.HostApduService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.atheer.sdk.security.AtheerPaymentSession

import com.atheer.sdk.nfc.AtheerFeedbackUtils
import java.nio.charset.StandardCharsets

/**
 * ## AtheerApduService
 * خدمة محاكاة البطاقة المضيفة (HCE) التي تسمح للجهاز بالعمل كبطاقة دفع لا تلامسية.
 * * التحديث: يتوافق مع معمارية Zero-Trust بحيث يتم إرسال البيانات الموقعة رقمياً
 * مباشرة دون الحاجة لتشفير إضافي غير ضروري.
 */
class AtheerApduService : HostApduService() {

    companion object {
        private const val TAG = "AtheerApduService"
        private const val CHANNEL_ID = "atheer_payment_channel"
        private const val NOTIFICATION_ID = 101

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
     * معالجة طلب بيانات الدفع وتنبيه العميل.
     *
     * Fix #4: تم استبدال القراءة المنفصلة (getPayload + getSignature + clearSession)
     * بعملية ذرية واحدة (consumeSession) لمنع Race Conditions بين خيوط NFC المتزامنة.
     */
    private fun handleGetPaymentData(): ByteArray {
        // عملية ذرية: قراءة + مسح في نفس اللحظة (Thread-Safe)
        val session = AtheerPaymentSession.consumeSession()

        return if (session != null) {
            val (payload, signature) = session

            // التغذية الراجعة والاهتزاز
            AtheerFeedbackUtils.playSuccessFeedback(applicationContext)

            // إظهار إشعار محلي للمستخدم
            showPaymentSentNotification()

            // دمج البيانات الموقعة رقمياً وإرسالها
            val rawPayload = "$payload|$signature"
            val response = rawPayload.toByteArray(StandardCharsets.UTF_8) + APDU_OK

            Log.i(TAG, "تم إرسال بيانات الدفع بنجاح عبر NFC")
            response
        } else {
            Log.e(TAG, "لا توجد جلسة دفع نشطة!")
            APDU_SESSION_NOT_ARMED
        }
    }

    private fun showPaymentSentNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "مدفوعات أثير", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("دفع ناجح")
            .setContentText("تم إرسال بيانات الدفع للتاجر بنجاح، يرجى انتظار التأكيد")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun isSelectAidCommand(apdu: ByteArray) = 
        apdu.size >= 2 && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte()

    private fun isGetPaymentDataCommand(apdu: ByteArray) = 
        apdu.size >= 2 && apdu[0] == SELECT_APDU_HEADER && apdu[1] == GET_PAYMENT_DATA_INS

    override fun onDeactivated(reason: Int) {}
}
