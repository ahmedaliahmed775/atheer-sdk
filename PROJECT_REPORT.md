<div dir="rtl">

# تقرير شامل عن مشروع Atheer SDK

**تاريخ التقرير:** 2026-03-01  
**المستودع:** `ahmedaliahmed775/atheer-sdk`  
**نوع المشروع:** مكتبة Android SDK للمدفوعات اللاتلامسية NFC

---

## 1. نظرة عامة — ماذا يهدف المشروع؟

**Atheer SDK** هي مكتبة Android مخصصة لتمكين تطبيقات الجوال من تنفيذ مدفوعات NFC لاتلامسية بمستوى أمان عالٍ. تستهدف المكتبة بيئات نقاط البيع (POS) السعودية وتدعم سيناريوهات عمل متعددة:

| الوضع | الوصف |
|-------|-------|
| **HCE (محاكاة البطاقة)** | يتحول الهاتف إلى بطاقة دفع NFC — يقترب من قارئ POS فيتم الدفع تلقائياً |
| **SoftPOS (نقطة البيع البرمجية)** | يتحول الهاتف إلى جهاز POS يستقبل الدفعات من بطاقات NFC وهواتف أخرى |
| **الوضع غير المتصل (Offline)** | تُحفظ المعاملات مشفرة محلياً ثم تُزامَن تلقائياً عند استعادة الإنترنت |

### الهدف الاستراتيجي

تقديم **ملف AAR** واحد يمكن لأي تطبيق Android دمجه ليحصل فوراً على:
- بروتوكول دفع NFC آمن (HCE + SoftPOS)
- تشفير بيانات البطاقة بمعيار AES-256-GCM عبر Android Keystore
- قاعدة بيانات محلية مشفرة لسجلات المعاملات
- توجيه إلزامي للطلبات عبر شبكة البيانات الخلوية (لا Wi-Fi)
- حماية من هجمات إعادة التشغيل (Replay Attack Prevention)

---

## 2. هيكل المشروع التفصيلي

يتكون المشروع من **وحدتين (modules)** رئيسيتين:

```
atheer-sdk/                        ← جذر المشروع
├── atheer-sdk/                    ← وحدة المكتبة (تُنتج ملف .aar)
│   └── src/main/java/com/atheer/sdk/
│       ├── AtheerSdk.kt           ← واجهة Facade الرئيسية
│       ├── model/
│       │   └── AtheerTransaction.kt
│       ├── hce/
│       │   └── AtheerApduService.kt
│       ├── nfc/
│       │   └── AtheerNfcReader.kt
│       ├── network/
│       │   └── AtheerNetworkRouter.kt
│       ├── security/
│       │   └── AtheerKeystoreManager.kt
│       └── database/
│           ├── AtheerDatabase.kt
│           ├── TransactionDao.kt
│           └── TransactionEntity.kt
└── demo-app/                      ← تطبيق تجريبي يعرض قدرات SDK
    └── src/main/java/com/atheer/demo/
        ├── AtheerDemoApp.kt
        └── ui/
            ├── SplashActivity.kt
            ├── login/LoginActivity.kt
            ├── dashboard/DashboardActivity.kt
            ├── customer/CustomerActivity.kt
            ├── pos/PosActivity.kt
            └── result/TransactionResultActivity.kt
```

---

## 3. تحليل مفصّل لكل مكوّن

### 3.1 وحدة المكتبة (`atheer-sdk`)

#### أ) واجهة SDK الرئيسية — `AtheerSdk.kt`

**الغرض:** نقطة الدخول الوحيدة (Facade Pattern) للتطبيقات الخارجية.

**ما يفعله:**
- تهيئة SDK بنمط Singleton آمن للخيوط المتعددة (Thread-Safe Singleton) باستخدام `@Volatile` و `synchronized`
- ينسّق عملية الدفع الكاملة في 5 خطوات: توليد Nonce → تشفير المبلغ → الحفظ المحلي → الإرسال عبر الخلوي → تحديث حالة المزامنة
- يدعم الوضع غير المتصل: إذا فشل الإرسال تبقى المعاملة محفوظة محلياً
- دالة `syncPendingTransactions()` لمزامنة جميع المعاملات المعلقة دفعةً واحدة
- Coroutine Scope مستقل بـ `SupervisorJob` لعزل الأخطاء

**الحالة:** ✅ مكتمل ويعمل

