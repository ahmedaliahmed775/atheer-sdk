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
 * وحدة قارئ NFC لنظام SoftPOS في Atheer SDK
 *
 * تحول هذه الفئة الهاتف إلى جهاز نقطة بيع (POS Terminal) بدلاً من البطاقة.
 * يُستخدَم هذا النمط في نظام SoftPOS حيث يكون الهاتف هو القارئ الذي يستقبل
 * بيانات الدفع من بطاقة NFC أخرى أو من هاتف آخر يعمل بوضع HCE.
 *
 * الفرق بين HCE و SoftPOS:
 * - HCE (AtheerApduService): الهاتف يحاكي البطاقة (يُرسِل البيانات)
 * - SoftPOS (AtheerNfcReader): الهاتف يحاكي نقطة البيع (يستقبل البيانات)
 *
 * بروتوكول الاتصال:
 * 1. اكتشاف بطاقة NFC في النطاق
 * 2. إنشاء اتصال IsoDep (ISO 14443-4)
 * 3. إرسال SELECT AID لاختيار تطبيق Atheer
 * 4. طلب بيانات الدفع
 * 5. استقبال ومعالجة الرمز المميز
 *
 * @param merchantId معرف التاجر الذي سيُنسَب إليه الدفع
 * @param transactionCallback دالة رد النداء عند اكتمال قراءة المعاملة
 * @param errorCallback دالة رد النداء عند حدوث خطأ
 */
