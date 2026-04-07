# مكتبة أثير (Atheer SDK) 💳 - النسخة الأمنية المطورة

![الأمان](https://img.shields.io/badge/Security-Zero--Trust-red)
![التشفير](https://img.shields.io/badge/Crypto-Dynamic--LUK-blue)
![المنصة](https://img.shields.io/badge/Platform-Android-green)
![النسخة](https://img.shields.io/badge/Version-2.0.0-orange)

مكتبة **Atheer SDK** هي المحرك البرمجي المتطور لنظام أثير للدفع، تم ترقيتها في الإصدار 2.0.0 من معمارية التوكنات المسبقة (Pre-fetched Tokens) إلى معمارية **Zero-Trust Dynamic Key Derivation**. تضمن هذه المعمارية أماناً مطلقاً وقدرة على تنفيذ عمليات دفع "أوفلاين" لا نهائية دون الحاجة للاتصال المسبق بالسيرفر.

---

## 🛡️ المعمارية الأمنية الجديدة (Zero-Trust)

تعتمد المكتبة الآن على أربعة ركائز أمنية أساسية:

### 1. اشتقاق المفاتيح الديناميكي (LUK)
*   **Master Seed**: يتم توليد زوج مفاتيح (Ed25519/AES-256) وتخزينه في الـ Android Hardware-backed Keystore (TEE/StrongBox) عند أول تشغيل.
*   **Monotonic Counter**: عداد رتيب لا ينقص أبداً، مخزن في `EncryptedSharedPreferences`.
*   **Limited Use Key (LUK)**: لكل عملية دفع، يتم اشتقاق مفتاح فريد باستخدام `HMAC-SHA256(MasterSeed, Counter)`. هذا يمنع هجمات إعادة التشغيل (Replay Attacks) تماماً.

### 2. حماية المسافة (NFC Distance Bounding)
*   **RTT Measurement**: يقوم الـ SDK بقياس زمن الرحلة الذهاب والإياب (Round-Trip Time) أثناء مصافحة الـ NFC.
*   **Relay Attack Prevention**: إذا تجاوز الـ RTT حاجز الـ **50ms**، يتم إيقاف العملية فوراً ورمي `RelayAttackException` لمنع هجمات الترحيل عبر الإنترنت.

### 3. نزاهة الجهاز (Device Integrity)
*   **Google Play Integrity API**: يتم التحقق من سلامة بيئة التشغيل عند كل تهيئة للـ SDK.
*   **Root Detection**: يتم حظر العمل على الأجهزة المروتة (Rooted) أو المحاكيات (Emulators) لضمان بيئة تنفيذ آمنة.

### 4. المصادقة البيومترية القوية
*   تكامل مع `Android BiometricPrompt` (Strong Biometrics) لتفويض كل عملية اشتقاق مفتاح وتوقيع Payload.

---

## 📁 خريطة وملفات المشروع المحدثة

### 🔹 الحزمة الرئيسية (`com.atheer.sdk`)
*   **`AtheerSdk.kt`**: نقطة الدخول الرئيسية، تدير التحقق من النزاهة (Play Integrity) والمصادقة البيومترية.

### 🔹 الأمان والتشفير (`com.atheer.sdk.security`)
*   **`AtheerKeystoreManager.kt`**: قلب النظام التشفيري، يدير الـ Master Seed واشتقاق الـ LUK والعداد الرتيب.
*   **`AtheerPaymentSession.kt`**: إدارة الجلسة المؤقتة للبيانات الموقعة (60 ثانية).

### 🔹 التواصل والشبكة (`com.atheer.sdk.nfc` & `hce`)
*   **`AtheerApduService.kt`**: خدمة HCE لإرسال الـ Payload الموقع عبر NFC.
*   **`AtheerNfcReader.kt`**: محرك القراءة (SoftPOS) مع ميزة قياس الـ RTT لمنع الـ Relay Attacks.

---

## 🚀 التثبيت والتهيئة

### 1. إضافة المستودع والتبعية
```kotlin
repositories {
    maven { url = uri("https://maven.pkg.github.com/ahmedaliahmed775/atheer-sdk") }
}

dependencies {
    implementation("com.github.ahmedaliahmed775:atheer-sdk:2.0.0")
}
```

### 2. التهيئة الآمنة
يجب استدعاء `init` في الـ `Application class`. ستقوم الدالة تلقائياً بالتحقق من الـ Root ونزاهة الجهاز.

```kotlin
try {
    AtheerSdk.init(
        context = this,
        merchantId = "YOUR_MERCHANT_ID",
        apiKey = "YOUR_API_KEY",
        isSandbox = true
    )
} catch (e: SecurityException) {
    // الجهاز غير آمن (Rooted) - يجب إغلاق التطبيق أو تقييد الميزات
}
```

---

## 🔐 المتطلبات التقنية
*   **Android 7.0 (API 24)+**
*   **Hardware-backed Keystore Support**
*   **NFC & Biometric Hardware**
*   **Google Play Services** (لـ Play Integrity API)

---

## ⚖️ الترخيص
جميع الحقوق محفوظة لنظام أثير © 2026. مرخص بموجب [MIT License](LICENSE.txt).
