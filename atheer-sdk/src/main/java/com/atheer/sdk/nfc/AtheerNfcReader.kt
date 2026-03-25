package com.atheer.sdk.nfc

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.atheer.sdk.model.AtheerTransaction
import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.security.AtheerKeystoreManager
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
 */
class AtheerNfcReader(
    private val context: Context,
    private val merchantId: String, // معرف التاجر المستلم
    private val receiverAccount: String, // رقم هاتف التاجر أو POS
    private val amount: Long,
    private val transactionType: String = "P2M",
    private val transactionCallback: (ChargeRequest) -> Unit, // تم تغيير المخرجات لـ ChargeRequest
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

            // 2. طلب بيانات الدفع
            val paymentResponse = isoDep.transceive(GET_PAYMENT_DATA_APDU)
            if (!isResponseOk(paymentResponse)) throw Exception("فشل استلام البيانات")

            // 3. استخراج البيانات المشفرة
            val encryptedDataBytes = paymentResponse.copyOfRange(0, paymentResponse.size - 2)
            val encryptedPayload = String(encryptedDataBytes, StandardCharsets.UTF_8)

            // 4. بناء طلب الدفع الجديد المتوافق مع المحافظ اليمنية والمقسم
            val chargeRequest = ChargeRequest(
                amount = amount,
                currency = "YER",
                merchantId = merchantId,
                receiverAccount = receiverAccount,
                transactionRef = "REF_${System.currentTimeMillis()}", // توليد مرجع فريد
                transactionType = transactionType,
                atheerToken = encryptedPayload, // الحمولة المشفرة (توكن + توقيع)
                signature = "NFC_SECURE_PAYLOAD",
                description = "عملية دفع عبر أثير SDK"
            )

            withContext(Dispatchers.Main) {
                AtheerFeedbackUtils.playSuccessFeedback(context)
                transactionCallback(chargeRequest)
            }

        } catch (e: Exception) {
            Log.e(TAG, "خطأ NFC: ${e.message}")
            withContext(Dispatchers.Main) { errorCallback(e) }
        } finally {
            try { isoDep.close() } catch (e: Exception) {}
        }
    }

    private fun isResponseOk(response: ByteArray) = 
        response.size >= 2 && response[response.size - 2] == STATUS_OK[0] && response[response.size - 1] == STATUS_OK[1]
}