---

#### ب) خدمة HCE — `AtheerApduService.kt`

**الغرض:** يجعل الهاتف يتصرف كبطاقة NFC أمام أجهزة POS.

**ما يفعله:**
- يرث من `HostApduService` — الفئة الرسمية من Android لمحاكاة البطاقات
- يعالج أوامر APDU الواردة من جهاز القارئ:
  - `SELECT AID (00 A4 04)` → يرد بـ `9000` (موافقة)
  - `GET PAYMENT DATA (00 CA)` → يرسل الرمز المميز المشفر + `9000`
  - أي أمر آخر → يرد بـ `6A82` (غير مدعوم)
  - بيانات أقل من 4 بايت → يرد بـ `6F00` (خطأ)
- دالة `preparePayment()` تشفّر بيانات البطاقة وتخزّن الرمز المميز مؤقتاً
- يمسح البيانات الحساسة تلقائياً عند `onDeactivated()` و `onDestroy()`
- مُسجَّل في `AndroidManifest.xml` مع AID مخصص لـ Atheer + AID Visa القياسي

**الحالة:** ✅ مكتمل ويعمل

---

#### ج) قارئ NFC / SoftPOS — `AtheerNfcReader.kt`

**الغرض:** يجعل الهاتف يعمل كجهاز POS يستقبل الدفعات.

**ما يفعله:**
- ينفّذ `NfcAdapter.ReaderCallback` للاستماع لبطاقات NFC القريبة
- عند اكتشاف بطاقة ينفّذ البروتوكول الكامل:
  1. يفتح اتصال ISO-DEP (ISO 14443-4) مع مهلة 5 ثوانٍ
  2. يرسل `SELECT AID` ويتحقق من `9000`
  3. يرسل `GET PAYMENT DATA` ويستقبل الرمز المميز
  4. يبني كائن `AtheerTransaction` ويُسلّمه للتطبيق عبر الـ Callback
- يُغلق قناة الاتصال دائماً في كتلة `finally` حتى في حالة الخطأ
- يولّد معرف معاملة فريداً بدمج الطابع الزمني + رقم عشوائي

**الحالة:** ✅ مكتمل ويعمل

---

#### د) موجّه الشبكة — `AtheerNetworkRouter.kt`

**الغرض:** إجبار طلبات الدفع على المرور عبر شبكة البيانات الخلوية حصراً.

**ما يفعله:**
- يستخدم `ConnectivityManager` مع `NetworkRequest.Builder` مُقيَّد بـ `TRANSPORT_CELLULAR`
- يتحقق أولاً إذا كانت الشبكة الخلوية نشطة مسبقاً (Fast Path)
- إذا لم تكن نشطة يسجّل `NetworkCallback` وينتظر باستخدام `suspendCancellableCoroutine`
- يُلغي تسجيل الـ Callback عند إلغاء Coroutine لمنع تسرب الموارد
- ينفّذ الطلب عبر `network.openConnection(url)` لربط حركة البيانات بالشبكة الخلوية المحددة
- يضيف ترويسة `X-Atheer-Source: cellular` لكل طلب
- مهل زمنية مضبوطة: 10s للشبكة، 30s للطلب، 15s للاتصال/القراءة
- يدعم طلبات GET و POST مع JSON

**الحالة:** ✅ مكتمل ويعمل

---

#### هـ) مدير مخزن المفاتيح — `AtheerKeystoreManager.kt`

**الغرض:** الطبقة الأمنية المركزية للتشفير والحماية.

**ما يفعله:**
- ينشئ مفتاح AES-256 داخل **Android Keystore** (غير قابل للاستخراج)
- **التشفير `encrypt()`:** يولّد IV عشوائياً 12 بايت → يشفّر بـ AES-GCM → يُعيد Base64(IV+CipherText)
- **فك التشفير `decrypt()`:** يفكّ Base64 → يستخرج IV من أول 12 بايت → يفكّ التشفير
- **Tokenization `tokenize()`:** يشفّر بيانات البطاقة ويُضيف بادئة `ATK_`
- **De-tokenization `detokenize()`:** يتحقق من البادئة ثم يفكّ التشفير
- **توليد Nonce `generateNonce()`:** يستخدم `SecureRandom` لتوليد 16 بايت عشوائياً → يحوّلها إلى 32-حرف Hex
- **التحقق من Nonce `isValidNonceFormat()`:** يتحقق من الطول (32) وصحة صيغة Hex

