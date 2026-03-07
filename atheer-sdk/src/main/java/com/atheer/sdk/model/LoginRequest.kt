package com.atheer.sdk.model

/**
 * نموذج بيانات طلب تسجيل الدخول
 *
 * @param username اسم المستخدم أو البريد الإلكتروني
 * @param password كلمة المرور
 */
data class LoginRequest(
    val username: String,
    val password: String
)
