# Atheer SDK: معالجة دفع آمنة وسلسة لنظام Android (v1.1.0)

![حالة البناء](https://img.shields.io/badge/build-passing-brightgreen)
![المنصة](https://img.shields.io/badge/platform-Android-green)
![مستوى API](https://img.shields.io/badge/API%20Level-21%2B-orange)

## نظرة عامة على المشروع

تُعد Atheer SDK مكتبة Android قوية وآمنة مصممة لتسهيل معالجة المدفوعات الحديثة عبر مقسم أثير (Atheer Switch). تم تحديث هذا الإصدار ليتوافق تماماً مع متطلبات الـ Backend الجديدة (V1) من حيث هيكلية البيانات وأمن الطلبات.

## التحديثات الجديدة في الإصدار 1.1.0 (Atheer Switch V1 Compatibility)

بناءً على تقرير فحص التكامل الأخير، تم إجراء التحديثات الجوهرية التالية:

### 1. تحديثات الشبكة والمسارات
*   **المسار الجديد لعملية الدفع:** تم تغيير مسار طلبات الشحن (Charge) من `/merchant/charge` إلى المسار الرسمي الجديد: `/api/v1/payments/process`.
*   **ترويسة الأمان الإلزامية:** يجب الآن تمرير مفتاح التاجر (`apiKey`) في ترويسة الطلب تحت اسم `x-atheer-api-key` لضمان قبول الطلب في السيرفر.

### 2. هيكلية البيانات المسطحة (Data Flattening)
*   تم إلغاء التغليف المتداخل (`Header`/`Body`) في طلبات JSON. يتم الآن إرسال كافة الحقول مباشرة في جسم الطلب لتبسيط المعالجة وزيادة الكفاءة.
*   **إضافة حقل رقم الهاتف:** أصبح حقل `customerMobile` (String) إلزامياً في كائن `ChargeRequest` لضمان تحديد هوية العميل بدقة.

### 3. نظام التوكنز المطور (TokenInfo)
*   تم استبدال القوائم النصية البسيطة للتوكنز بهيكل بيانات غني `TokenInfo` يحتوي على:
    *   `id`: المعرف الفريد للتوكن.
    *   `tokenValue`: القيمة الفعلية للتوكن.
    *   `providerName`: اسم مزود الخدمة.
    *   `expiryDate`: تاريخ انتهاء الصلاحية بصيغة ISO.
*   **إدارة الصلاحية:** يقوم `AtheerTokenManager` الآن بالتحقق من صلاحية التوكن بناءً على `expiryDate` القادم من السيرفر، مع الاحتفاظ بصلاحية 7 أيام كأمان إضافي محلي.

## التهيئة والاستخدام المحدث

### تهيئة الـ SDK
يجب تمرير الـ `apiKey` الخاص بالتاجر عند التهيئة:

```kotlin
AtheerSdk.init(
    context = context,
    merchantId = "YOUR_MERCHANT_ID",
    apiKey = "YOUR_ATHEER_API_KEY", // الحقل الجديد
    isSandbox = true
)
```

### تنفيذ عملية دفع (Charge)
لاحظ إضافة رقم هاتف العميل واستخدام الهيكل المسطح تلقائياً بواسطة الـ SDK:

```kotlin
val chargeRequest = ChargeRequest(
    amount = 1000,
    currency = "SAR",
    merchantId = "MERCHANT_123",
    atheerToken = "ATK_...",
    customerMobile = "9665XXXXXXXX", // حقل إلزامي جديد
    description = "وصف العملية"
)

val result = AtheerSdk.getInstance().charge(chargeRequest, "ACCESS_TOKEN")
```

## الميزات الرئيسية المستمرة

*   **محاكاة بطاقة المضيف (HCE)** و **SoftPOS**.
*   **معالجة الدفع دون اتصال بالإنترنت** مع مزامنة تلقائية.
*   **أمان فائق:** تكامل مع Android Keystore، تثبيت الشهادات (Certificate Pinning)، واكتشاف الروت.
*   **دعم المحافظ اليمنية:** حقول مخصصة لـ `agentWallet`, `receiverMobile`, و `externalRefId`.

## الأمان والامتثال

تلتزم Atheer SDK بأعلى معايير الأمان:
*   **x-atheer-api-key:** ترويسة مخصصة لحماية نقاط النهاية (Endpoints).
*   **AES-256-GCM:** لتشفير التوكنز والبيانات الحساسة محلياً.
*   **TLS 1.2+:** لتأمين كافة الاتصالات الشبكية.

---
© 2024 Atheer Pay. جميع الحقوق محفوظة.