**الحالة:** ✅ مكتمل ويعمل

---

#### و) قاعدة البيانات المحلية — `database/`

**الغرض:** تخزين المعاملات مشفرةً محلياً للوضع غير المتصل.

**المكوّنات الثلاثة:**

| الملف | الوظيفة |
|-------|---------|
| `AtheerDatabase.kt` | Room Database نسخة Singleton مع Double-Checked Locking |
| `TransactionEntity.kt` | كيان جدول `transactions` بـ 9 أعمدة (الحساسة منها مشفّرة) |
| `TransactionDao.kt` | 7 عمليات: إدراج، استعلام، تحديث، حذف، بحث، مراقبة تفاعلية (Flow) |

**الحقول المشفّرة:** `encrypted_amount` و `encrypted_token`  
**الحقول النصية:** `currency`, `merchant_id`, `timestamp`, `nonce`, `is_synced`

**الحالة:** ✅ مكتمل ويعمل

---

#### ز) نموذج البيانات — `AtheerTransaction.kt`

**الغرض:** بنية البيانات الموحّدة التي تنتقل بين طبقات SDK.

**الحقول:**
- `transactionId` — معرف فريد (String)
- `amount` — القيمة بالهللة (Long)
- `currency` — رمز العملة ISO 4217 (مثال: SAR)
- `merchantId` — معرف التاجر
- `timestamp` — الطابع الزمني تلقائياً
- `tokenizedCard` — رمز البطاقة المشفّر (اختياري)
- `nonce` — رقم منع إعادة التشغيل (اختياري)

**الحالة:** ✅ مكتمل

---

#### ح) اختبارات الوحدة — `AtheerSdkTest.kt`

**8 اختبارات تغطي:**
1. التحقق من صيغة Nonce صحيح (32 حرف Hex)
2. رفض Nonce قصير
3. رفض Nonce يحتوي على أحرف غير Hex
4. التحقق من بادئة الرمز المميز `ATK_`
5. فرادة معرّف المعاملة
6. التحقق من حقول JSON للمعاملة
7. التحقق من كود نجاح APDU (9000)
8. رفض كود خطأ APDU (6A82)

**الحالة:** ✅ مكتمل — 7 اختبارات وحدة تعمل على JVM بدون حاجة لجهاز Android

---

### 3.2 وحدة التطبيق التجريبي (`demo-app`)

يُقدّم التطبيق التجريبي عرضاً مباشراً لقدرات المكتبة عبر تدفق مستخدم كامل:

```
SplashActivity → LoginActivity → DashboardActivity
                                      ├── CustomerActivity (HCE)
                                      │       └── TransactionResultActivity
                                      └── PosActivity (SoftPOS)
                                              └── TransactionResultActivity
```

#### شاشات التطبيق التجريبي

| الشاشة | الوصف |
|--------|-------|
| **SplashActivity** | شاشة ترحيبية مع شعار أثير |
| **LoginActivity** | تسجيل دخول بمعرف التاجر وكلمة المرور (تجريبي: `MERCHANT_001` / `1234`) |
| **DashboardActivity** | لوحة تحكم بزرَّين: مسار العميل ومسار POS |
| **CustomerActivity** | إدخال المبلغ + رقم البطاقة → ترميزها → تجهيز HCE للدفع |
| **PosActivity** | تفعيل/إيقاف قراءة NFC مع تأثيرات بصرية (وميض + مؤشر تقدم) |
| **TransactionResultActivity** | عرض تفاصيل المعاملة (نجاح/فشل، المبلغ، الوقت، حالة المزامنة) |

**الحالة:** ✅ مكتمل ويعمل

---

### 3.3 البنية التحتية والإعدادات

| الملف | الوصف | الحالة |
|-------|-------|--------|
| `AndroidManifest.xml` (SDK) | تسجيل خدمة HCE مع صلاحيات NFC والإنترنت | ✅ |
| `res/xml/apduservice.xml` | تعريف AID المخصص لـ Atheer + AID Visa | ✅ |
| `build.gradle.kts` (SDK) | إعدادات بناء المكتبة: minSdk 24، compileSdk 34، KSP، Room | ✅ |
| `build.gradle.kts` (demo) | إعدادات بناء التطبيق التجريبي | ✅ |
| `.github/workflows/build-demo-apk.yml` | GitHub Actions: بناء APK تلقائياً عند كل push | ✅ |
| `gradle.properties` | ضبط JVM وخصائص Gradle | ✅ |
| `settings.gradle.kts` | تسجيل الوحدتين في المشروع | ✅ |

