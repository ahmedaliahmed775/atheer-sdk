<div align="center">

# Atheer SDK

**مكتبة Android متكاملة وآمنة لمعالجة المدفوعات عبر تقنية محاكاة البطاقة المضيفة (HCE) وتقنية SoftPOS، مع دعم متقدم للمعاملات.**

[![Android API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://android-arsenal.com/api?level=24)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-See%20LICENSE.txt-orange.svg)](LICENSE.txt)
[![GitHub Packages](https://img.shields.io/badge/Published-GitHub%20Packages-blue.svg)](https://github.com/ahmedaliahmed775/atheer-sdk/packages)

</div>

---

## فهرس المحتويات

1. [نظرة عامة على المشروع](#نظرة-عامة-على-المشروع)
2. [المعمارية التقنية](#المعمارية-التقنية)
3. [المميزات](#المميزات)
4. [المتطلبات المسبقة](#المتطلبات-المسبقة)
5. [التثبيت](#التثبيت)
6. [الصلاحيات المطلوبة](#الصلاحيات-المطلوبة)
7. [البداية السريعة](#البداية-السريعة)
8. [الاستخدام](#الاستخدام)
   - [جهة الدافع — HCE (محاكاة البطاقة المضيفة)](#جهة-الدافع--hce-محاكاة-البطاقة-المضيفة)
   - [جهة التاجر — قارئ NFC بتقنية SoftPOS](#جهة-التاجر--قارئ-nfc-بتقنية-softpos)
   - [الدفع المباشر (أونلاين)](#الدفع-المباشر-أونلاين)
   - [معالجة الأخطاء](#معالجة-الأخطاء)
9. [تدفق عملية الدفع](#تدفق-عملية-الدفع)
10. [نماذج البيانات](#نماذج-البيانات)
11. [نموذج الأمان](#نموذج-الأمان)
12. [هيكل المشروع](#هيكل-المشروع)
13. [المساهمة](#المساهمة)
14. [الترخيص](#الترخيص)

---

## نظرة عامة على المشروع

**Atheer SDK** هي مكتبة Android مكتوبة بلغة Kotlin، توفر طبقة دفع متكاملة ومدعومة بالعتاد لتطبيقات Android المتكاملة مع **Atheer Switch Backend**. تتيح المكتبة وضعَين متكاملَين للدفع:

| الوضع | الوصف |
|-------|-------|
| **HCE (الدافع)** | يحاكي جهاز الدافع بطاقة دفع لا تلامسية. تقوم المكتبة بتهيئة جلسة دفع موقعة تشفيريًا وإرسالها عبر النقر على NFC. |
| **SoftPOS (التاجر)** | يعمل جهاز التاجر كنقطة استقبال NFC. تقرأ المكتبة البيانات الموقعة وتتحقق منها وترسلها إلى مقسّم أثير. |

تعتمد المكتبة على معمارية **Zero-Trust Dynamic Key Derivation** (اشتقاق المفاتيح الديناميكي عديم الثقة)، بحيث تستخدم كل عملية مفتاحًا مشتقًا فريدًا، مما يلغي الحاجة إلى تخزين أسرار نصية ثابتة على الجهاز.

---

## المعمارية التقنية

```
┌────────────────────────────────────────────────────────┐
│                  AtheerSdk (الواجهة الرئيسية)           │
│  init()  │  preparePayment()  │  charge()  │  parseNfc │
└──────────┬─────────────────────────────────────────────┘
           │
    ┌──────┴────────────────────────────────────┐
    │                                           │
┌───▼──────────────┐              ┌─────────────▼───────┐
│  طبقة الأمان     │              │   طبقة الشبكة       │
│                  │              │                     │
│ AtheerKeystore   │              │ AtheerNetworkRouter │
│  Manager         │              │ (OkHttp / TLS 1.3)  │
│  ├─ Master Seed  │              └─────────────────────┘
│  │  (TEE/AES256) │
│  ├─ عداد رتيب   │              ┌─────────────────────┐
│  │  (Counter)    │              │   طبقة التخزين      │
│  └─ اشتقاق LUK  │              │                     │
│    (HMAC-SHA256) │              │  AtheerDatabase     │
│                  │              │  (Room + SQLCipher) │
│ AtheerPayment    │              └─────────────────────┘
│  Session (60s)   │
└──────────────────┘
        │
┌───────┴───────────────────────────┐
│           طبقة NFC                │
│                                   │
│  AtheerApduService (HCE / دافع)   │
│  AtheerNfcReader  (SoftPOS /      │
│                    تاجر)          │
└───────────────────────────────────┘
```

### آلية اشتقاق المفاتيح الديناميكي (Zero-Trust)

1. يتم توليد **Master Seed** (AES-256) مرة واحدة وتخزينه داخل **Android Keystore** المدعوم بالعتاد (TEE / StrongBox).
2. يتم تخزين **عداد رتيب (Monotonic Counter)** في `EncryptedSharedPreferences` ويُزاد تلقائيًا مع كل عملية.
3. يتم اشتقاق **مفتاح محدود الاستخدام (LUK)** لكل عملية: `LUK = HMAC-SHA256(MasterSeed, Counter)`.
4. يتم توقيع حمولة العملية (`DeviceID | Counter | Timestamp`) باستخدام LUK قبل الإرسال عبر NFC.
5. يتحقق الـ Backend من التوقيع ويرفض أي قيمة عداد مكررة سبق استخدامها.

---

## المميزات

- ✅ **محاكاة البطاقة المضيفة (HCE)** — يتصرف الجهاز كبطاقة دفع لا تلامسية عبر NFC
- ✅ **قارئ SoftPOS NFC** — يقرأ الجهاز بيانات الدفع من جهاز HCE آخر
- ✅ **اشتقاق مفاتيح ديناميكي بلا ثقة (Zero-Trust)** — مفتاح تشفيري فريد لكل عملية
- ✅ **تخزين المفاتيح بالعتاد** — Master Seed محمي في Android Keystore (TEE/StrongBox)
- ✅ **التحقق البيومتري** — BIOMETRIC_STRONG مطلوب قبل تهيئة كل جلسة دفع
- ✅ **العداد الرتيب** — يمنع هجمات إعادة الإرسال (Replay Attacks)
- ✅ **الحماية من هجمات الترحيل** — قياس RTT عبر NFC ورفض الاستجابات التي تتجاوز 50 مللي ثانية
- ✅ **كشف الـ Root** — مدعوم بـ [RootBeer](https://github.com/scottyab/rootbeer)؛ يطرح `SecurityException` على الأجهزة المروتة
- ✅ **Google Play Integrity API** — التحقق غير المتزامن من نزاهة الجهاز
- ✅ **نافذة جلسة 60 ثانية** — تنتهي جلسات الدفع المهيأة تلقائيًا بعد 60 ثانية
- ✅ **تخزين محلي مشفر** — Room + SQLCipher لسجل المعاملات
- ✅ **إلزام TLS 1.2 / 1.3** — اتصال HTTPS آمن فقط
- ✅ **نموذج أخطاء هيكلي** — `AtheerError` كـ Sealed Class مع أكواد أخطاء محددة
- ✅ **Kotlin Coroutines** — واجهة برمجية غير متزامنة بالكامل

---

## المتطلبات المسبقة

| المتطلب | التفاصيل |
|---------|---------|
| **Android API** | الحد الأدنى API مستوى **24** (Android 7.0 Nougat) |
| **Compile SDK** | API مستوى **34** (Android 14) |
| **Kotlin** | الإصدار 1.9.23 أو أحدث |
| **عتاد NFC** | يجب أن يحتوي الجهاز على شريحة NFC |
| **دعم HCE** | مطلوب على أجهزة الدافع (`android.hardware.nfc.hce`) |
| **عتاد البيومترية** | مستشعر بصمة أو ما يعادله (BIOMETRIC_STRONG) |
| **Google Play Services** | مطلوب لاستخدام Play Integrity API |
| **أداة البناء** | Gradle 8.x مع إضافة KSP |

---

## التثبيت

يتم نشر المكتبة على **GitHub Packages**. أضف ما يلي إلى ملفات Gradle في مشروعك:

### 1. إعداد المستودع

في ملف `settings.gradle.kts` الجذري (أو `build.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/ahmedaliahmed775/atheer-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(System.getenv("GITHUB_ACTOR")).get()
                password = providers.gradleProperty("gpr.key").orElse(System.getenv("GITHUB_TOKEN")).get()
            }
        }
    }
}
```

> **ملاحظة:** يتطلب GitHub Packages المصادقة. أنشئ [Personal Access Token](https://github.com/settings/tokens) بصلاحية `read:packages`.
>
> خزّن بيانات الاعتماد في ملف `~/.gradle/gradle.properties` على مستوى المستخدم (مشترك بين جميع مشاريع Gradle، لا يُرفع إلى المستودع):
> ```
> gpr.user=YOUR_GITHUB_USERNAME
> gpr.key=YOUR_PERSONAL_ACCESS_TOKEN
> ```
>
> بديلًا، يمكن إضافة ملف `gradle.properties` محلي للمشروع مع التأكد من إضافته إلى `.gitignore` لمنع رفع بيانات الاعتماد عن طريق الخطأ.

### 2. إضافة الاعتمادية

في ملف `build.gradle.kts` لوحدة التطبيق:

```kotlin
dependencies {
    implementation("com.github.ahmedaliahmed775:atheer-sdk:1.0.0")
}
```

استبدل `1.0.0` بـ [أحدث إصدار منشور](https://github.com/ahmedaliahmed775/atheer-sdk/packages).

---

## الصلاحيات المطلوبة

تُعلن المكتبة عن الصلاحيات التالية في ملف الـ manifest (يتم دمجها تلقائيًا في تطبيقك):

```xml
<!-- التواصل عبر NFC -->
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc.hce" android:required="true" />

<!-- الوصول للشبكة -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- البيومترية -->
<uses-permission android:name="android.permission.BIOMETRIC" />

<!-- التغذية الراجعة الاهتزازية -->
<uses-permission android:name="android.permission.VIBRATE" />
```

---

## البداية السريعة

### 1. تهيئة المكتبة

استدعِ `AtheerSdk.init()` مرة واحدة في `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        try {
            AtheerSdk.init(
                context    = this,
                merchantId = "YOUR_MERCHANT_ID",
                apiKey     = "YOUR_API_KEY",
                isSandbox  = false   // اضبطها على true للاختبار
            )
        } catch (e: SecurityException) {
            // يُطرح إذا تم اكتشاف Root أو فشل التحقق من نزاهة الجهاز
            Log.e("App", "جهاز غير آمن: ${e.message}")
        }
    }
}
```

> ⚠️ تُجري `init()` **فحص الـ Root** بشكل متزامن وتُطلق طلب **Play Integrity** بشكل غير متزامن. على الأجهزة المروتة يُطرح `SecurityException` فورًا.

---

## الاستخدام

### جهة الدافع — HCE (محاكاة البطاقة المضيفة)

يقرّب الدافع هاتفه من جهاز التاجر. تقوم المكتبة بما يلي:
1. طلب التحقق البيومتري من المستخدم.
2. اشتقاق LUK للاستخدام مرة واحدة وتوقيع الحمولة.
3. تهيئة جلسة مدتها 60 ثانية لتقديم بيانات الدفع عبر `AtheerApduService`.

```kotlin
// يُستدعى من FragmentActivity عند بدء المستخدم لعملية الدفع
AtheerSdk.getInstance().preparePayment(
    activity = this,
    deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
    onSuccess = {
        // الجلسة مهيأة الآن — اطلب من المستخدم تقريب جهازه من الجهاز الطرفي
        showMessage("قرّب هاتفك من الجهاز لإتمام الدفع.")
    },
    onError = { errorMessage ->
        showError(errorMessage)
    }
)
```

يتم تسجيل خدمة `AtheerApduService` تلقائيًا عبر إدخال manifest في المكتبة. لا توجد أي إعدادات إضافية مطلوبة.

---

### جهة التاجر — قارئ NFC بتقنية SoftPOS

يقرأ تطبيق التاجر بيانات NFC الخاصة بالدافع ويرسل طلب الدفع:

```kotlin
// 1. إنشاء قارئ NFC
val nfcReader = AtheerNfcReader(
    context             = this,
    merchantId          = "MERCHANT_001",
    receiverAccount     = "00967700000001",  // محفظة التاجر أو حساب POS
    amount              = 1500L,             // المبلغ بأصغر وحدة للعملة
    currency            = "YER",
    transactionType     = "P2M",
    transactionCallback = { chargeRequest ->
        // تم استلام بيانات NFC — أرسل إلى مقسّم أثير
        submitCharge(chargeRequest)
    },
    errorCallback = { exception ->
        when (exception) {
            is AtheerNfcReader.RelayAttackException ->
                showError("تم اكتشاف هجوم ترحيل. يرجى إعادة المحاولة وجهًا لوجه.")
            else ->
                showError("خطأ NFC: ${exception.message}")
        }
    }
)

// 2. تفعيل وضع القراءة في onResume
override fun onResume() {
    super.onResume()
    NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
        this,
        nfcReader,
        NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
        null
    )
}

// 3. تعطيل القراءة في onPause
override fun onPause() {
    super.onPause()
    NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
}

// 4. إرسال طلب الدفع
private fun submitCharge(request: ChargeRequest) {
    lifecycleScope.launch {
        val result = AtheerSdk.getInstance().charge(request, accessToken = "BEARER_TOKEN")
        result.fold(
            onSuccess = { response -> showSuccess("رقم العملية: ${response.transactionId}") },
            onFailure = { error -> handleAtheerError(error) }
        )
    }
}
```

---

### الدفع المباشر (أونلاين)

للمدفوعات داخل التطبيق التي لا تمر عبر NFC، اشتق التوقيع باستخدام مدير المفاتيح في المكتبة:

```kotlin
val keystoreManager = AtheerSdk.getInstance().getKeystoreManager()

// 1. زيادة العداد واشتقاق LUK للاستخدام مرة واحدة
val counter   = keystoreManager.incrementAndGetCounter()
val luk       = keystoreManager.deriveLUK(counter)
val timestamp = SystemClock.elapsedRealtime()
val deviceId  = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

// 2. بناء الحمولة وتوقيعها: DeviceID|Counter|Timestamp
val payload   = "$deviceId|$counter|$timestamp"
val signature = keystoreManager.signWithLUK(payload, luk)

// 3. بناء طلب الدفع
val request = ChargeRequest(
    amount          = 5000L,
    currency        = "YER",
    merchantId      = "MERCHANT_001",
    receiverAccount = "00967700000001",
    transactionRef  = UUID.randomUUID().toString(),
    transactionType = "P2M",
    deviceId        = deviceId,
    counter         = counter,
    timestamp       = timestamp,
    authMethod      = "BIOMETRIC_CRYPTOGRAM",
    signature       = signature,
    description     = "طلب رقم 12345"
)

lifecycleScope.launch {
    val result = AtheerSdk.getInstance().charge(request, accessToken = "BEARER_TOKEN")
    result.onSuccess { response ->
        Log.i("Payment", "نجاح — رقم العملية: ${response.transactionId}")
    }.onFailure { error ->
        handleAtheerError(error)
    }
}
```

---

### معالجة الأخطاء

تستخدم المكتبة Sealed Class مكتوبة بأنواع محددة لجميع الأخطاء:

```kotlin
fun handleAtheerError(error: Throwable) {
    when (error) {
        is AtheerError.InsufficientFunds    -> showError("رصيد غير كافٍ.")
        is AtheerError.AuthenticationFailed -> showError("فشل التحقق. تحقق من مفتاح API.")
        is AtheerError.InvalidVoucher       -> showError("رمز غير صالح أو منتهي الصلاحية.")
        is AtheerError.ProviderTimeout      -> showError("انتهت مهلة سيرفر المقسّم. أعد المحاولة.")
        is AtheerError.NetworkError         -> showError("لا يوجد اتصال بالإنترنت.")
        is AtheerError.UnknownError         -> showError("حدث خطأ غير متوقع.")
        else                                -> showError(error.message ?: "خطأ غير معروف")
    }
}
```

| كلاس الخطأ | كود الخطأ | سبب الخطأ |
|-----------|----------|----------|
| `InsufficientFunds` | `ERR_FUNDS` | HTTP 400 مع حالة `REJECTED` |
| `InvalidVoucher` | `ERR_VOUCHER` | HTTP 400 / حالة `EXPIRED` |
| `AuthenticationFailed` | `ERR_AUTH` | HTTP 401 / 403 |
| `ProviderTimeout` | `ERR_TIMEOUT` | HTTP 504 |
| `NetworkError` | `ERR_NETWORK` | استثناء IO / لا يوجد اتصال |
| `UnknownError` | `ERR_UNKNOWN` | أي خطأ آخر من السيرفر |

---

## تدفق عملية الدفع

### تدفق الدفع عبر النقر بتقنية HCE

```
  جهاز الدافع                             جهاز التاجر (SoftPOS)
       │                                          │
       │  1. المستخدم يبدأ عملية الدفع            │
       │  2. التحقق البيومتري                      │
       │  3. Counter++ ← اشتقاق LUK               │
       │  4. توقيع الحمولة: DeviceID|Counter|TS   │
       │  5. تهيئة جلسة مدتها 60 ثانية            │
       │                                          │
       │   ────────── نقرة NFC ────────────────>  │
       │   SELECT AID (A000000003101001)           │
       │   <────────────────────────────────────  │
       │   GET_PAYMENT_DATA (0x00CA)               │
       │   ─────── الحمولة الموقعة ─────────────> │
       │                                          │
       │   (RTT يجب أن يكون أقل من 50 مللي ثانية) │
       │                                          │
       │                                   6. تحليل الحمولة
       │                                   7. بناء ChargeRequest
       │                                   8. POST /api/v1/payments/process
       │                                          │
       │                                   9. Backend يتحقق من التوقيع
       │                                      والعداد الرتيب
       │                                  10. اكتمال المعاملة
```

---

## نماذج البيانات

### `ChargeRequest`

| الحقل | النوع | الوصف |
|-------|------|-------|
| `amount` | `Long` | المبلغ بأصغر وحدة للعملة |
| `currency` | `String` | رمز العملة (الافتراضي: `"YER"`) |
| `merchantId` | `String` | معرف التاجر |
| `receiverAccount` | `String` | محفظة التاجر أو رقم حساب POS |
| `transactionRef` | `String` | مرجع فريد للعملية (UUID) |
| `transactionType` | `String` | `"P2M"` (شخص لتاجر) أو `"P2P"` (شخص لشخص) |
| `deviceId` | `String` | معرف جهاز المرسل |
| `counter` | `Long` | قيمة العداد الرتيب |
| `timestamp` | `Long` | قيمة `SystemClock.elapsedRealtime()` |
| `authMethod` | `String` | الافتراضي: `"BIOMETRIC_CRYPTOGRAM"` |
| `signature` | `String` | توقيع HMAC-SHA256 مشفر بـ Base64 |
| `description` | `String?` | وصف اختياري للعملية |

### `ChargeResponse`

| الحقل | النوع | الوصف |
|-------|------|-------|
| `transactionId` | `String` | معرف فريد للعملية من المقسّم |
| `status` | `String` | حالة العملية (مثال: `"ACCEPTED"`) |
| `message` | `String` | رسالة نتيجة مقروءة |

### `AtheerTransaction`

يمثل كائن معاملة كامل يُستخدم في `processTransaction()`:

| الحقل | النوع | الوصف |
|-------|------|-------|
| `amount` | `Double` | مبلغ العملية |
| `currency` | `String` | رمز العملة |
| `receiverAccount` | `String` | حساب المستلم |
| `transactionType` | `String` | نوع العملية |
| `deviceId` | `String` | معرف جهاز المرسل |
| `counter` | `Long` | العداد الرتيب |
| `timestamp` | `Long` | الطابع الزمني للعملية |
| `authMethod` | `String` | وسيلة التحقق |
| `signature` | `String` | التوقيع الرقمي |

### `AtheerError`

Sealed Class — راجع قسم [معالجة الأخطاء](#معالجة-الأخطاء) أعلاه.

---

## نموذج الأمان

تعتمد Atheer SDK على استراتيجية دفاع متعددة الطبقات:

| الطبقة | الآلية |
|-------|-------|
| **نزاهة الجهاز** | كشف الـ Root عبر RootBeer؛ رفض الأجهزة المروتة عند التهيئة |
| **تصديق المنصة** | Google Play Integrity API للتحقق من صحة الجهاز والتطبيق |
| **تخزين المفاتيح** | Master Seed (AES-256) محفوظ في Android Keystore، اختياريًا في StrongBox TEE |
| **اشتقاق المفاتيح** | `LUK = HMAC-SHA256(MasterSeed, Counter)` — مفتاح فريد لكل عملية |
| **منع الإعادة** | عداد رتيب في `EncryptedSharedPreferences`؛ يرفض الـ Backend قيم العداد المكررة |
| **بوابة البيومترية** | مصادقة `BIOMETRIC_STRONG` مطلوبة قبل كل جلسة دفع |
| **انتهاء الجلسة** | جلسات الدفع المهيأة تنتهي تلقائيًا بعد **60 ثانية** |
| **منع هجمات الترحيل** | RTT عبر NFC يُفرض أقل من 50 مللي ثانية؛ الجلسات تُلغى عند تجاوز الحد |
| **أمان النقل** | TLS 1.2 / 1.3 مُفرض عبر `ConnectionSpec.MODERN_TLS` |
| **سلامة الحمولة** | كل حمولة NFC موقعة رقميًا: `DeviceID | Counter | Timestamp | Signature` |

---

## هيكل المشروع

```
atheer-sdk/
├── atheer-sdk/
│   ├── build.gradle.kts           # إعدادات بناء الوحدة والنشر
│   ├── consumer-rules.pro         # قواعد ProGuard للمستهلكين
│   ├── proguard-rules.pro         # قواعد ProGuard للمكتبة
│   └── src/main/
│       ├── AndroidManifest.xml    # الصلاحيات، خدمة HCE، نشاط الإعدادات
│       ├── java/com/atheer/sdk/
│       │   ├── AtheerSdk.kt                          # واجهة المكتبة الرئيسية (init, charge, preparePayment)
│       │   ├── AtheerPaymentSettingsActivity.kt      # شاشة إعدادات دفع HCE
│       │   ├── database/
│       │   │   ├── AtheerDatabase.kt                 # قاعدة البيانات (Room + SQLCipher)
│       │   │   ├── TransactionDao.kt                 # استعلامات المعاملات
│       │   │   └── TransactionEntity.kt              # كيان المعاملة المحلية
│       │   ├── hce/
│       │   │   └── AtheerApduService.kt              # خدمة HostApduService (محاكاة البطاقة)
│       │   ├── model/
│       │   │   ├── AtheerError.kt                    # Sealed Class للأخطاء المكتوبة
│       │   │   ├── AtheerTransaction.kt              # نموذج المعاملة الكامل
│       │   │   ├── BalanceResponse.kt                # استجابة استعلام الرصيد
│       │   │   ├── ChargeRequest.kt                  # نموذج طلب الدفع
│       │   │   ├── ChargeResponse.kt                 # نموذج استجابة الدفع
│       │   │   ├── HistoryResponse.kt                # استجابة سجل المعاملات
│       │   │   ├── LoginRequest.kt / LoginResponse.kt
│       │   │   ├── SignupRequest.kt / SignupResponse.kt
│       │   ├── network/
│       │   │   ├── AtheerNetworkRouter.kt            # عميل شبكة مبني على OkHttp
│       │   │   ├── AtheerCellularRouter.kt           # موجه الشبكة الخلوية البديل
│       │   │   └── AtheerSyncWorker.kt               # عامل المزامنة (⚠️ متوقف منذ v1.2.0 — لا يؤدي عملًا)
│       │   ├── nfc/
│       │   │   ├── AtheerNfcReader.kt                # قارئ IsoDep NFC (SoftPOS / تاجر)
│       │   │   └── AtheerFeedbackUtils.kt            # مساعدات التغذية الراجعة الحسية والصوتية
│       │   └── security/
│       │       ├── AtheerKeystoreManager.kt          # توليد المفاتيح، اشتقاق LUK، العداد
│       │       └── AtheerPaymentSession.kt           # جلسة الدفع المهيأة محدودة الوقت
│       └── res/
│           ├── values/strings.xml
│           └── xml/
│               ├── apduservice.xml                   # تسجيل AID لـ HCE
│               └── network_security_config.xml
├── build.gradle.kts               # إعدادات البناء الجذرية
├── settings.gradle.kts            # إعدادات المشروع والوحدات
├── gradle.properties
├── LICENSE.txt
└── NOTICE.txt
```

---

## المساهمة

نرحب بمساهماتكم! لتوسيع المكتبة أو تحسينها:

1. **Fork** المستودع وأنشئ فرعًا لميزتك.
2. اتبع أسلوب Kotlin الموجود في الكود (`kotlin.code.style=official`).
3. اكتب أو حدّث الاختبارات في مجلد `src/test/`.
4. تأكد من نجاح البناء: `./gradlew :atheer-sdk:build`
5. افتح Pull Request مع وصف واضح للتغيير.

**مجالات مفتوحة للمساهمة:**

- إضافة موجهات شبكة إضافية (مثل APN الخلوي)
- دعم أنواع عمليات إضافية تتجاوز P2M و P2P
- تحسين استراتيجيات تصديق الجهاز
- توسيع نموذج الأخطاء والتعريب

---

## الترخيص

راجع ملفَي [`LICENSE.txt`](LICENSE.txt) و [`NOTICE.txt`](NOTICE.txt) في جذر المستودع للاطلاع على التفاصيل الكاملة للترخيص.
