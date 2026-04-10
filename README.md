<div align="center">

# ⚡ Atheer SDK

**مكتبة Android قوية وآمنة لمعالجة المدفوعات عبر تقنية NFC وSoftPOS**

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android API](https://img.shields.io/badge/Android%20API-24%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE.txt)
[![GitHub Packages](https://img.shields.io/badge/GitHub%20Packages-Published-181717?logo=github&logoColor=white)](https://github.com/ahmedaliahmed775/atheer-sdk/packages)

`Kotlin` · `NFC HCE` · `SoftPOS` · `HMAC-SHA256` · `Android Keystore` · `Certificate Pinning` · `Offline Support`

</div>

---

## 📋 جدول المحتويات

- [المقدمة](#-المقدمة)
- [المميزات الرئيسية](#-المميزات-الرئيسية)
- [المبادئ المعمارية](#-المبادئ-المعمارية)
- [الهيكلية](#-الهيكلية)
- [المتطلبات](#-المتطلبات)
- [التثبيت](#-التثبيت)
- [أمثلة الاستخدام](#-أمثلة-الاستخدام)
- [بروتوكول التشفير](#-بروتوكول-التشفير)
- [طبقات الأمان](#-طبقات-الأمان)
- [الترخيص](#-الترخيص)

---

## 🌟 المقدمة

**Atheer SDK** مكتبة أندرويد مبنية بلغة **Kotlin** تمنح التطبيقات قدرة معالجة المدفوعات اللاتلامسية بمستوى بنكي عالٍ. تحوّل المكتبة هاتف العميل إلى بطاقة دفع افتراضية كاملة عبر تقنية **NFC Host Card Emulation (HCE)**، وتدعم كذلك سيناريوهات **SoftPOS** التي تحوّل هاتف التاجر إلى نقطة بيع لاستقبال المدفوعات.

تعمل المكتبة بالتكامل مع [Atheer Switch Backend](https://github.com/ahmedaliahmed775/atheer-switch-backend) ضمن منظومة أثير المتكاملة، مع دعم متقدم للمعاملات **دون اتصال بالإنترنت** عبر آلية المزامنة الذكية في الخلفية.

---

## ✨ المميزات الرئيسية

| الميزة | الوصف |
|:---|:---|
| 📲 **محاكاة البطاقة (HCE)** | تحويل هاتف العميل إلى بطاقة دفع لا تلامسية كاملة عبر NFC |
| 🏪 **SoftPOS** | تحويل هاتف التاجر إلى نقطة بيع (POS) لاستقبال المدفوعات |
| 🔐 **أمان من الدرجة البنكية** | HMAC-SHA256 · Android Keystore · Certificate Pinning |
| 🔑 **مفاتيح ديناميكية (LUK)** | مفتاح فريد لكل معاملة — لا يمكن إعادة استخدامه أبداً |
| 👆 **مصادقة حيوية** | حماية كل عملية ببصمة الإصبع أو التعرف على الوجه |
| 🌐 **دعم APN الخاص** | اتصال عبر نفق خلوي مشفر (Zero-Rating) مع شركات الاتصالات |
| 🛡️ **كشف Root تلقائي** | رفض الأجهزة المخترقة فوراً عند التهيئة |
| 🗄️ **قاعدة بيانات مشفرة** | تخزين المعاملات بتشفير SQLCipher (AES-256) |
| ⚡ **معمارية Pure Synchronous** | لا تأخير ولا طوابير انتظار — استجابة لحظية |

---

## 🏗️ المبادئ المعمارية

| المبدأ | التنفيذ |
|:---|:---|
| **Pure Synchronous** | معمارية لحظية متزامنة — لا تخزين مؤقت ولا طوابير انتظار |
| **Zero-Trust** | كل معاملة تُوقَّع تشفيرياً وتُتحقَّق منها مستقلاً |
| **Dynamic Key Derivation** | مفتاح LUK فريد لكل عملية يُشتق من `deviceSeed` + عداد رتيب |
| **Biometric-Gated** | لا يمكن تفعيل جلسة الدفع إلا بعد المصادقة الحيوية الناجحة |
| **Hardware-Backed** | المفاتيح مخزنة داخل TEE/StrongBox عبر Android Keystore |
| **Certificate Pinning** | حماية من هجمات MITM عبر تثبيت شهادات الخادم (Primary + Backup) |

---

## 📂 الهيكلية

```
com.atheer.sdk/
├── AtheerSdk.kt                     — الواجهة الرئيسية (Facade) + Builder DSL
├── AtheerPaymentSettingsActivity.kt  — شاشة إعدادات الدفع
├── security/
│   ├── AtheerKeystoreManager.kt     — إدارة المفاتيح + Device Enrollment
│   └── AtheerPaymentSession.kt      — جلسة الدفع المسلحة (Thread-Safe)
├── hce/
│   └── AtheerApduService.kt         — خدمة HCE لمحاكاة البطاقة
├── nfc/
│   ├── AtheerNfcReader.kt           — محرك قراءة NFC (طرف التاجر / SoftPOS)
│   └── AtheerFeedbackUtils.kt       — تغذية راجعة (اهتزاز + صوت)
├── network/
│   ├── AtheerNetworkRouter.kt       — موجه الشبكة (OkHttp + TLS 1.3 + Pinning)
│   ├── AtheerCellularRouter.kt      — موجه شبكة APN الخاص
│   └── AtheerSyncWorker.kt          — عامل المزامنة في الخلفية (WorkManager) هذه الخدمه متوقفه ومحذوفه تماما
├── database/
│   ├── AtheerDatabase.kt            — قاعدة بيانات مشفرة (SQLCipher)
│   ├── TransactionDao.kt            — واجهة وصول البيانات
│   └── TransactionEntity.kt         — كيان المعاملة
└── model/
    ├── ChargeRequest.kt             — نموذج طلب الخصم
    ├── ChargeResponse.kt            — نموذج استجابة الخصم
    ├── AtheerTransaction.kt         — نموذج المعاملة
    ├── AtheerError.kt               — نماذج الأخطاء
    ├── LoginRequest.kt / LoginResponse.kt
    ├── SignupRequest.kt / SignupResponse.kt
    ├── BalanceResponse.kt
    └── HistoryResponse.kt
```

---

## 📋 المتطلبات

### متطلبات الجهاز

| المتطلب | الحد الأدنى |
|:---|:---|
| **Android API** | 24 (Android 7.0 Nougat) أو أعلى |
| **NFC** | مطلوب + دعم Host Card Emulation (HCE) |
| **المصادقة الحيوية** | بصمة إصبع أو تعرف على الوجه (BIOMETRIC_STRONG) |
| **الاتصال بالإنترنت** | HTTPS مطلوب للمعاملات الفورية · يدعم العمل offline |

### أذونات Android المطلوبة

أضف الأذونات التالية في ملف `AndroidManifest.xml` الخاص بتطبيقك:

```xml
<!-- NFC وHCE -->
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc.hce" android:required="true" />

<!-- الشبكة -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />

<!-- التغذية الراجعة -->
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### متطلبات التطوير

| الأداة | الإصدار المطلوب |
|:---|:---|
| **Android Studio** | Hedgehog (2023.1.1) أو أحدث |
| **JDK** | 17 |
| **Kotlin** | 1.9.23 |
| **Gradle** | 8.3.2 |

---

## 📦 التثبيت

تُنشر المكتبة على **GitHub Packages**. اتبع الخطوات التالية لإضافتها إلى مشروعك:

### الخطوة 1: إعداد المستودع

في ملف `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/ahmedaliahmed775/atheer-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

### الخطوة 2: إضافة بيانات الاعتماد

في ملف `~/.gradle/gradle.properties` (خارج المشروع لحماية البيانات الحساسة):

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.token=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
```

> **💡 ملاحظة:** تأكد من أن الـ Personal Access Token يملك صلاحية `read:packages`.

### الخطوة 3: إضافة الاعتمادية

في ملف `build.gradle.kts` الخاص بالوحدة:

```kotlin
dependencies {
    implementation("com.github.ahmedaliahmed775:atheer-sdk:1.0.0")
}
```

---

## 🚀 أمثلة الاستخدام

### 1. التهيئة الأولية

يجب تهيئة المكتبة مرة واحدة عند بدء التطبيق، ويُنصح بذلك داخل `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        AtheerSdk.init {
            context = applicationContext
            merchantId = "MERCHANT_001"
            apiKey = "YOUR_API_KEY"
            isSandbox = true                             // false في بيئة الإنتاج
            networkMode = NetworkMode.PUBLIC_INTERNET    // أو PRIVATE_APN
        }
    }
}
```

> **⚠️ تنبيه أمني:** ستُطلق المكتبة `SecurityException` تلقائياً إذا كان الجهاز مخترقاً (Rooted).

---

### 2. تسجيل الجهاز (مرة واحدة)

يجب تسجيل الجهاز مع الخادم مرة واحدة فقط عند أول تشغيل للتطبيق:

```kotlin
import android.provider.Settings
import com.atheer.sdk.AtheerSdk

val sdk = AtheerSdk.getInstance()
val deviceId = Settings.Secure.getString(
    contentResolver,
    Settings.Secure.ANDROID_ID
)

if (!sdk.isDeviceEnrolled()) {
    sdk.enrollDevice(
        deviceId = deviceId,
        onSuccess = {
            // تم تسجيل الجهاز وتخزين deviceSeed بأمان
            // يمكن الآن تجهيز عمليات الدفع
        },
        onError = { errorMessage ->
            // فشل التسجيل — عرض رسالة خطأ للمستخدم
            Log.e("Atheer", "فشل تسجيل الجهاز: $errorMessage")
        }
    )
}
```

---

### 3. تجهيز عملية الدفع (المصادقة الحيوية)

قبل كل عملية دفع يجب تجهيز الجلسة عبر المصادقة الحيوية:

```kotlin
sdk.preparePayment(
    activity = this,
    deviceId = deviceId,
    onSuccess = {
        // الهاتف جاهز للـ Tap — اطلب من المستخدم تقريب هاتفه من جهاز التاجر
        showReadyForPaymentUI()
    },
    onError = { errorMessage ->
        // فشلت المصادقة أو انتهت مهلة الجلسة
        showError(errorMessage)
    }
)
```

---

### 4. تنفيذ عملية الدفع (Charge)

بعد استلام بيانات NFC من التاجر، نُرسلها للمعالجة:

```kotlin
// تحليل بيانات NFC المُستلمة وبناء طلب الدفع
val chargeRequest = sdk.parseNfcDataToRequest(
    rawNfcData = nfcPayload,           // البيانات الخام المستلمة من NFC
    amount = 500.0,                    // المبلغ بالريال اليمني
    receiverAccount = "7XXXXXXXXX",    // رقم هاتف التاجر
    transactionType = "P2M"            // Person-to-Merchant
)

// إرسال طلب الدفع للسويتش
lifecycleScope.launch {
    val result = sdk.charge(chargeRequest, accessToken)

    result.fold(
        onSuccess = { response ->
            // عملية ناجحة
            showSuccess("رقم المعاملة: ${response.transactionId}")
        },
        onFailure = { exception ->
            // فشلت العملية
            showError(exception.message)
        }
    )
}
```

---

### 5. وضع SoftPOS (استقبال المدفوعات)

لاستخدام الهاتف كنقطة بيع لاستقبال المعاملات عبر NFC:

```kotlin
// معالجة معاملة واردة
sdk.processTransaction(
    transaction = atheerTransaction,
    accessToken = accessToken,
    onSuccess = { message ->
        showSuccess(message)
    },
    onError = { exception ->
        showError(exception.message)
    }
)
```

---

### 6. وضع عدم الاتصال (Offline)

تُخزِّن المكتبة المعاملات تلقائياً في قاعدة البيانات المشفرة عند غياب الاتصال، وتُزامنها عند عودته:

```kotlin
// الشبكة الخاصة (APN) مع Fallback تلقائي للإنترنت العام
AtheerSdk.init {
    context = applicationContext
    merchantId = "MERCHANT_001"
    apiKey = "YOUR_API_KEY"
    networkMode = NetworkMode.PRIVATE_APN
    // يحاول APN أولاً — يرجع للإنترنت العام تلقائياً إذا فشل
}
```

---

### 7. التنظيف وتحرير الموارد

```kotlin
// عند إغلاق التطبيق أو عند الحاجة لإعادة التهيئة
override fun onDestroy() {
    super.onDestroy()
    AtheerSdk.destroy() // إلغاء جميع العمليات الجارية + تحرير الموارد
}
```

---

## 🌐 وضع الشبكة (Feature Toggle)

| الوضع | الوصف | متى تستخدمه |
|:---|:---|:---|
| `PUBLIC_INTERNET` | الإنترنت العام (Wi-Fi / بيانات الجوال) | التطوير والاختبار |
| `PRIVATE_APN` | نفق APN خلوي خاص (Zero-Rating) | بيئة الإنتاج مع شركات الاتصالات |

```kotlin
AtheerSdk.init {
    networkMode = NetworkMode.PRIVATE_APN
    // يحاول APN أولاً — يرجع للإنترنت العام تلقائياً إذا فشل (Fallback)
}
```

---

## 🔐 بروتوكول التشفير

يعتمد Atheer SDK على هرمية تشفير متعددة الطبقات تضمن أن كل معاملة فريدة تشفيرياً:

```
DEVICE_MASTER_SEED (Backend)
    │
    ▼  HMAC-SHA256(MASTER_SEED, deviceId)   ← عند التسجيل (مرة واحدة)
deviceSeed ────────────► EncryptedSharedPrefs (Android Keystore)
    │
    ▼  HMAC-SHA256(deviceSeed, counter)     ← لكل عملية
LUK — Limited Use Key (32 bytes)
    │
    ▼  HMAC-SHA256(LUK, "deviceId|counter|timestamp")   ← التوقيع
signature (Base64) ──────► إرسال مع حمولة NFC
```

**نقاط القوة:**
- `deviceSeed` مخزّن بتشفير في Android Keystore داخل TEE/StrongBox
- `counter` رتيب يمنع إعادة تشغيل نفس الطلب (Replay Attack)
- `timestamp` يُقيِّد النافذة الزمنية للمعاملة (RTT ≤ 50ms)
- `LUK` يُتلف بعد الاستخدام — لا يمكن استخدامه مرتين

---

## 🛡️ طبقات الأمان

| الطبقة | الآلية |
|:---|:---|
| **فحص نزاهة الجهاز** | RootBeer + Play Integrity API |
| **تخزين المفاتيح** | Android Keystore (TEE/StrongBox) |
| **تشفير قاعدة البيانات** | SQLCipher (AES-256) |
| **المصادقة الحيوية** | BiometricPrompt (BIOMETRIC_STRONG) |
| **حماية من Relay Attack** | RTT ≤ 50ms |
| **توقيع المعاملة** | HMAC-SHA256 بمفتاح LUK أحادي الاستخدام |
| **حماية النقل** | TLS 1.2/1.3 + Certificate Pinning |
| **Thread Safety** | `@Volatile` + `@Synchronized` + `consumeSession()` ذرية |
| **خصوصية API** | `internal` visibility modifier |

---

## 📄 الترخيص

```
Copyright 2024 Atheer SDK Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

راجع ملف [LICENSE.txt](LICENSE.txt) للتفاصيل الكاملة، وملف [NOTICE.txt](NOTICE.txt) للاطلاع على المكتبات المستخدمة وتراخيصها.
