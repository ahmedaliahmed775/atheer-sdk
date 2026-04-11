# Atheer SDK — سجل التغييرات والمعمارية الشاملة

> **الإصدار الحالي:** `1.0.0`  
> **تاريخ آخر تحديث:** أبريل 2026  
> **الفرع:** `copilot/create-codebase-analysis-file`

---

## جدول المحتويات

1. [ملخص التحسينات الأخيرة](#١-ملخص-التحسينات-الأخيرة)
2. [المقارنة بين النسخة السابقة والحالية](#٢-المقارنة-بين-النسخة-السابقة-والحالية)
3. [سير عملية الدفع الكاملة](#٣-سير-عملية-الدفع-الكاملة)
4. [تجربة المستخدم — قبل وبعد](#٤-تجربة-المستخدم--قبل-وبعد)
5. [مكونات الحمولة (Payload)](#٥-مكونات-الحمولة-payload)
6. [تفاعل Atheer SDK مع الأطراف](#٦-تفاعل-atheer-sdk-مع-الأطراف)
7. [نماذج الأخطاء الموحدة](#٧-نماذج-الأخطاء-الموحدة)
8. [قائمة API العامة](#٨-قائمة-api-العامة)

---

## ١. ملخص التحسينات الأخيرة

### 🗑️ حذف الكود المهجور (Dead Code Removal)

| الملف المحذوف | السبب |
|---|---|
| `AtheerCellularRouter.kt` | كان stub فارغاً مُعلَّماً بـ `@Deprecated` — وظائفه دُمجت في `AtheerNetworkRouter` |
| `AtheerSyncWorker.kt` | النظام يعمل بمعمارية متزامنة لحظية — لا حاجة لمزامنة خلفية |
| اعتمادية `work-runtime-ktx` | أُزيلت من `build.gradle.kts` لأنه لا يوجد عمال خلفية |

---

### 🔒 تحسينات الأمان

#### أ. تفعيل كشف الأجهزة المكسورة (Root Detection)
كانت مكتبة `rootbeer-lib` موجودة كاعتمادية في `build.gradle.kts` لكنها لم تُستدعَ قط.

```kotlin
// النسخة القديمة — مكتبة موجودة لكن غير مفعّلة ❌
// لا يوجد كود لكشف الـ Root

// النسخة الجديدة ✅
val rootBeer = RootBeer(config.context)
if (rootBeer.isRooted) {
    if (config.blockRootedDevices) {
        throw SecurityException("رُفض التهيئة: الجهاز يبدو أنه مكسور الحماية.")
    } else {
        Log.w(TAG, "⚠️ تحذير: الجهاز مكسور الحماية.")
    }
}
```

خيار `blockRootedDevices` في `AtheerSdkConfig` يتيح للمطور التحكم في السلوك:
- `false` (افتراضي): تحذير فقط في الـ Log
- `true`: رفض تهيئة SDK كلياً برمي `SecurityException`

---

#### ب. حذف الـ Getters الخطيرة من `AtheerPaymentSession`

```kotlin
// النسخة القديمة — وصول مباشر للبيانات يفتح باب Race Conditions ❌
fun getPayload(): String? = if (isSessionArmed()) payload else null
fun getSignature(): String? = if (isSessionArmed()) signature else null

// النسخة الجديدة — عملية ذرية واحدة فقط ✅
@Synchronized
fun consumeSession(): Pair<String, String>? {
    if (!isSessionArmed()) return null
    val result = payload!! to signature!!
    clearSession()
    onSessionConsumed?.invoke()
    return result
}
```

**لماذا هذا مهم؟** في السيناريو القديم، كان ممكناً أن يقرأ خيطان NFC المتزامنان `getPayload()` و`getSignature()` في نفس اللحظة، مما يسمح بمعالجة نفس الجلسة مرتين. الآن القراءة والمسح عملية ذرية واحدة لا تنقسم.

---

#### ج. التحقق من عمر الـ Timestamp في بيانات NFC

```kotlin
// النسخة القديمة — لا يوجد تحقق من عمر البيانات ❌
val timestamp = parts[2].toLongOrNull() ?: throw ...

// النسخة الجديدة — رفض بيانات أقدم من دقيقتين أو من المستقبل ✅
val ageMs = System.currentTimeMillis() - timestamp
if (ageMs > NFC_PAYMENT_TIMESTAMP_MAX_AGE_MS) { // 120 ثانية
    throw IllegalArgumentException("انتهت صلاحية بيانات الدفع (${ageMs/1000}ث > 120ث)")
}
if (ageMs < -10_000) {
    throw IllegalArgumentException("Timestamp من المستقبل — رُفض")
}
```

---

#### د. إصلاح Bug حرج في بناء الحمولة

```kotlin
// النسخة القديمة — Bug: كان يُمرر phoneNumber مجرداً بدلاً من الحمولة الكاملة ❌
AtheerPaymentSession.arm(phoneNumber, cryptogram)
// النتيجة: البيانات المُرسَلة عبر NFC = "777123456|<signature>"
// والـ Backend يفشل في التحقق لأن payload لا يحتوي على counter و timestamp

// النسخة الجديدة — الحمولة الصحيحة ✅
val payload = "$phoneNumber|$counter|$timestamp"
val cryptogram = keystoreManager.signWithLUK(payload, luk)
AtheerPaymentSession.arm(payload, cryptogram)
// النتيجة: البيانات المُرسَلة = "777123456|42|1700000000000|<signature>"
```

---

### 🏗️ تحسينات المعمارية

#### أ. فصل مسؤوليات `AtheerDatabase`

```kotlin
// النسخة القديمة — AtheerDatabase تعتمد على AtheerKeystoreManager لتوليد كلمة المرور ❌
private fun buildDatabase(context: Context): AtheerDatabase {
    val keystoreManager = AtheerKeystoreManager(context) // اعتماد غير ضروري
    val passphrase = getOrCreateDatabasePassphrase(context, keystoreManager)
    ...
}

// النسخة الجديدة — SecureRandom مباشرة بدون اعتماد خارجي ✅
private val secureRandom = SecureRandom() // instance مشترك في companion

private fun getOrCreateDatabasePassphrase(context: Context): ByteArray {
    // توليد 32 بايت عشوائية مباشرة
    val nonceBytes = ByteArray(32)
    secureRandom.nextBytes(nonceBytes)
    ...
}
```

#### ب. استخدام Gson بدلاً من JSONObject اليدوي

```kotlin
// النسخة القديمة — 12 سطراً لبناء body الطلب يدوياً ❌
val body = JSONObject().apply {
    put("amount", request.amount)
    put("currency", request.currency)
    put("merchantId", request.merchantId)
    // ... 9 حقول إضافية
}.toString()

// النسخة الجديدة — سطر واحد ✅
val body = gson.toJson(request)
```

#### ج. نموذج `EnrollResponse` للتحليل الآمن

```kotlin
// النسخة القديمة — تحليل هش بدون type safety ❌
val seed = responseObj.optJSONObject("data")?.optString("deviceSeed") 
           ?: responseObj.optString("deviceSeed")

// النسخة الجديدة — نموذج مكتوب يدعم بنيتَي JSON ✅
val enrollResponse = gson.fromJson(responseJson, EnrollResponse::class.java)
val seed = enrollResponse.getSeed() // يبحث تلقائياً في data.deviceSeed أو deviceSeed
```

---

### ⚡ تحسينات الأداء وتجربة المستخدم

#### أ. `SessionState` + `StateFlow` للواجهة التفاعلية

```kotlin
// النسخة القديمة — لا توجد طريقة لمعرفة حالة الجلسة ❌
// المطور لا يعرف هل انتهت الـ 60 ثانية أم لا

// النسخة الجديدة — StateFlow قابل للمراقبة ✅
sdk.sessionState.collect { state ->
    when (state) {
        SessionState.ARMED   -> showCountdown(60)   // عداد تنازلي
        SessionState.EXPIRED -> showMessage("انتهت الجلسة، يرجى المصادقة مجدداً")
        SessionState.IDLE    -> resetPaymentButton()
    }
}
```

#### ب. Rate Limiting لمنع المحاولات المتكررة

```kotlin
// النسخة القديمة — لا يوجد حماية من الإرسال المتكرر ❌

// النسخة الجديدة — 5 ثوانٍ بين كل محاولة ✅
if (now - lastPaymentAttemptMs < MIN_PAYMENT_INTERVAL_MS) {
    onError("يرجى الانتظار $remaining ثوانٍ قبل المحاولة مجدداً")
    return
}
```

#### ج. إصلاح تسريب الذاكرة في `AtheerNfcReader`

```kotlin
// النسخة القديمة — CoroutineScope لا يُلغى أبداً ❌
class AtheerNfcReader(...) : NfcAdapter.ReaderCallback {
    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // لا توجد طريقة لإلغاء الـ scope عند إيقاف القارئ
}

// النسخة الجديدة — Closeable يضمن إلغاء الـ coroutines ✅
class AtheerNfcReader(...) : NfcAdapter.ReaderCallback, Closeable {
    override fun close() {
        readerScope.cancel()
    }
}
// الاستخدام: nfcReader.use { ... } أو nfcReader.close() عند onPause()
```

#### د. تجاوز التسجيل إذا الجهاز مسجّل مسبقاً

```kotlin
// النسخة القديمة — طلب شبكة في كل مرة ❌
fun enrollDevice(onSuccess, onError) { /* دائماً يطلب من الشبكة */ }

// النسخة الجديدة — ذكي، يتجنب الطلب غير الضروري ✅
fun enrollDevice(forceRenew: Boolean = false, onSuccess, onError) {
    if (!forceRenew && keystoreManager.isDeviceEnrolled()) {
        onSuccess() // فوري بدون شبكة
        return
    }
    // ... طلب الشبكة فقط عند الحاجة
}
```

#### هـ. SDK Version Headers في كل طلب HTTP

```kotlin
// النسخة الجديدة — headers تشخيصية في كل طلب ✅
requestBuilder.addHeader("x-atheer-sdk-version", "1.0.0")
requestBuilder.addHeader("x-atheer-platform", "android")
requestBuilder.addHeader("x-atheer-os-version", Build.VERSION.RELEASE)
```

---

### ✅ اكتمال المشروع

#### أ. حفظ المعاملات تلقائياً

```kotlin
// النسخة القديمة — قاعدة البيانات موجودة لكن لا تُستخدم ❌
// AtheerDatabase كانت تُبنى لكن charge() لم تكتب فيها أبداً

// النسخة الجديدة — حفظ تلقائي عند نجاح charge() ✅
sdkScope.launch {
    database.transactionDao().insertTransaction(
        request.toTransactionEntity(transactionId)
    )
}
```

#### ب. `checkReadiness()` — فحص جاهزية الجهاز

```kotlin
// ميزة جديدة كلياً ✅
val report = AtheerSdk.getInstance().checkReadiness()
if (!report.isReadyForPayment) {
    if (!report.isNfcEnabled) showDialog("يرجى تفعيل NFC")
    if (!report.isDeviceEnrolled) navigateToEnrollment()
    if (report.isDeviceRooted) showSecurityWarning()
}
```

---

## ٢. المقارنة بين النسخة السابقة والحالية

| الجانب | النسخة القديمة | النسخة الجديدة |
|---|---|---|
| **كشف الـ Root** | مكتبة موجودة غير مفعّلة | مفعّلة في `init()` مع خيار الحجب |
| **حالة الجلسة** | لا يمكن معرفتها من الخارج | `StateFlow<SessionState>` قابل للمراقبة |
| **الحمولة المُرسَلة عبر NFC** | `phoneNumber\|signature` (ناقصة) | `phoneNumber\|counter\|timestamp\|signature` (صحيحة) |
| **Rate Limiting** | لا يوجد | 5 ثوانٍ بين كل محاولة |
| **Timestamp Validation** | لا يوجد | رفض > 120 ثانية أو من المستقبل |
| **بناء طلب HTTP** | 12 سطراً يدوياً (JSONObject) | سطر واحد (Gson) |
| **تسجيل الجهاز** | طلب شبكة في كل مرة | يتخطى إذا مسجّل مسبقاً |
| **حفظ المعاملات** | DB موجودة لكن لا تُكتب | تُحفظ تلقائياً عند نجاح `charge()` |
| **فحص الجاهزية** | غير موجود | `checkReadiness()` شامل |
| **تسريب الذاكرة في NFC** | `CoroutineScope` لا يُلغى | `Closeable.close()` يُلغي الـ scope |
| **Thread-Safety للجلسة** | Getters منفصلة (Race Condition) | `consumeSession()` ذرية |
| **اعتمادية `work-runtime-ktx`** | موجودة غير مستخدمة | محذوفة |
| **ملفات مهجورة** | 2 ملفات Deprecated | محذوفة نهائياً |

---

## ٣. سير عملية الدفع الكاملة

### 🔑 المرحلة صفر: التسجيل (مرة واحدة فقط)

```
تطبيق العميل
     │
     ▼
AtheerSdk.enrollDevice()
     │
     ├── [مسجّل مسبقاً?] ──YES──► onSuccess() مباشرة (لا شبكة)
     │
     └── [غير مسجّل] ──►  POST /api/v1/devices/enroll
                               { "deviceId": "777123456" }
                                      │
                                      ▼
                               Backend يُنشئ:
                               deviceSeed = HMAC(MASTER_SEED, deviceId)
                                      │
                                      ▼
                        { "data": { "deviceSeed": "<Base64>" } }
                                      │
                                      ▼
                        AtheerKeystoreManager.storeEnrolledSeed()
                        (مُشفَّر في EncryptedSharedPreferences)
```

---

### 💳 المرحلة الأولى: تجهيز الدفع (جانب العميل/الدافع)

```
المستخدم يضغط "ادفع"
     │
     ▼
AtheerSdk.preparePayment(activity)
     │
     ├── [Rate Limit Check] ── أقل من 5 ثوانٍ منذ آخر محاولة?
     │        └── YES ──► onError("يرجى الانتظار X ثوانٍ")
     │
     ▼
BiometricPrompt.authenticate()
     │
     ├── onAuthenticationError ──► onError(message)
     ├── onAuthenticationFailed ──► onError("فشلت المصادقة")
     │
     └── onAuthenticationSucceeded
              │
              ▼
         [اشتقاق المفاتيح]
         counter = KeystoreManager.incrementAndGetCounter()  // +1 رتيب
         timestamp = System.currentTimeMillis()
         luk = HMAC-SHA256(enrolledSeed, counter)            // مفتاح وحيد
         payload = "$phoneNumber|$counter|$timestamp"
         cryptogram = HMAC-SHA256(luk, payload)             // التوقيع
              │
              ▼
         AtheerPaymentSession.arm(payload, cryptogram)
              │
              ▼
         sessionState → ARMED ✅
         [مؤقت 60 ثانية يبدأ]
              │
              ▼
         onSuccess() — الجهاز جاهز للنقر
```

---

### 📡 المرحلة الثانية: النقل عبر NFC (تلامس الجهازين)

```
جهاز التاجر (SoftPOS)                    جهاز العميل (HCE)
        │                                        │
        │──── SELECT AID (0x00 A4...) ──────────►│
        │                                        │ AtheerApduService
        │◄─── 0x90 0x00 (OK) ───────────────────│
        │                                        │
        │──── GET PAYMENT DATA (0x00 CA...) ────►│
        │    [RTT قياس يبدأ]                     │ AtheerPaymentSession.consumeSession()
        │    [RTT قياس ينتهي]                    │ (ذرية: قراءة + مسح في آنٍ)
        │                                        │
        │◄─── "$payload|$signature" + 0x90 00 ──│
        │                                        │ sessionState → IDLE
        │                                        │ إشعار: "دفع ناجح"
        │                                        │ اهتزاز + صوت
        │
        [RTT > 50ms?] ──YES──► RelayAttackException (رُفض)
        [RTT ≤ 50ms] ──────► بيانات صالحة ✅
```

---

### 🌐 المرحلة الثالثة: تسوية الدفع (جانب التاجر)

```
AtheerNfcReader (تطبيق التاجر)
     │
     ▼
parseNfcDataToRequest(rawPayload, amount, receiverAccount, "P2M")
     │
     ├── [Timestamp Validation]
     │        ├── عمر > 120 ثانية? ──► رُفض
     │        └── من المستقبل? ──────► رُفض
     │
     ▼
ChargeRequest {
    amount, currency, merchantId, receiverAccount,
    transactionRef (UUID جديد), transactionType,
    deviceId, counter, timestamp,
    authMethod = "BIOMETRIC_CRYPTOGRAM",
    signature
}
     │
     ▼
AtheerSdk.charge(request, accessToken)
     │
     ├── [Network Check] ── لا إنترنت? ──► Result.failure
     │
     ▼
POST /api/v1/payments/process
Headers:
  Authorization: Bearer <accessToken>
  x-atheer-api-key: <merchantApiKey>
  x-atheer-sdk-version: 1.0.0
  x-atheer-platform: android
     │
     ▼
Backend يتحقق:
  1. LUK = HMAC(HMAC(MASTER_SEED, deviceId), counter)
  2. expectedSig = HMAC(LUK, payload)
  3. expectedSig == request.signature ? ✅ : ❌
  4. counter > lastCounter ? ✅ (يمنع Replay) : ❌
  5. timestamp within 2min? ✅ : ❌
     │
     ▼
ChargeResponse { transactionId, status: "ACCEPTED", message }
     │
     ▼
[Auto-Save] AtheerDatabase.insertTransaction() ── حفظ محلي تلقائي
     │
     ▼
Result.success(response) ──► تطبيق التاجر يعرض تأكيد الدفع
```

---

## ٤. تجربة المستخدم — قبل وبعد

### 👤 تجربة العميل (الدافع)

#### النسخة القديمة
```
[ضغط "ادفع"]
     └── BiometricPrompt يظهر
              └── [بصمة ناجحة]
                       └── onSuccess() — لا يعرف المستخدم ماذا يفعل الآن
                                          ❌ لا يوجد عداد
                                          ❌ لا يعرف متى تنتهي الجلسة
                                          ❌ يمكن الضغط مجدداً فوراً
```

#### النسخة الجديدة
```
[ضغط "ادفع"]
     └── [Rate Limit OK]
              └── BiometricPrompt يظهر
                       └── [بصمة ناجحة]
                                └── sessionState = ARMED
                                         └── واجهة التطبيق تعرض:
                                              ✅ "ضع هاتفك على جهاز الدفع"
                                              ✅ عداد تنازلي: 60... 59... 58...
                                              └── [بعد النقر NFC]
                                                       ├── sessionState = IDLE
                                                       ├── اهتزاز + صوت نجاح
                                                       └── إشعار: "تم إرسال بيانات الدفع"
                                              └── [بعد 60 ثانية بدون نقر]
                                                       ├── sessionState = EXPIRED
                                                       └── "انتهت الجلسة، يرجى المصادقة مجدداً"
```

---

### 🏪 تجربة التاجر (المُستلِم)

#### النسخة القديمة
```
[اقتراب هاتف العميل]
     └── NFC يقرأ البيانات
              └── parseNfcDataToRequest()
                       └── لا يوجد تحقق من العمر ❌
                       └── حمولة ناقصة (phoneNumber فقط) ❌
```

#### النسخة الجديدة
```
[قبل استقبال الدفع]
     └── checkReadiness()
              ├── NFC مفعّل? ✅
              ├── HCE مدعوم? ✅
              ├── مسجّل في النظام? ✅
              └── isReadyForPayment = true ✅

[اقتراب هاتف العميل]
     └── NFC يقرأ البيانات
              └── [RTT ≤ 50ms] ✅ (ليس Relay Attack)
              └── parseNfcDataToRequest()
                       ├── Timestamp < 120 ثانية? ✅
                       └── ChargeRequest كامل مع counter + timestamp + signature
              └── charge(request, token) ──► تأكيد آني ✅
```

---

## ٥. مكونات الحمولة (Payload)

### حمولة NFC — ما يُرسَل من جهاز العميل إلى التاجر

```
Format: DeviceID | Counter | Timestamp | Signature
Example: "777123456|42|1700000000000|abc123xyz=="
          ─────────  ──  ─────────────  ──────────
              │       │        │              │
              │       │        │         HMAC-SHA256
              │       │        │         (LUK, payload)
              │       │        │
              │       │   Unix timestamp (ms)
              │       │   System.currentTimeMillis()
              │       │
              │   Monotonic Counter
              │   (يزيد بمقدار 1 لكل عملية، يُمنع Replay)
              │
          رقم هاتف المستخدم
          (يعمل كـ deviceId في النظام)
```

### تفصيل كل حقل

| الحقل | النوع | المصدر | الغرض |
|---|---|---|---|
| `DeviceID` | `String` | `phoneNumber` في `AtheerSdkConfig` | تحديد هوية العميل |
| `Counter` | `Long` | `EncryptedSharedPreferences` (يزيد +1) | منع إعادة الاستخدام (Anti-Replay) |
| `Timestamp` | `Long` | `System.currentTimeMillis()` | منع إعادة الاستخدام الزمني |
| `Signature` | `String (Base64)` | `HMAC-SHA256(LUK, payload)` | إثبات الأصالة |

---

### حمولة HTTP — ما يُرسَل من تطبيق التاجر إلى الخادم

```json
{
  "amount": 5000,
  "currency": "YER",
  "merchantId": "MERCHANT_001",
  "receiverAccount": "777000111",
  "transactionRef": "550e8400-e29b-41d4-a716-446655440000",
  "transactionType": "P2M",
  "deviceId": "777123456",
  "counter": 42,
  "timestamp": 1700000000000,
  "authMethod": "BIOMETRIC_CRYPTOGRAM",
  "signature": "abc123xyz==",
  "description": "عملية دفع عبر أثير SDK - SoftPOS"
}
```

| الحقل | المصدر | الوصف |
|---|---|---|
| `amount` | التاجر | المبلغ بالفلوس (Long) |
| `currency` | ثابت `"YER"` | العملة اليمنية |
| `merchantId` | `AtheerSdkConfig.merchantId` | معرف التاجر |
| `receiverAccount` | التاجر | رقم هاتف التاجر أو POS |
| `transactionRef` | `UUID.randomUUID()` | مرجع فريد يتولد في جهاز التاجر |
| `transactionType` | التاجر | `P2M` (شخص لتاجر) أو `P2P` |
| `deviceId` | حمولة NFC | رقم هاتف العميل الدافع |
| `counter` | حمولة NFC | العداد الرتيب من جهاز العميل |
| `timestamp` | حمولة NFC | الطابع الزمني من جهاز العميل |
| `authMethod` | ثابت | `"BIOMETRIC_CRYPTOGRAM"` |
| `signature` | حمولة NFC | التوقيع HMAC-SHA256 |

---

### اشتقاق المفاتيح الكامل (Key Derivation Chain)

```
Backend MASTER_SEED (سر مركزي)
         │
         ▼ HMAC-SHA256(MASTER_SEED, deviceId)
    enrolledSeed  ◄──────────── يُرسَل للعميل عند التسجيل
         │                       يُخزَّن في EncryptedSharedPreferences
         │
         ▼ HMAC-SHA256(enrolledSeed, counter)
       LUK (Limited Use Key)  ──── مفتاح لعملية واحدة فقط
         │
         ▼ HMAC-SHA256(LUK, "$phoneNumber|$counter|$timestamp")
     Signature  ──────────────────────────────────────────────► يُرسَل عبر NFC
```

---

## ٦. تفاعل Atheer SDK مع الأطراف

```
┌─────────────────────────────────────────────────────────────────────┐
│                        نظام أثير للدفع                             │
│                                                                     │
│  ┌──────────────┐       NFC        ┌──────────────────────────┐    │
│  │  هاتف العميل │◄────────────────►│     هاتف التاجر (POS)    │    │
│  │  (Payer)     │  APDU Protocol   │     (Merchant)           │    │
│  │              │                  │                          │    │
│  │ AtheerSdk    │                  │ AtheerSdk                │    │
│  │ ┌──────────┐ │                  │ ┌────────────────────┐   │    │
│  │ │BiometricP│ │                  │ │AtheerNfcReader     │   │    │
│  │ │rompt     │ │                  │ │+ parseNfc...()     │   │    │
│  │ └────┬─────┘ │                  │ │+ charge()          │   │    │
│  │      │       │                  │ └─────────┬──────────┘   │    │
│  │ ┌────▼─────┐ │                  │           │              │    │
│  │ │Payment   │ │                  │           │              │    │
│  │ │Session   │ │                  │           │ HTTPS        │    │
│  │ │(Armed)   │ │                  │           │              │    │
│  │ └────┬─────┘ │                  └───────────┼──────────────┘    │
│  │      │       │                              │                    │
│  │ ┌────▼─────┐ │                              │                    │
│  │ │APDU      │ │                              │                    │
│  │ │Service   │ │                              │                    │
│  │ │(HCE)     │ │                              │                    │
│  │ └──────────┘ │                              │                    │
│  └──────────────┘                              │                    │
│                                                ▼                    │
│                                   ┌────────────────────┐           │
│                                   │  Atheer Backend    │           │
│                                   │  /api/v1/payments/ │           │
│                                   │  /api/v1/devices/  │           │
│                                   │                    │           │
│                                   │  يتحقق من:         │           │
│                                   │  ✓ HMAC Signature  │           │
│                                   │  ✓ Counter (Replay)│           │
│                                   │  ✓ Timestamp       │           │
│                                   └─────────┬──────────┘           │
│                                             │                       │
│                                             ▼                       │
│                                   ┌────────────────────┐           │
│                                   │  محافظ يمنية        │           │
│                                   │  (جوالي، الكريمي،  │           │
│                                   │   أم فلوس، ...)    │           │
│                                   └────────────────────┘           │
└─────────────────────────────────────────────────────────────────────┘
```

### وصف كل طرف

| الطرف | الدور | المكونات المستخدمة |
|---|---|---|
| **هاتف العميل** | يُصادق ويُوقّع ويُرسَل عبر NFC | `BiometricPrompt`, `AtheerKeystoreManager`, `AtheerPaymentSession`, `AtheerApduService` (HCE) |
| **هاتف التاجر (POS)** | يقرأ NFC ويُرسِل للخادم | `AtheerNfcReader`, `AtheerSdk.charge()`, `AtheerNetworkRouter` |
| **Atheer Backend** | يتحقق من التوقيع ويُنفّذ التحويل | `/api/v1/devices/enroll`, `/api/v1/payments/process` |
| **المحافظ اليمنية** | تُنفّذ عملية الخصم والإيداع الفعلية | يتعامل معها البيكاند مباشرةً |

---

### بروتوكول APDU المستخدم

```
┌─────────────────────────────────────────────────────┐
│              APDU Command / Response Map             │
├──────────────────────────┬──────────────────────────┤
│ SELECT AID               │ 00 A4 04 00 08 [AID]     │
│ (اختيار التطبيق)         │                          │
├──────────────────────────┼──────────────────────────┤
│ GET PAYMENT DATA         │ 00 CA 00 01 00            │
│ (طلب بيانات الدفع)       │                          │
├──────────────────────────┼──────────────────────────┤
│ Response: نجاح           │ [payload] 90 00           │
│ Response: جلسة منتهية    │ 69 85                     │
│ Response: ملف غير موجود  │ 6A 82                     │
│ Response: خطأ عام        │ 6F 00                     │
└──────────────────────────┴──────────────────────────┘

Atheer AID: A0 00 00 00 03 10 10 01
```

---

### قنوات الشبكة المدعومة

```
┌─────────────────────────────────────────────────┐
│            AtheerNetworkRouter                  │
│                                                 │
│  ┌────────────────┐    ┌────────────────────┐   │
│  │ PUBLIC_INTERNET│    │   PRIVATE_APN      │   │
│  │ (افتراضي)      │    │ (Zero-Rating)      │   │
│  │                │    │                    │   │
│  │ Wi-Fi / LTE    │    │ شبكة خلوية خاصة   │   │
│  │ TLS 1.2/1.3    │    │ TLS 1.2/1.3        │   │
│  │ Cert Pinning*  │    │ Cert Pinning       │   │
│  └────────────────┘    └──────────┬─────────┘   │
│                                   │              │
│                         [فشل APN?]               │
│                               └──► Fallback إلى  │
│                                   PUBLIC_INTERNET │
└─────────────────────────────────────────────────┘
* Cert Pinning مُعطَّل في وضع Sandbox
```

---

## ٧. نماذج الأخطاء الموحدة

```kotlin
sealed class AtheerError(val errorCode: String, message: String) : Exception(message)
```

| الكلاس | الكود | السبب الشائع |
|---|---|---|
| `InsufficientFunds` | `ERR_FUNDS` | رصيد المحفظة غير كافٍ |
| `InvalidVoucher` | `ERR_VOUCHER` | رمز منتهي أو مُستخدَم |
| `AuthenticationFailed` | `ERR_AUTH` | توقيع HMAC خاطئ أو مفتاح API منتهٍ |
| `ProviderTimeout` | `ERR_TIMEOUT` | استجابة بطيئة من المحفظة (HTTP 504) |
| `NetworkError` | `ERR_NETWORK` | لا اتصال أو HTTP 503 |
| `UnknownError` | `ERR_UNKNOWN` | أي حالة غير محددة |

```kotlin
// مثال على معالجة الأخطاء
sdk.charge(request, token).fold(
    onSuccess = { response -> showSuccess(response.transactionId) },
    onFailure = { error ->
        when (error) {
            is AtheerError.InsufficientFunds  -> showDialog("رصيد غير كافٍ")
            is AtheerError.AuthenticationFailed -> reEnroll()
            is AtheerError.NetworkError       -> showOfflineMessage()
            else -> showGenericError(error.message)
        }
    }
)
```

---

## ٨. قائمة API العامة

### `AtheerSdk`

```kotlin
// التهيئة
AtheerSdk.init {
    context = applicationContext
    merchantId = "MERCHANT_001"
    apiKey = "api-key-here"
    phoneNumber = "777123456"
    isSandbox = false
    blockRootedDevices = true   // جديد: حجب الأجهزة المكسورة
}

// الحصول على الـ instance
val sdk = AtheerSdk.getInstance()

// ─── فحص الجاهزية (جديد) ───
val report: AtheerReadinessReport = sdk.checkReadiness()
report.isReadyForPayment // true/false

// ─── حالة الجلسة (جديدة) ───
sdk.sessionState.collect { state: SessionState -> ... }
// SessionState: IDLE | ARMED | EXPIRED

// ─── تسجيل الجهاز ───
sdk.enrollDevice(
    forceRenew = false, // جديد: تجاوز إذا مسجّل
    onSuccess = { ... },
    onError = { msg -> ... }
)

// ─── تجهيز الدفع (العميل) ───
sdk.preparePayment(
    activity = this,
    onSuccess = { /* sessionState → ARMED */ },
    onError = { msg -> ... }
)

// ─── الدفع (التاجر) ───
lifecycleScope.launch {
    val result: Result<ChargeResponse> = sdk.charge(chargeRequest, accessToken)
}

// ─── تحليل بيانات NFC ───
val request: ChargeRequest = sdk.parseNfcDataToRequest(
    rawNfcData = "777123456|42|1700000000000|abc==",
    amount = 5000.0,
    receiverAccount = "777000111",
    transactionType = "P2M"
)
```

---

### `AtheerSdkBuilder`

```kotlin
// للتحقق المبكر من الإعدادات قبل التهيئة
val config = AtheerSdkBuilder().apply {
    context = applicationContext
    merchantId = "MERCHANT_001"
    apiKey = "key"
    phoneNumber = "777123456"
}.build() // يرمي IllegalStateException إذا أي حقل ناقص
```

---

### `AtheerReadinessReport`

```kotlin
data class AtheerReadinessReport(
    val isNfcSupported: Boolean,
    val isNfcEnabled: Boolean,
    val isHceSupported: Boolean,
    val isBiometricAvailable: Boolean,
    val isDeviceEnrolled: Boolean,
    val isDeviceRooted: Boolean
) {
    val isReadyForPayment: Boolean
        get() = isNfcSupported && isNfcEnabled && isHceSupported 
                && isDeviceEnrolled && !isDeviceRooted
}
```

---

*وثّقه: Copilot Agent — أبريل 2026*
