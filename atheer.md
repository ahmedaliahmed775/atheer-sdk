# Atheer SDK — Comprehensive Audit Report

**Audit Date:** 2026-02-21  
**Audited By:** Senior QA Engineer & Project Manager  
**Repository:** `ahmedaliahmed775/atheer-sdk`  
**Branch:** `main` (commit `a879ec3`)

---

## 1. Component Status Table

| # | Component Name | Status | Implementation Details |
|---|----------------|--------|------------------------|
| 1 | **HCE — HostApduService** (`AtheerApduService.kt`) | **Complete** | Extends `HostApduService`. Registered in `AndroidManifest.xml` with `BIND_NFC_SERVICE` permission and `HOST_APDU_SERVICE` intent-filter. AID filter configured in `apduservice.xml` (Atheer custom AID `A000000003101001` + Visa `A0000000031010`). `processCommandApdu()` implements real APDU command parsing: validates minimum 4-byte length, recognizes SELECT AID (`00 A4 04 xx`), recognizes GET PAYMENT DATA (`00 CA`), returns encrypted token bytes + SW `9000` on success, returns `6A82` for unknown commands, and `6F00` for errors. `preparePayment()` generates a nonce, encrypts card data with Keystore, and stores the token for NFC transmission. `onDeactivated()` clears sensitive data. Lifecycle management with coroutine scope cancellation in `onDestroy()`. |
| 2 | **SoftPOS — NFC Reader Mode** (`AtheerNfcReader.kt`) | **Complete** | Implements `NfcAdapter.ReaderCallback`. `onTagDiscovered()` dispatches to background coroutine. `readNfcTag()` implements full ISO-DEP (ISO 14443-4) protocol: connects to tag with 5s timeout, sends SELECT AID APDU (`00 A4 04 00` + AID), validates `9000` response, sends GET PAYMENT DATA APDU (`00 CA 00 01 FF`), extracts payment token from response (strips 2-byte status word), builds `AtheerTransaction` object, and delivers result via callback on main thread. Error handling with proper `IsoDep.close()` in `finally` block. Transaction ID generated with timestamp + random suffix. |
| 3 | **Private APN / Network Routing** (`AtheerNetworkRouter.kt`) | **Complete** | Uses `ConnectivityManager` with `NetworkRequest.Builder()` specifying `TRANSPORT_CELLULAR` + `NET_CAPABILITY_INTERNET`. `getCellularNetwork()` first checks if active network is already cellular (fast path), otherwise registers `NetworkCallback` with `requestNetwork()` and uses `suspendCancellableCoroutine` for Kotlin coroutine integration. Cancellation handler unregisters callback to prevent resource leaks. `executeOnNetwork()` uses `network.openConnection(url)` to bind HTTP traffic to the specific cellular network (not default route), sets HTTPS with JSON headers and `X-Atheer-Source: cellular` marker. Timeouts: 10s for network acquisition, 30s for request, 15s for connect/read. `isCellularAvailable()` and `isNetworkAvailable()` utility methods provided. **Note:** This implements cellular-enforced routing (the software-level mechanism), not carrier-side Private APN configuration, which requires external carrier cooperation. |
| 4 | **Security — Keystore, AES-256-GCM, Tokenization** (`AtheerKeystoreManager.kt`) | **Complete** | Android Keystore integration: loads `AndroidKeyStore` provider, generates AES-256 master key with `KeyGenParameterSpec` (GCM block mode, no padding, randomized encryption required, non-extractable). `encrypt()`: initializes `AES/GCM/NoPadding` cipher in ENCRYPT_MODE, auto-generates 12-byte IV, prepends IV to ciphertext, encodes as Base64. `decrypt()`: decodes Base64, extracts 12-byte IV prefix, initializes cipher with `GCMParameterSpec` (128-bit tag), decrypts remaining bytes. `tokenize()`: encrypts data and prepends `ATK_` prefix for identification. `detokenize()`: validates prefix, strips it, decrypts. `generateNonce()`: uses `SecureRandom` to generate 16 random bytes, converts to 32-char hex string. `isValidNonceFormat()`: validates 32-char hex format with regex. |
| 5 | **Local Database — Room + Encrypted Logs** (`database/` package) | **Complete** | `AtheerDatabase`: Room database (version 1) with Singleton pattern using double-checked locking, `fallbackToDestructiveMigration()` for schema changes. `TransactionEntity`: Room `@Entity` on table `transactions` with auto-generated primary key, columns for `transaction_id`, `encrypted_amount`, `currency`, `merchant_id`, `timestamp`, `encrypted_token`, `nonce`, `is_synced`. Sensitive fields (`encrypted_amount`, `encrypted_token`) are encrypted at the application layer by `AtheerKeystoreManager` before insertion (field-level encryption). `TransactionDao`: full CRUD — `insertTransaction()` with REPLACE strategy, `getUnsyncedTransactions()` ordered by timestamp ASC, `getAllTransactions()` as `Flow<List>` for reactive UI, `updateSyncStatus()`, `deleteSyncedTransactions()`, `getTransactionById()`, `deleteTransactionById()`. All DAO methods are `suspend` (coroutine-compatible) except the Flow query. |
| 6 | **Facade Entry Point** (`AtheerSdk.kt`) | **Complete** | Singleton with `init(context, merchantId, apiBaseUrl)` / `getInstance()` pattern. Thread-safe initialization with `@Volatile` + `synchronized`. Internally wires `AtheerKeystoreManager`, `AtheerNetworkRouter`, and `AtheerDatabase`. `processTransaction()`: generates nonce, validates tokenized card, encrypts amount, saves to local DB, attempts cellular network send, updates sync status on success, gracefully handles offline mode. `syncPendingTransactions()`: queries unsynced records, iterates with individual error handling, reports count. JSON body built manually (no external JSON lib). Exposes `getKeystoreManager()`, `getNetworkRouter()`, `getDatabase()` for advanced use. `resetForTesting()` internal method for test isolation. Coroutine scope with `SupervisorJob() + Dispatchers.IO`. |

