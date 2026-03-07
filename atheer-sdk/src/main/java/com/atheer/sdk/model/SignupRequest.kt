package com.atheer.sdk.model

/**
 * نموذج بيانات طلب تسجيل مستخدم جديد
 *
 * @param username اسم المستخدم المطلوب
 * @param password كلمة المرور
 * @param email البريد الإلكتروني (اختياري)
 * @param phone رقم الهاتف (اختياري)
 */
data class SignupRequest(
    val username: String,
    val password: String,
    val email: String? = null,
    val phone: String? = null
)
