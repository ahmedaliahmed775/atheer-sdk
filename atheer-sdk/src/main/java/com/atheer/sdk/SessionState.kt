package com.atheer.sdk

/**
 * ## SessionState
 * حالة جلسة الدفع — قابلة للمراقبة عبر [AtheerSdk.sessionState].
 */
enum class SessionState {
    /** الجلسة غير نشطة */
    IDLE,
    /** الجلسة مسلحة — جاهزة للنقر عبر NFC */
    ARMED,
    /** انتهت صلاحية الجلسة (60 ثانية) */
    EXPIRED
}
