package com.atheer.sdk.security

import android.os.SystemClock

/**
 * ## AtheerPaymentSession
 * تدير هذه الفئة الجلسة "المسلحة" (Armed Session) لعملية الدفع.
 * عند نجاح المصادقة الحيوية، يتم تفعيل الجلسة لمدة 60 ثانية فقط.
 * خلال هذه النافذة الزمنية، يمكن لخدمة HCE تقديم بيانات الدفع عبر NFC.
 *
 * B-02 Fix: تم جعل الكلاس Thread-Safe باستخدام @Volatile و @Synchronized
 * لمنع حالات السباق بين خيط NFC (processCommandApdu) والخيط الرئيسي (preparePayment).
 *
 * A-01 Fix: تم تعيين الكلاس كـ internal لمنع الوصول من خارج الـ SDK.
 */
internal object AtheerPaymentSession {

    private const val SESSION_TIMEOUT_MS = 60_000L // 60 ثانية

    @Volatile private var armedTimestamp: Long = 0L
    @Volatile private var signature: String? = null
    @Volatile private var payload: String? = null

    /**
     * تفعيل الجلسة وتخزين التوقيع والحمولة.
     * B-02: synchronized لمنع الكتابة المتزامنة.
     */
    @Synchronized
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
     * استهلاك الجلسة: الحصول على البيانات ومسح الجلسة في عملية ذرية واحدة.
     * B-02: يمنع استهلاك الجلسة أكثر من مرة (مثلاً من خيطي NFC متزامنين).
     *
     * @return Pair(payload, signature) أو null إذا لم تكن الجلسة نشطة.
     */
    @Synchronized
    fun consumeSession(): Pair<String, String>? {
        if (!isSessionArmed()) return null
        val result = payload!! to signature!!
        clearSession()
        return result
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
     * B-02: synchronized لمنع مسح جلسة يتم قراءتها حالياً.
     */
    @Synchronized
    fun clearSession() {
        payload = null
        signature = null
        armedTimestamp = 0L
    }
}