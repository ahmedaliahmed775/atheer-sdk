package com.atheer.sdk.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * قاعدة البيانات المحلية لـ Atheer SDK
 *
 * تستخدم هذه الفئة مكتبة Room لإدارة قاعدة بيانات SQLite محلية.
 * يتم تطبيق التشفير على مستوى الحقول (Field-Level Encryption) بدلاً من تشفير
 * الملف بالكامل، حيث يقوم AtheerKeystoreManager بتشفير البيانات الحساسة
 * قبل تخزينها في قاعدة البيانات.
 *
 * النمط المستخدم هو Singleton لضمان وجود نسخة واحدة فقط من قاعدة البيانات
 * طوال دورة حياة التطبيق، مما يوفر الموارد ويمنع تعارض العمليات.
 *
 * @see TransactionEntity كيان المعاملة المُخزَّن في قاعدة البيانات
 * @see TransactionDao واجهة الوصول إلى بيانات المعاملات
 */
@Database(
    entities = [TransactionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AtheerDatabase : RoomDatabase() {

    /**
     * يوفر الوصول إلى واجهة بيانات المعاملات (DAO)
     * تُولِّد Room هذه الدالة تلقائياً وقت التصريف
     */
    abstract fun transactionDao(): TransactionDao

    companion object {
        private const val TAG = "AtheerDatabase"

        // اسم ملف قاعدة البيانات على الجهاز
        private const val DATABASE_NAME = "atheer_transactions.db"

        @Volatile
        private var INSTANCE: AtheerDatabase? = null

        /**
         * الحصول على النسخة الوحيدة من قاعدة البيانات (نمط Singleton)
         *
         * تستخدم هذه الدالة قفل مزدوج (Double-Checked Locking) لضمان سلامة
         * الخيوط المتعددة (Thread Safety) عند الاستخدام المتزامن.
         *
         * @param context سياق التطبيق للوصول إلى مسار قاعدة البيانات
         * @return النسخة الوحيدة من AtheerDatabase
         */
        fun getInstance(context: Context): AtheerDatabase {
            // التحقق الأول بدون قفل لتحسين الأداء
            return INSTANCE ?: synchronized(this) {
                // التحقق الثاني داخل القفل لضمان عدم تكرار الإنشاء
                INSTANCE ?: buildDatabase(context).also { instance ->
                    Log.i(TAG, "تم إنشاء قاعدة البيانات المحلية بنجاح")
                    INSTANCE = instance
                }
            }
        }

        /**
         * بناء وتهيئة قاعدة البيانات
         *
         * يتم استخدام تشفير على مستوى الحقول بواسطة AtheerKeystoreManager
         * لحماية البيانات الحساسة قبل تخزينها.
         *
         * @param context سياق التطبيق
         * @return كائن AtheerDatabase جاهز للاستخدام
         */
        private fun buildDatabase(context: Context): AtheerDatabase {
            Log.d(TAG, "جاري بناء قاعدة البيانات المحلية...")
            return Room.databaseBuilder(
                context.applicationContext,
                AtheerDatabase::class.java,
                DATABASE_NAME
            )
                // في حالة تغيير مخطط قاعدة البيانات وعدم وجود migration، يتم إعادة البناء
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
