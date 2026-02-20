# قواعد ProGuard للمستهلكين
# تحتفظ بجميع الفئات العامة في حزمة com.atheer.sdk حتى لا يتأثر المستخدمون بعملية التصغير
-keep public class com.atheer.sdk.** { public *; }
-keepattributes *Annotation*
-keepattributes Signature
