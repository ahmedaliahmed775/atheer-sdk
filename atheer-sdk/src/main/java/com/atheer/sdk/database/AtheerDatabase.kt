package com.atheer.sdk.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SupportFactory
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * ## AtheerDatabase
 * قاعدة البيانات المحلية لـ Atheer SDK - مشفرة بالكامل بواسطة SQLCipher.
 */
@Database(
    entities = [TransactionEntity::class],
    version = 3,
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
            val passphrase = getOrCreateDatabasePassphrase(context)
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

        /**
         * توليد أو استرجاع كلمة مرور قاعدة البيانات من EncryptedSharedPreferences.
         * لا تعتمد على AtheerKeystoreManager لفصل المسؤوليات.
         */
        private fun getOrCreateDatabasePassphrase(context: Context): ByteArray {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            var passphrase = encryptedPrefs.getString(KEY_PASSPHRASE, null)

            if (passphrase == null) {
                // توليد nonce عشوائي آمن بدون اعتماد خارجي
                val nonceBytes = ByteArray(32)
                SecureRandom().nextBytes(nonceBytes)
                passphrase = nonceBytes.joinToString("") { "%02x".format(it) }
                encryptedPrefs.edit().putString(KEY_PASSPHRASE, passphrase).apply()
            }

            return passphrase.toByteArray(Charsets.UTF_8)
        }
    }
}