---

## 2. Supporting Files Status

| File | Status | Details |
|------|--------|---------|
| `AndroidManifest.xml` | **Complete** | Declares NFC, INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE permissions. NFC and NFC-HCE features declared as optional (`required="false"`). HCE service exported with `BIND_NFC_SERVICE` permission. |
| `res/xml/apduservice.xml` | **Complete** | Defines payment AID group with two AID filters (Atheer custom + Visa standard). Category set to `payment`. |
| `res/values/strings.xml` | **Complete** | Arabic string resources for HCE service descriptions. |
| `model/AtheerTransaction.kt` | **Complete** | Data class with all required fields: transactionId, amount, currency, merchantId, timestamp (default `currentTimeMillis`), tokenizedCard (nullable), nonce (nullable). |
| `build.gradle.kts` (module) | **Complete** | Android library plugin, compileSdk 34, minSdk 24, JDK 17. Dependencies: core-ktx, coroutines-android, Room (runtime + ktx + KSP compiler), JUnit, Mockito, coroutines-test. |
| `AtheerSdkTest.kt` | **Complete** | 7 unit tests covering: nonce format validation (valid, too short, invalid chars), token prefix verification, transaction ID uniqueness, JSON body field presence, APDU response status code validation. Tests use local helper methods to avoid Android Keystore dependency. |

---

## 3. Detailed Findings

### Strengths
- **All six core components contain real, functional implementation logic** — none are empty stubs or TODO placeholders.
- APDU command/response processing in `AtheerApduService` follows ISO 7816-4 conventions with correct CLA/INS/P1/P2 byte parsing.
- NFC Reader (`AtheerNfcReader`) implements the full ISO-DEP transceive protocol with proper connect/transceive/close lifecycle.
- Cellular routing uses the correct Android API (`network.openConnection()`) to bind traffic to a specific network interface, not just preference.
- Encryption uses hardware-backed Android Keystore with AES-256-GCM (industry standard for authenticated encryption).
- Field-level encryption strategy allows Room queries on non-sensitive columns while protecting sensitive data.
- Comprehensive Arabic documentation in code comments throughout all files.