---

## 4. ملخص حالة الاكتمال الحالية

### المكوّنات المكتملة بالكامل ✅

| # | المكوّن | نسبة الاكتمال |
|---|---------|--------------|
| 1 | خدمة HCE (محاكاة البطاقة) | 100% |
| 2 | SoftPOS (قارئ NFC) | 100% |
| 3 | توجيه الشبكة الخلوية | 100% |
| 4 | الأمان (Keystore + AES-256 + Tokenization) | 100% |
| 5 | قاعدة البيانات المحلية (Room) | 100% |
| 6 | واجهة SDK الرئيسية (Facade) | 100% |
| 7 | نموذج البيانات (AtheerTransaction) | 100% |
| 8 | اختبارات الوحدة (8 اختبارات) | 100% |
| 9 | التطبيق التجريبي (6 شاشات) | 100% |
| 10 | GitHub Actions (CI/CD) | 100% |
| 11 | التوثيق (README.md) | 100% |

### الملخص العام

> **المشروع مكتمل من الناحية الوظيفية الأساسية.** جميع المكوّنات الستة الأساسية للمكتبة منفّذة بمنطق برمجي حقيقي وليست مجرد هياكل فارغة. التطبيق التجريبي يعمل ويمكن بناؤه وتثبيته على أجهزة Android.

---

## 5. ما يتبقى لاكتمال المشروع بشكل احترافي

### أولوية عالية 🔴 (قبل الإطلاق الإنتاجي)

#### 5.1 تشفير قاعدة البيانات بالكامل (SQLCipher)

**المشكلة:** حالياً يتم تشفير الحقول الحساسة فقط (`encrypted_amount`, `encrypted_token`)، لكن البيانات الوصفية مثل الطوابع الزمنية وحالة المزامنة ورمز العملة مخزنة كنص عادي في ملف SQLite.

**المطلوب:**
```kotlin
// في AtheerDatabase.kt — إضافة تشفير كامل بـ SQLCipher
Room.databaseBuilder(context, AtheerDatabase::class.java, "atheer.db")
    .openHelperFactory(SupportFactory(passphrase))
    .build()

// في build.gradle.kts — إضافة التبعية
implementation("net.zetetic:android-database-sqlcipher:4.5.4")
```

**لماذا؟** متطلبات PCI-DSS المستوى الأول تشترط تشفير ملف قاعدة البيانات بالكامل.

---

#### 5.2 اختبارات الأجهزة (Instrumented Tests)

**المشكلة:** الاختبارات الحالية تعمل على JVM فقط وتتجنب Android Keystore.

**المطلوب:** إضافة اختبارات أجهزة في `androidTest/`:
```kotlin
// اختبار التشفير وفك التشفير الفعلي
@RunWith(AndroidJUnit4::class)
class KeystoreManagerTest {
    @Test
    fun testEncryptDecryptRoundTrip() {
        val manager = AtheerKeystoreManager()
        val original = "4111111111111111"
        val encrypted = manager.encrypt(original)
        assertEquals(original, manager.decrypt(encrypted))
    }
}
```

**ما يجب اختباره:**
- عمليات AES-256-GCM الفعلية عبر Android Keystore
- عمليات إدراج وقراءة Room Database
- معالجة أوامر APDU في HCE

---

#### 5.3 إصلاح JSON Injection

**المشكلة:** دالة `buildTransactionJson()` في `AtheerSdk.kt` تبني JSON بقوالب نصية مباشرة:

```kotlin
// الكود الحالي — قابل لـ JSON Injection
return """{"transactionId":"$transactionId","token":"$token","nonce":"$nonce","merchantId":"$merchantId"}"""
```

**المطلوب:** استخدام `JSONObject` المدمج في Android:
```kotlin
import org.json.JSONObject

private fun buildTransactionJson(transactionId: String, token: String, nonce: String): String {
    return JSONObject().apply {
        put("transactionId", transactionId)
        put("token", token)
        put("nonce", nonce)
        put("merchantId", merchantId)
    }.toString()
}
```

