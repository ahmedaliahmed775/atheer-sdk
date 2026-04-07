package com.atheer.sdk.security

import android.os.SystemClock

/**
 * ## AtheerPaymentSession
 * تدير هذه الفئة الجلسة "المسلحة" (Armed Session) لعملية الدفع.
 * عند نجاح المصادقة الحيوية، يتم تفعيل الجلسة لمدة 60 ثانية فقط.
 * خلال هذه النافذة الزمنية، يمكن لخدمة HCE تقديم بيانات الدفع عبر NFC.
 */
object AtheerPaymentSession {

    private const val SESSION_TIMEOUT_MS = 60_000L // 60 ثانية

    private var armedTimestamp: Long = 0L
    private var signature: String? = null
    private var payload: String? = null

    /**
     * تفعيل الجلسة وتخزين التوقيع والحمولة.
     */
    fun arm(payload: String, signature: String) {
        this.payload = payload
        this.signature = signature
        this.armedTimestamp = SystemClock.elapsedRealtime()
    }

    /**
     * التحقق مما إذا كانت الجلسة لا تزال نشطة ولم تنتهِ صلاحيتها.
     */
    fun isSessionArmed(): Boolean {
        val currentTime = SystemClock.elapsedRealtime()
        return (currentTime - armedTimestamp) < SESSION_TIMEOUT_MS && signature != null
    }

    /**
     * الحصول على حمولة الجلسة الحالية (DeviceID|Counter|Timestamp).
     */
    fun getPayload(): String? = if (isSessionArmed()) payload else null

    /**
     * الحصول على التوقيع الرقمي للجلسة الحالية.
     */
    fun getSignature(): String? = if (isSessionArmed()) signature else null

    /**
     * مسح الجلسة فوراً (يستدعى بعد نجاح التوصيل عبر NFC).
     */
    fun clearSession() {
        payload = null
        signature = null
        armedTimestamp = 0L
    }
}