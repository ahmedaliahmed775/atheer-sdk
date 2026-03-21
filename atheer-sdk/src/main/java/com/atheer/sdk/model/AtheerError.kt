package com.atheer.sdk.model

/**
 * فئة مختومة (Sealed Class) تمثل الأخطاء الموحدة في Atheer SDK.
 * توفر بنية واضحة للتعامل مع أخطاء بوابات الدفع والمحافظ الإلكترونية اليمنية.
 */
sealed class AtheerError(val errorCode: String, override val message: String) : Exception(message) {

    /**
     * خطأ: رصيد غير كافٍ (Insufficient Funds).
     */
    class InsufficientFunds(message: String = "رصيد المحفظة غير كافٍ لإتمام العملية") : 
        AtheerError("ERR_FUNDS", message)

    /**
     * خطأ: قسيمة غير صالحة أو منتهية (Invalid Voucher).
     */
    class InvalidVoucher(message: String = "رمز القسيمة أو التوكن المستخدم غير صالح أو منتهي") : 
        AtheerError("ERR_VOUCHER", message)

    /**
     * خطأ: فشل التحقق من الهوية (Authentication Failed).
     */
    class AuthenticationFailed(message: String = "فشل التحقق من هوية التاجر أو الوكيل") : 
        AtheerError("ERR_AUTH", message)

    /**
     * خطأ: انتهاء وقت مهلة المزود (Provider Timeout).
     */
    class ProviderTimeout(message: String = "انتهى الوقت المسموح لانتظار رد سيرفر المحفظة") : 
        AtheerError("ERR_TIMEOUT", message)

    /**
     * خطأ في الشبكة (Network Error).
     */
    class NetworkError(message: String = "مشكلة في اتصال الإنترنت أو الـ APN المخصص") : 
        AtheerError("ERR_NETWORK", message)

    /**
     * خطأ غير معروف (Unknown Error).
     */
    class UnknownError(message: String = "حدث خطأ غير معروف أثناء معالجة الطلب") : 
        AtheerError("ERR_UNKNOWN", message)
}