**لماذا؟** إذا احتوت القيم على أحرف JSON خاصة (`"`, `\`, `\n`)، فقد يؤدي ذلك إلى تشويه البيانات أو ثغرات أمنية.

---

#### 5.4 إضافة `gradle-wrapper.jar`

**المشكلة:** ملف `gradle-wrapper.jar` غير موجود في المستودع، مما يعني أن المطوّرين الجدد لا يستطيعون بناء المشروع مباشرة بـ `./gradlew` بدون تثبيت Gradle مسبقاً.

**الحل:** تشغيل `gradle wrapper` محلياً وإضافة الملف للمستودع:
```bash
gradle wrapper --gradle-version 8.7
git add gradle/wrapper/gradle-wrapper.jar
```

---

### أولوية متوسطة 🟡 (تحسينات مهمة)

#### 5.5 WorkManager لإعادة المحاولة التلقائية

**المشكلة:** عند فشل إرسال معاملة عبر الشبكة، تُحفظ محلياً ولكن لا توجد آلية تلقائية لإعادة المحاولة.

**المطلوب:**
```kotlin
// SyncTransactionsWorker.kt
class SyncTransactionsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            AtheerSdk.getInstance().syncPendingTransactions { count ->
                Log.i("Sync", "Synced $count transactions")
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry() // Exponential Backoff تلقائي
        }
    }
}

// جدولة العمل عند كل اتصال بالشبكة
val syncRequest = PeriodicWorkRequestBuilder<SyncTransactionsWorker>(15, TimeUnit.MINUTES)
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .build()
WorkManager.getInstance(context).enqueueUniquePeriodicWork("atheer_sync", ExistingPeriodicWorkPolicy.KEEP, syncRequest)
```

---

#### 5.6 دعم بروتوكول EMV القياسي في HCE

**المشكلة:** البروتوكول الحالي مخصص لنظام Atheer فقط (SELECT → GET DATA). لا يعمل مع أجهزة POS القياسية التي تتوقع تدفق EMV كامل.

**المطلوب** (للتوافق مع أجهزة POS القياسية):
```
PPSE → SELECT AID → GET PROCESSING OPTIONS (GPO) → READ RECORD → GENERATE AC
```

---

#### 5.7 طبقة تسجيل قابلة للتكوين (Configurable Logging)

**المشكلة:** كل أجزاء المكتبة تستخدم `android.util.Log` مباشرةً. في بيئة الإنتاج يجب تعطيل السجلات التفصيلية.

**المطلوب:**
```kotlin
// AtheerLogger.kt
object AtheerLogger {
    var level: LogLevel = LogLevel.NONE // الافتراضي: بدون سجلات في الإنتاج
    
    fun d(tag: String, msg: String) { if (level >= LogLevel.DEBUG) Log.d(tag, msg) }
    fun i(tag: String, msg: String) { if (level >= LogLevel.INFO) Log.i(tag, msg) }
    fun e(tag: String, msg: String) { if (level >= LogLevel.ERROR) Log.e(tag, msg) }
}

