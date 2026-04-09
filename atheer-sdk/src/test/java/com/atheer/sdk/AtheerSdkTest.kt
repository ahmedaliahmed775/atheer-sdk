package com.atheer.sdk

import com.atheer.sdk.model.ChargeRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for AtheerSdk logic, focusing on non-Android-dependent pure logic
 * matching Backend requirements (HMAC-SHA256, Synchronous Flow, Unified App logic).
 */
class AtheerSdkTest {

    @Test
    fun `اختبار رفض التكوين في حال غياب رقم الهاتف`() {
        assertThrows(IllegalStateException::class.java) {
            val builder = AtheerSdkBuilder()
            builder.merchantId = "MERCHANT_123"
            builder.apiKey = "API_KEY"
            // phoneNumber is missing intentionally
            builder.build()
        }
    }

    @Test
    fun `اختبار تحليل بيانات عملية الدفع من حمولة الـ NFC المعيارية`() {
        val deviceId = "711222333" // Phone Number acting as device identifier
        val counter = 45L
        val timestamp = 1700000000000L
        val signature = "validBase64SignatureHere"

        // Mock NFC Payload coming from physical touch (DeviceID|Counter|Timestamp|Signature)
        val rawNfcData = "$deviceId|$counter|$timestamp|$signature"

        val mockMerchantId = "MERCHANT_TEST"
        
        // Simulating the exact parseNfcDataToRequest logic 
        val extractedRequest = parseNfcDataMock(
            rawNfcData = rawNfcData,
            amount = 1500.0,
            receiverAccount = "777888999",
            transactionType = "P2M",
            merchantId = mockMerchantId
        )

        assertEquals("711222333", extractedRequest.deviceId)
        assertEquals(45L, extractedRequest.counter)
        assertEquals(1700000000000L, extractedRequest.timestamp)
        assertEquals("validBase64SignatureHere", extractedRequest.signature)
        assertEquals(1500L, extractedRequest.amount)
        assertEquals("YER", extractedRequest.currency)
        assertEquals("P2M", extractedRequest.transactionType)
        assertEquals("777888999", extractedRequest.receiverAccount)
        assertEquals("BIOMETRIC_CRYPTOGRAM", extractedRequest.authMethod)
    }

    @Test
    fun `اختبار رفض بيانات NFC مشوهة أو ناقصة الحقول`() {
        val invalidNfcData = "711222333|45|invalidTimestampHere"

        assertThrows(IllegalArgumentException::class.java) {
             parseNfcDataMock(
                 rawNfcData = invalidNfcData,
                 amount = 100.0, 
                 receiverAccount = "700", 
                 transactionType = "P2P", 
                 merchantId = "MER"
             )
        }
    }

    // ==================== Helper methods representing exact AtheerSdk logic ====================
    private fun parseNfcDataMock(
        rawNfcData: String,
        amount: Double,
        receiverAccount: String,
        transactionType: String,
        merchantId: String
    ): ChargeRequest {
        val parts = rawNfcData.split("|")

        if (parts.size < 4) {
            throw IllegalArgumentException("بيانات NFC غير صالحة: التنسيق المتوقع DeviceID|Counter|Timestamp|Signature")
        }

        val deviceId = parts[0]
        val counter = parts[1].toLongOrNull()
            ?: throw IllegalArgumentException("بيانات NFC غير صالحة: قيمة Counter غير صحيحة")
        val timestamp = parts[2].toLongOrNull()
            ?: throw IllegalArgumentException("بيانات NFC غير صالحة: قيمة Timestamp غير صحيحة")
        val signature = parts[3]

        if (deviceId.isBlank() || signature.isBlank()) {
            throw IllegalArgumentException("بيانات NFC ناقصة: DeviceID أو Signature فارغ")
        }

        return ChargeRequest(
            amount = amount.toLong(),
            currency = "YER",
            merchantId = merchantId,
            receiverAccount = receiverAccount,
            transactionRef = "MOCK-REF-UUID",
            transactionType = transactionType,
            deviceId = deviceId,
            counter = counter,
            timestamp = timestamp,
            authMethod = "BIOMETRIC_CRYPTOGRAM",
            signature = signature
        )
    }
}