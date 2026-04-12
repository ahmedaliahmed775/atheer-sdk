package com.atheer.sdk.repository

import com.atheer.sdk.model.AtheerReadinessReport
import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.model.ChargeResponse

/**
 * ## AtheerRepository
 * واجهة الوصول للبيانات المركزية لـ Atheer SDK.
 *
 * تفصل منطق الوصول للبيانات (الشبكة، قاعدة البيانات، Keystore) عن طبقة الأعمال
 * في [com.atheer.sdk.AtheerSdk]، مما يتيح:
 * - اختبار وحدات كل طبقة بشكل مستقل (Testability).
 * - استبدال مصادر البيانات دون تغيير منطق الأعمال (Separation of Concerns).
 * - التوافق مع مبدأ Dependency Inversion (SOLID).
 */
interface AtheerRepository {

    /**
     * تسجيل الجهاز مع الخادم واستلام [deviceSeed] وتخزينه بأمان في Keystore.
     * إذا كان الجهاز مسجلاً مسبقاً وكان [forceRenew] = false، يُرجع النجاح فوراً.
     *
     * @param phoneNumber رقم الهاتف المستخدم كمعرّف الجهاز.
     * @param apiKey مفتاح API للمصادقة مع الخادم.
     * @param forceRenew إعادة التسجيل حتى لو كان الجهاز مسجلاً.
     * @return [Result.success] عند النجاح، [Result.failure] مع رسالة الخطأ.
     */
    suspend fun enrollDevice(
        phoneNumber: String,
        apiKey: String,
        forceRenew: Boolean = false
    ): Result<Unit>

    /**
     * إرسال طلب الدفع إلى الخادم وحفظ المعاملة محلياً عند النجاح.
     *
     * @param request تفاصيل طلب الدفع.
     * @param accessToken رمز الوصول للمستخدم.
     * @param apiKey مفتاح API للتاجر.
     * @return [Result.success] مع [ChargeResponse]، أو [Result.failure] مع الخطأ.
     */
    suspend fun charge(
        request: ChargeRequest,
        accessToken: String,
        apiKey: String
    ): Result<ChargeResponse>

    /**
     * التحقق مما إذا كان الجهاز مسجلاً (لديه deviceSeed من Backend).
     */
    fun isDeviceEnrolled(): Boolean

    /**
     * التحقق من جاهزية الجهاز الكاملة لاستخدام SDK.
     *
     * @return [AtheerReadinessReport] يحتوي على حالة جميع المتطلبات.
     */
    fun checkReadiness(): AtheerReadinessReport
}
