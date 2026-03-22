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
 *
 * يعتمد الكلاس على بروتوكول ISO-DEP لتبادل وحدات بيانات بروتوكول التطبيق (APDU) مع البطاقة.
 * يتضمن آليات حماية من هجمات التمرير (Relay Attacks) عبر قياس زمن الاستجابة (RTT).
 *
 * @property merchantId معرف التاجر الفريد المرتبط بالعملية.
 * @property amount المبلغ المطلوب معالجته في العملية.
 * @property transactionCallback دالة استدعاء راجعة يتم تنفيذها عند نجاح قراءة البيانات وتكوين المعاملة.
 * @property errorCallback دالة استدعاء راجعة يتم تنفيذها عند حدوث خطأ أثناء عملية القراءة.
 */
class AtheerNfcReader(
    private val merchantId: String,
    private val amount: Long,
    private val transactionCallback: (AtheerTransaction) -> Unit,
    private val errorCallback: (Exception) -> Unit
) : NfcAdapter.ReaderCallback {

    companion object {
        private const val TAG = "AtheerNfcReader"
        
        // 🛡️ حد الأمان الصارم (L1 Level): 50ms تضمن أن البطاقة موجودة فعلياً أمام الجهاز.
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

    /**
     * يتم استدعاؤها بواسطة نظام أندرويد عند اكتشاف بطاقة NFC بالقرب من الحساس.
     * تقوم ببدء معالجة البطاقة في سياق Coroutine منفصل لضمان عدم حظر واجهة المستخدم.
     *
     * @param tag كائن البطاقة المكتشفة.
     */
    override fun onTagDiscovered(tag: Tag?) {
        tag ?: return
        Log.i(TAG, "تم اكتشاف بطاقة. بدء المصافحة الأمنية...")
        readerScope.launch { readNfcTag(tag) }
    }

    /**
     * الوظيفة الأساسية لقراءة بيانات البطاقة عبر بروتوكول APDU.
     *
     * منطق العمل:
     * 1. الاتصال بالبطاقة باستخدام تقنية IsoDep.
     * 2. إرسال أمر SELECT APDU لاختيار تطبيق Atheer AID على البطاقة.
     * 3. قياس زمن الاستجابة (RTT) للتحقق من عدم وجود هجوم تمرير (Relay Attack).
     * 4. إرسال أمر GET_PAYMENT_DATA لجلب بيانات الدفع المشفرة.
     * 5. تحويل البيانات المستلمة إلى كائن [AtheerTransaction].
     *
     * @param tag كائن البطاقة المراد قراءتها.
     */
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

            // 🛡️ التحقق من هجوم التمرير (Relay Attack Check)
            if (rtt > MAX_RELAY_RTT_MS) {
                throw SecurityException("انتهاك أمني: زمن الاستجابة ($rtt ms) يتجاوز الحد المسموح. احتمال وجود هجوم تمرير.")
            }

            if (!isResponseOk(selectResponse)) {
                throw Exception("تطبيق الدفع (Atheer AID) غير مدعوم في هذه البطاقة")
            }

            // طلب بيانات الدفع المشفرة من البطاقة
            val paymentResponse = isoDep.transceive(GET_PAYMENT_DATA_APDU)

            if (!isResponseOk(paymentResponse)) {
                throw Exception("فشل استخراج بيانات الدفع الآمنة")
            }

            val paymentData = paymentResponse.copyOfRange(0, paymentResponse.size - 2)
            val paymentToken = String(paymentData, Charsets.UTF_8)

            val transaction = AtheerTransaction(
                transactionId = "TXN_${System.currentTimeMillis()}",
                amount = amount, // استخدام المبلغ الفعلي الممرر
                currency = "SAR",
                merchantId = merchantId,
                tokenizedCard = paymentToken,
                nonce = "SECURE_TAP"
            )

            withContext(Dispatchers.Main) { transactionCallback(transaction) }

        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء معالجة البطاقة: ${e.message}")
            withContext(Dispatchers.Main) { errorCallback(e) }
        } finally {
            try { isoDep.close() } catch (e: Exception) { /* Ignore */ }
        }
    }

    /**
     * يتحقق مما إذا كان رد البطاقة يشير إلى نجاح العملية (Status Word = 9000).
     *
     * @param response مصفوفة البايتات المستلمة من البطاقة.
     * @return صحيح إذا كان الرد ناجحاً، خطأ خلاف ذلك.
     */
    private fun isResponseOk(response: ByteArray): Boolean {
        return response.size >= 2 && 
               response[response.size - 2] == STATUS_OK[0] &&
               response[response.size - 1] == STATUS_OK[1]
    }
}
