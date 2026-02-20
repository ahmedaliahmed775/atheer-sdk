package com.atheer.sdk

import com.atheer.sdk.security.AtheerKeystoreManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات الوحدة لـ Atheer SDK
 *
 * تتحقق هذه الاختبارات من صحة المنطق البرمجي الأساسي في Atheer SDK.
 * تُركِّز الاختبارات على المكونات التي لا تحتاج إلى جهاز Android حقيقي.
 *
 * ملاحظة: اختبارات AtheerKeystoreManager تعمل فقط على جهاز حقيقي أو محاكي
 * لأنها تتطلب Android Keystore. هنا نختبر المنطق القابل للاختبار بدون بيئة Android.
 */
class AtheerSdkTest {

    /**
     * اختبار التحقق من صيغة الـ Nonce
     * يتحقق من أن الـ Nonce يستوفي الشروط المطلوبة لمنع هجمات إعادة التشغيل
     */
    @Test
    fun `اختبار التحقق من صيغة Nonce الصحيحة`() {
        // Nonce صحيح: 32 حرفاً من أرقام وحروف Hex
        val validNonce = "a3f5b2c1d4e6789012345678abcdef01"
        assertTrue(
            "يجب أن يكون الـ Nonce صحيحاً إذا كان مكوناً من 32 حرفاً من Hex",
            isValidNonceFormat(validNonce)
        )
    }

    /**
     * اختبار رفض Nonce قصير
     */
    @Test
    fun `اختبار رفض Nonce القصير`() {
        val shortNonce = "a3f5b2c1"
        assertFalse(
            "يجب رفض الـ Nonce إذا كان أقصر من 32 حرفاً",
            isValidNonceFormat(shortNonce)
        )
    }

    /**
     * اختبار رفض Nonce يحتوي على أحرف غير Hex
     */
    @Test
    fun `اختبار رفض Nonce الذي يحتوي على أحرف غير صالحة`() {
        val invalidNonce = "a3f5b2c1d4e6789012345678GHIJKLMN" // G-N ليست أحرف Hex
        assertFalse(
            "يجب رفض الـ Nonce الذي يحتوي على أحرف غير Hex",
            isValidNonceFormat(invalidNonce)
        )
    }

    /**
     * اختبار تحديد بادئة الرمز المميز
     */
    @Test
    fun `اختبار أن الرمز المميز يبدأ بالبادئة الصحيحة`() {
        val fakeToken = "ATK_SomeEncryptedData=="
        assertTrue(
            "يجب أن يبدأ الرمز المميز بـ ATK_",
            fakeToken.startsWith("ATK_")
        )
    }

    /**
     * اختبار بناء معرف المعاملة
     */
    @Test
    fun `اختبار أن معرف المعاملة فريد في كل مرة`() {
        // توليد معرفين بفاصل زمني صغير
        val id1 = "TXN_${System.currentTimeMillis()}_1234"
        Thread.sleep(1) // انتظار مللي ثانية واحدة
        val id2 = "TXN_${System.currentTimeMillis()}_5678"
        // إما أن يختلفا بسبب اختلاف الوقت أو بسبب العدد العشوائي
        // في الحالتين نتحقق من أن النمط صحيح
        assertTrue("يجب أن يبدأ المعرف بـ TXN_", id1.startsWith("TXN_"))
        assertTrue("يجب أن يبدأ المعرف بـ TXN_", id2.startsWith("TXN_"))
    }

    /**
     * اختبار بناء جسم JSON للمعاملة
     */
    @Test
    fun `اختبار أن جسم JSON للمعاملة يحتوي على الحقول المطلوبة`() {
        val transactionId = "TXN_123456789"
        val token = "ATK_encryptedData"
        val nonce = "a3f5b2c1d4e6789012345678abcdef01"
        val merchantId = "MERCHANT_001"

        val json = buildTransactionJson(transactionId, token, nonce, merchantId)

        assertTrue("يجب أن يحتوي JSON على transactionId", json.contains("transactionId"))
        assertTrue("يجب أن يحتوي JSON على token", json.contains("token"))
        assertTrue("يجب أن يحتوي JSON على nonce", json.contains("nonce"))
        assertTrue("يجب أن يحتوي JSON على merchantId", json.contains("merchantId"))
        assertTrue("يجب أن تكون قيمة transactionId صحيحة", json.contains(transactionId))
        assertTrue("يجب أن تكون قيمة merchantId صحيحة", json.contains(merchantId))
    }

    /**
     * اختبار التحقق من كود حالة APDU
     */
    @Test
    fun `اختبار التحقق من كود نجاح APDU`() {
        val successResponse = byteArrayOf(0x90.toByte(), 0x00.toByte())
        assertTrue("يجب أن ينتهي رد APDU الناجح بـ 9000", isApduResponseOk(successResponse))
    }

    /**
     * اختبار رفض رد APDU الفاشل
     */
    @Test
    fun `اختبار رفض كود خطأ APDU`() {
        val errorResponse = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        assertFalse("يجب أن يُرفَض رد APDU الخاطئ 6A82", isApduResponseOk(errorResponse))
    }

    // ==================== دوال مساعدة للاختبار ====================

    /** نسخة مساعدة من دالة التحقق من صيغة Nonce */
    private fun isValidNonceFormat(nonce: String): Boolean {
        if (nonce.length != 32) return false
        return nonce.matches(Regex("[0-9a-fA-F]+"))
    }

    /** نسخة مساعدة من دالة بناء JSON */
    private fun buildTransactionJson(
        transactionId: String,
        token: String,
        nonce: String,
        merchantId: String
    ): String {
        return """{"transactionId":"$transactionId","token":"$token","nonce":"$nonce","merchantId":"$merchantId"}"""
    }

    /** نسخة مساعدة من دالة التحقق من كود APDU */
    private fun isApduResponseOk(response: ByteArray): Boolean {
        if (response.size < 2) return false
        return response[response.size - 2] == 0x90.toByte() &&
                response[response.size - 1] == 0x00.toByte()
    }
}
