# Atheer SDK — حزمة برمجيات الأندرويد

<div align="center">

**نظام دفع غير تلامسي بمعمارية Pure Synchronous Zero-Trust**

`Kotlin` · `NFC HCE` · `HMAC-SHA256` · `Android Keystore` · `Certificate Pinning`

</div>

---

## نظرة عامة

**Atheer SDK** هي مكتبة أندرويد مبنية بلغة Kotlin تمنح التطبيقات القدرة على تحويل هاتف العميل إلى بطاقة دفع افتراضية عبر تقنية **NFC Host Card Emulation (HCE)**. تعمل المكتبة ضمن منظومة أثير المتكاملة مع [Atheer Switch Backend](https://github.com/ahmedaliahmed775/atheer-switch-backend).

### المبادئ المعمارية

| المبدأ | التنفيذ |
|:---|:---|
| **Pure Synchronous** | معمارية لحظية متزامنة — لا تخزين محلي ولا طوابير انتظار |
| **Zero-Trust** | كل معاملة تُوقَّع تشفيرياً وتُتحقَّق مستقلاً |
| **Dynamic Key Derivation** | مفتاح فريد (LUK) لكل عملية يُشتق من `deviceSeed` + عداد رتيب |
| **Biometric-Gated** | لا يمكن تفعيل جلسة الدفع إلا بعد المصادقة الحيوية |
| **Hardware-Backed** | المفاتيح مخزنة في TEE/StrongBox عبر Android Keystore |
| **Certificate Pinning** | حماية من هجمات MITM عبر تثبيت شهادات الخادم |

---

## الهيكلية

```
com.atheer.sdk/
├── AtheerSdk.kt              — الواجهة الرئيسية (Facade) + Builder DSL
├── security/
│   ├── AtheerKeystoreManager.kt   — إدارة المفاتيح + Device Enrollment
│   └── AtheerPaymentSession.kt    — جلسة الدفع المسلحة (Thread-Safe)
├── hce/
│   └── AtheerApduService.kt       — خدمة HCE لمحاكاة البطاقة
├── nfc/
│   ├── AtheerNfcReader.kt         — محرك قراءة NFC (طرف التاجر)
│   └── AtheerFeedbackUtils.kt     — تغذية راجعة (اهتزاز + صوت)
├── network/
│   └── AtheerNetworkRouter.kt     — موجه الشبكة (OkHttp + TLS 1.3 + Certificate Pinning + APN Toggle)
├── database/
│   ├── AtheerDatabase.kt          — قاعدة بيانات مشفرة (SQLCipher)
│   ├── TransactionDao.kt          — واجهة وصول البيانات
│   └── TransactionEntity.kt       — كيان المعاملة
└── model/
    ├── ChargeRequest.kt / ChargeResponse.kt / AtheerTransaction.kt / AtheerError.kt
```

---

## البدء السريع

### 1. التهيئة

```kotlin
AtheerSdk.init {
    context = applicationContext
    merchantId = "MERCHANT_001"
    apiKey = "YOUR_API_KEY"
    isSandbox = true
    networkMode = NetworkMode.PUBLIC_INTERNET  // أو PRIVATE_APN
}
```

### 2. تسجيل الجهاز (مرة واحدة)

```kotlin
val sdk = AtheerSdk.getInstance()
if (!sdk.isDeviceEnrolled()) {
    sdk.enrollDevice(
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
        onSuccess = { /* تم التسجيل */ },
        onError = { error -> /* فشل */ }
    )
}
```

### 3. الدفع

```kotlin
// تجهيز (مصادقة حيوية + اشتقاق LUK)
sdk.preparePayment(activity, deviceId,
    onSuccess = { /* الهاتف جاهز للـ Tap */ },
    onError = { /* فشل */ }
)

// إرسال للسويتش
val result = sdk.charge(chargeRequest, accessToken)
```

### 4. التنظيف

```kotlin
AtheerSdk.destroy() // إلغاء جميع العمليات + تحرير الموارد
```

---

## Feature Toggle: وضع الشبكة

| الوضع | الوصف | الاستخدام |
|:---|:---|:---|
| `PUBLIC_INTERNET` | الإنترنت العام (Wi-Fi / بيانات) | التطوير والاختبار |
| `PRIVATE_APN` | نفق APN خلوي خاص (Zero-Rating) | الإنتاج مع شركة اتصالات |

```kotlin
AtheerSdk.init {
    networkMode = NetworkMode.PRIVATE_APN
    // يحاول APN أولاً — يرجع للإنترنت العام تلقائياً إذا فشل (Fallback)
}
```

---

## بروتوكول التشفير

```
DEVICE_MASTER_SEED (Backend)
    │
    ▼  HMAC-SHA256(MASTER_SEED, deviceId) ← مرة واحدة عند التسجيل
deviceSeed ────► EncryptedSharedPrefs
    │
    ▼  HMAC-SHA256(deviceSeed, counter) ← كل عملية
LUK (32 bytes)
    │
    ▼  HMAC-SHA256(LUK, "deviceId|counter|timestamp") ← التوقيع
signature (Base64)
```

---

## طبقات الأمان

| الطبقة | الآلية |
|:---|:---|
| فحص نزاهة الجهاز | RootBeer + Play Integrity API |
| تخزين المفاتيح | Android Keystore (TEE/StrongBox) |
| تشفير قاعدة البيانات | SQLCipher (AES-256) |
| المصادقة الحيوية | BiometricPrompt (BIOMETRIC_STRONG) |
| حماية من Relay Attack | RTT ≤ 50ms |
| توقيع المعاملة | HMAC-SHA256 بمفتاح LUK أحادي الاستخدام |
| حماية النقل | TLS 1.2/1.3 + Certificate Pinning |
| Thread Safety | @Volatile + @Synchronized + consumeSession() ذرية |
| خصوصية API | internal visibility modifier |

---

## متطلبات التشغيل

- Android API 26+ (Android 8.0) · NFC HCE · مستشعر بيومتري · اتصال إنترنت (HTTPS)
