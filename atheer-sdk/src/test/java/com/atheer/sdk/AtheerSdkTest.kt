package com.atheer.sdk

import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.model.ChargeResponse
import com.atheer.sdk.AtheerSDK // تأكد من استيراد الكلاس الرئيسي للـ SDK
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking // لاستدعاء الدوال المعلقة (Suspend) إن وجدت

class AtheerSdkTest {

    // ==================== اختبارات المنطق (Unit Tests) ====================

    @Test
    fun `اختبار التحقق من صيغة Nonce الصحيحة`() {
        val validNonce = "a3f5b2c1d4e6789012345678abcdef01"
        assertTrue("يجب أن يكون الـ Nonce مكون من 32 حرف Hex", isValidNonceFormat(validNonce))
    }

    @Test
    fun `اختبار رفض Nonce غير صالح`() {
        assertFalse("يجب رفض الـ Nonce القصير", isValidNonceFormat("short"))
        assertFalse("يجب رفض أحرف غير Hex", isValidNonceFormat("a3f5b2c1d4e6789012345678GHIJKLMN"))
    }

    @Test
    fun `اختبار التحقق من كود نجاح APDU`() {
        val successResponse = byteArrayOf(0x90.toByte(), 0x00.toByte())
        assertTrue("يجب أن ينتهي بـ 9000", isApduResponseOk(successResponse))
    }

    // ==================== اختبارات الربط مع السيرفر (Integration Test) ====================

    /**
     * ملاحظة: هذا الاختبار يضرب فعلياً في السيرفر الخاص بك (206.189.137.59)
     * تأكد من تشغيل السيرفر قبل تشغيله.
     */
    @Test
    fun `اختبار عملية الدفع الحقيقية مع السويتش`() = runBlocking {
        // 1. بناء الـ SDK بالإعدادات التي نجحت في Postman
        val sdk = AtheerSDK.Builder()
            .setApiKey("test_api_key_123")
            .setBaseUrl("http://206.189.137.59:4000") // لا تضف /api/v1 هنا إذا كانت مضافة داخلياً في الـ SDK
            .build()

        // 2. محاكاة بيانات عملية الدفع
        // استخدم نفس البيانات التي أعطتك SUCCESS سابقاً
        val amount = 150L
        val receiver = "777333444"
        val token = "TEST_TOKEN_001"

        try {
            // 3. استدعاء الدالة (نفترض أنها تعيد كائن يحتوي على حالة النجاح)
            val result = sdk.processPayment(
                amount = amount,
                receiverMobile = receiver,
                atheerToken = token
            )

            // 4. التحقق من النتيجة
            assertNotNull("يجب ألا يكون الرد فارغاً", result)
            // إذا كان الـ SDK يعيد SUCCESS بناءً على رد السيرفر الذي رأيناه سابقاً
            assertTrue("يجب أن تكون العملية ناجحة في السيرفر", result.isSuccess)
            println("✅ تم الاتصال بالسويتش بنجاح: ${result.message}")

        } catch (e: Exception) {
            fail("❌ فشل الاتصال بالسيرفر: ${e.message}")
        }
    }

    // ==================== دوال مساعدة (Helper Methods) ====================

    private fun isValidNonceFormat(nonce: String): Boolean {
        return nonce.length == 32 && nonce.matches(Regex("[0-9a-fA-F]+"))
    }

    private fun isApduResponseOk(response: ByteArray): Boolean {
        if (response.size < 2) return false
        return response[response.size - 2] == 0x90.toByte() &&
                response[response.size - 1] == 0x00.toByte()
    }

    private fun buildTransactionJson(id: String, token: String, nonce: String, mId: String): String {
        return JSONObject().apply {
            put("transactionId", id)
            put("token", token)
            put("nonce", nonce)
            put("merchantId", mId)
        }.toString()
    }
}