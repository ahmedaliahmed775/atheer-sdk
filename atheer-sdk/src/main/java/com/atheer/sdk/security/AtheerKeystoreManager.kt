package com.atheer.sdk.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * ## AtheerKeystoreManager
 * المستودع المركزي لإدارة المفاتيح التشفيرية داخل بيئة معزولة (TEE/StrongBox).
 * 
 * تم تحديثه ليدعم معمارية "Zero-Trust Dynamic Key Derivation" عبر:
 * 1. Master Seed (AES-256) مخزن في Android Keystore.
 * 2. عداد رتيب (Monotonic Counter) مخزن في EncryptedSharedPreferences.
 * 3. اشتقاق مفاتيح الاستخدام المحدود (LUK) لكل عملية.
 */
class AtheerKeystoreManager(private val context: Context) {

    companion object {
        private const val TAG = "AtheerKeystoreManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_SEED_ALIAS = "AtheerSDK_MasterSeed"
        private const val PREFS_NAME = "atheer_secure_prefs"
        private const val COUNTER_KEY = "monotonic_counter"
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        if (!keyStore.containsAlias(MASTER_SEED_ALIAS)) {
            generateMasterSeed()
        }
    }

    /**
     * توليد Master Seed (AES-256) وتخزينه في الـ Keystore المدعوم بالعتاد.
     */
    private fun generateMasterSeed() {
        Log.d(TAG, "جاري إنشاء Master Seed آمن...")
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keySpec = KeyGenParameterSpec.Builder(
            MASTER_SEED_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(keySpec)
        keyGenerator.generateKey()
    }

    /**
     * استرجاع الـ Master Seed من الـ Keystore.
     */
    private fun getMasterSeed(): SecretKey {
        val entry = keyStore.getEntry(MASTER_SEED_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("لم يتم العثور على Master Seed")
        return entry.secretKey
    }

    /**
     * الحصول على قيمة العداد الرتيب الحالية وزيادتها بشكل آمن.
     */
    @Synchronized
    fun incrementAndGetCounter(): Long {
        val currentCounter = encryptedPrefs.getLong(COUNTER_KEY, 0L)
        val nextCounter = currentCounter + 1
        encryptedPrefs.edit().putLong(COUNTER_KEY, nextCounter).apply()
        return nextCounter
    }

    /**
     * اشتقاق مفتاح استخدام محدود (LUK) باستخدام HMAC-SHA256.
     * الصيغة: LUK = HMAC-SHA256(MasterSeed, Counter)
     * 
     * @param counter العداد الرتيب الحالي للعملية.
     * @return مفتاح LUK مشتق كـ ByteArray.
     */
    fun deriveLUK(counter: Long): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(getMasterSeed())
        val counterBytes = counter.toString().toByteArray(Charsets.UTF_8)
        return mac.doFinal(counterBytes)
    }

    /**
     * توقيع البيانات باستخدام مفتاح LUK المشتق.
     */
    fun signWithLUK(payload: String, luk: ByteArray): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val secretKey = SecretKeySpec(luk, HMAC_ALGORITHM)
        mac.init(secretKey)
        val signatureBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
    }

    /**
     * توليد قيمة عشوائية (Nonce) تستخدم لمرة واحدة.
     */
    fun generateNonce(): String {
        val nonceBytes = ByteArray(16)
        SecureRandom().nextBytes(nonceBytes)
        return nonceBytes.joinToString("") { "%02x".format(it) }
    }
}
