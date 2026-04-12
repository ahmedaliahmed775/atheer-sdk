package com.atheer.sdk.model

/**
 * ## AtheerReadinessReport
 * تقرير شامل لحالة جاهزية الجهاز لاستخدام Atheer SDK.
 * يُستخدَم مع [com.atheer.sdk.AtheerSdk.checkReadiness] للتحقق من بيئة التشغيل قبل البدء.
 *
 * @property isNfcSupported هل يدعم الجهاز تقنية NFC؟
 * @property isNfcEnabled هل NFC مفعّل حالياً؟
 * @property isHceSupported هل يدعم الجهاز محاكاة البطاقة (HCE)؟
 * @property isBiometricAvailable هل المصادقة الحيوية متاحة ومهيأة؟
 * @property isDeviceEnrolled هل سُجِّل الجهاز وحصل على deviceSeed؟
 * @property isDeviceRooted هل يبدو الجهاز مكسور الحماية (Rooted/Jailbroken)؟
 * @property isReadyForPayment هل الجهاز جاهز تماماً لإجراء عمليات الدفع؟
 */
data class AtheerReadinessReport(
    val isNfcSupported: Boolean,
    val isNfcEnabled: Boolean,
    val isHceSupported: Boolean,
    val isBiometricAvailable: Boolean,
    val isDeviceEnrolled: Boolean,
    val isDeviceRooted: Boolean
) {
    /**
     * الجهاز جاهز للدفع إذا توفرت جميع المتطلبات الأساسية،
     * بما فيها توفر المصادقة الحيوية القوية.
     */
    val isReadyForPayment: Boolean
        get() = isNfcSupported && isNfcEnabled && isHceSupported &&
                isBiometricAvailable && isDeviceEnrolled && !isDeviceRooted
}
