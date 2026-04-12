package com.atheer.sdk

import android.content.Context

/**
 * ## AtheerSdkConfig
 * إعدادات التهيئة الموحدة للمكتبة باستخدام DSL
 */
class AtheerSdkConfig {
    var context: Context? = null
    var merchantId: String = ""
    var apiKey: String = ""
    var phoneNumber: String = ""
    var isSandbox: Boolean = true
    var enableApnFallback: Boolean = false
    /** حجب الأجهزة المكسورة الحماية (Rooted). false = تحذير فقط */
    var blockRootedDevices: Boolean = false
    /**
     * قائمة بـ SHA-256 hashes لشهادات الخادم (Certificate Pinning).
     * مطلوبة في وضع الإنتاج لحماية الاتصال من هجمات MITM.
     * تُجاهَل تلقائياً في وضع Sandbox.
     *
     * مثال:
     * ```kotlin
     * certificatePins = listOf(
     *     "sha256/AAABBBCCC...=",   // Primary certificate hash
     *     "sha256/DDDEEEFFF...="    // Backup certificate hash
     * )
     * ```
     * للحصول على الـ hash:
     * ```shell
     * openssl s_client -connect api.atheer.com:443 | openssl x509 -pubkey -noout | \
     *   openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
     * ```
     */
    var certificatePins: List<String> = emptyList()
}
