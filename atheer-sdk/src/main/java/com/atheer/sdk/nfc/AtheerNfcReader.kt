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
 * ## AtheerNfcReader
 * محرك قراءة بطاقات الـ NFC المتوافق مع معايير EMVCo لنظام SoftPOS.
 */
class AtheerNfcReader(
    private val receiverAccount: String, // تم تغيير المسمى ليتوافق مع النموذج الجديد
    private val amount: Long,
    private val transactionType: String = "PURCHASE",
    private val transactionCallback: (AtheerTransaction) -> Unit,
    private val errorCallback: (Exception) -> Unit
) : NfcAdapter.ReaderCallback {

    companion object {
        private const val TAG = "AtheerNfcReader"
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
        tag ?: return
        Log.i(TAG, "تم اكتشاف بطاقة. بدء المصافحة الأمنية...")
        readerScope.launch { readNfcTag(tag) }
    }

    private suspend fun readNfcTag(tag: Tag) = withContext(Dispatchers.IO) {
        val isoDep = IsoDep.get(tag) ?: run {
            withContext(Dispatchers.Main) { errorCallback(Exception("البطاقة لا تدعم بروتوكول ISO-DEP المالي")) }
            return@withContext
        }

        try {
            isoDep.connect()
            isoDep.timeout = TIMEOUT_MS

            val startTime = System.currentTimeMillis()
            val selectResponse = isoDep.transceive(SELECT_APDU)
            val rtt = System.currentTimeMillis() - startTime

            if (rtt > MAX_RELAY_RTT_MS) {
                throw SecurityException("انتهاك أمني: زمن الاستجابة ($rtt ms) يتجاوز الحد المسموح.")
            }

            if (!isResponseOk(selectResponse)) {
                throw Exception("تطبيق الدفع (Atheer AID) غير مدعوم")
            }

            val paymentResponse = isoDep.transceive(GET_PAYMENT_DATA_APDU)

            if (!isResponseOk(paymentResponse)) {
                throw Exception("فشل استخراج بيانات الدفع")
            }

            val paymentData = paymentResponse.copyOfRange(0, paymentResponse.size - 2)
            val payload = String(paymentData, Charsets.UTF_8)
            
            // تقسيم الحمولة المستلمة (tokenId|signature)
            val parts = payload.split("|")
            if (parts.size < 2) {
                throw Exception("بيانات الدفع المستلمة غير صالحة")
            }

            val tokenId = parts[0]
            val signature = parts[1]

            val transaction = AtheerTransaction(
                transactionId = "TXN_${System.currentTimeMillis()}",
                amount = amount,
                currency = "SAR",
                receiverAccount = receiverAccount,
                transactionType = transactionType,
                atheerToken = tokenId,
                signature = signature
            )

            withContext(Dispatchers.Main) { transactionCallback(transaction) }

        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء معالجة البطاقة: ${e.message}")
            withContext(Dispatchers.Main) { errorCallback(e) }
        } finally {
            try { isoDep.close() } catch (e: Exception) { /* Ignore */ }
        }
    }

    private fun isResponseOk(response: ByteArray): Boolean {
        return response.size >= 2 && 
               response[response.size - 2] == STATUS_OK[0] &&
               response[response.size - 1] == STATUS_OK[1]
    }
}