### Observations (Not Blockers)
- **No SQLCipher:** Database file itself is not encrypted at rest. Field-level encryption covers sensitive columns, but metadata (timestamps, currency, sync status) is stored in plaintext SQLite. For PCI-DSS Level 1, full-database encryption with SQLCipher may be required.
- **No EMV Kernel:** The HCE service uses a custom APDU protocol (SELECT AID → GET DATA), not a full EMV contactless kernel (PPSE, GPO, READ RECORD, GENERATE AC). This is appropriate for a proprietary Atheer-to-Atheer payment scheme but would not work with standard POS terminals expecting EMV.
- **Private APN:** The implementation handles cellular network binding at the OS level, which is the maximum software-side capability. Actual Private APN provisioning (APN name, authentication, dedicated gateway) requires carrier-side configuration and is outside the SDK's scope.
- **Gradle Wrapper:** The `gradle-wrapper.jar` was missing from the repository, and the `gradlew` script had a quoting issue in `DEFAULT_JVM_OPTS` (fixed in this audit).

---

<div dir="rtl">

## 4. الملخص التفصيلي (باللغة العربية)

### نتيجة التدقيق

تم إجراء تدقيق شامل لمستودع `atheer-sdk` ومقارنة الكود المصدري الحالي بالمتطلبات الأساسية الستة المحددة. **النتيجة العامة: جميع المكونات الستة مُنفَّذة بشكل كامل وتحتوي على منطق برمجي حقيقي وليست مجرد هياكل فارغة (Stubs).**

#### تفاصيل كل مكون:

**1. خدمة HCE (محاكاة البطاقة المضيفة):**
- الحالة: ✅ مكتمل
- التنفيذ يشمل: معالجة أوامر APDU الحقيقية (SELECT AID + GET PAYMENT DATA)، تشفير بيانات الدفع قبل الإرسال عبر NFC، إدارة دورة حياة الخدمة بشكل صحيح، مسح البيانات الحساسة عند إلغاء التفعيل.
- التسجيل في AndroidManifest.xml مع ملف apduservice.xml يتضمن معرفات AID صحيحة.

**2. وحدة SoftPOS (وضع قارئ NFC):**
- الحالة: ✅ مكتمل
- التنفيذ يشمل: بروتوكول ISO-DEP الكامل (الاتصال → إرسال SELECT AID → التحقق من الاستجابة → طلب بيانات الدفع → استخراج الرمز المميز)، إدارة المهلات الزمنية، إغلاق القناة في كل الحالات.

**3. توجيه الشبكة عبر البيانات الخلوية:**
- الحالة: ✅ مكتمل
- التنفيذ يشمل: طلب شبكة خلوية حصرية عبر ConnectivityManager، ربط حركة HTTP بالشبكة الخلوية باستخدام `network.openConnection()`، دعم الـ Coroutines مع إدارة المهلات والإلغاء.
- ملاحظة: إعداد APN الخاص يتطلب تعاون شركة الاتصالات وهو خارج نطاق SDK.

**4. الأمان (التشفير والترميز):**
- الحالة: ✅ مكتمل
- التنفيذ يشمل: Android Keystore لحماية المفاتيح، تشفير AES-256-GCM مع IV عشوائي 12 بايت وعلامة مصادقة 128 بت، عمليات Tokenization و De-tokenization، توليد Nonce باستخدام SecureRandom لمنع هجمات إعادة التشغيل.

**5. قاعدة البيانات المحلية:**
- الحالة: ✅ مكتمل
- التنفيذ يشمل: Room Database مع نمط Singleton، كيان المعاملة مع تشفير على مستوى الحقول الحساسة، واجهة DAO كاملة (إدراج، استعلام، تحديث، حذف)، دعم Flow للمراقبة التفاعلية، فصل المعاملات المتزامنة وغير المتزامنة.

