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
    private var tokenId: String? = null

    /**
     * تفعيل الجلسة وتخزين التوقيع والرمز.
     */
    fun arm(tokenId: String, signature: String) {
        this.tokenId = tokenId
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
     * الحصول على الرمز المميز للجلسة الحالية.
     */
    fun getTokenId(): String? = if (isSessionArmed()) tokenId else null

    /**
     * الحصول على التوقيع الرقمي للجلسة الحالية.
     */
    fun getSignature(): String? = if (isSessionArmed()) signature else null

    /**
     * مسح الجلسة فوراً (يستدعى بعد نجاح التوصيل عبر NFC).
     */
    fun clearSession() {
        tokenId = null
        signature = null
        armedTimestamp = 0L
    }
}