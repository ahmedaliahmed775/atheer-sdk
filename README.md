# مكتبة أثير (Atheer SDK): نظام التشفير الحيوي الموثق مسبقاً (v2.0.0)

![الأمان](https://img.shields.io/badge/Security-Hardware--Backed-blue)
![المنصة](https://img.shields.io/badge/Platform-Android-green)
![الامتثال](https://img.shields.io/badge/Compliance-CDCVM-orange)

## نظرة عامة

يمثل الإصدار **Atheer SDK v2.0** نقلة نوعية في معايير الأمان، حيث انتقل من نظام تبادل الرموز البسيط إلى آلية **"التشفير الحيوي الموثق مسبقاً" (Pre-Authorized Biometric Cryptogram)**. تضمن هذه البنية عدم إرسال بيانات الدفع الحساسة إلا بعد نجاح المصادقة الحيوية للمستخدم، وتكون محمية بتوقيعات رقمية (ECDSA) تعتمد على العتاد الصلب للجهاز.

## كيف تعمل الآلية (تدفق الدفع بلمسة واحدة)

1.  **التجهيز**: يقوم التطبيق باستدعاء الدالة `preparePayment()`.
2.  **التحدي الحيوي**: تظهر للمستخدم واجهة بصمة الإصبع/الوجه. عند النجاح، تقوم وحدة الأمان (TEE/StrongBox) داخل الجهاز بتوقيع بيانات تحتوي على معرف الرمز (`tokenId`) وطابع زمني.
3.  **التفعيل (Arming)**: يتم إنشاء جلسة "مسلحة" آمنة مؤقتة لمدة 60 ثانية داخل التطبيق.
4.  **اللمس (The Tap)**: عند ملامسة الجهاز لجهاز القراءة (POS)، تتحقق خدمة HCE من أن الجلسة مفعلة.
5.  **تبادل البيانات**: ترسل المكتبة "الرمز + التوقيع الرقمي" (Cryptogram) عبر تقنية NFC.
6.  **المسح التلقائي**: يتم مسح الجلسة فوراً بعد اللمس لمنع هجمات إعادة الإرسال (Replay Attacks).
7.  **التغذية الراجعة**: يتلقى المستخدم تأكيداً فورياً عبر الاهتزاز (Haptic) والصوت (Audio).

## ميزات الأمان

*   **مفاتيح معتمدة على العتاد**: يتم توليد المفاتيح الخاصة داخل "المنطقة الآمنة" (TEE) في الجهاز ولا تخرج منها أبداً.
*   **امتثال CDCVM**: فرض التحقق من حامل البطاقة عبر الجهاز (بصمة الإصبع/الوجه).
*   **تشفير ديناميكي**: كل عملية دفع لها توقيع فريد مرتبط برمز وزمن محددين.
*   **دعم P2P و P2M**: تعمل بسلاسة في عمليات الدفع بين الأفراد أو للدفع للتجار.

## التكامل البرمجي (Integration)

### 1. تهيئة المكتبة (Initialization)

```kotlin
AtheerSdk.init(
    context = this,
    merchantId = "YOUR_MERCHANT_ID",
    apiKey = "YOUR_API_KEY",
    isSandbox = true
)
```

### 2. تجهيز الدفع (المصادقة الحيوية)

```kotlin
AtheerSdk.getInstance().preparePayment(
    activity = this,
    tokenId = "ATK_USER_TOKEN_123",
    onSuccess = {
        // تم توثيق المستخدم. الجهاز جاهز للمس عبر NFC لمدة 60 ثانية.
    },
    onError = { error ->
        // معالجة فشل المصادقة
    }
)
```

### 3. نماذج البيانات المبسطة

تم تبسيط كائنات `AtheerTransaction` و `ChargeRequest` لزيادة الكفاءة:

```kotlin
val transaction = AtheerTransaction(
    transactionId = "TX_998877",
    amount = 15000L, // 150.00 ريال
    currency = "SAR",
    receiverAccount = "MERCHANT_WALLET_ID",
    transactionType = "PURCHASE",
    atheerToken = "...",
    signature = "..." // التوقيع الرقمي (Cryptogram) الناتج
)
```

## المتطلبات

*   **أدنى مستوى API**: 24 (Android 7.0)
*   **العتاد**: يجب أن يدعم الجهاز تقنيتي NFC والمصادقة الحيوية.
*   **الأذونات**: `NFC`, `BIOMETRIC`, `VIBRATE`, `INTERNET`.

---
© 2024 Atheer Pay. جميع الحقوق محفوظة.