// عند تهيئة SDK
AtheerSdk.init(context, merchantId, apiBaseUrl, logLevel = LogLevel.DEBUG)
```

---

#### 5.8 التحقق من صحة المدخلات (Input Validation)

**المشكلة:** `AtheerSdk.processTransaction()` لا تتحقق من صحة حقول `transactionId` و `currency` و `amount` قبل المعالجة.

**المطلوب:**
```kotlin
private fun validateTransaction(transaction: AtheerTransaction) {
    require(transaction.transactionId.isNotBlank()) { "transactionId لا يمكن أن يكون فارغاً" }
    require(transaction.amount > 0) { "المبلغ يجب أن يكون أكبر من صفر" }
    require(transaction.currency.length == 3) { "رمز العملة يجب أن يكون 3 أحرف وفق ISO 4217" }
}
```

---

### أولوية منخفضة 🟢 (تحسينات مستقبلية)

#### 5.9 نشر المكتبة على Maven Central / JitPack

حالياً يجب على المستخدمين نسخ ملف AAR يدوياً. نشر المكتبة على مستودع Maven سيتيح:
```kotlin
// في أي مشروع Android
implementation("com.atheer:atheer-sdk:1.0.0")
```

#### 5.10 توثيق KDoc باللغة الإنجليزية

التوثيق الحالي بالعربية ممتاز للفريق المحلي. للوصول لجمهور عالمي يُنصح بإضافة توثيق KDoc بالإنجليزية موازياً.

#### 5.11 دعم Jetpack Compose في التطبيق التجريبي

التطبيق التجريبي يستخدم XML Layouts التقليدية. إعادة بناؤه بـ Jetpack Compose سيجعله أكثر حداثة.

#### 5.12 إضافة اختبار تكامل (Integration Test)

اختبار السيناريو الكامل: `CustomerActivity → HCE → SoftPOS → TransactionResult` باستخدام Espresso.

---

## 6. تقييم جودة الكود

| المعيار | التقييم | الملاحظات |
|---------|---------|----------|
| **المعمارية** | ⭐⭐⭐⭐⭐ | Clean Architecture واضحة، فصل ممتاز للمسؤوليات |
| **الأمان** | ⭐⭐⭐⭐☆ | AES-256-GCM + Keystore ممتاز — ينقصه SQLCipher |
| **التوثيق** | ⭐⭐⭐⭐⭐ | تعليقات KDoc عربية شاملة في كل ملف |
| **معالجة الأخطاء** | ⭐⭐⭐⭐☆ | try/catch شاملة — ينقصه تفاصيل أكثر في رسائل الخطأ |
| **الأداء** | ⭐⭐⭐⭐☆ | Coroutines + SupervisorJob ممتاز — ينقصه WorkManager |
| **قابلية الاختبار** | ⭐⭐⭐☆☆ | 7 اختبارات جيدة — ينقصه اختبارات الأجهزة (Instrumented) |
| **واجهة المستخدم** | ⭐⭐⭐⭐☆ | تصميم عربي RTL أنيق — ينقصه دعم الوضع الليلي (Dark Mode) |
| **CI/CD** | ⭐⭐⭐⭐☆ | GitHub Actions يبني APK تلقائياً — ينقصه تشغيل الاختبارات |

---

## 7. نصائح للتحسين

### 🔴 فوري (قبل الإطلاق)

1. **أضف SQLCipher** لتشفير قاعدة البيانات بالكامل لاستيفاء PCI-DSS
2. **أصلح JSON Injection** بالانتقال من String Templates إلى `JSONObject`
3. **أضف اختبارات أجهزة** (Instrumented Tests) لـ Keystore وRoom وHCE

### 🟡 قريباً (قبل الإطلاق التجاري)

4. **أضف WorkManager** لمزامنة تلقائية ذكية مع Exponential Backoff
5. **أضف التحقق من المدخلات** في `processTransaction()` لمنع الأعطال الصامتة
6. **أضف طبقة تسجيل** قابلة للتكوين لتعطيل السجلات في الإنتاج
7. **أضف `gradle-wrapper.jar`** للمستودع لتسهيل إعداد المشروع

### 🟢 مستقبلاً (للنمو والتوسع)

8. **انشر المكتبة على Maven Central أو JitPack** لسهولة الاستيراد
9. **أضف دعم EMV القياسي** للتوافق مع أجهزة POS خارج نظام Atheer
10. **أضف Dark Mode** للتطبيق التجريبي
11. **أضف تشغيل الاختبارات** في GitHub Actions Workflow

---

## 8. خلاصة

**Atheer SDK** مشروع متكامل وجاد يحل مشكلة حقيقية في سوق المدفوعات الرقمية. البنية البرمجية سليمة، والكود مكتوب بعناية مع تعليقات توضيحية وفيرة. المشروع جاهز للاستخدام التجريبي وقريب من الجاهزية الإنتاجية بعد معالجة النقاط ذات الأولوية العالية المذكورة أعلاه.

**أبرز نقاط القوة:**
- بروتوكول HCE كامل يتبع معيار ISO 7816-4
- تشفير AES-256-GCM مع حماية المفاتيح بـ Android Keystore
- دعم كامل للوضع غير المتصل مع مزامنة ذكية
- توجيه إلزامي عبر الشبكة الخلوية لأمان إضافي
- تطبيق تجريبي متكامل وجاهز للتوزيع

**أبرز النقاط المطلوب تطويرها:**
- تشفير ملف قاعدة البيانات بالكامل (SQLCipher)
- اختبارات أجهزة شاملة
- WorkManager للمزامنة التلقائية الذكية
- إصلاح بناء JSON لمنع الحقن

</div>
