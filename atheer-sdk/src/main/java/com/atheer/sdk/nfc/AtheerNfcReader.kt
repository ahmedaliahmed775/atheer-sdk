package com.atheer.sdk.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.atheer.sdk.model.AtheerTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * وحدة قارئ NFC لنظام SoftPOS مع حماية من هجمات التمرير (Relay Attack Mitigation)
 */
class AtheerNfcReader(
    private val merchantId: String,
    private val transactionCallback: (AtheerTransaction) -> Unit,
    private val errorCallback: (Exception) -> Unit
) : NfcAdapter.ReaderCallback {

    companion object {
        private const val TAG = "AtheerNfcReader"
        
        // 🛡️ حد الأمان لهجمات التمرير: 50 ملي ثانية كأقصى حد لزمن الاستجابة (RTT)
        // أي تأخير إضافي يشير إلى احتمال تمرير الإشارة عبر الإنترنت (Relay Attack)
        private const val MAX_RELAY_RTT_MS = 50L

        private val ATHEER_AID = byteArrayOf(
            0xA0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x03.toByte(), 0x10.toByte(), 0x10.toByte(), 0x01.toByte()
        )

        private val SELECT_APDU = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            ATHEER_AID.size.toByte()
        ) + ATHEER_AID

        private val GET_PAYMENT_DATA_APDU = byteArrayOf(
            0x00.toByte(), 0xCA.toByte(), 0x00.toByte(), 0x01.toByte(), 0xFF.toByte()
        )

        private val STATUS_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private const val TIMEOUT_MS = 5000
    }

    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTagDiscovered(tag: Tag?) {
        Log.i(TAG, "تم اكتشاف بطاقة NFC جديدة...")
        tag ?: return
        readerScope.launch { readNfcTag(tag) }
    }

    private suspend fun readNfcTag(tag: Tag) = withContext(Dispatchers.IO) {
        val isoDep = IsoDep.get(tag) ?: run {
            errorCallback(Exception("ISO-DEP غير مدعوم"))
            return@withContext
        }

        try {
            isoDep.connect()
            isoDep.timeout = TIMEOUT_MS

            // 🕒 قياس زمن الاستجابة (RTT) لاكتشاف هجمات التمرير
            val startTime = System.currentTimeMillis()
            
            // الخطوة 1: إرسال SELECT AID
            val selectResponse = isoDep.transceive(SELECT_APDU)
            
            val rtt = System.currentTimeMillis() - startTime
            Log.d(TAG, "زمن الاستجابة لـ SELECT AID: $rtt ملي ثانية")

            // 🛡️ فحص حماية التمرير
            if (rtt > MAX_RELAY_RTT_MS) {
                Log.e(TAG, "🛡️ تم اكتشاف محاولة هجوم تمرير (Relay Attack)! RTT=$rtt ms > $MAX_RELAY_RTT_MS ms")
                isoDep.close()
                withContext(Dispatchers.Main) {
                    errorCallback(SecurityException("انتهاك أمني: تم اكتشاف محاولة تمرير غير مصرح بها للاتصال"))
                }
                return@withContext
            }

            if (!isResponseOk(selectResponse)) {
                errorCallback(Exception("تطبيق Atheer غير موجود على الجهاز المجاور"))
                return@withContext
            }

            // الخطوة 2: طلب بيانات الدفع
            val paymentResponse = isoDep.transceive(GET_PAYMENT_DATA_APDU)

            if (!isResponseOk(paymentResponse)) {
                errorCallback(Exception("فشل في استقبال بيانات الدفع"))
                return@withContext
            }

            val paymentData = paymentResponse.copyOfRange(0, paymentResponse.size - 2)
            val paymentToken = String(paymentData, Charsets.UTF_8)

            val transaction = AtheerTransaction(
                transactionId = generateTransactionId(),
                amount = 0L,
                currency = "SAR",
                merchantId = merchantId,
                tokenizedCard = paymentToken,
                nonce = "RECEIVED"
            )

            withContext(Dispatchers.Main) { transactionCallback(transaction) }

        } catch (e: Exception) {
            Log.e(TAG, "خطأ NFC: ${e.message}")
            withContext(Dispatchers.Main) { errorCallback(e) }
        } finally {
            if (isoDep.isConnected) isoDep.close()
        }
    }

    private fun isResponseOk(response: ByteArray): Boolean {
        if (response.size < 2) return false
        return response[response.size - 2] == STATUS_OK[0] &&
                response[response.size - 1] == STATUS_OK[1]
    }

    private fun generateTransactionId(): String = "TXN_${System.currentTimeMillis()}_${(1000..9999).random()}"

    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }
}
