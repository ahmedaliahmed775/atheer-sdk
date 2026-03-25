package com.atheer.sdk.nfc

import android.content.Context
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
import java.nio.charset.StandardCharsets

/**
 * ## AtheerNfcReader
 * محرك قراءة بطاقات الـ NFC المتوافق مع معايير EMVCo لنظام SoftPOS.
 * 
 * يقوم هذا الكلاس بإرسال أوامر APDU لاستخراج بيانات الدفع المشفرة من هاتف العميل،
 * وتغليفها في كائن معاملة جاهز للإرسال إلى المقسم (Switch).
 */
class AtheerNfcReader(
    private val context: Context,
    private val receiverAccount: String,
    private val amount: Long,
    private val transactionType: String = "P2M",
    private val transactionCallback: (AtheerTransaction) -> Unit,
    private val errorCallback: (Exception) -> Unit
) : NfcAdapter.ReaderCallback {

    companion object {
        private const val TAG = "AtheerNfcReader"
        private const val MAX_RELAY_RTT_MS = 150L // الحد الأقصى لزمن الاستجابة

        // المعرف الخاص بتطبيق أثير (AID)
        private val ATHEER_AID = byteArrayOf(
            0xA0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x03.toByte(), 0x10.toByte(), 0x10.toByte(), 0x01.toByte()
        )

        // أمر اختيار التطبيق (SELECT AID)
        private val SELECT_APDU = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
            ATHEER_AID.size.toByte()
        ) + ATHEER_AID

        // أمر جلب بيانات الدفع (GET DATA)
        private val GET_PAYMENT_DATA_APDU = byteArrayOf(
            0x00.toByte(), 0xCA.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte()
        )

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
        val isoDep = IsoDep.get(tag) ?: run {
            withContext(Dispatchers.Main) { errorCallback(Exception("الجهاز لا يدعم بروتوكول ISO-DEP المالي")) }
            return@withContext
        }

        try {
            isoDep.connect()
            isoDep.timeout = TIMEOUT_MS

            // 1. اختيار تطبيق أثير
            val selectResponse = isoDep.transceive(SELECT_APDU)
            if (!isResponseOk(selectResponse)) {
                throw Exception("تطبيق أثير غير متوفر على جهاز العميل")
            }

            // 2. طلب بيانات الدفع المشفرة (Cryptogram)
            val startTime = System.currentTimeMillis()
            val paymentResponse = isoDep.transceive(GET_PAYMENT_DATA_APDU)
            val rtt = System.currentTimeMillis() - startTime

            // حماية ضد هجمات الـ Relay
            if (rtt > MAX_RELAY_RTT_MS) {
                throw SecurityException("تم رفض العملية: زمن الاستجابة مرتفع جداً ($rtt ms)")
            }

            if (!isResponseOk(paymentResponse)) {
                throw Exception("فشل استلام بيانات الدفع من العميل")
            }

            // 3. استخراج الحمولة المشفرة
            val encryptedDataBytes = paymentResponse.copyOfRange(0, paymentResponse.size - 2)
            val encryptedPayload = String(encryptedDataBytes, StandardCharsets.UTF_8)

            Log.d(TAG, "تم استلام البيانات المشفرة بنجاح")

            // إنشاء كائن المعاملة (البيانات المشفرة ستفك في المقسم)
            val transaction = AtheerTransaction(
                transactionId = "TXN_${System.currentTimeMillis()}",
                amount = amount,
                currency = "YER",
                receiverAccount = receiverAccount,
                transactionType = transactionType,
                atheerToken = encryptedPayload, // التوكن والتوقيع المشفرين
                signature = "ENCRYPTED_NFC_PAYLOAD",
                authMethod = "BIOMETRIC_CRYPTOGRAM"
            )

            withContext(Dispatchers.Main) {
                AtheerFeedbackUtils.playSuccessFeedback(context)
                transactionCallback(transaction)
            }

        } catch (e: Exception) {
            Log.e(TAG, "خطأ في معالجة NFC: ${e.message}")
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