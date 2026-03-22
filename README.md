# Atheer SDK: معالجة دفع آمنة وسلسة لنظام Android (v1.1.0)

![حالة البناء](https://img.shields.io/badge/build-passing-brightgreen)
![المنصة](https://img.shields.io/badge/platform-Android-green)
![مستوى API](https://img.shields.io/badge/API%20Level-21%2B-orange)

## نظرة عامة

تُعد **Atheer SDK** مكتبة Android متطورة ومصممة لتسهيل عمليات الدفع الإلكتروني، مع التركيز على تقنيات **SoftPOS** ومحاكاة بطاقة المضيف (**HCE**). توفر المكتبة بيئة آمنة تماماً لمعالجة المعاملات المالية سواء كنت متصلاً بالإنترنت أو في وضع العمل "دون اتصال".

تم تحديث هذا الإصدار (v1.1.0) ليتوافق بالكامل مع نظام **Atheer Switch V1**، مما يضمن سرعة المعالجة ودعم أنظمة المحافظ والمقاسم المالية الحديثة.

## الميزات الرئيسية

*   **محاكاة بطاقة المضيف (HCE)**: تحويل الهاتف إلى بطاقة دفع لا تلامسية.
*   **تقنية SoftPOS**: تمكين الأجهزة من استقبال المدفوعات عبر NFC.
*   **الأمان المتقدم**: تشفير AES-256-GCM باستخدام Android Keystore.
*   **المزامنة الخلفية**: إرسال المعاملات المخزنة تلقائياً عند توفر الشبكة عبر WorkManager.
*   **دعم القسائم والتوكنز**: نظام متكامل لإدارة الرموز المميزة (Tokens) مع التحقق من الصلاحية.
*   **حماية من هجمات التمرير (Relay Attack)**: قياس زمن الاستجابة RTT لضمان وجود البطاقة فعلياً.

## التثبيت (Installation)

أضف الاعتمادية التالية إلى ملف `build.gradle` (Module-level):

```gradle
dependencies {
    implementation("com.atheer.sdk:atheer-sdk:1.1.0")
}
```

تأكد من إضافة الأذونات اللازمة في `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-permission android:name="android.permission.INTERNET" />
```

## دليل استخدام الدوال الرئيسية

### 1. تهيئة المكتبة (Initialization)

يجب تهيئة المكتبة مرة واحدة في فئة الـ `Application`:

```kotlin
AtheerSdk.init(
    context = this,
    merchantId = "MERCHANT_ID",
    apiKey = "YOUR_API_KEY",
    isSandbox = true // استخدم false للإنتاج
)
```

### 2. تنفيذ عملية شحن (Direct Charge)

استخدم الدالة `charge` لإرسال طلب دفع مباشر للسيرفر:

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
    println("نجاح العملية: ${response.transactionId}")
}.onFailure { error ->
    println("فشل العملية: ${error.message}")
}
```

### 3. معالجة دفع عبر NFC

عند استخدام `AtheerNfcReader` لقراءة البطاقات:

```kotlin
val nfcReader = AtheerNfcReader(
    merchantId = "MERCHANT_ID",
    amount = 500L,
    transactionCallback = { transaction ->
        // معالجة المعاملة المستلمة
        AtheerSdk.getInstance().processTransaction(transaction, "ACCESS_TOKEN", 
            onSuccess = { msg -> println(msg) },
            onError = { err -> println(err.message) }
        )
    },
    errorCallback = { exception ->
        println("خطأ NFC: ${exception.message}")
    }
)
```

### 4. المزامنة اليدوية (Manual Sync)

لإرسال المعاملات المخزنة يدوياً في أي وقت:

```kotlin
AtheerSdk.getInstance().syncPendingTransactions("ACCESS_TOKEN") { count ->
    println("تمت مزامنة $count معاملات بنجاح.")
}
```

## الأمان وحماية البيانات

*   **تشفير البيانات الحساسة**: يتم تشفير كافة المبالغ والتوكنز قبل تخزينها في قاعدة البيانات المحلية.
*   **مسح الذاكرة (Memory Wiping)**: يتم تصفير المصفوفات التي تحتوي على بيانات حساسة فور الانتهاء من استخدامها في الذاكرة العشوائية.
*   **التحقق من البيئة**: ترفض المكتبة العمل على الأجهزة التي تحتوي على صلاحيات "Root" أو المحاكيات (Emulators) لضمان أعلى مستويات الأمان.

---
© 2024 Atheer Pay. جميع الحقوق محفوظة.
