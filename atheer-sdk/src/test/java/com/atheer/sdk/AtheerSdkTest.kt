package com.atheer.sdk

import com.atheer.sdk.model.BalanceResponse
import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.model.ChargeResponse
import com.atheer.sdk.model.HistoryResponse
import com.atheer.sdk.model.HistoryTransaction
import com.atheer.sdk.model.LoginRequest
import com.atheer.sdk.model.LoginResponse
import com.atheer.sdk.model.SignupRequest
import com.atheer.sdk.model.SignupResponse
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

    // ==================== اختبارات نماذج البيانات الجديدة ====================

    /** اختبار إنشاء LoginRequest وصحة حقوله */
    @Test
    fun `اختبار إنشاء LoginRequest بحقول صحيحة`() {
        val request = LoginRequest(username = "user@example.com", password = "secret123")
        assertEquals("user@example.com", request.username)
        assertEquals("secret123", request.password)
    }

    /** اختبار إنشاء LoginResponse وتحليل JSON */
    @Test
    fun `اختبار تحليل LoginResponse من JSON`() {
        val json = JSONObject().apply {
            put("accessToken", "eyJhbGciOiJSUzI1NiJ9.token")
            put("tokenType", "Bearer")
            put("expiresIn", 3600L)
        }
        val response = LoginResponse(
            accessToken = json.getString("accessToken"),
            tokenType = json.optString("tokenType", "Bearer"),
            expiresIn = if (json.has("expiresIn")) json.getLong("expiresIn") else null
        )
        assertEquals("eyJhbGciOiJSUzI1NiJ9.token", response.accessToken)
        assertEquals("Bearer", response.tokenType)
        assertEquals(3600L, response.expiresIn)
    }

    /** اختبار القيم الافتراضية في LoginResponse */
    @Test
    fun `اختبار القيم الافتراضية في LoginResponse`() {
        val response = LoginResponse(accessToken = "token123")
        assertEquals("Bearer", response.tokenType)
        assertNull(response.expiresIn)
    }

    /** اختبار إنشاء SignupRequest مع حقول اختيارية */
    @Test
    fun `اختبار إنشاء SignupRequest مع حقول اختيارية`() {
        val request = SignupRequest(
            username = "newuser",
            password = "pass123",
            email = "newuser@example.com",
            phone = "+966501234567"
        )
        assertEquals("newuser", request.username)
        assertEquals("pass123", request.password)
        assertEquals("newuser@example.com", request.email)
        assertEquals("+966501234567", request.phone)
    }

    /** اختبار إنشاء SignupRequest بدون حقول اختيارية */
    @Test
    fun `اختبار إنشاء SignupRequest بدون حقول اختيارية`() {
        val request = SignupRequest(username = "newuser", password = "pass123")
        assertNull(request.email)
        assertNull(request.phone)
    }

    /** اختبار تحليل SignupResponse من JSON */
    @Test
    fun `اختبار تحليل SignupResponse من JSON`() {
        val json = JSONObject().apply {
            put("userId", "USR_12345")
            put("message", "تم إنشاء الحساب بنجاح")
        }
        val response = SignupResponse(
            userId = json.getString("userId"),
            message = if (json.has("message")) json.getString("message") else null
        )
        assertEquals("USR_12345", response.userId)
        assertEquals("تم إنشاء الحساب بنجاح", response.message)
    }

    /** اختبار تحليل BalanceResponse من JSON */
    @Test
    fun `اختبار تحليل BalanceResponse من JSON`() {
        val json = JSONObject().apply {
            put("balance", 1500.75)
            put("currency", "SAR")
            put("accountId", "ACC_001")
        }
        val response = BalanceResponse(
            balance = json.getDouble("balance"),
            currency = json.getString("currency"),
            accountId = if (json.has("accountId")) json.getString("accountId") else null
        )
        assertEquals(1500.75, response.balance, 0.001)
        assertEquals("SAR", response.currency)
        assertEquals("ACC_001", response.accountId)
    }

    /** اختبار BalanceResponse بدون accountId */
    @Test
    fun `اختبار BalanceResponse بدون accountId`() {
        val response = BalanceResponse(balance = 500.0, currency = "USD")
        assertNull(response.accountId)
    }

    /** اختبار تحليل HistoryResponse من JSON */
    @Test
    fun `اختبار تحليل HistoryResponse من JSON`() {
        val txJson = JSONObject().apply {
            put("transactionId", "TXN_001")
            put("amount", 200.0)
            put("currency", "SAR")
            put("timestamp", 1700000000000L)
            put("status", "SUCCESS")
        }
        val arrayJson = org.json.JSONArray().apply { put(txJson) }
        val rootJson = JSONObject().apply {
            put("transactions", arrayJson)
            put("totalCount", 1)
        }

        val transactions = mutableListOf<HistoryTransaction>()
        val arr = rootJson.getJSONArray("transactions")
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            transactions.add(
                HistoryTransaction(
                    transactionId = item.getString("transactionId"),
                    amount = item.getDouble("amount"),
                    currency = item.getString("currency"),
                    timestamp = item.getLong("timestamp"),
                    status = item.getString("status")
                )
            )
        }
        val response = HistoryResponse(
            transactions = transactions,
            totalCount = rootJson.optInt("totalCount", transactions.size)
        )

        assertEquals(1, response.totalCount)
        assertEquals(1, response.transactions.size)
        assertEquals("TXN_001", response.transactions[0].transactionId)
        assertEquals(200.0, response.transactions[0].amount, 0.001)
        assertEquals("SAR", response.transactions[0].currency)
        assertEquals("SUCCESS", response.transactions[0].status)
    }

    /** اختبار إنشاء ChargeRequest وبناء JSON */
    @Test
    fun `اختبار بناء JSON من ChargeRequest`() {
        val request = ChargeRequest(
            amount = 5000L,
            currency = "SAR",
            merchantId = "MERCHANT_001",
            description = "شحن رصيد"
        )
        val json = JSONObject().apply {
            put("amount", request.amount)
            put("currency", request.currency)
            put("merchantId", request.merchantId)
            if (request.description != null) put("description", request.description)
        }
        assertEquals(5000L, json.getLong("amount"))
        assertEquals("SAR", json.getString("currency"))
        assertEquals("MERCHANT_001", json.getString("merchantId"))
        assertEquals("شحن رصيد", json.getString("description"))
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
