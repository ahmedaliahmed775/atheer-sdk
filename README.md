# Atheer SDK - مكتبة مدفوعات NFC الذكية لـ Android

<div dir="rtl">

## نظرة عامة

**Atheer SDK** هي مكتبة Android متكاملة مصممة لتمكين التطبيقات من تنفيذ مدفوعات NFC اللاتلامسية بأمان عالٍ، سواء في الوضع المتصل بالإنترنت أو الوضع غير المتصل. تنتج المكتبة ملف `.aar` يمكن للتطبيقات الخارجية تضمينه مباشرة.

---

## المعمارية

تطبق المكتبة معمارية **Clean Architecture** بالطبقات التالية:

```
com.atheer.sdk/
├── AtheerSdk.kt              ← واجهة Facade الرئيسية
├── model/
│   ├── AtheerTransaction.kt  ← نموذج المعاملة
│   ├── LoginRequest.kt       ← نموذج طلب تسجيل الدخول
│   ├── LoginResponse.kt      ← نموذج استجابة تسجيل الدخول
│   ├── SignupRequest.kt      ← نموذج طلب التسجيل
│   ├── SignupResponse.kt     ← نموذج استجابة التسجيل
│   ├── BalanceResponse.kt    ← نموذج استجابة الرصيد
│   ├── ChargeRequest.kt      ← نموذج طلب الشحن
│   ├── ChargeResponse.kt     ← نموذج استجابة الشحن
│   └── HistoryResponse.kt    ← نموذج سجل المعاملات
├── hce/
│   └── AtheerApduService.kt  ← وحدة HCE للمدفوعات اللاتلامسية
├── nfc/
│   └── AtheerNfcReader.kt    ← وحدة SoftPOS لقراءة NFC
├── network/
│   └── AtheerNetworkRouter.kt← توجيه الشبكة عبر الشبكة الخلوية
├── security/
│   └── AtheerKeystoreManager.kt ← التشفير والـ Tokenization
└── database/
    ├── AtheerDatabase.kt     ← قاعدة البيانات المحلية
    ├── TransactionDao.kt     ← واجهة الوصول للبيانات
    └── TransactionEntity.kt  ← كيان المعاملة
```

---

## الوحدات الرئيسية

### 1. واجهة SDK الرئيسية (`AtheerSdk.kt`)
نقطة الدخول الوحيدة للتطبيقات الخارجية. تستخدم نمط Singleton وتوفر دوال معالجة المعاملات والمزامنة.

### 2. وحدة HCE (`AtheerApduService.kt`)
تحول الهاتف إلى بطاقة NFC لمحاكاة المدفوعات اللاتلامسية في الوضع غير المتصل. مسجلة في `AndroidManifest.xml` مع ملف `apduservice.xml`.

### 3. وحدة SoftPOS (`AtheerNfcReader.kt`)
تحول الهاتف إلى جهاز نقطة بيع (POS Terminal) لاستقبال المدفوعات من بطاقات NFC والهواتف الأخرى.

### 4. وحدة الشبكة (`AtheerNetworkRouter.kt`)
تفرض توجيه طلبات الدفع عبر شبكة البيانات الخلوية حصراً باستخدام `ConnectivityManager` و `TRANSPORT_CELLULAR`.

### 5. وحدة الأمان (`AtheerKeystoreManager.kt`)
- **Tokenization**: تحويل بيانات البطاقة إلى رمز مميز آمن
- **منع Replay Attacks**: توليد Nonce فريد لكل معاملة
- **التشفير**: AES-256-GCM مع مفاتيح محمية بـ Android Keystore

### 6. قاعدة البيانات (`AtheerDatabase.kt`)
قاعدة بيانات Room لتخزين سجلات المعاملات المشفرة محلياً للوضع غير المتصل.

---

## المتطلبات

- **Android SDK**: الحد الأدنى API 24 (Android 7.0)
- **Kotlin**: 1.9.x أو أحدث
- **JDK**: 17 أو أحدث
- **Android Studio**: Hedgehog (2023.1.1) أو أحدث

---

## كيفية بناء ملف AAR

### الطريقة 1: من سطر الأوامر (مُوصَى به)

```bash
# بناء النسخة الإصدارية للمكتبة (Release AAR)
./gradlew :atheer-sdk:assembleRelease

# موقع ملف AAR بعد البناء:
# atheer-sdk/build/outputs/aar/atheer-sdk-release.aar
```

```bash
# بناء النسخة التطويرية (Debug AAR)
./gradlew :atheer-sdk:assembleDebug

# موقع ملف AAR بعد البناء:
# atheer-sdk/build/outputs/aar/atheer-sdk-debug.aar
```

### الطريقة 2: من Android Studio

1. افتح المشروع في Android Studio
2. من القائمة: **Build → Make Project** أو اضغط `Ctrl+F9`
3. بعد نجاح البناء، انتقل إلى: **Build → Build Bundle(s)/APK(s) → Build APK(s)**
4. أو مباشرة عبر Gradle panel: ابحث عن `:atheer-sdk → Tasks → build → assembleRelease`

### الطريقة 3: تشغيل الاختبارات والبناء معاً

```bash
# تشغيل اختبارات الوحدة أولاً ثم البناء
./gradlew :atheer-sdk:test :atheer-sdk:assembleRelease
```

---

## كيفية دمج المكتبة في مشروعك

### الخيار 1: نسخ ملف AAR مباشرة

