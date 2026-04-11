# اقتراحات التحسين الهندسي الشامل — Atheer SDK

> هذا الملف مرفق لـ [`CODEBASE_ANALYSIS.md`](./CODEBASE_ANALYSIS.md) ويتضمن اقتراحات تقنية مفصلة لتحسين المعمارية، البروتوكول، الأمان، تجربة المستخدم، والأداء، إضافةً إلى حذف العناصر غير الضرورية واقتراحات لاكتمال المشروع.

---

## الفهرس

1. [تحسينات المعمارية](#١-تحسينات-المعمارية)
2. [تحسينات بروتوكول APDU والـ NFC](#٢-تحسينات-بروتوكول-apdu-والـ-nfc)
3. [تعزيز الأمان](#٣-تعزيز-الأمان)
4. [تحسينات تجربة المستخدم والأداء](#٤-تحسينات-تجربة-المستخدم-والأداء)
5. [حذف العناصر الزائدة وتبسيط الكود](#٥-حذف-العناصر-الزائدة-وتبسيط-الكود)
6. [ميزات مقترحة لاكتمال المشروع](#٦-ميزات-مقترحة-لاكتمال-المشروع)

---

## ١. تحسينات المعمارية

### ١.١ — استبدال Callbacks بـ Coroutines/Flow في الواجهة العامة

**المشكلة الحالية:**
```kotlin
// في AtheerSdk.kt — أسلوب قديم يصعب تركيبه مع Compose أو MVVM
fun enrollDevice(onSuccess: () -> Unit, onError: (String) -> Unit)
fun preparePayment(activity, onSuccess: () -> Unit, onError: (String) -> Unit)
```

**الاقتراح:**
```kotlin
// أسلوب حديث — يدعم structured concurrency ويُبسط معالجة الأخطاء
suspend fun enrollDevice(): Result<Unit>
suspend fun preparePayment(activity: FragmentActivity): Result<Unit>

// مثال الاستخدام في ViewModel
viewModelScope.launch {
    sdk.enrollDevice()
        .onSuccess { /* نجاح */ }
        .onFailure { error -> /* معالجة AtheerError */ }
}
```

**الفائدة:** توافق كامل مع Jetpack Compose وMVVM، وإلغاء تشابك الـ Callbacks.

---

### ١.٢ — إضافة طبقة Repository رسمية

**المشكلة الحالية:** `AtheerSdk` يتولى مباشرةً منطق الشبكة والتشفير وقاعدة البيانات — انتهاك لمبدأ Single Responsibility.

**الاقتراح:** تقسيم المنطق إلى مستودعات متخصصة:

```
AtheerSdk (Public API)
    │
    ├── AtheerPaymentRepository   ← منطق الدفع + الشبكة
    ├── AtheerDeviceRepository    ← تسجيل الجهاز + إدارة الـ Seed
    └── AtheerTransactionRepository ← قاعدة البيانات + سجل المعاملات
```

```kotlin
// بدلاً من:
val responseJson = networkRouter.executeStandard(...)
val seed = JSONObject(responseJson).optJSONObject("data")?.optString("deviceSeed")

// يصبح:
val seed = deviceRepository.enrollAndGetSeed(phoneNumber)
```

---

### ١.٣ — إضافة Dependency Injection (Hilt أو Manual DI)

**المشكلة الحالية:** كل كلاس ينشئ اعتمادياته داخله مباشرةً (تشابك صعب الاختبار):
```kotlin
// في AtheerSdk.kt
private val keystoreManager = AtheerKeystoreManager(context)
private val networkRouter = AtheerNetworkRouter(context, isSandbox = isSandbox)
private val database = AtheerDatabase.getInstance(context)
```

**الاقتراح:** حقن الاعتماديات عبر Constructor (بدون مكتبة خارجية لتجنب زيادة حجم SDK):
```kotlin
class AtheerSdk internal constructor(
    private val keystoreManager: AtheerKeystoreManager,
    private val networkRouter: AtheerNetworkRouter,
    private val transactionRepository: AtheerTransactionRepository
)

// Factory داخلي في companion object
fun init(block: AtheerSdkConfig.() -> Unit) {
    val config = AtheerSdkConfig().apply(block)
    instance = AtheerSdk(
        keystoreManager = AtheerKeystoreManager(config.context!!),
        networkRouter = AtheerNetworkRouter(config.context!!, config.isSandbox),
        transactionRepository = AtheerTransactionRepository(AtheerDatabase.getInstance(config.context!!))
    )
}
```

**الفائدة:** تسهيل كتابة Unit Tests عبر Mocking.

---

### ١.٤ — تحويل `AtheerSdkConfig` إلى Data Class مع Validation مركزية

**المشكلة الحالية:** `AtheerSdkConfig` هو كلاس عادي بحقول `var` — لا يمنع إنشاء config ناقص.

**الاقتراح:**
```kotlin
data class AtheerSdkConfig(
    val context: Context,
    val merchantId: String,
    val apiKey: String,
    val phoneNumber: String,
    val isSandbox: Boolean = true,
    val networkMode: NetworkMode = NetworkMode.PUBLIC_INTERNET,
    val sessionTimeoutMs: Long = 60_000L  // قابل للتخصيص
) {
    init {
        require(phoneNumber.isNotBlank()) { "رقم الهاتف إلزامي" }
        require(apiKey.isNotBlank()) { "مفتاح API إلزامي" }
        require(merchantId.isNotBlank()) { "معرف التاجر إلزامي" }
    }
}
```

---

### ١.٥ — استبدال JSONObject اليدوي بـ Gson (المكتبة موجودة فعلاً)

**المشكلة الحالية:** `AtheerSdk.charge()` يبني JSON يدوياً رغم أن Gson مُدرَجة في `build.gradle.kts`:
```kotlin
// حالياً — هش وعرضة للأخطاء
val body = JSONObject().apply {
    put("amount", request.amount)
    put("currency", request.currency)
    // ... 10 حقول يدوياً
}.toString()
```

**الاقتراح:**
```kotlin
// استخدام Gson المتوفرة بالفعل
val body = Gson().toJson(request)  // سطر واحد بدلاً من 12
```

---

## ٢. تحسينات بروتوكول APDU والـ NFC

### ٢.١ — تقسيم البيانات الكبيرة عبر Chaining (NFC Fragmentation)

**المشكلة الحالية:** الحمولة الكاملة `"phone|counter|timestamp|signature"` تُرسَل في استجابة APDU واحدة. قد يتجاوز الـ signature طول الـ buffer في بعض أجهزة القراءة (APDU max ~255 بايت في بعض الأجهزة القديمة).

**الاقتراح:** إضافة دعم **Extended APDU** أو تقسيم تلقائي:
```kotlin
private fun buildApduResponse(payload: String, signature: String): ByteArray {
    val combined = "$payload|$signature"
    val dataBytes = combined.toByteArray(StandardCharsets.UTF_8)
    
    return if (dataBytes.size <= 253) {
        // استجابة عادية
        dataBytes + APDU_OK
    } else {
        // Extended APDU response
        byteArrayOf(0x00) + dataBytes.size.toShort().toByteArray() + dataBytes + APDU_OK
    }
}
```

---

### ٢.٢ — إضافة أمر APDU للتحقق من الإصدار (Version Negotiation)

**الاقتراح:** إضافة أمر تفاوض على الإصدار بين القارئ والخدمة:

```kotlin
// في AtheerApduService.kt
private const val GET_VERSION_INS = 0xCB.toByte()

when {
    isSelectAidCommand(commandApdu) -> APDU_OK
    isGetVersionCommand(commandApdu) -> buildVersionResponse()  // إرجاع "ATHEER/1.1"
    isGetPaymentDataCommand(commandApdu) -> handleGetPaymentData()
    else -> APDU_FILE_NOT_FOUND
}
```

**الفائدة:** يُمكِّن التوافق التدريجي (backward compatibility) عند تطوير بروتوكولات جديدة.

---

### ٢.٣ — تشديد حد RTT للـ Relay Attack

**المشكلة الحالية:** الحد الحالي 50ms قد يكون متساهلاً على أجهزة بطيئة بينما يرفض اتصالات حقيقية على أجهزة أخرى.

**الاقتراح:** جعل الحد ديناميكياً مع Calibration:
```kotlin
// قياس RTT في SELECT AID أولاً كمرجع calibration
val calibrationRtt = measureRtt { isoDep.transceive(SELECT_APDU) }
val dynamicThreshold = calibrationRtt * 2.5  // نسبة آمنة

// قياس RTT الفعلي
val paymentRtt = measureRtt { isoDep.transceive(GET_PAYMENT_DATA_APDU) }

if (paymentRtt > dynamicThreshold.coerceAtMost(150)) {
    throw RelayAttackException("RTT: ${paymentRtt}ms > threshold: ${dynamicThreshold}ms")
}
```

---

### ٢.٤ — إضافة Nonce في حمولة NFC لمنع Replay Attacks بشكل أقوى

**المشكلة الحالية:** الحمولة الحالية `"phone|counter|timestamp"` تعتمد على العداد الرتيب فقط. اكتشاف تغيير العداد يتطلب تحقق من الخادم.

**الاقتراح:** إضافة `challengeNonce` يُولَّد من جانب التاجر:
```
// بروتوكول جديد (4 خطوات):
1. التاجر يرسل GET_CHALLENGE_APDU → يستقبل nonce عشوائي
2. العميل يوقع: HMAC(LUK, "phone|counter|timestamp|nonce")
3. التاجر يستقبل الحمولة الموقعة
4. الخادم يتحقق من nonce + signature
```

---

## ٣. تعزيز الأمان

### ٣.١ — تحديث Certificate Pinning بـ Hash حقيقي

**المشكلة الحالية (خطر أمني عالي):** القيم الحالية في `AtheerNetworkRouter` هي قيم وهمية:
```kotlin
// ⚠️ هذا لا يوفر أي حماية فعلية!
.add(BASE_DOMAIN, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
.add(BASE_DOMAIN, "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")
```

**الاقتراح الفوري:**
```bash
# الحصول على الـ hash الحقيقي:
openssl s_client -connect api.atheer.com:443 < /dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary | base64
```

ثم تحديث الكود بالقيمة الحقيقية. **هذا الإصلاح واجب قبل أي إطلاق إنتاجي.**

---

### ٣.٢ — إضافة Root/Emulator Detection

**المشكلة الحالية:** رغم أن `rootbeer-lib` مُدرجة في `build.gradle.kts`، لا يوجد أي استخدام لها في الكود الحالي.

**الاقتراح:** إضافة فحص في `AtheerSdk.init()`:
```kotlin
// في AtheerSdk.init()
val rootBeer = RootBeer(config.context)
if (rootBeer.isRooted) {
    Log.w(TAG, "⚠️ تحذير: الجهاز يبدو أنه مكسور الحماية (Rooted)")
    // يمكن رفع استثناء أو تسجيل التحذير فقط حسب سياسة الأعمال
    if (config.blockRootedDevices) {
        throw SecurityException("رُفض التهيئة: الجهاز مكسور الحماية")
    }
}
```

---

### ٣.٣ — إضافة Play Integrity API

**المشكلة الحالية:** `com.google.android.play:integrity` مُدرجة كاعتمادية لكن لا يوجد أي استخدام.

**الاقتراح:** استخدامها أثناء `enrollDevice()` لإثبات سلامة التطبيق:
```kotlin
// في enrollDevice() قبل إرسال الطلب
val integrityToken = getPlayIntegrityToken()
val requestBody = JSONObject().apply {
    put("deviceId", phoneNumber)
    put("integrityToken", integrityToken)  // الخادم يتحقق منها
}.toString()
```

---

### ٣.٤ — تشفير الحمولة المُرسَلة عبر NFC

**المشكلة الحالية:** الحمولة النصية `"phone|counter|timestamp|signature"` تُرسَل كـ **Plain Text** عبر HCE:
```kotlin
// في AtheerApduService.kt
val rawPayload = "$payload|$signature"
val response = rawPayload.toByteArray(StandardCharsets.UTF_8) + APDU_OK
```

**الاقتراح:** تشفير الحمولة بـ AES-GCM قبل الإرسال:
```kotlin
// التاجر يرسل challenge nonce
// العميل يشفر: AES-GCM(LUK, "phone|counter|timestamp|signature", nonce)
val encryptedPayload = encryptWithAesGcm(rawPayload.toByteArray(), luk, nonce)
val response = encryptedPayload + APDU_OK
```

---

### ٣.٥ — تحديد عمر الـ Counter المسموح به في Backend

**المشكلة الحالية:** الكود لا يُعرِّف نافذة حد أقصى للعداد المقبول في الخادم، مما قد يسمح باستخدام counters قديمة جداً.

**الاقتراح:** إضافة validation في `ChargeRequest`:
```kotlin
data class ChargeRequest(
    // ...
    val timestamp: Long,
) {
    init {
        val ageMs = System.currentTimeMillis() - timestamp
        require(ageMs < 120_000) { "انتهت صلاحية الطلب (أكثر من 2 دقيقة)" }
        require(ageMs >= 0) { "Timestamp من المستقبل — رُفض" }
    }
}
```

---

### ٣.٦ — تأمين `AtheerPaymentSettingsActivity`

**المشكلة الحالية:** النشاط `android:exported="true"` بدون أي حماية، مما يجعله قابلاً للاستدعاء من تطبيقات خارجية:
```xml
<activity android:name=".AtheerPaymentSettingsActivity" android:exported="true">
```

**الاقتراح:**
```xml
<activity
    android:name=".AtheerPaymentSettingsActivity"
    android:exported="true"
    android:permission="com.atheer.sdk.permission.PAYMENT_SETTINGS">
```

مع إضافة الإذن في الـ Manifest:
```xml
<permission
    android:name="com.atheer.sdk.permission.PAYMENT_SETTINGS"
    android:protectionLevel="signature" />
```

---

## ٤. تحسينات تجربة المستخدم والأداء

### ٤.١ — إضافة واجهة Listener لحالة جلسة الدفع

**المشكلة الحالية:** المستخدم لا يعلم إن انتهت صلاحية جلسته (60 ثانية) حتى يحاول النقر مجدداً.

**الاقتراح:** إضافة `Flow` لحالة الجلسة:
```kotlin
// في AtheerSdk.kt
val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.IDLE)

enum class SessionState { IDLE, ARMED, EXPIRED }

// في preparePayment() بعد arm():
_sessionState.value = SessionState.ARMED
sdkScope.launch {
    delay(SESSION_TIMEOUT_MS)
    if (_sessionState.value == SessionState.ARMED) {
        _sessionState.value = SessionState.EXPIRED
    }
}
```

```kotlin
// استخدام في الـ UI:
sdk.sessionState.collect { state ->
    when (state) {
        SessionState.ARMED   -> showCountdown(60) // عرض عداد تنازلي
        SessionState.EXPIRED -> showMessage("انتهت الجلسة، حاول مجدداً")
        SessionState.IDLE    -> resetUI()
    }
}
```

---

### ٤.٢ — عرض مؤشر تقدم خلال عملية Enrollment

**المشكلة الحالية:** `enrollDevice()` يعمل في الخلفية دون أي ملاحظة للمستخدم، وقد يستغرق عدة ثوانٍ.

**الاقتراح:**
```kotlin
// إضافة callback للتقدم
fun enrollDevice(
    onProgress: (EnrollmentStep) -> Unit = {},
    onSuccess: () -> Unit,
    onError: (String) -> Unit
)

enum class EnrollmentStep {
    CONNECTING,    // "جارٍ الاتصال بالخادم..."
    GENERATING,    // "جارٍ إنشاء مفاتيح التشفير..."
    STORING,       // "جارٍ حفظ البيانات بأمان..."
    DONE
}
```

---

### ٤.٣ — تحسين رسائل الخطأ للمستخدم النهائي

**المشكلة الحالية:** رسائل بعض الأخطاء تقنية جداً:
```kotlin
throw IllegalStateException("الجهاز غير مسجل. استدعِ enrollDevice() أولاً.")
```

**الاقتراح:** تصنيف الأخطاء إلى طبقتَين:
```kotlin
sealed class AtheerError {
    abstract val userMessage: String   // رسالة للمستخدم النهائي
    abstract val developerMessage: String  // رسالة للمطور
    abstract val errorCode: String

    class InsufficientFunds : AtheerError() {
        override val userMessage = "رصيدك غير كافٍ لإتمام هذه العملية"
        override val developerMessage = "Wallet balance below required amount"
        override val errorCode = "ERR_FUNDS"
    }
    
    class DeviceNotEnrolled : AtheerError() {
        override val userMessage = "يرجى إعداد التطبيق أولاً من الإعدادات"
        override val developerMessage = "enrollDevice() must be called before preparePayment()"
        override val errorCode = "ERR_NOT_ENROLLED"
    }
}
```

---

### ٤.٤ — تقليل وقت الاستجابة في `enrollDevice()` بـ Caching

**المشكلة الحالية:** كل استدعاء لـ `enrollDevice()` يُرسَل كطلب شبكة حتى لو الجهاز مسجل مسبقاً.

**الاقتراح:**
```kotlin
fun enrollDevice(forceRenew: Boolean = false, ...) {
    if (!forceRenew && keystoreManager.isDeviceEnrolled()) {
        // الجهاز مسجل — لا حاجة لطلب شبكة
        withContext(Dispatchers.Main) { onSuccess() }
        return
    }
    // تسجيل جديد...
}
```

---

### ٤.٥ — دعم Structured Concurrency في `AtheerNfcReader`

**المشكلة الحالية:** `readerScope` في `AtheerNfcReader` لا يُلغى أبداً عند انتهاء استخدام القارئ، مما قد يُسبب تسريب ذاكرة:
```kotlin
private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
// لا يوجد cancel() في أي مكان!
```

**الاقتراح:** تطبيق `Closeable`:
```kotlin
internal class AtheerNfcReader(...) : NfcAdapter.ReaderCallback, Closeable {
    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun close() {
        readerScope.cancel()
    }
}

// في التطبيق:
val nfcReader = AtheerNfcReader(...)
nfcAdapter.enableReaderMode(activity, nfcReader, flags, null)
// عند إيقاف القراءة:
nfcAdapter.disableReaderMode(activity)
nfcReader.close()
```

---

### ٤.٦ — إضافة دعم لـ Dark Mode في `AtheerPaymentSettingsActivity`

**المشكلة الحالية:** الواجهة تُنشأ برمجياً بألوان ثابتة — لا تستجيب لوضع الظلام.

**الاقتراح:** استخدام `MaterialAlertDialogBuilder` أو Colors من النظام:
```kotlin
val backgroundColor = if (isInDarkTheme()) Color.parseColor("#1C1C1E") else Color.WHITE
val textColor = if (isInDarkTheme()) Color.WHITE else Color.BLACK

layout.setBackgroundColor(backgroundColor)
title.setTextColor(textColor)
```

---

## ٥. حذف العناصر الزائدة وتبسيط الكود

### ٥.١ — ✂️ حذف `AtheerCellularRouter.kt`

**الملف الحالي (عديم الفائدة):**
```kotlin
// AtheerCellularRouter.kt — مهجور تماماً
@Deprecated("...")
internal class AtheerCellularRouter
```

**القرار:** حذف الملف كلياً. وجوده يُربك قراء الكود ويُضخِّم حجم المكتبة بلا فائدة.

---

### ٥.٢ — ✂️ حذف `AtheerSyncWorker.kt`

**الملف الحالي (عديم الفائدة):**
```kotlin
// AtheerSyncWorker.kt — إيقاف نهائي
@Deprecated("نظام أثير يعمل بمعمارية لحظية متزامنة.", level = DeprecationLevel.HIDDEN)
internal class AtheerSyncWorkerStub
```

**القرار:** حذف الملف. وإذا احتُيج لـ WorkManager مستقبلاً، يُنشأ من الصفر بتصميم صحيح.

---

### ٥.٣ — ✂️ حذف اعتمادية `androidx.work:work-runtime-ktx` من `build.gradle.kts`

**المشكلة الحالية:** الاعتمادية مُدرَجة رغم أن `AtheerSyncWorker` محذوف/مهجور:
```kotlin
implementation("androidx.work:work-runtime-ktx:2.9.0")  // ← غير مستخدم
```

**القرار:** حذف هذا السطر لتقليل حجم SDK الإجمالي.

---

### ٥.٤ — ✂️ توحيد منطق قراءة الـ JSON في `AtheerSdk.enrollDevice()`

**الكود الحالي — هش (يقرأ مسارَين مختلفَين):**
```kotlin
val seed = responseObj.optJSONObject("data")?.optString("deviceSeed") 
    ?: responseObj.optString("deviceSeed")
```

**الاقتراح:** تعريف نموذج استجابة موحد بـ Gson:
```kotlin
data class EnrollResponse(
    val data: EnrollData?  = null,
    val deviceSeed: String? = null  // للتوافق مع البنيتَين
) {
    data class EnrollData(val deviceSeed: String?)
    fun getSeed(): String? = data?.deviceSeed ?: deviceSeed
}

val enrollResponse = Gson().fromJson(responseJson, EnrollResponse::class.java)
val seed = enrollResponse.getSeed()
```

---

### ٥.٥ — ✂️ إزالة `getPayload()` و `getSignature()` من `AtheerPaymentSession`

**المشكلة الحالية:** هذان المتغيران قابلان للاستخدام مباشرةً، مما قد يُسبب race conditions إذا استُخدِما بدلاً من `consumeSession()`:
```kotlin
fun getPayload(): String? = if (isSessionArmed()) payload else null   // ← خطر
fun getSignature(): String? = if (isSessionArmed()) signature else null // ← خطر
```

**الاقتراح:** حذفهما كلياً والاكتفاء بـ `consumeSession()` الآمن ذرياً، أو تحويلهما إلى `private`.

---

### ٥.٦ — ✂️ تبسيط `AtheerDatabase.buildDatabase()`

**الكود الحالي — `keystoreManager` يُنشأ داخل `buildDatabase()` مرة أخرى رغم وجوده في SDK:**
```kotlin
private fun buildDatabase(context: Context): AtheerDatabase {
    val keystoreManager = AtheerKeystoreManager(context) // ← نسخة جديدة غير ضرورية
    val passphrase = getOrCreateDatabasePassphrase(context, keystoreManager)
```

**الاقتراح:** إزالة اعتماد `AtheerDatabase` على `AtheerKeystoreManager` كلياً، واستخدام `EncryptedSharedPreferences` مباشرةً لتوليد Nonce:
```kotlin
private fun getOrCreateDatabasePassphrase(context: Context): ByteArray {
    // توليد UUID عشوائي مباشرةً بدلاً من استيراد AtheerKeystoreManager
    val passphrase = UUID.randomUUID().toString().replace("-", "")
    // تخزينه في EncryptedSharedPreferences...
}
```

---

## ٦. ميزات مقترحة لاكتمال المشروع

### ٦.١ — 🔨 SDK Initialization Checklist (شاشة صحة التهيئة)

إضافة دالة تفحص جاهزية الجهاز قبل استخدام SDK:
```kotlin
data class AtheerReadinessReport(
    val isNfcSupported: Boolean,
    val isNfcEnabled: Boolean,
    val isHceSupported: Boolean,
    val isBiometricAvailable: Boolean,
    val isDeviceEnrolled: Boolean,
    val isDefaultPaymentApp: Boolean,
    val isDeviceRooted: Boolean
)

fun checkReadiness(): AtheerReadinessReport
```

---

### ٦.٢ — 🔨 دعم P2P (Person-to-Person) عبر NFC مباشرة

المشروع يدعم `transactionType = "P2P"` في النماذج، لكن لا يوجد تدفق كامل لعملية P2P.

**الاقتراح:** إضافة:
```kotlin
// في AtheerSdk
suspend fun sendMoneyP2P(
    receiverPhone: String,
    amount: Long,
    currency: String = "YER"
): Result<ChargeResponse>

// الفرق عن P2M: receiverAccount = receiverPhone (مستخدم آخر، لا تاجر)
```

---

### ٦.٣ — 🔨 Transaction Receipt Cache (حفظ الإيصالات محلياً)

قاعدة البيانات موجودة لكن لا يوجد كود يحفظ المعاملات بعد نجاح `charge()`.

**الاقتراح:** حفظ تلقائي في `AtheerSdk.charge()`:
```kotlin
suspend fun charge(request: ChargeRequest, accessToken: String): Result<ChargeResponse> {
    val result = executeCharge(request, accessToken)
    result.onSuccess { response ->
        // حفظ في قاعدة البيانات تلقائياً
        database.transactionDao().insertTransaction(
            request.toEntity(transactionId = response.transactionId)
        )
    }
    return result
}
```

---

### ٦.٤ — 🔨 Webhook / Push Notification للتأكيد اللحظي

**المشكلة الحالية:** بعد إرسال طلب الدفع، النتيجة تعود كـ `ACCEPTED` لكن قد تكون غير نهائية (Pending).

**الاقتراح:** إضافة دعم **FCM Push Notifications** لاستقبال تأكيد نهائي من الخادم:
```kotlin
// في AtheerSdk
fun setFcmToken(token: String) {
    // إرسال الـ token للخادم عند كل تسجيل جهاز
}

// في AtheerSdk (استقبال الـ notification)
fun handlePaymentConfirmation(data: Map<String, String>): AtheerTransaction?
```

---

### ٦.٥ — 🔨 Rate Limiting محلي لمنع الإرسال المتكرر

**المشكلة الحالية:** لا يوجد حد للعمليات في فترة زمنية قصيرة — يمكن استدعاء `preparePayment()` بشكل متكرر.

**الاقتراح:**
```kotlin
private var lastPaymentAttempt = 0L
private const val MIN_INTERVAL_MS = 5_000L  // 5 ثوانٍ بين كل محاولة

fun preparePayment(activity: FragmentActivity, ...) {
    val now = System.currentTimeMillis()
    if (now - lastPaymentAttempt < MIN_INTERVAL_MS) {
        onError("يرجى الانتظار ${MIN_INTERVAL_MS/1000} ثوانٍ بين كل محاولة")
        return
    }
    lastPaymentAttempt = now
    // ...
}
```

---

### ٦.٦ — 🔨 إضافة SDK Version Header للطلبات

**الاقتراح:** إضافة معلومات الإصدار في كل طلب للخادم لدعم التوافق:
```kotlin
// في AtheerNetworkRouter.executeStandard()
requestBuilder.addHeader("x-atheer-sdk-version", BuildConfig.SDK_VERSION)
requestBuilder.addHeader("x-atheer-platform", "android")
requestBuilder.addHeader("x-atheer-os-version", Build.VERSION.RELEASE)
```

---

### ٦.٧ — 🔨 Proguard Rules محسّنة

**المشكلة الحالية:** ملف `proguard-rules.pro` فارغ، مما قد يُتلف الكود عند التصغير:

```proguard
# atheer-sdk/proguard-rules.pro

# الحفاظ على النماذج (Models)
-keep class com.atheer.sdk.model.** { *; }

# الحفاظ على الواجهة العامة للـ SDK
-keep public class com.atheer.sdk.AtheerSdk { public *; }
-keep public class com.atheer.sdk.AtheerSdkConfig { *; }
-keep public class com.atheer.sdk.AtheerError { *; }

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
```

---

### ٦.٨ — 🔨 اختبارات Unit Tests شاملة

**المشكلة الحالية:** يوجد ملف اختبار واحد فقط (`AtheerSdkTest.kt`) يغطي جزءاً صغيراً من المنطق.

**الاقتراح — خريطة اختبارات مقترحة:**

```
src/test/
├── AtheerSdkTest.kt           ← موجود (يغطي parseNfcDataToRequest)
├── AtheerKeystoreManagerTest  ← جديد: اختبار deriveLUK, signWithLUK, generateNonce
├── AtheerPaymentSessionTest   ← جديد: اختبار TTL، Thread-Safety، consumeSession
├── AtheerNetworkRouterTest    ← جديد: اختبار mapToAtheerError، resolveClient
├── AtheerNfcReaderTest        ← جديد: اختبار Relay Attack، parseNfcData
└── TransactionDaoTest         ← جديد: اختبار CRUD عمليات قاعدة البيانات
```

---

## ملخص أولويات التحسين

| الأولوية | الاقتراح | الجهد | الأثر |
|:---:|---|:---:|:---:|
| 🔴 **فوري** | تحديث Certificate Pinning بـ hash حقيقي (§3.1) | منخفض | أمان عالٍ |
| 🔴 **فوري** | تفعيل Root Detection (§3.2) | منخفض | أمان عالٍ |
| 🔴 **فوري** | تفعيل Play Integrity API (§3.3) | متوسط | أمان عالٍ |
| 🟠 **عالي** | حذف الملفات الزائدة §5.1–5.3 | منخفض جداً | تبسيط |
| 🟠 **عالي** | حذف `getPayload()`/`getSignature()` (§5.5) | منخفض | أمان |
| 🟠 **عالي** | إضافة Proguard Rules (§6.7) | منخفض | استقرار |
| 🟡 **متوسط** | استبدال Callbacks بـ Coroutines (§1.1) | متوسط | جودة كود |
| 🟡 **متوسط** | حفظ المعاملات بعد نجاح charge() (§6.3) | منخفض | اكتمال |
| 🟡 **متوسط** | Rate Limiting محلي (§6.5) | منخفض | أمان |
| 🟢 **منخفض** | دعم P2P كامل (§6.2) | عالٍ | اكتمال |
| 🟢 **منخفض** | FCM للتأكيد اللحظي (§6.4) | عالٍ | تجربة مستخدم |
