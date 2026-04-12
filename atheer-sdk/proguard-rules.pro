# قواعد ProGuard الخاصة بمكتبة Atheer SDK

# ==========================================
# 1. إصلاح أخطاء بناء R8 مع Java 17
# ==========================================
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn java.lang.invoke.**

# ==========================================
# 2. حماية كلاسات البيانات (Models)
# مهم جداً لكي لا تفشل مكتبة Gson في قراءة JSON
# ==========================================
-keep class com.atheer.sdk.model.** { *; }

# ==========================================
# 3. حماية واجهة الـ SDK الرئيسية
# لكي يستطيع المطورون استدعاء دوال المكتبة
# ==========================================
-keep class com.atheer.sdk.AtheerSdk {
    public *;
}

# ==========================================
# 5. حماية واجهة الـ SDK العامة الإضافية
# ==========================================
-keep class com.atheer.sdk.AtheerSdkConfig { *; }
-keep class com.atheer.sdk.AtheerSdkBuilder { *; }
-keep class com.atheer.sdk.SessionState { *; }
-keep enum com.atheer.sdk.SessionState { *; }

# ==========================================
# 7. حماية AtheerError المختومة
# ==========================================
-keep class com.atheer.sdk.model.AtheerError { *; }
-keep class com.atheer.sdk.model.AtheerError$* { *; }

# ==========================================
# 8. RootBeer — كشف الـ Root
# ==========================================
-keep class com.scottyab.rootbeer.** { *; }
-dontwarn com.scottyab.rootbeer.**