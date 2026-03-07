package com.atheer.sdk.model

/**
 * نموذج بيانات استجابة تسجيل الدخول
 *
 * @param accessToken رمز المصادقة (Bearer Token) للوصول إلى نقاط النهاية المحمية
 * @param tokenType نوع الرمز (عادةً "Bearer")
 * @param expiresIn مدة صلاحية الرمز بالثواني (اختياري)
 */
data class LoginResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long? = null
)
