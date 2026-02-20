# قواعد ProGuard الخاصة بمكتبة Atheer SDK
# الاحتفاظ بكيانات Room حتى لا يتم حذفها أثناء التصغير
-keep class com.atheer.sdk.database.** { *; }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}