class AtheerNfcReader(
    private val merchantId: String,
    private val transactionCallback: (AtheerTransaction) -> Unit,
    private val errorCallback: (Exception) -> Unit
) : NfcAdapter.ReaderCallback {

    companion object {
        private const val TAG = "AtheerNfcReader"

        // AID الخاص بـ Atheer SDK - يجب أن يتطابق مع ما في apduservice.xml
        private val ATHEER_AID = byteArrayOf(
            0xA0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x03.toByte(), 0x10.toByte(), 0x10.toByte(), 0x01.toByte()
        )

        // بناء أمر SELECT AID وفق بروتوكول ISO 7816-4
        // الهيكل: [00][A4][04][00][طول AID][AID]
        private val SELECT_APDU = byteArrayOf(
            0x00.toByte(), // CLA - فئة الأمر
            0xA4.toByte(), // INS - تعليمة SELECT
            0x04.toByte(), // P1 - SELECT by AID
            0x00.toByte(), // P2 - First or only occurrence
            ATHEER_AID.size.toByte() // Lc - طول AID
        ) + ATHEER_AID

        // أمر طلب بيانات الدفع
        private val GET_PAYMENT_DATA_APDU = byteArrayOf(
            0x00.toByte(), // CLA
            0xCA.toByte(), // INS - GET DATA
            0x00.toByte(), // P1
            0x01.toByte(), // P2 - Payment Data Tag
            0xFF.toByte()  // Le - طلب أقصى حجم بيانات ممكن
        )

        // كود نجاح APDU
        private val STATUS_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())

        // مهلة انتظار الاتصال بالملي ثانية
        private const val TIMEOUT_MS = 5000
    }

    // نطاق Coroutines لتنفيذ عمليات NFC في الخلفية
    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * دالة رد النداء التي تُستدعى تلقائياً عند اكتشاف بطاقة NFC
     *
     * تُنفِّذ هذه الدالة بروتوكول APDU الكامل لاستقبال بيانات الدفع:
     * 1. إنشاء اتصال IsoDep مع البطاقة
     * 2. إرسال SELECT AID
     * 3. التحقق من استجابة SELECT
     * 4. طلب واستقبال بيانات الدفع
     * 5. بناء كائن AtheerTransaction وإرسال النتيجة للتطبيق
     *
     * @param tag علامة NFC المكتشفة التي تحتوي على معلومات الجهاز المجاور
     */
    override fun onTagDiscovered(tag: Tag?) {
        Log.i(TAG, "تم اكتشاف بطاقة NFC جديدة...")
        tag ?: run {
            Log.w(TAG, "تحذير: علامة NFC فارغة")
            return
        }

        readerScope.launch {
            readNfcTag(tag)
        }
    }

    /**
     * قراءة بيانات من بطاقة NFC باستخدام بروتوكول ISO-DEP
     *
     * @param tag علامة NFC المراد قراءتها
     */
    private suspend fun readNfcTag(tag: Tag) = withContext(Dispatchers.IO) {
        val isoDep = IsoDep.get(tag) ?: run {
            Log.e(TAG, "الجهاز لا يدعم بروتوكول ISO-DEP (ISO 14443-4)")
            errorCallback(Exception("الجهاز لا يدعم بروتوكول ISO-DEP"))
            return@withContext
        }

        try {
            Log.d(TAG, "جاري فتح قناة الاتصال مع بطاقة NFC...")
            isoDep.connect()
            // ضبط مهلة الانتظار لتجنب التعليق في حالة انقطاع الإشارة
            isoDep.timeout = TIMEOUT_MS

            // الخطوة 1: إرسال SELECT AID لاختيار تطبيق Atheer
            Log.d(TAG, "إرسال أمر SELECT AID...")
            val selectResponse = isoDep.transceive(SELECT_APDU)

            if (!isResponseOk(selectResponse)) {
                Log.e(TAG, "فشل في SELECT AID - الاستجابة: ${selectResponse.toHexString()}")
                errorCallback(Exception("تطبيق Atheer غير موجود على الجهاز المجاور"))
                return@withContext
            }

            Log.i(TAG, "نجاح SELECT AID - جاري طلب بيانات الدفع...")

            // الخطوة 2: طلب بيانات الدفع (الرمز المميز)
            val paymentResponse = isoDep.transceive(GET_PAYMENT_DATA_APDU)

            if (!isResponseOk(paymentResponse)) {
                Log.e(TAG, "فشل في استقبال بيانات الدفع: ${paymentResponse.toHexString()}")
                errorCallback(Exception("فشل في استقبال بيانات الدفع"))
                return@withContext
            }

            // استخراج بيانات الدفع (آخر بايتين هما كود الحالة 9000)
            val paymentData = paymentResponse.copyOfRange(0, paymentResponse.size - 2)
            val paymentToken = String(paymentData, Charsets.UTF_8)

            Log.i(TAG, "تم استقبال بيانات الدفع بنجاح - حجم البيانات: ${paymentData.size} بايت")

            // بناء كائن المعاملة وإرسال النتيجة
            // ملاحظة: القيمة والنقاش يُستخرجان من الرمز المميز على الخادم بعد فك التشفير
            val transaction = AtheerTransaction(
                transactionId = generateTransactionId(),
                amount = 0L, // تُحدَّد القيمة الفعلية من الخادم بعد فك تشفير الرمز المميز
                currency = "SAR",
                merchantId = merchantId,
                tokenizedCard = paymentToken,
                nonce = "RECEIVED" // الـ Nonce مُضمَّن في الرمز المميز ويُستخرج على الخادم
            )

            withContext(Dispatchers.Main) {
                transactionCallback(transaction)
            }

        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء قراءة بطاقة NFC: ${e.message}", e)
            withContext(Dispatchers.Main) {
                errorCallback(e)
            }
        } finally {
            // إغلاق قناة الاتصال دائماً حتى في حالة الخطأ
            if (isoDep.isConnected) {
                try {
                    isoDep.close()
                    Log.d(TAG, "تم إغلاق قناة اتصال NFC")
                } catch (e: Exception) {
                    Log.w(TAG, "تحذير: فشل في إغلاق قناة NFC: ${e.message}")
                }
            }
        }
    }

    /**
     * التحقق من أن استجابة APDU تشير إلى النجاح
     * الاستجابة الناجحة تنتهي بـ [90][00]
     *
     * @param response مصفوفة البايتات الواردة من الجهاز
     * @return true إذا كانت الاستجابة تشير إلى نجاح العملية
     */
    private fun isResponseOk(response: ByteArray): Boolean {
        if (response.size < 2) return false
        return response[response.size - 2] == STATUS_OK[0] &&
                response[response.size - 1] == STATUS_OK[1]
    }

    /**
     * توليد معرف فريد للمعاملة باستخدام وقت النظام والعشوائية
     *
     * @return معرف المعاملة بصيغة نصية
     */
    private fun generateTransactionId(): String {
        return "TXN_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }

    /**
     * دالة مساعدة لتحويل مصفوفة البايتات إلى تمثيل نصي Hex لأغراض التسجيل
     */
    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }
}