1. انسخ ملف `atheer-sdk-release.aar` إلى مجلد `libs` في مشروعك
2. أضف التبعية في `build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("libs/atheer-sdk-release.aar"))
    
    // تبعيات مطلوبة مع المكتبة
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

---

## كيفية الاستخدام

### التهيئة

```kotlin
// في Application.kt
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AtheerSdk.init(
            context = this,
            merchantId = "YOUR_MERCHANT_ID",
            apiBaseUrl = "https://api.atheer.com"
        )
    }
}
```

### معالجة معاملة دفع

```kotlin
val sdk = AtheerSdk.getInstance()
val transaction = AtheerTransaction(
    transactionId = "TXN_${System.currentTimeMillis()}",
    amount = 10000L, // 100.00 ريال (بالهللة)
    currency = "SAR",
    merchantId = "MERCHANT_001",
    tokenizedCard = "ATK_..." // الرمز المميز المُعدَّ مسبقاً
)

sdk.processTransaction(
    transaction = transaction,
    accessToken = "YOUR_ACCESS_TOKEN",
    onSuccess = { response ->
        Log.d("Payment", "نجاح الدفع: $response")
    },
    onError = { error ->
        Log.e("Payment", "فشل الدفع: ${error.message}")
    }
)
```

### تفعيل خدمة HCE (وضع البطاقة)

```kotlin
// تهيئة خدمة HCE في Activity أو Service
val apduService = AtheerApduService()
apduService.preparePayment(
    cardData = "4111111111111111", // رقم البطاقة
    amount = 10000L,
    currency = "SAR"
)
```

### تفعيل SoftPOS (وضع نقطة البيع)

```kotlin
// في Activity التي تريد تفعيل قراءة NFC
val nfcReader = AtheerNfcReader(
    merchantId = "MERCHANT_001",
    transactionCallback = { transaction ->
        Log.i("SoftPOS", "تم استقبال دفعة: ${transaction.transactionId}")
    },
    errorCallback = { error ->
        Log.e("SoftPOS", "خطأ في القراءة: ${error.message}")
    }
)

val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
nfcAdapter?.enableReaderMode(
    this,
    nfcReader,
    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B,
    null
)
```

### مزامنة المعاملات غير المتزامنة

```kotlin
// مزامنة المعاملات عند استعادة الاتصال بالشبكة
sdk.syncPendingTransactions(accessToken = "YOUR_ACCESS_TOKEN") { syncedCount ->
    Log.i("Sync", "تمت مزامنة $syncedCount معاملة بنجاح")
}
```

### تسجيل الدخول

```kotlin
// في Coroutine أو ViewModel
val sdk = AtheerSdk.getInstance()
val result = sdk.login(LoginRequest(username = "user@example.com", password = "password"))
result.onSuccess { response ->
    val accessToken = response.accessToken
    Log.i("Auth", "تم تسجيل الدخول - التوكن: $accessToken")
}.onFailure { error ->
    Log.e("Auth", "فشل تسجيل الدخول: ${error.message}")
}
```

### تسجيل مستخدم جديد

```kotlin
val result = sdk.signup(
    SignupRequest(
        username = "newuser",
        password = "password",
        email = "newuser@example.com",
        phone = "+966500000000"
    )
)
result.onSuccess { response ->
    Log.i("Auth", "تم التسجيل - معرف المستخدم: ${response.userId}")
}
```

### الاستعلام عن الرصيد

```kotlin
val result = sdk.getBalance(accessToken = "YOUR_ACCESS_TOKEN")
result.onSuccess { response ->
    Log.i("Balance", "الرصيد: ${response.balance} ${response.currency}")
}
```

### سجل المعاملات

```kotlin
val result = sdk.getHistory(accessToken = "YOUR_ACCESS_TOKEN")
result.onSuccess { response ->
    Log.i("History", "عدد المعاملات: ${response.totalCount}")
    response.transactions.forEach { tx ->
        Log.d("History", "معاملة: ${tx.transactionId} - ${tx.amount} ${tx.currency}")
    }
}
```

### إجراء عملية شحن

```kotlin
val result = sdk.charge(
    request = ChargeRequest(
        amount = 5000L, // 50.00 ريال (بالهللة)
        currency = "SAR",
        merchantId = "MERCHANT_001",
        description = "شحن رصيد"
    ),
    accessToken = "YOUR_ACCESS_TOKEN"
)
result.onSuccess { response ->
    Log.i("Charge", "نجاح الشحن - معرف المعاملة: ${response.transactionId}")
}
```

---

## الصلاحيات المطلوبة في تطبيقك

أضف الصلاحيات التالية في `AndroidManifest.xml` الخاص بتطبيقك:

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
```

---

## الاختبار

```bash
# تشغيل اختبارات الوحدة
./gradlew :atheer-sdk:test

# عرض نتائج الاختبار
# atheer-sdk/build/reports/tests/testDebugUnitTest/index.html
```

---

## الأمان

- **Android Keystore**: جميع المفاتيح التشفيرية محمية داخل Android Keystore ولا يمكن استخراجها
- **AES-256-GCM**: تشفير قوي مع التحقق من النزاهة
- **Nonce**: رقم عشوائي فريد لكل معاملة لمنع هجمات إعادة التشغيل
- **Tokenization**: بيانات البطاقة الحقيقية لا تُخزَّن ولا تُرسَل أبداً
- **تشفير الحقول**: البيانات الحساسة مشفرة قبل تخزينها في قاعدة البيانات

---

## الترخيص

جميع الحقوق محفوظة لـ Atheer © 2025

</div>
