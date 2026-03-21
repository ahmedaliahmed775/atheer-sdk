package com.atheer.sdk.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SupportFactory
import com.atheer.sdk.security.AtheerKeystoreManager

/**
 * قاعدة البيانات المحلية لـ Atheer SDK - مشفرة بالكامل بواسطة SQLCipher
 * 
 * توفر هذه الفئة وصولاً آمناً للبيانات المحلية مع تشفير مستوى الملف (File-level encryption)
 * باستخدام مفتاح ديناميكي مُدار بواسطة Android Keystore.
 */
@Database(
    entities = [TransactionEntity::class],
    version = 2, // تم التحديث للإصدار 2 لدعم حقول المحافظ اليمنية (agentWallet, receiverMobile, etc.)
    exportSchema = false
)
abstract class AtheerDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    companion object {
        private const val TAG = "AtheerDatabase"
        private const val DATABASE_NAME = "atheer_transactions.db"
        private const val PREFS_NAME = "atheer_db_prefs"
        private const val KEY_PASSPHRASE = "db_passphrase"

        @Volatile
        private var INSTANCE: AtheerDatabase? = null

        /**
         * الحصول على نسخة Singleton من قاعدة البيانات
         */
        fun getInstance(context: Context): AtheerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { instance ->
                    Log.i(TAG, "تمت تهيئة قاعدة البيانات المشفرة بنجاح")
                    INSTANCE = instance
                }
            }
        }

        /**
         * بناء قاعدة البيانات مع تفعيل SQLCipher وتزويدها بالمفتاح من Keystore
         */
        private fun buildDatabase(context: Context): AtheerDatabase {
            Log.d(TAG, "جاري بناء قاعدة البيانات وتطبيق تشفير SQLCipher...")

            // 1. جلب مفتاح التشفير من KeystoreManager وتحويله إلى ByteArray
            val keystoreManager = AtheerKeystoreManager()
            val passphrase = getOrCreateDatabasePassphrase(context, keystoreManager)

            // 2. إنشاء Factory الخاص بـ SQLCipher باستخدام المفتاح
            val factory = SupportFactory(passphrase)

            // 3. بناء قاعدة البيانات مع ربط الـ OpenHelperFactory
            return Room.databaseBuilder(
                context.applicationContext,
                AtheerDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(factory) // تفعيل التشفير هنا
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * توليد أو استرجاع مفتاح تشفير قاعدة البيانات
         * يتم تخزين المفتاح مشفراً بواسطة Keystore لضمان أعلى درجات الأمان
         */
        private fun getOrCreateDatabasePassphrase(context: Context, keystore: AtheerKeystoreManager): ByteArray {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val encryptedKey = prefs.getString(KEY_PASSPHRASE, null)

            return if (encryptedKey == null) {
                Log.d(TAG, "توليد مفتاح تشفير جديد لقاعدة البيانات...")
                // توليد سلسلة عشوائية قوية باستخدام KeystoreManager
                val rawKey = keystore.generateNonce()
                // تشفير المفتاح قبل تخزينه في SharedPreferences
                val encrypted = keystore.encrypt(rawKey)
                
                prefs.edit().putString(KEY_PASSPHRASE, encrypted).apply()
                
                // تحويل المفتاح الأصلي إلى ByteArray لاستخدامه في SQLCipher
                rawKey.toByteArray(Charsets.UTF_8)
            } else {
                Log.d(TAG, "استرجاع مفتاح التشفير الموجود مسبقاً...")
                // فك تشفير المفتاح المخزن وتحويله إلى ByteArray
                keystore.decrypt(encryptedKey).toByteArray(Charsets.UTF_8)
            }
        }
    }
}
