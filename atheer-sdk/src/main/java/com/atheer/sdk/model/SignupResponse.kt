package com.atheer.sdk.model

/**
 * نموذج بيانات استجابة تسجيل مستخدم جديد
 *
 * @param userId معرف المستخدم المُنشأ
 * @param message رسالة تأكيد من الخادم (اختياري)
 */
data class SignupResponse(
    val userId: String,
    val message: String? = null
)
