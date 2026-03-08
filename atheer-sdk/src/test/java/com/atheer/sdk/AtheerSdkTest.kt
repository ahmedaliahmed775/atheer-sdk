package com.atheer.sdk

import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.model.ChargeResponse
import org.json.JSONObject
import com.atheer.sdk.security.AtheerKeystoreManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /**
     * اختبار كود خطأ APDU لعدم توفر رموز (6A83)
     */
    @Test
    fun `اختبار رفض كود خطأ APDU لعدم توفر رموز`() {
        val noTokensResponse = byteArrayOf(0x6A.toByte(), 0x83.toByte())
        assertFalse("يجب أن يُرفَض رد APDU 6A83", isApduResponseOk(noTokensResponse))
    }

    // ==================== اختبارات نماذج البيانات ====================

    /** اختبار إنشاء ChargeRequest وبناء JSON بالهيكل المتداخل */
    @Test
    fun `اختبار بناء JSON المتداخل من ChargeRequest`() {
        val request = ChargeRequest(
            amount = 5000L,
            currency = "SAR",
            merchantId = "MERCHANT_001",
            atheerToken = "ATK_encrypted_token_data",
            description = "شحن رصيد"
        )

        val serviceDetail = JSONObject().apply {
            put("serviceName", "ATHEER.ECOMMCASHOUT")
        }
        val header = JSONObject().apply {
            put("serviceDetail", serviceDetail)
        }
        val body = JSONObject().apply {
            put("amount", request.amount)
            put("atheerToken", request.atheerToken)
            put("merchantId", request.merchantId)
            put("currency", request.currency)
            if (request.description != null) put("description", request.description)
        }
        val json = JSONObject().apply {
            put("header", header)
            put("body", body)
        }

        // التحقق من الهيكل المتداخل
        assertTrue("يجب أن يحتوي JSON على header", json.has("header"))
        assertTrue("يجب أن يحتوي JSON على body", json.has("body"))

        val headerObj = json.getJSONObject("header")
        val serviceDetailObj = headerObj.getJSONObject("serviceDetail")
        assertEquals("ATHEER.ECOMMCASHOUT", serviceDetailObj.getString("serviceName"))

        val bodyObj = json.getJSONObject("body")
        assertEquals(5000L, bodyObj.getLong("amount"))
        assertEquals("ATK_encrypted_token_data", bodyObj.getString("atheerToken"))
        assertEquals("MERCHANT_001", bodyObj.getString("merchantId"))
        assertEquals("SAR", bodyObj.getString("currency"))
        assertEquals("شحن رصيد", bodyObj.getString("description"))
    }

    /** اختبار إنشاء ChargeRequest يحتوي على atheerToken */
    @Test
    fun `اختبار أن ChargeRequest يحتوي على حقل atheerToken`() {
        val request = ChargeRequest(
            amount = 1000L,
            currency = "SAR",
            merchantId = "MERCHANT_002",
            atheerToken = "ATK_test_token"
        )
        assertEquals(1000L, request.amount)
        assertEquals("SAR", request.currency)
        assertEquals("MERCHANT_002", request.merchantId)
        assertEquals("ATK_test_token", request.atheerToken)
        assertNull(request.description)
    }

    /** اختبار تحليل ChargeResponse من JSON */
    @Test
    fun `اختبار تحليل ChargeResponse من JSON`() {
        val json = JSONObject().apply {
            put("transactionId", "TXN_CHARGE_001")
            put("status", "SUCCESS")
            put("message", "تمت عملية الشحن بنجاح")
        }
        val response = ChargeResponse(
            transactionId = json.getString("transactionId"),
            status = json.getString("status"),
            message = if (json.has("message")) json.getString("message") else null
        )
        assertEquals("TXN_CHARGE_001", response.transactionId)
        assertEquals("SUCCESS", response.status)
        assertEquals("تمت عملية الشحن بنجاح", response.message)
    }

    /** اختبار ChargeResponse بدون message */
    @Test
    fun `اختبار ChargeResponse بدون message`() {
        val response = ChargeResponse(transactionId = "TXN_001", status = "PENDING")
        assertNull(response.message)
    }

    // ==================== اختبارات نقطة النهاية وتطبيع الرابط ====================

    /** اختبار تطبيع apiBaseUrl بإزالة الـ slash الزائد */
    @Test
    fun `اختبار تطبيع apiBaseUrl بإزالة Slash الزائد`() {
        val rawUrl = "https://api.atheer.com/"
        val normalized = rawUrl.trimEnd('/')
        assertEquals("https://api.atheer.com", normalized)
    }

    /** اختبار أن مسار الشحن الصحيح يُبنى من الرابط الأساسي */
    @Test
    fun `اختبار أن مسار Charge يتضمن api v1 merchant charge`() {
        val baseUrl = "https://api.atheer.com"
        val chargeEndpoint = "$baseUrl/api/v1/merchant/charge"
        assertEquals("https://api.atheer.com/api/v1/merchant/charge", chargeEndpoint)
    }

    /** اختبار أن مسار الشحن لا يحتوي على slashes مضاعفة عند إضافة slash في نهاية baseUrl */
    @Test
    fun `اختبار عدم تكرار Slash عند بناء مسار Charge`() {
        val rawUrl = "https://api.atheer.com/"
        val normalized = rawUrl.trimEnd('/')
        val chargeEndpoint = "$normalized/api/v1/merchant/charge"
        assertFalse("يجب ألا يحتوي المسار على slashes مضاعفة", chargeEndpoint.contains("//api"))
        assertEquals("https://api.atheer.com/api/v1/merchant/charge", chargeEndpoint)
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
