# Atheer SDK: معالجة دفع آمنة وسلسة لنظام Android

![حالة البناء](https://img.shields.io/badge/build-passing-brightgreen)
![الترخيص](https://img.shields.io/badge/license-MIT-blue)
![المنصة](https://img.shields.io/badge/platform-Android-green)
![مستوى API](https://img.shields.io/badge/API%20Level-21%2B-orange)

## نظرة عامة على المشروع

تُعد Atheer SDK مكتبة Android قوية وآمنة مصممة لتسهيل معالجة المدفوعات الحديثة، مع التركيز على **محاكاة بطاقة المضيف (HCE)**، و**إمكانيات SoftPOS**، و**إدارة المعاملات دون اتصال بالإنترنت**. توفر مجموعة شاملة من الأدوات للمطورين لدمج وظائف دفع آمنة وفعالة وموثوقة في تطبيقات Android الخاصة بهم. تم بناء SDK مع التركيز القوي على الأمان، وتتضمن تقنيات تشفير متقدمة، وتدابير لمكافحة التلاعب، وممارسات آمنة للتعامل مع البيانات لحماية معلومات الدفع الحساسة.

هدفنا هو تمكين الشركات والمطورين من خلال حل دفع ليس فقط قويًا ومرنًا، ولكنه يلتزم أيضًا بأعلى معايير الصناعة للأمان والامتثال، مما يتيح تجربة دفع سلسة للمستخدمين النهائيين.

## الميزات الرئيسية

تقدم Atheer SDK مجموعة غنية من الميزات المصممة لمعالجة المدفوعات المتقدمة على Android:

*   **محاكاة بطاقة المضيف (HCE)**: تمكن أجهزة Android من العمل كبطاقات دفع، مما يسمح بإجراء معاملات لا تلامسية باستخدام تقنية NFC دون الحاجة إلى عنصر أمان مادي.
*   **إمكانيات SoftPOS**: تحول الهواتف الذكية القياسية التي تعمل بنظام Android إلى محطات دفع، قادرة على قبول مدفوعات البطاقات مباشرة، وهي مثالية للشركات الصغيرة والتجار المتنقلين.
*   **معالجة الدفع دون اتصال بالإنترنت**: تدعم المعاملات حتى عندما يكون الجهاز غير متصل بالإنترنت، باستخدام الرموز المميزة المجهزة مسبقًا وخزنة محلية آمنة، مع مزامنة تلقائية بمجرد استعادة الاتصال.
*   **إجراءات أمان متقدمة**:
    *   **تكامل Android Keystore**: يخزن مفاتيح التشفير بأمان، ويحميها من الوصول غير المصرح به.
    *   **ترميز البيانات (Data Tokenization)**: يحول بيانات البطاقة الحساسة إلى رموز غير حساسة، مما يقلل من نطاق الامتثال لمعيار PCI DSS.
    *   **مسح الذاكرة (Memory Wiping)**: يمسح البيانات الحساسة من الذاكرة فور الاستخدام لمنع استغلال بقايا البيانات.
    *   **توليد Nonce**: يستخدم أرقامًا فريدة تستخدم لمرة واحدة لكل معاملة لمنع هجمات إعادة التشغيل.
    *   **اكتشاف الروت والمحاكي**: ينفذ فحوصات لمنع تشغيل SDK في بيئات مخترقة أو محاكية، مما يعزز الأمان.
    *   **واجهة برمجة تطبيقات Google Play Integrity**: تتحقق من أصالة وسلامة التطبيق وبيئة الجهاز.
    *   **تثبيت الشهادة (Certificate Pinning)**: يضمن الاتصال الآمن مع خوادم الواجهة الخلفية عن طريق التحقق من شهادات الخادم، مما يمنع هجمات Man-in-the-Middle (MITM).
*   **معالجة الشبكة القوية**: تدير الاتصال الآمن مع بوابات الدفع، بما في ذلك آليات إعادة المحاولة والمزامنة في الخلفية للمعاملات دون اتصال بالإنترنت.
*   **قاعدة بيانات المعاملات المحلية**: تخزن تفاصيل المعاملات بأمان محليًا، مما يضمن استمرارية البيانات وتمكين إمكانيات عدم الاتصال بالإنترنت.
*   **واجهة برمجة تطبيقات مرنة**: توفر واجهة برمجة تطبيقات واضحة وسهلة الاستخدام للمطورين لدمج وظائف الدفع بأقل جهد.

## نظرة عامة على البنية

تم تصميم Atheer SDK ببنية معيارية ومتعددة الطبقات لضمان قابلية التوسع وسهولة الصيانة والأمان القوي. تتفاعل المكونات الأساسية لتوفير تجربة دفع سلسة مع الالتزام ببروتوكولات الأمان الصارمة.

**المكونات المعمارية الرئيسية:**

*   **AtheerSdk (واجهة Facade)**: نقطة الدخول الرئيسية للمطورين، وتوفر واجهة مبسطة لوظائف SDK. تنسق التفاعلات بين الوحدات الداخلية المختلفة وتفرض فحوصات الأمان الأولية مثل اكتشاف الروت والمحاكي.
*   **وحدة الأمان (`com.atheer.sdk.security`)**:
    *   **AtheerKeystoreManager**: يدير مفاتيح التشفير باستخدام Android Keystore، ويقوم بالتشفير وفك التشفير وترميز البيانات الحساسة (مثل أرقام البطاقات). كما يتعامل مع توليد Nonce لمنع هجمات إعادة التشغيل.
    *   **AtheerTokenManager**: ينفذ خزنة رموز مميزة دون اتصال بالإنترنت باستخدام `EncryptedSharedPreferences` لتخزين رموز الدفع المجهزة مسبقًا بأمان. يدير استهلاك الرموز وانتهاء صلاحيتها، مما يضمن الامتثال لمعيار PCI-DSS للبيانات المخزنة.
*   **وحدة الشبكة (`com.atheer.sdk.network`)**:
    *   **AtheerNetworkRouter**: يتعامل مع جميع الاتصالات الشبكية الآمنة مع الواجهة الخلفية لـ Atheer. يستخدم `OkHttp` مع ميزات متقدمة مثل **تثبيت الشهادة (Certificate Pinning)** لمنع هجمات Man-in-the-Middle (MITM) ويفرض TLS 1.2+ للاتصالات الآمنة. كما يتضمن فحوصات توفر الشبكة.
    *   **AtheerSyncWorker**: تطبيق `WorkManager` مسؤول عن المزامنة في الخلفية للمعاملات دون اتصال بالإنترنت مع الواجهة الخلفية بمجرد استعادة الاتصال بالشبكة.
*   **وحدة HCE (`com.atheer.sdk.hce`)**:
    *   **AtheerApduService**: تطبيق `HostApduService` الذي يمكّن الجهاز من العمل كبطاقة دفع لا تلامسية. يعالج أوامر APDU، ويسترد رموز الدفع من `AtheerTokenManager`، ويقوم بمسح الذاكرة للبيانات الحساسة فور الاستخدام.
*   **وحدة قاعدة البيانات (`com.atheer.sdk.database`)**:
    *   **AtheerDatabase**: تطبيق مكتبة Room Persistence لتخزين كيانات المعاملات بأمان محليًا. تعد قاعدة البيانات هذه حاسمة لتمكين إمكانيات الدفع دون اتصال بالإنترنت وضمان سلامة البيانات.
*   **وحدة النموذج (`com.atheer.sdk.model`)**: تحدد هياكل البيانات للطلبات والاستجابات وكائنات المعاملات الداخلية، مما يضمن عقود بيانات واضحة عبر SDK.

تضمن هذه البنية عزل العمليات الحساسة وحمايتها، مع توفير إطار عمل مرن وفعال لمعالجة المدفوعات.

## المتطلبات / المتطلبات المسبقة

لدمج واستخدام Atheer SDK، تأكد من أن بيئة التطوير الخاصة بك وجهاز Android المستهدف يفيان بالمواصفات التالية:

*   **Android Studio**: يوصى بأحدث إصدار مستقر.
*   **Android SDK**: مستوى API 21 (Android 5.0 Lollipop) أو أعلى.
*   **Kotlin**: تم كتابة SDK بلغة Kotlin، لذا فإن الإلمام باللغة مفيد.
*   **جهاز يدعم NFC**: لوظائف HCE و SoftPOS، يجب أن يحتوي جهاز Android المستهدف على أجهزة NFC.
*   **أذونات الإنترنت**: سيتطلب تطبيقك `android.permission.INTERNET` للاتصال بالشبكة.
*   **أذونات NFC**: سيتطلب تطبيقك `android.permission.NFC` لوظائف HCE.
*   **خدمات Google Play**: مطلوبة لفحوصات Google Play Integrity API.

## التثبيت والإعداد

يتضمن دمج Atheer SDK في مشروع Android الخاص بك بضع خطوات مباشرة:

### 1. إضافة SDK كاعتمادية

أولاً، أضف Atheer SDK إلى ملف `build.gradle.kts` (على مستوى الوحدة) الخاص بمشروعك. تأكد من الإعلان عن مستودع `mavenCentral()` في ملف `settings.gradle.kts` أو `build.gradle.kts` (على مستوى المشروع).

```kotlin
// build.gradle.kts (module-level)

dependencies {
    implementation("com.atheer.sdk:atheer-sdk:1.0.0") // استبدل بأحدث إصدار
    // مطلوب لـ WorkManager للمزامنة في الخلفية
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    // مطلوب لـ EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // مطلوب لـ Google Play Integrity API
    implementation("com.google.android.play:integrity:1.3.0")
    // مطلوب لـ RootBeer (إذا تم تمكين اكتشاف الروت)
    implementation("com.scottyab:rootbeer:0.0.8")
}
```

### 2. تكوين الأذونات في `AndroidManifest.xml`

أضف الأذونات الضرورية إلى ملف `AndroidManifest.xml` الخاص بك للوصول إلى الشبكة ووظائف NFC:

```xml
<!-- AndroidManifest.xml -->

<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.NFC" />

<uses-feature android:name="android.hardware.nfc" android:required="false" />
<uses-feature android:name="android.hardware.nfc.hce" android:required="false" />
```

### 3. الإعلان عن خدمة HCE وتكوين أمان الشبكة

لوظيفة HCE، أعلن عن `AtheerApduService` في ملف `AndroidManifest.xml` الخاص بك واربطه بمورد `apduservice.xml`. تأكد أيضًا من أن تكوين أمان الشبكة الخاص بك يسمح بالاتصال الآمن.

```xml
<!-- AndroidManifest.xml -->

<application
    ...
    android:networkSecurityConfig="@xml/network_security_config">

    <service
        android:name=".hce.AtheerApduService"
        android:exported="true"
        android:permission="android.permission.BIND_NFC_SERVICE">
        <intent-filter>
            <action android:name="android.nfc.cardemulation.action.HOST_APDU_SERVICE" />
        </intent-filter>
        <meta-data
            android:name="android.nfc.cardemulation.host_apdu_service"
            android:resource="@xml/apduservice" />
    </service>

    <!-- إعلان WorkManager للمزامنة في الخلفية -->
    <provider
        android:name="androidx.work.impl.WorkManagerInitializer"
        android:authorities="${applicationId}.workmanager-init"
        android:exported="false"
        android:enabled="false" />

    <provider
        android:name="androidx.startup.InitializationProvider"
        android:authorities="${applicationId}.androidx-startup"
        android:exported="false"
        tools:node="merge">
        <meta-data android:name="androidx.work.WorkManagerInitializer" android:value="androidx.startup" />
    </provider>

</application>
```

**`res/xml/apduservice.xml`**:

```xml
<!-- res/xml/apduservice.xml -->

<host-apdu-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/service_name"
    android:requireDeviceUnlock="false">
    <aid-list>
        <!-- استبدل بمعرف AID الفعلي الخاص بك -->
        <aid-filter android:name="F222222222" />
    </aid-list>
</host-apdu-service>
```

**`res/xml/network_security_config.xml`**:

```xml
<!-- res/xml/network_security_config.xml -->

<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <!-- اختياري: تثبيت للمجالات المحددة إذا لم يتم استخدام CertificatePinner في OkHttp -->
    <!--
    <domain-config>
        <domain includeSubdomains="true">api.atheer.com</domain>
        <pin-set expiration="2025-01-01">
            <pin digest="SHA-256">7HIpSrxNzqQOK6hzZcl8N0VYRsqDx9Le5tpx3468AlA=</pin>
            <pin digest="SHA-256">Af8uCAY87z1S9x10G95F63YvX1D4v10G95F63YvX1D4=</pin>
        </pin-set>
    </domain-config>
    -->
</network-security-config>
```

### 4. تهيئة SDK

قم بتهيئة Atheer SDK في فئة `Application` أو `Activity` الرئيسية الخاصة بك في أقرب وقت ممكن. يضمن ذلك أن...

## الاستخدام

### `AtheerSdk`

| الطريقة                                   | الوصف                                                                                                                                      |
| :--------------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------- |
| `init(context, merchantId, apiBaseUrl)`  | **(ثابت)** تهيئة SDK بمعرف التاجر الخاص بك وعنوان URL الأساسي لواجهة برمجة التطبيقات. يقوم بإجراء فحوصات أمان حرجة. يجب استدعاؤه مرة واحدة.                   |
| `getInstance()`                          | **(ثابت)** يسترد مثيل Singleton لـ SDK. يرمي `IllegalStateException` إذا لم يتم استدعاء `init()`.
| `checkAppIntegrity(nonce, onComplete)`   | يتحقق من سلامة التطبيق والجهاز باستخدام Google Play Integrity API.
| `processTransaction(...)`                | يعالج معاملة دفع، ويتعامل مع السيناريوهات المتصلة وغير المتصلة بالإنترنت. يقوم بتشفير البيانات، ويخزن المعاملة محليًا، ويزامنها مع الواجهة الخلفية. |
| `charge(request, accessToken)`           | **(تعليق)** ينفذ عملية شحن مباشرة عبر الإنترنت ضد الواجهة الخلفية لـ Atheer.
| `syncPendingTransactions(...)`           | يشغل يدويًا مزامنة أي معاملات معلقة دون اتصال بالإنترنت.
| `scheduleBackgroundSync(authToken)`      | يجدول مهمة `WorkManager` لمزامنة المعاملات دون اتصال بالإنترنت في الخلفية عند توفر الشبكة.
| `getKeystoreManager()`                   | يعيد مثيل `AtheerKeystoreManager` لعمليات الأمان المتقدمة.
| `getDatabase()`                          | يعيد مثيل `AtheerDatabase` للوصول المباشر إلى قاعدة البيانات إذا لزم الأمر.

### `AtheerKeystoreManager`

يدير عمليات التشفير وتخزين المفاتيح الآمن.

| الطريقة                 | الوصف                                                                                             |
| :--------------------- | :------------------------------------------------------------------------------------------------------ |
| `encrypt(plainText)`   | يشفر سلسلة باستخدام AES-256-GCM بمفتاح مخزن في Android Keystore.
| `decrypt(encryptedText)` | يفك تشفير سلسلة تم تشفيرها مسبقًا باستخدام `encrypt()`.
| `tokenize(cardData)`   | يحول البيانات الحساسة (مثل PAN) إلى رمز آمن وغير حساس.
| `detokenize(token)`    | يسترد البيانات الحساسة الأصلية من الرمز. استخدم بحذر شديد.
| `generateNonce()`      | يولد Nonce عشوائيًا آمنًا تشفيريًا بحجم 16 بايت لمنع هجمات إعادة التشغيل.

### `AtheerTokenManager`

يدير خزنة الرموز المميزة دون اتصال بالإنترنت.

| الطريقة                  | الوصف                                                                                              |
| :----------------------- | :-------------------------------------------------------------------------------------------------------- |
| `provisionTokens(tokens)` | يضيف قائمة من رموز الدفع المجهزة مسبقًا إلى الخزنة المشفرة الآمنة للاستخدام دون اتصال بالإنترنت.
| `consumeNextToken()`    | يسترد ويزيل الرمز التالي المتاح وغير المنتهي الصلاحية من الخزنة. يعيد `null` إذا لم يكن هناك أي رمز متاح.
| `getTokensCount()`      | يعيد عدد الرموز الصالحة وغير المنتهية الصلاحية المتوفرة حاليًا في الخزنة.

## الأمان

الأمان هو حجر الزاوية في Atheer SDK. نستخدم نهجًا متعدد الطبقات لحماية بيانات الدفع وضمان سلامة كل معاملة.

#### تشفير البيانات
*   **التشفير في وضع السكون**: يتم تشفير جميع البيانات الحساسة المخزنة على الجهاز، بما في ذلك الرموز المميزة دون اتصال بالإنترنت وسجلات المعاملات، باستخدام **AES-256-GCM**. يستفيد `AtheerTokenManager` من `EncryptedSharedPreferences`، المدعوم بواسطة Android Keystore، لحماية خزنة الرموز المميزة.
*   **التشفير أثناء النقل**: يتم فرض جميع الاتصالات مع الواجهة الخلفية عبر **TLS 1.2+**. تم تكوين `AtheerNetworkRouter` لاستخدام مجموعات تشفير حديثة وآمنة.

#### إدارة المفاتيح
*   **نظام Android Keystore**: يتم إنشاء مفاتيح التشفير وتخزينها داخل **Android Keystore**، الذي يحميها من الاستخراج من الجهاز. يتم تكوين المفاتيح لتكون غير قابلة للتصدير وترتبط بالأجهزة الآمنة للجهاز (إذا كانت متوفرة).

#### معالجة البيانات
*   **ترميز البيانات (Tokenization)**: بدلاً من التعامل مع بيانات البطاقة الخام (PAN)، تعتمد SDK على الترميز. يمكن لـ `AtheerKeystoreManager` تحويل البيانات الحساسة إلى رموز آمنة (`ATK_...`) يمكن التعامل معها بمخاطر أقل بكثير.
*   **مسح الذاكرة (Memory Wiping)**: يتم الاحتفاظ بالبيانات الحساسة، مثل رموز الدفع أو مبالغ المعاملات، في الذاكرة لأقصر فترة ممكنة. يقوم `AtheerApduService` بشكل صريح بالكتابة فوق المصفوفات البايتية والمخازن المؤقتة الأخرى التي تحتوي على معلومات حساسة وإلغاء قيمتها فور استخدامها.

#### منع التهديدات
*   **الحماية من هجمات إعادة التشغيل (Replay Attack Protection)**: تصاحب كل معاملة **Nonce** فريد يستخدم لمرة واحدة يتم إنشاؤه بواسطة `AtheerKeystoreManager`. يتحقق خادم الواجهة الخلفية من هذا Nonce لضمان عدم إمكانية إعادة تشغيل طلب معاملة تم التقاطه بشكل ضار.
*   **منع هجمات Man-in-the-Middle (MITM)**: ينفذ `AtheerNetworkRouter` **تثبيت الشهادة (Certificate Pinning)** عبر `CertificatePinner` الخاص بـ `OkHttp`. يضمن ذلك أن SDK يتصل فقط بخادم الواجهة الخلفية الأصيل لـ Atheer، مما يمنع المهاجمين من اعتراض حركة المرور بشهادة احتيالية.
*   **فحوصات مكافحة التلاعب والبيئة**: تتضمن SDK **اكتشاف الروت (Root Detection)** المدمج (باستخدام مكتبة RootBeer) و**اكتشاف المحاكي (Emulator Detection)**. تمنع هذه الفحوصات، التي يتم إجراؤها أثناء التهيئة، تشغيل SDK في بيئات مخترقة أو غير آمنة حيث يمكن اعتراض البيانات بسهولة.
*   **سلامة التطبيق**: تستفيد طريقة `checkAppIntegrity()` من **Google Play Integrity API** للتحقق من أن التطبيق لم يتم التلاعب به ويعمل على جهاز Android أصلي.

## المدفوعات دون اتصال بالإنترنت

توفر Atheer SDK آلية قوية لقبول المدفوعات حتى عندما لا يكون للجهاز اتصال بالإنترنت. هذا أمر بالغ الأهمية للتجار في المناطق ذات التغطية الشبكية الضعيفة أو لحالات الاستخدام المتنقلة.

### كيف تعمل

1.  **تزويد الرموز المميزة**: عندما يكون الجهاز متصلاً بالإنترنت، يطلب التطبيق المضيف دفعة من رموز الدفع التي تستخدم لمرة واحدة من الواجهة الخلفية لـ Atheer. ثم يتم تخزين هذه الرموز بأمان في خزنة `AtheerTokenManager` المشفرة.
2.  **المعاملة دون اتصال بالإنترنت**: عندما يتم بدء الدفع دون اتصال بالإنترنت (على سبيل المثال، عبر HCE/SoftPOS)، يسترد `AtheerApduService` أحد الرموز المجهزة مسبقًا من الخزنة المحلية. يتم تخزين تفاصيل المعاملة، بما في ذلك الرمز والمبلغ المشفر، بأمان في قاعدة البيانات المحلية.
3.  **حد عدم الاتصال بالإنترنت**: يمكن ربط كل رمز مجهز مسبقًا بحد معاملة دون اتصال بالإنترنت، والذي يتم فرضه محليًا بواسطة SDK. إذا تجاوزت المعاملة هذا الحد، فسيتم رفضها.
4.  **المزامنة في الخلفية**: بمجرد استعادة الاتصال بالإنترنت، يكتشف `AtheerSyncWorker` تلقائيًا المعاملات المعلقة دون اتصال بالإنترنت في قاعدة البيانات المحلية ويزامنها بأمان مع الواجهة الخلفية لـ Atheer. يضمن ذلك معالجة جميع المعاملات وتسويتها في النهاية.

يسمح هذا النهج بتجربة مستخدم سلسة، حيث يمكن إكمال المدفوعات على الفور حتى بدون اتصال بالشبكة، مع الحفاظ على معايير أمان عالية وضمان الاتساق النهائي مع الواجهة الخلفية.

## خيارات التكوين

تقدم Atheer SDK العديد من نقاط التكوين لتكييف سلوكها مع احتياجات تطبيقك.

*   **`AtheerSdk.init(context, merchantId, apiBaseUrl)`**: يتم التكوين الأساسي أثناء التهيئة:
    *   `merchantId`: معرفك الفريد كتاجر، مقدم من Atheer.
    *   `apiBaseUrl`: عنوان URL الأساسي لنقاط نهاية Atheer API (على سبيل المثال، `https://api.atheer.com`).
*   **HCE AID**: قم بتكوين معرف التطبيق (AID) الخاص بك في `res/xml/apduservice.xml`. هذا AID حاسم لمقارئ NFC لتوجيه طلبات الدفع بشكل صحيح إلى خدمة HCE الخاصة بك.
*   **تكوين أمان الشبكة**: بينما تستخدم SDK تثبيت الشهادة داخليًا، يمكنك تخصيص سلوك أمان الشبكة بشكل أكبر عبر `res/xml/network_security_config.xml` إذا لزم الأمر، على سبيل المثال، لإضافة نقاط ثقة إضافية أو تكوينات خاصة بالمجال.
*   **اكتشاف الروت/المحاكي**: تتضمن SDK رمزًا معلقًا لاكتشاف الروت والمحاكي في `AtheerSdk.kt`. يؤدي إلغاء التعليق على هذه الأسطر إلى تمكين فحوصات أمان صارمة ستمنع SDK من التهيئة على الأجهزة المخترقة أو المحاكية. يوصى بشدة بتمكين هذه في بيئات الإنتاج.
*   **عدد الرموز المميزة دون اتصال بالإنترنت والحد الأقصى**: عند تزويد الرموز المميزة باستخدام `fetchOfflineTokens`، يمكنك تحديد `count` (عدد الرموز المميزة المراد جلبها) و `limit` (الحد الأقصى لمبلغ المعاملة لكل رمز للاستخدام دون اتصال بالإنترنت). تسمح لك هذه المعلمات بإدارة سعة الدفع دون اتصال بالإنترنت والمخاطر.

## معالجة الأخطاء

توفر Atheer SDK آليات واضحة لمعالجة الأخطاء، مما يسمح للمطورين ببناء تطبيقات دفع قوية ومرنة.

*   **أخطاء التهيئة**: يمكن أن ترمي طريقة `AtheerSdk.init()` `SecurityException` إذا تم تمكين اكتشاف الروت أو المحاكي وتم اكتشاف بيئة مخترقة. يمكن أن ترمي أيضًا `IllegalStateException` إذا تم استدعاء `init()` عدة مرات أو إذا لم يتم تكوين SDK بشكل صحيح.
    ```kotlin
    try {
        AtheerSdk.init(context, merchantId, apiBaseUrl)
    } catch (e: SecurityException) {
        // معالجة فشل الأمان، على سبيل المثال، تسجيل، إخطار المستخدم، أو الخروج من التطبيق
        Log.e("AtheerSDK", "خطأ أمان أثناء التهيئة: ${e.message}")
    } catch (e: IllegalStateException) {
        // معالجة أخطاء التكوين أو الحالة
        Log.e("AtheerSDK", "خطأ التهيئة: ${e.message}")
    }
    ```
*   **أخطاء الشبكة**: عمليات الشبكة، مثل `AtheerNetworkRouter.executeStandard()` و `AtheerSdk.charge()`، تعيد كائنات `Result` أو ترمي `IOException` (أو فئاتها الفرعية) للمشكلات المتعلقة بالشبكة (على سبيل المثال، لا يوجد إنترنت، مهلة، خادم غير قابل للوصول) والأخطاء الخاصة بواجهة برمجة التطبيقات.
    ```kotlin
    // مثال مع AtheerSdk.charge()
    val result = AtheerSdk.getInstance().charge(chargeRequest, authToken)
    result.onSuccess { chargeResponse ->
        // معالجة الشحن الناجح
    }.onFailure { e ->
        when (e) {
            is IOException -> Log.e("Charge", "خطأ شبكة: ${e.message}")
            is IllegalArgumentException -> Log.e("Charge", "طلب غير صالح: ${e.message}")
            // معالجة أخطاء أخرى خاصة بواجهة برمجة التطبيقات بناءً على الاستثناء أو نص الاستجابة
            else -> Log.e("Charge", "خطأ غير معروف: ${e.message}")
        }
    }
    ```
*   **رفض دفع HCE**: يبث `AtheerApduService` نية `ACTION_PAYMENT_REJECTED` إذا تجاوزت معاملة دون اتصال بالإنترنت الحد المكون. يمكن لتطبيقك تسجيل `BroadcastReceiver` للتعامل مع هذه الأحداث.
    ```kotlin
    // انظر أمثلة الاستخدام -> معالجة بث رفض الدفع للتطبيق
    ```
*   **أخطاء إدارة الرموز المميزة**: يعيد `AtheerTokenManager.consumeNextToken()` `null` إذا لم تكن هناك رموز صالحة متاحة. يمكن أن يرمي `AtheerKeystoreManager.detokenize()` `IllegalArgumentException` لتنسيقات الرموز المميزة غير الصالحة.
    ```kotlin
    val token = AtheerSdk.getInstance().getTokenManager().consumeNextToken()
    if (token == null) {
        Log.w("TokenManager", "لا توجد رموز مميزة دون اتصال بالإنترنت متاحة. يرجى تزويد المزيد.")
        // مطالبة المستخدم بالاتصال بالإنترنت أو جلب رموز جديدة
    }
    ```

من الأهمية بمكان تنفيذ معالجة أخطاء شاملة في تطبيقك لتوفير تجربة مستخدم سلسة وإدارة المواقف غير المتوقعة بأمان.

## الاختبار

يعد الاختبار الشامل ضروريًا لأي SDK للدفع. تم تصميم Atheer SDK لتكون قابلة للاختبار، ويجب عليك تنفيذ اختبارات لتكاملك.

*   **اختبارات الوحدة**: تتضمن SDK نفسها اختبارات وحدة للمنطق الأساسي ومكونات الأمان ووظائف الأداة المساعدة. يمكنك تشغيل هذه الاختبارات كجزء من عملية البناء الخاصة بك.
    *   `AtheerSdkTest.kt`: يحتوي على أمثلة لكيفية اختبار وظائف SDK الرئيسية.
*   **اختبارات التكامل**: لتطبيقك، يجب عليك كتابة اختبارات تكامل لضمان تفاعل SDK بشكل صحيح مع مكونات تطبيقك وواجهة المستخدم والخدمات الخلفية.
    *   **محاكاة استجابات الشبكة**: عند اختبار الميزات التي تعتمد على الشبكة، قم بمحاكاة `AtheerNetworkRouter` لمحاكاة استجابات API المختلفة (نجاح، فشل، رموز خطأ مختلفة) دون إجراء مكالمات شبكة فعلية.
    *   **محاكاة أحداث NFC**: لاختبار HCE/SoftPOS، يمكنك استخدام محاكيات Android التي تدعم محاكاة NFC أو الأجهزة المادية المزودة بأدوات اختبار NFC.
*   **تدقيقات الأمان**: لعمليات النشر في الإنتاج، يوصى بشدة بإجراء تدقيقات أمان مستقلة واختبار اختراق لتطبيقك، خاصة فيما يتعلق بتدفقات الدفع ومعالجة البيانات الحساسة.


---

## ⚖️ License and Copyright (الترخيص وحقوق الملكية)

**Copyright © 2026 Ahmed Ali Mohammed Hassan Al-Mutawakel. All Rights Reserved.**

### 🛑 Proprietary Software & Patent Notice
This repository and its contents (including but not limited to source code, documentation, and designs) are **proprietary** and protected by law. 

This work is officially patented under **Patent No. (153043)** by the Ministry of Industry, Economy, and Investment in Sana'a, Yemen.

You are **NOT** permitted to:
* Copy, duplicate, or reproduce any part of this repository.
* Modify, adapt, or create derivative works.
* Distribute, share, or publish the source code.
* Reverse engineer, decompile, or extract the source code.
* Use this work for commercial or personal purposes without explicit written permission.

### 🛑 إشعار حقوق الملكية وبراءة الاختراع
هذا المستودع ومحتوياته (بما في ذلك الأكواد البرمجية، الوثائق، والتصاميم) **مملوكة حصرياً** ومحمية بموجب القانون.

هذا العمل محمي رسمياً بموجب **براءة الاختراع رقم (153043)** المسجلة لدى وزارة الصناعة والاقتصاد والاستثمار في صنعاء، الجمهورية اليمنية.

**يُحظر تماماً:**
* نسخ أو إعادة إنتاج أي جزء من هذا المستودع.
* تعديل أو اقتباس أو إنشاء أعمال مشتقة.
* توزيع أو نشر أو مشاركة الكود المصدري.
* الهندسة العكسية أو تفكيك الكود.
* استخدام هذا العمل لأغراض تجارية أو شخصية دون إذن كتابي صريح.

---

### 📞 Contact & Permissions (للتواصل وطلب الأذونات)
For business inquiries, licensing, or permissions, please contact:
للاستفسارات التجارية أو طلب الإذن بالاستخدام، يرجى التواصل عبر:

* 📧 **Email:** [ahmedali.2004.2004.2004@gmail.com](mailto:ahmedali.2004.2004.2004@gmail.com)
* 📱 **Phone/WhatsApp:** +967 775 052 259

* **البريد الإلكتروني:** [ahmedali.2004.2004.2004@gmail.com](mailto:ahmedali.2004.2004.2004@gmail.com)
* **الهاتف:** +967 775 052 259
