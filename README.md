# Atheer SDK: معالجة دفع آمنة وسلسة لنظام Android (v1.2.0)

![حالة البناء](https://img.shields.io/badge/build-passing-brightgreen)
![المنصة](https://img.shields.io/badge/platform-Android-green)
![مستوى API](https://img.shields.io/badge/API%20Level-21%2B-orange)

## نظرة عامة

تُعد **Atheer SDK** مكتبة Android متطورة ومصممة لتسهيل عمليات الدفع الإلكتروني، مع التركيز على تقنيات **SoftPOS** ومحاكاة بطاقة المضيف (**HCE**). توفر المكتبة بيئة آمنة تماماً لمعالجة المعاملات المالية عبر الاتصال المباشر واللحظي مع المقسم.

تم تحديث هذا الإصدار (v1.2.0) ليعتمد نظام **الاتصال الفوري الإلزامي (Online-Only)** لضمان أقصى درجات الأمان والتحقق اللحظي من العمليات قبل إتمامها.

## الميزات الرئيسية

*   **نظام Online-Only**: إلزامية توفر اتصال بالإنترنت عند إجراء أي عملية دفع لضمان التحقق اللحظي من المقسم (Atheer Switch).
*   **تقنية SoftPOS**: تمكين الأجهزة من استقبال المدفوعات عبر NFC كجهاز نقاط بيع ذكي.
*   **الأمان المتقدم**: تشفير AES-256-GCM باستخدام Android Keystore مع عدم تخزين أي بيانات معاملات محلياً.
*   **دعم Offline Payment (للدافع فقط)**: تقتصر ميزة الدفع دون اتصال على "الدافع" (عبر HCE)، بينما يلتزم "المستلم" (SoftPOS) بالاتصال الدائم بالمقسم.
*   **دعم التحويل عبر APN**: خيار مستقبلي لتوفير الاتصال الإلزامي للمستلم حتى في حالة انعدام رصيد البيانات عبر توجيه حركة المرور لشبكة خاصة (Private APN).
*   **حماية من هجمات التمرير (Relay Attack)**: قياس زمن الاستجابة RTT لضمان وجود البطاقة فعلياً أمام الجهاز.

## التثبيت (Installation)

أضف الاعتمادية التالية إلى ملف `build.gradle` (Module-level):

```gradle
dependencies {
    implementation("com.atheer.sdk:atheer-sdk:1.2.0")
}
```

تأكد من إضافة الأذونات اللازمة في `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## دليل استخدام الدوال الرئيسية

### 1. تهيئة المكتبة (Initialization)

يجب تهيئة المكتبة مرة واحدة في فئة الـ `Application`:

```kotlin
AtheerSdk.init(
    context = this,
    merchantId = "MERCHANT_ID",
    apiKey = "YOUR_API_KEY",
    isSandbox = true,
    enableApnFallback = true // تفعيل خيار APN عند تعذر الوصول للإنترنت العام
)
```

### 2. تنفيذ عملية شحن (Direct Charge)

استخدم الدالة `charge` لإرسال طلب دفع مباشر للسيرفر (تتطلب إنترنت):

```kotlin
val request = ChargeRequest(
    amount = 1000L,
    currency = "SAR",
    customerMobile = "9665XXXXXXXX",
    atheerToken = "TOKEN_VALUE",
    merchantId = "MERCHANT_ID"
)

val result = AtheerSdk.getInstance().charge(request, "ACCESS_TOKEN")
result.onSuccess { response ->
    println("نجاح العملية والخصم اللحظي: ${response.transactionId}")
}.onFailure { error ->
    println("فشل العملية: ${error.message}")
}
```

### 3. معالجة دفع عبر NFC (SoftPOS)

عند استخدام `AtheerNfcReader` لقراءة البطاقات، سيتم طلب التأكيد من السيرفر فوراً:

```kotlin
val nfcReader = AtheerNfcReader(
    merchantId = "MERCHANT_ID",
    amount = 500L,
    transactionCallback = { transaction ->
        // سيقوم SDK بفحص الشبكة وإرسال الطلب فوراً للمقسم
        AtheerSdk.getInstance().processTransaction(transaction, "ACCESS_TOKEN", 
            onSuccess = { msg -> println("تم تأكيد الدفع من السيرفر: $msg") },
            onError = { err -> println("خطأ في العملية أو الشبكة: ${err.message}") }
        )
    },
    errorCallback = { exception ->
        println("خطأ NFC: ${exception.message}")
    }
)
```

## ملاحظات هامة

*   **إيقاف وضع Offline للمستلم**: تم إزالة كافة آليات "الحفظ ثم المزامنة" لضمان عدم قبول عمليات غير مغطاة مالياً أو مزورة.
*   **المزامنة الخلفية**: تم تعطيل `AtheerSyncWorker` حيث لم يعد هناك حاجة لمزامنة معاملات قديمة؛ العمليات الآن إما أن تنجح لحظياً أو تفشل فوراً.
*   **دعم APN**: في حال انقطاع رصيد الإنترنت لدى التاجر، يمكن للمكتبة (إذا تم تفعيل `enableApnFallback`) محاولة الاتصال بالمقسم عبر قناة بيانات مخصصة تضمن وصول طلبات الدفع فقط.

---
© 2024 Atheer Pay. جميع الحقوق محفوظة.
