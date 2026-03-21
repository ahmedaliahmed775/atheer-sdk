package com.atheer.sdk.model

/**
 * نموذج بيانات استجابة عملية الشحن (Charge Response)
 * 
 * تم تحديث هذا النموذج ليتوافق مع معايير الردود للمحافظ اليمنية (مثل جوالي، الكريمي، أم فلوس).
 * 
 * @param transactionId معرف المعاملة الفريد في نظام Atheer.
 * @param status حالة المعاملة، وتدعم القيم النصية التالية:
 *  - ACCEPTED: تم قبول العملية بنجاح.
 *  - PENDING: العملية قيد المعالجة (انتظار رد خارجي أو OTP).
 *  - REJECTED: تم رفض العملية من قبل النظام أو المزود.
 *  - REVERSED: تم عكس العملية واسترداد المبلغ.
 *  - EXPIRED: انتهت صلاحية الطلب قبل إتمامه.
 * @param message رسالة توضيحية لنتيجة العملية (باللغة العربية/الإنجليزية).
 * @param balance الرصيد المتبقي في المحفظة بعد إتمام العملية (اختياري، مدعوم في 'جوالي').
 * @param issuerRef الرقم المرجعي الصادر من جهة إصدار المحفظة أو البنك (Issuer Reference).
 * @param trxDate تاريخ ووقت تنفيذ العملية كما ورد من الخادم (Server Timestamp).
 */
data class ChargeResponse(
    val transactionId: String,
    val status: String,
    val message: String? = null,
    val balance: Double? = null,   // الرصيد المتبقي (للمحافظ اليمنية)
    val issuerRef: String? = null, // المرجع الخارجي (البنك/المحفظة)
    val trxDate: String? = null    // تاريخ العملية من السيرفر
)
