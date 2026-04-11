package com.atheer.sdk

/**
 * ## AtheerSdkBuilder
 * بانٍ للتحقق المبكر من صحة إعدادات SDK قبل التهيئة.
 *
 * يمكن استخدامه مباشرةً عند الحاجة للتحقق قبل استدعاء [AtheerSdk.init]:
 * ```kotlin
 * val config = AtheerSdkBuilder().apply {
 *     context = applicationContext
 *     merchantId = "MERCHANT_001"
 *     apiKey = "api-key-here"
 *     phoneNumber = "777123456"
 * }.build()
 * ```
 */
class AtheerSdkBuilder {
    var context: android.content.Context? = null
    var merchantId: String = ""
    var apiKey: String = ""
    var phoneNumber: String = ""
    var isSandbox: Boolean = true
    var blockRootedDevices: Boolean = false

    /**
     * التحقق من صحة الإعدادات وبناء [AtheerSdkConfig].
     * @throws IllegalStateException إذا كانت أي حقل إلزامي فارغًا.
     */
    fun build(): AtheerSdkConfig {
        check(phoneNumber.isNotBlank()) { "رقم الهاتف إلزامي لعمليات الدفع والتشفير" }
        check(merchantId.isNotBlank()) { "معرف التاجر إلزامي" }
        check(apiKey.isNotBlank()) { "مفتاح API إلزامي" }
        checkNotNull(context) { "يجب تمرير context صحيح للتهيئة" }

        return AtheerSdkConfig().apply {
            this.context = this@AtheerSdkBuilder.context
            this.merchantId = this@AtheerSdkBuilder.merchantId
            this.apiKey = this@AtheerSdkBuilder.apiKey
            this.phoneNumber = this@AtheerSdkBuilder.phoneNumber
            this.isSandbox = this@AtheerSdkBuilder.isSandbox
            this.blockRootedDevices = this@AtheerSdkBuilder.blockRootedDevices
        }
    }
}
