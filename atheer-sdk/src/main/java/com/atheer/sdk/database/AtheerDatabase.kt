package com.atheer.sdk.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SupportFactory
import com.atheer.sdk.security.AtheerKeystoreManager

/**
 * ## AtheerDatabase
 * قاعدة البيانات المحلية لـ Atheer SDK - مشفرة بالكامل بواسطة SQLCipher.
 */
@Database(
    entities = [TransactionEntity::class],
    version = 3, // تم التحديث للإصدار 3 لدعم هيكل البيانات المبسط الجديد
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

        fun getInstance(context: Context): AtheerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { instance ->
                    Log.i(TAG, "تمت تهيئة قاعدة البيانات المشفرة بنجاح")
                    INSTANCE = instance
                }
            }
        }

        private fun buildDatabase(context: Context): AtheerDatabase {
            val keystoreManager = AtheerKeystoreManager()
            val passphrase = getOrCreateDatabasePassphrase(context, keystoreManager)
            val factory = SupportFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                AtheerDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }

        private fun getOrCreateDatabasePassphrase(context: Context, keystore: AtheerKeystoreManager): ByteArray {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val encryptedKey = prefs.getString(KEY_PASSPHRASE, null)

            return if (encryptedKey == null) {
                val rawKey = keystore.generateNonce()
                val encrypted = keystore.encrypt(rawKey)
                prefs.edit().putString(KEY_PASSPHRASE, encrypted).apply()
                rawKey.toByteArray(Charsets.UTF_8)
            } else {
                keystore.decrypt(encryptedKey).toByteArray(Charsets.UTF_8)
            }
        }
    }
}