package com.atheer.sdk.model

/**
 * ## ChargeRequest
 * نموذج بيانات طلب إجراء عملية خصم (Charge) متوافق مع متطلبات المحافظ اليمنية والمقسم (Switch).
 * 
 * @property amount المبلغ المراد خصمه.
 * @property currency العملة المستخدمة (الافتراضي "YER").
 * @property merchantId معرف التاجر المستلم للعملية.
 * @property receiverAccount رقم هاتف التاجر أو رقم الـ POS المسجل لدى المزود.
 * @property transactionRef رقم مرجع فريد للعملية يتم توليده في هاتف التاجر.
 * @property transactionType نوع العملية (مثل P2M أو P2P).
 * @property deviceId معرف الجهاز المرسل المستخرج من حمولة NFC.
 * @property counter العداد الرتيب المستخرج من حمولة NFC.
 * @property timestamp الطابع الزمني المستخرج من حمولة NFC.
 * @property authMethod وسيلة التحقق (الافتراضي "BIOMETRIC_CRYPTOGRAM").
 * @property signature التوقيع الرقمي للعملية.
 * @property description وصف اختياري للعملية.
 */
data class ChargeRequest(
    val amount: Long,
    val currency: String = "YER",
    val merchantId: String,
    val receiverAccount: String,
    val transactionRef: String,
    val transactionType: String,
    val deviceId: String,
    val counter: Long,
    val timestamp: Long,
    val authMethod: String = "BIOMETRIC_CRYPTOGRAM",
    val signature: String,
    val description: String? = null
)