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

import com.atheer.sdk.security.AtheerKeystoreManager
import com.atheer.sdk.nfc.AtheerFeedbackUtils
import java.nio.charset.StandardCharsets

/**
 * ## AtheerApduService
 * خدمة محاكاة البطاقة المضيفة (HCE) التي تسمح للجهاز بالعمل كبطاقة دفع لا تلامسية.
 * 
 * التحديث: يدعم جلب التوكنات المرتبطة برقم الهاتف وتنبيه المستخدم بإرسال البيانات.
 */
class AtheerApduService : HostApduService() {


    private val keystoreManager by lazy { AtheerKeystoreManager(applicationContext) }

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
     */
    private fun handleGetPaymentData(): ByteArray {
        val sessionPayload = AtheerPaymentSession.getPayload()
        val sessionSignature = AtheerPaymentSession.getSignature()

        val (finalPayload, finalSignature) = if (sessionPayload != null && sessionSignature != null) {
            sessionPayload to sessionSignature
        } else {
            Log.e(TAG, "لا توجد جلسة دفع نشطة!")
            null to null
        }

        return if (finalPayload != null) {
            // التغذية الراجعة والاهتزاز
            AtheerFeedbackUtils.playSuccessFeedback(applicationContext)
            
            // إظهار إشعار محلي للمستخدم
            showPaymentSentNotification()
            
            // تشفير البيانات وإرسالها
            val rawPayload = "$finalPayload|$finalSignature"
            val encryptedPayload = keystoreManager.encrypt(rawPayload)
            
            val response = encryptedPayload.toByteArray(StandardCharsets.UTF_8) + APDU_OK
            
            AtheerPaymentSession.clearSession()
            Log.i(TAG, "تم إرسال بيانات الدفع بنجاح عبر NFC")
            response
        } else {
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