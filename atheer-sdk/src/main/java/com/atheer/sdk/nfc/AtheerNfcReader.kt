package com.atheer.sdk.nfc

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.atheer.sdk.AtheerSdk
import com.atheer.sdk.model.ChargeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * ## AtheerNfcReader
 * محرك قراءة بطاقات الـ NFC لنظام SoftPOS المتوافق مع متطلبات المحافظ اليمنية.
 * 
 * يقوم هذا الكلاس باستلام البيانات المشفرة وبناء كائن [ChargeRequest] متكامل للمقسم.
 * تم تحديثه ليدعم العملات المتغيرة وأرقام المرجع الفريدة.
 */
internal class AtheerNfcReader(
    private val context: Context,
    private val merchantId: String, // معرف التاجر المستلم
    private val receiverAccount: String, // رقم هاتف التاجر أو POS
    private val amount: Long,
    private val currency: String = "YER", // العملة المختارة من واجهة المستخدم
    private val transactionType: String = "P2M",
    private val transactionCallback: (ChargeRequest) -> Unit,
    private val errorCallback: (Exception) -> Unit
) : NfcAdapter.ReaderCallback {

    companion object {
        private const val TAG = "AtheerNfcReader"
        private val ATHEER_AID = byteArrayOf(0xA0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x03.toByte(), 0x10.toByte(), 0x10.toByte(), 0x01.toByte())
        private val SELECT_APDU = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), ATHEER_AID.size.toByte()) + ATHEER_AID
        private val GET_PAYMENT_DATA_APDU = byteArrayOf(0x00.toByte(), 0xCA.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte())
        private val STATUS_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private const val TIMEOUT_MS = 5000
    }

    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTagDiscovered(tag: Tag?) {
        tag ?: return
        Log.i(TAG, "تم اكتشاف جهاز عميل. بدء سحب البيانات...")
        readerScope.launch { readNfcTag(tag) }
    }

    private suspend fun readNfcTag(tag: Tag) = withContext(Dispatchers.IO) {
        val isoDep = IsoDep.get(tag) ?: return@withContext

        try {
            isoDep.connect()
            isoDep.timeout = TIMEOUT_MS

            // 1. اختيار تطبيق أثير
            val selectResponse = isoDep.transceive(SELECT_APDU)
            if (!isResponseOk(selectResponse)) throw Exception("تطبيق أثير غير متوفر")

            // 2. قياس Round-Trip Time (RTT) لمنع Relay Attacks
            // نستخدم SystemClock.elapsedRealtime() لدقة أعلى ومقاومة للتلاعب بالوقت
            val startTime = android.os.SystemClock.elapsedRealtime()
            
            // طلب بيانات الدفع (هذا الطلب هو الذي نقيس زمن استجابته)
            val paymentResponse = isoDep.transceive(GET_PAYMENT_DATA_APDU)
            
            val endTime = android.os.SystemClock.elapsedRealtime()
            val rtt = endTime - startTime
            
            Log.d(TAG, "NFC RTT: ${rtt}ms")
            
            // إذا تجاوز الـ RTT 50ms، نعتبره هجوم ترحيل (Relay Attack)
            if (rtt > 50) {
                Log.e(TAG, "تم اكتشاف محاولة Relay Attack! RTT: ${rtt}ms")
                throw RelayAttackException("فشل التحقق من المسافة الآمنة (RTT: ${rtt}ms)")
            }

            if (!isResponseOk(paymentResponse)) throw Exception("فشل استلام البيانات")

            // 3. استخراج البيانات المشفرة
            val encryptedDataBytes = paymentResponse.copyOfRange(0, paymentResponse.size - 2)
            val encryptedPayload = String(encryptedDataBytes, StandardCharsets.UTF_8)

            // 4. بناء طلب الدفع عبر دالة التحليل المركزية
            val chargeRequest = AtheerSdk.getInstance()
                .parseNfcDataToRequest(encryptedPayload, amount.toDouble(), receiverAccount, transactionType)
                .copy(
                    transactionRef = "REF_${android.os.SystemClock.elapsedRealtime()}",
                    merchantId = merchantId,
                    description = "عملية دفع عبر أثير SDK - SoftPOS"
                )

            withContext(Dispatchers.Main) {
                AtheerFeedbackUtils.playSuccessFeedback(context)
                transactionCallback(chargeRequest)
            }

        } catch (e: RelayAttackException) {
            Log.e(TAG, "Relay Attack Detected: ${e.message}")
            withContext(Dispatchers.Main) { errorCallback(e) }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ NFC: ${e.message}")
            withContext(Dispatchers.Main) { errorCallback(e) }
        } finally {
            try { isoDep.close() } catch (e: Exception) {}
        }
    }

    /**
     * استثناء مخصص لحالات هجوم الترحيل.
     */
    class RelayAttackException(message: String) : Exception(message)

    private fun isResponseOk(response: ByteArray) = 
        response.size >= 2 && response[response.size - 2] == STATUS_OK[0] && response[response.size - 1] == STATUS_OK[1]
}