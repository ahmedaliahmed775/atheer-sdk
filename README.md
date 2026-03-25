# مكتبة أثير (Atheer SDK) 💳

![الأمان](https://img.shields.io/badge/Security-Hardware--Backed-blue)
![المنصة](https://img.shields.io/badge/Platform-Android-green)
![النسخة](https://img.shields.io/badge/Version-1.0.0-orange)

مكتبة **Atheer SDK** هي المحرك البرمجي المتطور لنظام أثير للدفع، مصممة لتقديم تجربة دفع لاتلامسية (NFC) تجمع بين السرعة الفائقة والأمان المصرفي. تعتمد المكتبة على تقنية **Pre-Authorized Biometric Cryptogram** لضمان أن كل عملية دفع تتم بإذن صريح ومشفر من صاحب المحفظة.

---

## 🌟 ماذا سيقدم Atheer SDK لتطبيقك؟

عند دمج المكتبة في تطبيقك، ستحصل على القدرات والوظائف التالية فوراً وبأقل مجهود برمجي:

### 1. تحويل الهاتف إلى "بطاقة دفع ذكية"
*   سيتمكن مستخدمو تطبيقك من الدفع في المتاجر بمجرد تقريب هواتفهم من أجهزة نقاط البيع (POS).

### 2. نظام أمان "بيومتري" متكامل
*   واجهات جاهزة للمصادقة بالبصمة أو الوجه مرتبطة بتشفير العمليات Hardware-Backed.

### 3. الدفع بدون إنترنت (Offline Ready)
*   القدرة على إتمام عمليات دفع آمنة حتى في المناطق التي لا تتوفر فيها تغطية إنترنت.

---

## 📁 خريطة وملفات المشروع (Project Structure)

تنقسم المكتبة إلى عدة حزم برمجية متخصصة، إليك خريطة لأهم الملفات ووظائفها:

### 🔹 الحزمة الرئيسية (`com.atheer.sdk`)
*   **`AtheerSdk.kt`**: نقطة الدخول الوحيدة (Facade)، مسؤولة عن تهيئة المكتبة وتنسيق العمليات بين المكونات.
*   **`AtheerPaymentSettingsActivity.kt`**: واجهة مستخدم جاهزة لتمكين وتعطيل خدمات دفع أثير داخل التطبيق.

### 🔹 الأمان والتشفير (`com.atheer.sdk.security`)
*   **`AtheerKeystoreManager.kt`**: المسؤول عن التفاعل مع الـ TEE/StrongBox لتوليد وتخزين مفاتيح التوقيع الرقمي.
*   **`AtheerPaymentSession.kt`**: إدارة حالة "تسليح" الجلسة (Arming) والمؤقت الزمني الأمني (60 ثانية).
*   **`AtheerTokenManager.kt`**: إدارة دورة حياة توكنات الدفع (تخزين، تحديث، وتشفير).

### 🔹 التواصل والشبكة (`com.atheer.sdk.network` & `hce`)
*   **`AtheerApduService.kt`**: محرك الـ HCE الذي ينفذ بروتوكول ISO 7816 للتواصل مع أجهزة الـ POS عبر NFC.
*   **`AtheerNetworkRouter.kt`**: إدارة طلبات الـ REST API وترجمة استجابات السيرفر إلى أكواد مالية مفهومة.
*   **`AtheerSyncWorker.kt`**: خادم خلفي (Worker) لمزامنة العمليات التي تمت "أوفلاين" وضمان تحديث الرصيد.

### 🔹 البيانات والموديلات (`com.atheer.sdk.model` & `database`)
*   **`AtheerDatabase.kt`**: قاعدة بيانات Room مشفرة بـ SQLCipher لحماية سجل العمليات محلياً.
*   **`AtheerError.kt`**: نظام موحد لتصنيف الأخطاء (نقص رصيد، خطأ شبكة، فشل بصمة).
*   **`ChargeRequest.kt`**: تعريف هيكلية طلب الدفع الموحد لنظام أثير.

### 🔹 واجهة المستخدم والتفاعل (`com.atheer.sdk.nfc`)
*   **`AtheerFeedbackUtils.kt`**: إدارة الاستجابات الحسية (Haptic) والصوتية لإرشاد المستخدم أثناء الدفع.

---

## 🔗 التكامل مع نظام أثير (Atheer Switch Backend)

تتكامل هذه المكتبة مع **[Atheer Switch Backend](https://github.com/ahmedaliahmed775/atheer-switch-backend)** الذي يعمل كمقسم ذكي للتحقق من التوقيعات الحيوية وتوجيه العمليات للمزودين الماليين.

---

## 📋 متطلبات التهيئة والبيانات المطلوبة

1.  **Merchant ID**: المعرف الفريد للتطبيق.
2.  **API Key**: مفتاح تأمين الاتصال بالسيرفر.
3.  **isSandbox**: لتحديد بيئة التشغيل.

---

## 📱 تقنية الـ SoftPOS (استقبال المدفوعات)

تتيح المكتبة وضعية **NFC Reader Mode** لقراءة البيانات المشفرة من هواتف العملاء ومعالجتها كطلب خصم مباشر، مما يحول هاتف التاجر إلى نقطة بيع متكاملة.

---

## ⚙️ إعدادات إضافية هامة

### 1. إعدادات أمان الشبكة
يجب إضافة النطاقات التالية في ملف `network_security_config.xml`:
```xml
<domain includeSubdomains="true">206.189.137.59</domain>
<domain includeSubdomains="true">api.atheer.com</domain>
```

### 2. قواعد الحماية (ProGuard)
```proguard
-keep class com.atheer.sdk.model.** { *; }
-keep class com.atheer.sdk.security.** { *; }
```

---

## 🚀 التثبيت السريع

```kotlin
maven { url = uri("https://maven.pkg.github.com/ahmedaliahmed775/atheer-sdk") }
// ...
implementation("com.github.ahmedaliahmed775:atheer-sdk:1.0.0")
```

---

## 🔐 المتطلبات التقنية
*   **Android 7.0 (API 24)+**
*   **NFC & Biometric Hardware**

---

## ⚖️ الترخيص
جميع الحقوق محفوظة لنظام أثير © 2024. مرخص بموجب [MIT License](LICENSE.txt).
