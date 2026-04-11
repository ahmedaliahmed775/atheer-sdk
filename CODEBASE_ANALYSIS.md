# تقرير التحليل الهندسي الشامل — Atheer SDK

> **ملاحظة:** هذا التقرير مستنبط حصرياً من قراءة وتحليل الكود المصدري، وهيكلية المجلدات، والاعتماديات (Dependencies). لم يُستعَن بأي ملفات توثيق موجودة مسبقاً.

---

## الفهرس

1. [هوية المشروع والرؤية المعمارية](#١-هوية-المشروع-والرؤية-المعمارية)
2. [تشريح الملفات والأكواد](#٢-تشريح-الملفات-والأكواد)
3. [البروتوكول والمنطق الأساسي](#٣-البروتوكول-والمنطق-الأساسي)
4. [آليات الأمان وإدارة البيانات](#٤-آليات-الأمان-وإدارة-البيانات)

---

## ١. هوية المشروع والرؤية المعمارية

### ١.١ الغرض الأساسي من المشروع

**Atheer SDK** هي مكتبة Android (Android Library) تُمكّن التطبيقات من تنفيذ مدفوعات لا تلامسية عبر تقنية **NFC** بوضعَين متكاملَين:

| الوضع | الجهاز | الدور |
|---|---|---|
| **HCE (Host Card Emulation)** | جهاز المُدفِع (العميل) | يحاكي الجهاز بطاقة دفع لا تلامسية |
| **NFC Reader / SoftPOS** | جهاز التاجر | يقرأ بيانات الدفع من جهاز العميل |

المشكلة التي تعالجها المكتبة: تمكين **المحافظ الإلكترونية اليمنية** (مثل جوالي، الكريمي، أم فلوس) من قبول المدفوعات اللاتلامسية عبر هواتف أندرويد العادية، دون الحاجة لأجهزة POS متخصصة، مع ضمان أعلى مستويات الأمان عبر معمارية **Zero-Trust Dynamic Key Derivation**.

### ١.٢ البنية التحتية وتنظيم المجلدات

```
atheer-sdk/                          ← جذر المستودع (Multi-Module Gradle)
├── build.gradle.kts                 ← إعداد المشاريع الجذرية (Plugins فقط)
├── settings.gradle.kts              ← يُعرِّف الوحدة الوحيدة ":atheer-sdk"
└── atheer-sdk/                      ← الوحدة الرئيسية (Android Library)
    ├── build.gradle.kts             ← اعتماديات + إعداد النشر لـ GitHub Packages
    └── src/main/java/com/atheer/sdk/
        ├── AtheerSdk.kt             ← الواجهة العامة (Public API) للمكتبة
        ├── AtheerPaymentSettingsActivity.kt ← نشاط إعداد تطبيق الدفع الافتراضي
        ├── hce/                     ← طبقة محاكاة البطاقة (Card Emulation)
        │   └── AtheerApduService.kt
        ├── nfc/                     ← طبقة قراءة NFC (SoftPOS)
        │   ├── AtheerNfcReader.kt
        │   └── AtheerFeedbackUtils.kt
        ├── security/                ← طبقة التشفير وإدارة المفاتيح
        │   ├── AtheerKeystoreManager.kt
        │   └── AtheerPaymentSession.kt
        ├── network/                 ← طبقة الاتصال بالشبكة
        │   ├── AtheerNetworkRouter.kt
        │   ├── AtheerCellularRouter.kt  ← (مهجور، مدمج في NetworkRouter)
        │   └── AtheerSyncWorker.kt      ← (مهجور، لا حاجة له)
        ├── database/                ← طبقة قاعدة البيانات المشفرة
        │   ├── AtheerDatabase.kt
        │   ├── TransactionDao.kt
        │   └── TransactionEntity.kt
        └── model/                   ← نماذج البيانات (Data Models)
            ├── AtheerError.kt
            ├── AtheerTransaction.kt
            ├── ChargeRequest.kt
            ├── ChargeResponse.kt
            ├── BalanceResponse.kt
            ├── HistoryResponse.kt
            ├── LoginRequest.kt
            ├── LoginResponse.kt
            ├── SignupRequest.kt
            └── SignupResponse.kt
```

**التسلسل الهرمي للطبقات (Layered Architecture):**

```
┌─────────────────────────────────────────────────┐
│         Public API Layer (AtheerSdk)             │
├─────────────────────────────────────────────────┤
│   HCE Layer     │   NFC Reader Layer             │
│ (AtheerApduService) │ (AtheerNfcReader)          │
├─────────────────────────────────────────────────┤
│         Security Layer                           │
│  (AtheerKeystoreManager + AtheerPaymentSession)  │
├─────────────────────────────────────────────────┤
│    Network Layer        │  Database Layer        │
│ (AtheerNetworkRouter)   │  (AtheerDatabase)      │
├─────────────────────────────────────────────────┤
│         Data Models Layer (model/*)              │
└─────────────────────────────────────────────────┘
```

### ١.٣ أنماط التصميم المستخدمة

| نمط التصميم | المكان | التفصيل |
|---|---|---|
| **Singleton** | `AtheerSdk`, `AtheerDatabase`, `AtheerPaymentSession`, `AtheerFeedbackUtils` | يُضمن وجود نسخة واحدة فقط في الذاكرة في كل وقت |
| **Builder / DSL Pattern** | `AtheerSdkConfig` + `AtheerSdk.init {}` | يُتيح تهيئة المكتبة بأسلوب Kotlin DSL مريح |
| **Sealed Class (للأخطاء)** | `AtheerError` | تمثيل متعدد لأنواع الأخطاء مع ضمان type-safety كامل |
| **DAO Pattern (Repository)** | `TransactionDao` + `AtheerDatabase` | فصل منطق الوصول للبيانات عن المنطق التجاري |
| **Observer / Flow** | `TransactionDao.getAllTransactions()` | يعيد `Flow<List<TransactionEntity>>` للتحديث التلقائي |
| **Factory Method** | `AtheerDatabase.getInstance()` | بناء قاعدة البيانات مع إدارة كلمة المرور الاستثنائية |
| **Feature Toggle** | `AtheerNetworkRouter.NetworkMode` | تبديل ديناميكي بين وضع الإنترنت العام وشبكة APN خاصة |
| **Strategy Pattern** | `resolveClient()` في `AtheerNetworkRouter` | اختيار استراتيجية الاتصال بالشبكة وقت التشغيل |
| **Object (Kotlin Singleton)** | `AtheerPaymentSession`, `AtheerFeedbackUtils` | استخدام `object` لضمان النسخة الفردية thread-safe |

---

## ٢. تشريح الملفات والأكواد

### ٢.١ `AtheerSdk.kt` — الواجهة العامة للمكتبة

**الوظيفة:** نقطة الدخول الوحيدة للمطورين الذين يدمجون المكتبة. يجمع جميع العمليات الرئيسية في واجهة موحدة.

**الكلاسات والدوال:**

```kotlin
class AtheerSdkConfig        // حاوية إعدادات DSL قبل بناء SDK
class AtheerSdk private constructor(...)  // الكلاس الرئيسي (Singleton)
```

| الدالة | الغرض |
|---|---|
| `init(block)` | تهيئة SDK مرة واحدة (Double-Checked Locking Singleton) |
| `getInstance()` | استرجاع النسخة المُهيّأة أو رمي استثناء |
| `enrollDevice(onSuccess, onError)` | تسجيل الجهاز في الخادم واستلام `deviceSeed` |
| `preparePayment(activity, ...)` | تشغيل مصادقة بيومترية → توليد LUK → إنشاء التوقيع → تسليح الجلسة |
| `parseNfcDataToRequest(...)` | تحليل الحمولة الخام من NFC إلى `ChargeRequest` منظم |
| `charge(request, accessToken)` | إرسال طلب الدفع إلى الخادم عبر `AtheerNetworkRouter` |

**المتغيرات الهامة:**
- `PRODUCTION_URL = "http://206.189.137.59:4000"` — عنوان خادم الإنتاج
- `SANDBOX_URL = "http://10.0.2.2:4000"` — عنوان محاكي أندرويد (localhost)
- `CHARGE_PATH = "/api/v1/payments/process"` — مسار تنفيذ الدفع
- `ENROLL_PATH = "/api/v1/devices/enroll"` — مسار تسجيل الجهاز
- `sdkScope` — نطاق Coroutine مع `SupervisorJob` لعزل فشل العمليات

---

### ٢.٢ `AtheerPaymentSettingsActivity.kt` — نشاط إعداد الدفع الافتراضي

**الوظيفة:** واجهة مستخدم بسيطة تُمكّن المستخدم من تعيين تطبيقه كتطبيق الدفع الافتراضي في نظام أندرويد.

**الدوال:**

| الدالة | الغرض |
|---|---|
| `onCreate()` | بناء الواجهة برمجياً (بدون XML) وعرض زر "ضبط كافتراضي" |
| `requestSetDefaultPaymentApp()` | يطلب من نظام أندرويد تغيير تطبيق الدفع عبر `CardEmulation.ACTION_CHANGE_DEFAULT` |
| `onActivityResult()` | معالجة نتيجة طلب تغيير الافتراضي |

**السياق التقني:** تستخدم `CardEmulation.getInstance()` مع `ComponentName` للإشارة إلى `AtheerApduService` ليكون المعالج الافتراضي لفئة `CATEGORY_PAYMENT`.

---

### ٢.٣ `hce/AtheerApduService.kt` — خدمة محاكاة البطاقة (HCE)

**الوظيفة:** خدمة نظام Android تعمل في الخلفية؛ تجعل الجهاز يتصرف كبطاقة دفع لا تلامسية عند اقترابه من جهاز NFC Reader (POS).

**الكود الرئيسي:**

```kotlin
class AtheerApduService : HostApduService()
```

| الدالة | الغرض |
|---|---|
| `processCommandApdu(commandApdu, extras)` | نقطة الدخول الرئيسية — تستقبل أوامر APDU من قارئ NFC |
| `handleGetPaymentData()` | يستهلك الجلسة المسلحة ذرياً ويُرجع البيانات الموقعة |
| `showPaymentSentNotification()` | يعرض إشعاراً محلياً للمستخدم بنجاح إرسال بيانات الدفع |
| `isSelectAidCommand(apdu)` | يتحقق من أمر `SELECT AID` (0x00, 0xA4) |
| `isGetPaymentDataCommand(apdu)` | يتحقق من أمر سحب البيانات (0x00, 0xCA) |

**ردود APDU المعرَّفة:**

| الثابت | القيمة (hex) | المعنى |
|---|---|---|
| `APDU_OK` | `90 00` | نجاح |
| `APDU_FILE_NOT_FOUND` | `6A 82` | أمر غير معروف |
| `APDU_SESSION_NOT_ARMED` | `69 85` | لا توجد جلسة نشطة |
| `APDU_UNKNOWN_ERROR` | `6F 00` | خطأ داخلي |

**الإصلاح الهندسي (Fix #4):** استبدال القراءة المنفصلة `getPayload()` + `getSignature()` + `clearSession()` بعملية ذرية واحدة `consumeSession()` لمنع **Race Conditions** بين خيط NFC وخيط المصادقة.

---

### ٢.٤ `security/AtheerKeystoreManager.kt` — مدير المفاتيح التشفيرية

**الوظيفة:** المستودع المركزي للمفاتيح التشفيرية. يعمل داخل بيئة **TEE (Trusted Execution Environment)** أو **StrongBox** في الأجهزة الداعمة.

**الكلاس:**
```kotlin
internal class AtheerKeystoreManager(private val context: Context)
```
(مُعرَّف كـ `internal` لمنع الوصول من خارج الـ SDK — Fix A-01)

| الدالة | الغرض |
|---|---|
| `generateMasterSeed()` | توليد مفتاح AES-256 وتخزينه في Android Keystore |
| `isDeviceEnrolled()` | التحقق من تسجيل الجهاز (وجود `deviceSeed` من الخادم) |
| `storeEnrolledSeed(seedBase64)` | تخزين الـ seed المُستلم من الخادم في `EncryptedSharedPreferences` |
| `incrementAndGetCounter()` | قراءة + زيادة العداد الرتيب `@Synchronized` (thread-safe) |
| `deriveLUK(counter)` | اشتقاق مفتاح استخدام محدود (LUK) عبر `HMAC-SHA256(enrolledSeed, counter)` |
| `signWithLUK(payload, luk)` | توقيع الحمولة بمفتاح LUK وإعادة Base64 |
| `generateNonce()` | توليد 16 بايت عشوائي (Nonce) لكلمة مرور قاعدة البيانات |

**الخوارزمية الأساسية (Fix C-03):**
```
SDK:     LUK = HMAC-SHA256(enrolledSeed, counter)
Backend: LUK = HMAC-SHA256(HMAC-SHA256(MASTER_SEED, deviceId), counter)
```
حيث `enrolledSeed` من جانب الـ SDK يساوي `HMAC-SHA256(MASTER_SEED, deviceId)` من جانب الخادم.

---

### ٢.٥ `security/AtheerPaymentSession.kt` — مدير جلسة الدفع

**الوظيفة:** يدير "النافذة الزمنية المسلحة" بين لحظة نجاح المصادقة البيومترية ولحظة اكتشاف جهاز NFC.

```kotlin
internal object AtheerPaymentSession   // Kotlin Singleton thread-safe
```

| الدالة | الوصف |
|---|---|
| `arm(payload, signature)` | @Synchronized — تفعيل الجلسة وتخزين البيانات مع timestamp |
| `isSessionArmed()` | التحقق من أن الجلسة لم تتجاوز 60 ثانية |
| `consumeSession()` | @Synchronized — قراءة + مسح ذري في خطوة واحدة |
| `clearSession()` | @Synchronized — إبطال الجلسة فوراً |

**المتغيرات المتطايرة (`@Volatile`):**
- `armedTimestamp` — توقيت تسليح الجلسة
- `signature` — التوقيع الرقمي HMAC-SHA256
- `payload` — الحمولة: `"phoneNumber|counter|timestamp"`

**سبب التصميم (Fix B-02):** منع `Race Conditions` بين خيط NFC الذي ينتظر الجلسة وخيط الـ UI الذي قد يعيد تسليح الجلسة في نفس اللحظة.

---

### ٢.٦ `network/AtheerNetworkRouter.kt` — موجه الشبكة

**الوظيفة:** تنفيذ طلبات HTTP مع دعم مسارَي الاتصال: الإنترنت العام أو نفق APN خاص.

```kotlin
internal class AtheerNetworkRouter(
    context, networkMode: NetworkMode, isSandbox: Boolean
)
```

| الدالة | الغرض |
|---|---|
| `executeStandard(url, body, token, apiKey)` | تنفيذ طلب POST/GET مع headers موحدة ومعالجة أخطاء |
| `resolveClient()` | اختيار عميل HTTP المناسب بناءً على `NetworkMode` |
| `acquireApnClient()` | طلب شبكة خلوية خاصة عبر `ConnectivityManager` |
| `mapToAtheerError(body, default)` | تحويل رد الخادم JSON إلى `AtheerError` مناسب |
| `isNetworkAvailable()` | التحقق من توفر الاتصال بالإنترنت |

**Headers المُرسَلة لكل طلب:**
```
Accept: application/json
Authorization: Bearer <accessToken>   (إن وجد)
x-atheer-api-key: <merchantApiKey>    (إن وجد)
```

**Certificate Pinning (وضع الإنتاج فقط):**
- تثبيت شهادتَين (Primary + Backup) للنطاقَين `api.atheer.com` و `sandbox.atheer.com`
- في وضع Sandbox: تعطيل Certificate Pinning والسماح بـ HTTP cleartext

**Feature Toggle — وضع APN:**
```
PUBLIC_INTERNET  →  publicClient (OkHttpClient عادي)
PRIVATE_APN      →  acquireApnClient() أو fallback لـ publicClient
```

---

### ٢.٧ `nfc/AtheerNfcReader.kt` — قارئ NFC (جانب التاجر)

**الوظيفة:** يُمكّن جهاز التاجر من قراءة بيانات الدفع من جهاز العميل عبر NFC.

```kotlin
internal class AtheerNfcReader(...) : NfcAdapter.ReaderCallback
```

**المتغيرات الثابتة (Protocol Constants):**
```kotlin
ATHEER_AID = [0xA0, 0x00, 0x00, 0x00, 0x03, 0x10, 0x10, 0x01]
SELECT_APDU = [0x00, 0xA4, 0x04, 0x00, AID_size] + ATHEER_AID
GET_PAYMENT_DATA_APDU = [0x00, 0xCA, 0x00, 0x01, 0x00]
TIMEOUT_MS = 5000
```

| الدالة | الغرض |
|---|---|
| `onTagDiscovered(tag)` | callback أندرويد — يُطلق Coroutine لقراءة الـ Tag |
| `readNfcTag(tag)` | تسلسل أوامر APDU + قياس RTT + بناء `ChargeRequest` |

**الاستثناء المخصص:**
```kotlin
class RelayAttackException(message: String) : Exception(message)
```
يُرمى عند تجاوز RTT حد 50 ميلي ثانية.

---

### ٢.٨ `database/AtheerDatabase.kt` — قاعدة البيانات المشفرة

**الوظيفة:** قاعدة بيانات Room مشفرة بـ SQLCipher تخزن سجلات المعاملات المالية محلياً.

```kotlin
@Database(entities = [TransactionEntity::class], version = 3)
abstract class AtheerDatabase : RoomDatabase()
```

**بناء قاعدة البيانات:**
1. استدعاء `AtheerKeystoreManager.generateNonce()` لتوليد كلمة مرور عشوائية
2. تخزين كلمة المرور في `EncryptedSharedPreferences` (AES-256-GCM)
3. تمرير كلمة المرور إلى `SupportFactory` من SQLCipher

---

### ٢.٩ `database/TransactionDao.kt` — واجهة الوصول للبيانات

| الدالة | SQL | الغرض |
|---|---|---|
| `insertTransaction(entity)` | `INSERT OR REPLACE` | إدراج معاملة جديدة |
| `getUnsyncedTransactions()` | `SELECT WHERE is_synced=0` | المعاملات غير المُزامَنة |
| `getAllTransactions()` | `SELECT ORDER BY timestamp DESC` | كـ Flow للمراقبة |
| `updateSyncStatus(id, status)` | `UPDATE SET is_synced` | تحديث حالة المزامنة |
| `deleteSyncedTransactions()` | `DELETE WHERE is_synced=1` | تنظيف البيانات القديمة |
| `getTransactionById(id)` | `SELECT WHERE transaction_id=?` | بحث بالمعرف |
| `deleteTransactionById(id)` | `DELETE WHERE transaction_id=?` | حذف معاملة |

---

### ٢.١٠ نماذج البيانات (`model/`)

| الكلاس | الغرض |
|---|---|
| `AtheerError` (Sealed Class) | تمثيل موحد لأنواع الأخطاء: `InsufficientFunds`, `InvalidVoucher`, `AuthenticationFailed`, `ProviderTimeout`, `NetworkError`, `UnknownError` |
| `ChargeRequest` | طلب تنفيذ دفع — يحتوي على: amount, currency, merchantId, deviceId, counter, timestamp, signature |
| `ChargeResponse` | استجابة عملية الدفع — يحتوي على: transactionId, status, balance, issuerRef |
| `AtheerTransaction` | تمثيل عملية دفع مكتملة في الذاكرة |
| `TransactionEntity` | كيان قاعدة البيانات لجدول `transactions` |
| `LoginRequest` / `LoginResponse` | بيانات تسجيل الدخول والـ Bearer Token |
| `SignupRequest` / `SignupResponse` | بيانات إنشاء حساب جديد |
| `BalanceResponse` | استجابة استعلام الرصيد |
| `HistoryResponse` | قائمة المعاملات التاريخية |

---

## ٣. البروتوكول والمنطق الأساسي

### ٣.١ تدفق تسجيل الجهاز (Device Enrollment Flow)

```
التطبيق                    Atheer SDK                    خادم أثير
   │                           │                              │
   │── AtheerSdk.init{} ──────►│                              │
   │                           │ (إنشاء AtheerSdk Singleton)  │
   │── enrollDevice() ────────►│                              │
   │                           │── POST /api/v1/devices/enroll│
   │                           │   Body: { deviceId: phone }  │
   │                           │◄─────── { deviceSeed: "..." }│
   │                           │                              │
   │                           │ storeEnrolledSeed(seed)      │
   │                           │ (EncryptedSharedPreferences) │
   │◄── onSuccess() ───────────│                              │
```

### ٣.٢ تدفق تهيئة الدفع (Payment Preparation Flow)

```
العميل (المُدفِع)                    Atheer SDK
      │                                 │
      │── preparePayment(activity) ────►│
      │                                 │ (عرض نافذة بيومترية)
      │ ── [بصمة إصبع / وجه] ─────────►│
      │                                 │ counter = incrementAndGetCounter() + 1
      │                                 │ luk     = HMAC-SHA256(enrolledSeed, counter)
      │                                 │ payload = "phone|counter|timestamp"
      │                                 │ cryptogram = HMAC-SHA256(luk, payload)
      │                                 │ AtheerPaymentSession.arm(payload, cryptogram)
      │◄── onSuccess() ────────────────►│
      │                                 │ (الجلسة مسلحة لمدة 60 ثانية)
```

### ٣.٣ تدفق عملية الدفع عبر NFC (NFC Payment Flow)

```
جهاز التاجر (Reader)          جهاز العميل (HCE - AtheerApduService)
       │                                      │
       │── SELECT_APDU (0x00,0xA4) ──────────►│
       │                                      │ (SELECT AID → APDU_OK)
       │◄─────────────── 90 00 ───────────────│
       │                                      │
       │── GET_PAYMENT_DATA_APDU (0x00,0xCA) ►│
       │  [قياس RTT: startTime]               │ consumeSession() ← ذري
       │  [إذا RTT > 50ms → RelayAttack!]     │ payload = "phone|counter|timestamp"
       │◄── rawPayload + "|" + signature ─────│ response = payload|signature + 90 00
       │  [إذا RTT > 50ms → استثناء]          │ clearSession() (الجلسة تُمسح فوراً)
       │                                      │
       │ parseNfcDataToRequest(rawPayload)    │
       │ (بناء ChargeRequest كامل)            │
       │                                      │
```

### ٣.٤ تدفق إرسال الدفع للخادم (Charge Submission Flow)

```
تطبيق التاجر          Atheer SDK               خادم أثير
     │                    │                         │
     │── charge(req) ────►│                         │
     │                    │── POST /api/v1/payments/process
     │                    │   Headers:               │
     │                    │     Authorization: Bearer│
     │                    │     x-atheer-api-key     │
     │                    │   Body: {                │
     │                    │     amount, currency,    │
     │                    │     merchantId,          │
     │                    │     deviceId, counter,   │
     │                    │     timestamp,           │
     │                    │     authMethod: "BIOMETRIC_CRYPTOGRAM",
     │                    │     signature            │
     │                    │   }                      │
     │                    │◄── { transactionId, status }
     │◄── Result.success(ChargeResponse)             │
```

### ٣.٥ بروتوكول APDU الخاص بأثير

الـ SDK يُعرِّف **AID (Application Identifier)** خاصاً:

```
A0 00 00 00 03 10 10 01   ← AID خاص بأثير
A0 00 00 00 03 10 10      ← Visa (للتوافق مع أجهزة POS العالمية)
A0 00 00 00 04 10 10      ← Mastercard (للتوافق مع أجهزة POS العالمية)
```

**تسلسل الأوامر:**

| الخطوة | الأمر | CLA | INS | المعنى |
|---|---|---|---|---|
| 1 | SELECT AID | `0x00` | `0xA4` | اختيار تطبيق أثير |
| 2 | GET PAYMENT DATA | `0x00` | `0xCA` | سحب بيانات الدفع المسلحة |

### ٣.٦ آلية الحماية من هجوم الترحيل (Relay Attack Protection)

```kotlin
val startTime = SystemClock.elapsedRealtime()
val paymentResponse = isoDep.transceive(GET_PAYMENT_DATA_APDU)
val rtt = SystemClock.elapsedRealtime() - startTime

if (rtt > 50) throw RelayAttackException("RTT: ${rtt}ms")
```

**المبدأ:** في الاتصال NFC الحقيقي، يجب أن يستجيب الجهاز في أقل من 50 ميلي ثانية. أي تأخير يدل على وجود وسيط يُحاول ترحيل الإشارة عبر مسافة بعيدة.

---

## ٤. آليات الأمان وإدارة البيانات

### ٤.١ طبقات الأمان (Security Layers)

```
┌─────────────────────────────────────────────────────┐
│  Layer 1: Android Keystore (TEE/StrongBox)           │
│  Master Seed مخزن في Hardware-backed Keystore       │
├─────────────────────────────────────────────────────┤
│  Layer 2: EncryptedSharedPreferences (AES-256-GCM)  │
│  enrolledSeed, monotonic counter, db passphrase     │
├─────────────────────────────────────────────────────┤
│  Layer 3: SQLCipher (AES-256)                       │
│  قاعدة بيانات المعاملات مشفرة بالكامل              │
├─────────────────────────────────────────────────────┤
│  Layer 4: HMAC-SHA256 Dynamic Key Derivation        │
│  LUK جديد لكل عملية (Zero-Trust)                   │
├─────────────────────────────────────────────────────┤
│  Layer 5: Biometric Authentication                  │
│  مصادقة بيومترية قبل أي عملية دفع                 │
├─────────────────────────────────────────────────────┤
│  Layer 6: Certificate Pinning (Production)          │
│  حماية من MITM attacks                             │
├─────────────────────────────────────────────────────┤
│  Layer 7: Session TTL (60 seconds)                  │
│  نافذة زمنية محدودة للجلسة المسلحة                │
├─────────────────────────────────────────────────────┤
│  Layer 8: Relay Attack Protection (RTT < 50ms)      │
│  قياس زمن الاستجابة لمنع هجمات الترحيل            │
└─────────────────────────────────────────────────────┘
```

### ٤.٢ آلية التشفير التفصيلية

#### أ. تخزين الـ Seed (Enrollment)

```
الخادم  ──►  deviceSeed (Base64)
                    │
                    ▼
   EncryptedSharedPreferences
   (AES256_SIV للمفاتيح + AES256_GCM للقيم)
   المحمية بـ MasterKey من Android Keystore
```

#### ب. اشتقاق مفتاح LUK لكل عملية

```
enrolledSeed + counter
       │
       ▼
HMAC-SHA256 → LUK (32 bytes)
       │
       ▼
HMAC-SHA256(LUK, "phone|counter|timestamp") → signature (Base64)
```

هذه الآلية تُسمى **"Zero-Trust Dynamic Key Derivation"** لأن:
- لا يُعاد استخدام أي مفتاح في عمليتَين مختلفتَين
- العداد الرتيب يمنع **Replay Attacks** (لا يمكن إعادة استخدام توقيع قديم)
- يتطابق الحساب في الطرفَين (SDK + Backend) دون إرسال المفتاح عبر الشبكة

#### ج. تشفير قاعدة البيانات

```
generateNonce() → 32 حرف hex عشوائي
        │
        ▼
EncryptedSharedPreferences (AES-256-GCM)
        │
        ▼
SupportFactory(passphrase.toByteArray())
        │
        ▼
SQLCipher → ملف .db مشفر بالكامل
```

### ٤.٣ إدارة الحالة (State Management)

| المكوِّن | نوع الحالة | مكان التخزين | مدة الصلاحية |
|---|---|---|---|
| `AtheerSdk` (Singleton) | حالة في الذاكرة | `@Volatile instance` | طول عمر التطبيق |
| `AtheerPaymentSession` | جلسة مسلحة مؤقتة | `object` في الذاكرة | 60 ثانية |
| `enrolledSeed` | حالة دائمة | `EncryptedSharedPreferences` | دائم حتى الحذف |
| `monotonic_counter` | عداد متزايد | `EncryptedSharedPreferences` | دائم (لا يُعاد ضبطه) |
| `db_passphrase` | حالة دائمة | `EncryptedSharedPreferences` | دائم حتى حذف البيانات |
| `TransactionEntity` | سجل معاملة | SQLCipher Database | دائم (حتى حذف صريح) |
| `AtheerDatabase` (Singleton) | اتصال قاعدة بيانات | `@Volatile INSTANCE` | طول عمر التطبيق |

### ٤.٤ التحقق من صحة البيانات (Validation)

| موضع التحقق | المنطق | الكلاس |
|---|---|---|
| تهيئة SDK | `require(phoneNumber.isNotBlank())` | `AtheerSdk.init()` |
| حمولة NFC | `parts.size < 4` → استثناء | `parseNfcDataToRequest()` |
| قيمة Counter | `toLongOrNull()` + استثناء | `parseNfcDataToRequest()` |
| قيمة Timestamp | `toLongOrNull()` + استثناء | `parseNfcDataToRequest()` |
| تسجيل الجهاز | `isDeviceEnrolled()` قبل الاشتقاق | `AtheerKeystoreManager.getEnrolledSeed()` |
| حالة الجلسة | `isSessionArmed()` + TTL 60s | `AtheerPaymentSession.consumeSession()` |
| توفر الشبكة | `isNetworkAvailable()` | `AtheerSdk.charge()` |
| RTT قياس | `rtt > 50ms` → RelayAttackException | `AtheerNfcReader.readNfcTag()` |

### ٤.٥ معالجة الأخطاء الموحدة

```kotlin
sealed class AtheerError(val errorCode: String, override val message: String) : Exception(message)
```

تحويل أكواد HTTP إلى أخطاء دلالية:

| كود HTTP | AtheerError |
|---|---|
| 401 | `AuthenticationFailed` |
| 403 | `AuthenticationFailed("صلاحيات غير كافية")` |
| 400 | `InvalidVoucher` |
| 503 | `NetworkError("وضع الصيانة")` |
| 504 | `ProviderTimeout` |

تحويل حقل `status` في JSON:

| status JSON | AtheerError |
|---|---|
| `"REJECTED"` | `InsufficientFunds` |
| `"EXPIRED"` | `InvalidVoucher` |
| `"AUTH_FAILED"` | `AuthenticationFailed` |
| `"MAINTENANCE"` | `NetworkError` |

---

## ملخص إجمالي

**Atheer SDK** هي مكتبة Android احترافية متكاملة تُنفِّذ بروتوكول دفع لاتلامسي مخصصاً للسوق اليمني. تجمع بين:

- **تقنية HCE** لجعل أي هاتف أندرويد بطاقة دفع
- **معمارية Zero-Trust** مع اشتقاق مفاتيح ديناميكية لكل عملية
- **ثماني طبقات أمان** متكاملة من الأجهزة حتى الشبكة
- **حماية من Relay Attacks** بقياس زمن الاستجابة
- **قاعدة بيانات مشفرة** لتخزين سجلات المعاملات محلياً
- **دعم شبكة APN خاصة** للمعاملات ذات الأولوية

الكود مكتوب بأسلوب هندسي ناضج مع عزل واضح للطبقات، وحل صريح لمشاكل التزامن (Thread-Safety)، وتوثيق كامل لكل قرار هندسي باللغة العربية.