**6. واجهة SDK الرئيسية (Facade):**
- الحالة: ✅ مكتمل
- التنفيذ يشمل: نمط Singleton آمن للخيوط المتعددة، تنسيق كامل لمعالجة المعاملات (توليد Nonce → التشفير → الحفظ المحلي → الإرسال عبر الشبكة → تحديث حالة المزامنة)، دعم الوضع غير المتصل، مزامنة المعاملات المعلقة.

---

## 5. الخطوات التالية (Next Steps)

### أولوية عالية (يُوصى بتنفيذها قبل الإطلاق):

1. **إضافة تشفير قاعدة البيانات بالكامل باستخدام SQLCipher:**
   - حالياً يتم تشفير الحقول الحساسة فقط (encrypted_amount, encrypted_token).
   - البيانات الوصفية مثل الطوابع الزمنية (timestamps) وحالة المزامنة (is_synced) ورمز العملة (currency) مخزنة كنص عادي.
   - لتحقيق متطلبات PCI-DSS المستوى الأول، يُنصح بإضافة مكتبة `net.zetetic:android-database-sqlcipher` لتشفير ملف قاعدة البيانات بالكامل.

2. **إضافة اختبارات الأجهزة (Instrumented Tests):**
   - اختبارات الوحدة الحالية تغطي المنطق الأساسي فقط (التحقق من الصيغ، بناء JSON).
   - يجب إضافة اختبارات أجهزة لـ: عمليات التشفير وفك التشفير الفعلية عبر Android Keystore، عمليات قاعدة البيانات Room، معالجة أوامر APDU.

3. **إصلاح ملف Gradle Wrapper:**
   - ملف `gradle-wrapper.jar` غير موجود في المستودع — يجب إضافته لتمكين البناء المباشر بدون تثبيت Gradle مسبقاً.
   - تم إصلاح خطأ في علامات الاقتباس في ملف `gradlew` ضمن هذا التدقيق.

### أولوية متوسطة (تحسينات مستقبلية):

4. **دعم بروتوكول EMV القياسي في خدمة HCE:**
   - البروتوكول الحالي مخصص لنظام Atheer فقط (SELECT AID → GET DATA).
   - للتوافق مع أجهزة نقاط البيع القياسية، يجب تنفيذ تدفق EMV الكامل (PPSE → SELECT AID → GPO → READ RECORD → GENERATE AC).

5. **إضافة آلية إعادة المحاولة للمعاملات الفاشلة:**
   - حالياً عند فشل الإرسال عبر الشبكة، يتم حفظ المعاملة محلياً فقط.
   - يُنصح بإضافة WorkManager لجدولة إعادة المحاولات التلقائية مع خوارزمية Exponential Backoff.

6. **إضافة مراقبة وتسجيل الأحداث (Analytics/Logging):**
   - حالياً يتم استخدام `android.util.Log` فقط.
   - يُنصح بإضافة طبقة تسجيل قابلة للتكوين تدعم مستويات متعددة وتصدير السجلات.

7. **تحسين أمان بناء JSON:**
   - حالياً يتم بناء JSON يدوياً باستخدام قوالب النصوص (String Templates).
   - إذا احتوت قيم المعاملة على أحرف JSON خاصة (مثل `"` أو `\`)، قد يؤدي ذلك إلى تشوه البيانات.
   - يُنصح باستخدام `JSONObject` من مكتبة `org.json` المضمنة في Android.

### أولوية منخفضة:

8. **توثيق واجهة API العامة باللغة الإنجليزية:**
   - التوثيق الحالي باللغة العربية ممتاز للفريق المحلي.
   - للنشر العالمي، يُنصح بإضافة توثيق KDoc باللغة الإنجليزية.

9. **إعداد CI/CD:**
   - إضافة GitHub Actions لتشغيل الاختبارات والبناء تلقائياً عند كل طلب دمج (Pull Request).

</div>
