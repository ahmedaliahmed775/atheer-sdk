Atheer SDKمكتبة Android للدفع الآمن عبر NFCAtheer SDK هي مكتبة Android مكتوبة بـ Kotlin، مخصصة لتوفير طبقة دفع آمنة تعمل مع Atheer Switch Backend.تقوم المكتبة بإدارة:المفاتيح الآمنة داخل Android Keystore.التحقق من سلامة الجهاز.التوقيع الأمني للعمليات.الاتصال عبر NFC.تخزين البيانات محليًا.إرسال الطلبات إلى خادم Atheer Switch.الهدف من المكتبةتهدف هذه المكتبة إلى تمكين تطبيقات Android من تنفيذ تدفق دفع آمن يعتمد على:التحقق من هوية الجهاز.حماية المفاتيح داخل العتاد الآمن.إنشاء أرقام متسلسلة Counter لمنع إعادة الإرسال.دعم NFC كقناة تواصل سريعة.ربط التطبيق بخادم التحويل المركزي.أهم المكونات1) إدارة المفاتيح والأمانتستخدم المكتبة Android Keystore لحفظ الأسرار الحساسة بشكل آمن، مع آليات إضافية مثل:Root detection.BiometricPrompt.Play Integrity أو آليات تحقق مشابهة.إدارة Session مؤقتة للمدفوعات.2) طبقة NFCتدعم المكتبة:HCE على جهاز المستخدم.NFC Reader على جهاز الطرف الآخر.وهذا يسمح بتمرير بيانات الدفع بطريقة قصيرة وسريعة وآمنة.3) طبقة الشبكةتوجد طبقة اتصال مع backend مسؤولة عن:إرسال طلبات Charge.استلام الاستجابات.تحويل أخطاء الشبكة إلى نماذج مفهومة للتطبيق.4) التخزين المحليتستخدم المكتبة قاعدة بيانات محلية لتخزين:سجل العمليات.حالات المزامنة.بيانات يمكن الرجوع إليها عند عدم توفر الشبكة.هيكل المشروعatheer-sdk/
├── atheer-sdk/
│   └── src/main/
│       ├── java/com/atheer/sdk/
│       │   ├── AtheerSdk.kt
│       │   ├── AtheerPaymentSettingsActivity.kt
│       │   ├── database/
│       │   ├── hce/
│       │   ├── model/
│       │   ├── network/
│       │   ├── nfc/
│       │   └── security/
│       ├── res/
│       └── AndroidManifest.xml
├── build.gradle.kts
└── settings.gradle.kts
كيفية عمل المكتبةعند بدء جلسة الدفع:يتم التحقق من سلامة الجهاز.يتم فتح جلسة دفع آمنة.يتم إنشاء أو تحديث العداد Counter.يتم اشتقاق مفتاح محدود الاستخدام LUK.يتم توقيع البيانات.يتم تمرير الحمولة عبر NFC أو إرسالها عبر الشبكة.يتم استدعاء Backend للتنفيذ النهائي للعملية.المكونات البرمجيةAtheerSdk.ktالواجهة الرئيسية للمكتبة.تجمع بين الأمان، NFC، الشبكة، ونماذج الطلبات.AtheerKeystoreManagerمسؤول عن إدارة المفاتيح داخل Android Keystore وتوليد/حماية الأسرار.AtheerPaymentSessionيحفظ حالة الجلسة المؤقتة أثناء تنفيذ عملية الدفع.AtheerNfcReaderيقرأ البيانات القادمة عبر NFC ويتعامل معها قبل إرسالها للخادم.AtheerApduServiceخدمة HCE التي تسمح للهاتف بالتصرف كأنه بطاقة أو وسيط دفع.AtheerNetworkRouterمسؤول عن التواصل مع Atheer Switch Backend.AtheerCellularRouterيوفر مسارًا إضافيًا عبر الشبكة الخلوية عند الحاجة.AtheerDatabaseقاعدة البيانات المحلية للمكتبة.نماذج البياناتتحتوي المكتبة على نماذج واضحة لطلبات واستجابات الدفع، مثل:ChargeRequestChargeResponseLoginRequestLoginResponseSignupRequestSignupResponseBalanceResponseHistoryResponseAtheerTransactionAtheerErrorالتكامل مع السويتشهذه المكتبة مصممة لتعمل مع Atheer Switch Backend بشكل مباشر.من جهة SDK:تنشئ طلب الدفع.توقّع البيانات.ترسل deviceId وcounter وtimestamp وsignature.من جهة Backend:يتحقق من التوقيع.يمنع التكرار.يعالج الطلب.يعيد النتيجة.هذا التكامل هو ما يجعل المنظومة كاملة وآمنة.التثبيتإذا كنت تستخدم Gradle كنظام بناء، أضف المكتبة إلى مشروعك حسب آلية النشر المعتمدة لديك، ثم استدعِ الحزمة من التطبيق.مثال استخدامval sdk = AtheerSdk.initialize(context)

val request = ChargeRequest(
    deviceId = "DEVICE-123",
    counter = 10,
    timestamp = System.currentTimeMillis(),
    signature = "SIGNATURE",
    amount = 1500.0,
    currency = "YER",
    receiverAccount = "merchant_001",
    transactionType = "P2M"
)

sdk.charge(request) { result ->
    if (result.isSuccess) {
        // تم تنفيذ العملية بنجاح
    } else {
        // معالجة الخطأ
    }
}
الأمانالمكتبة تعتمد على عدة طبقات حماية، منها:حفظ الأسرار داخل Keystore.التحقق البيومتري.التحقق من حالة الجهاز.استخدام counter يمنع إعادة استخدام نفس الرسالة.التوقيع الرقمي لكل معاملة.المساهمةيمكنك توسيع المكتبة بإضافة:مزيد من مزودي الشبكة.دعم أنواع إضافية من العمليات.تحسينات في التحقق الأمني.توسيع نماذج الاستجابات والأخطاء.الترخيصراجع ملفات الترخيص الموجودة في المستودع.