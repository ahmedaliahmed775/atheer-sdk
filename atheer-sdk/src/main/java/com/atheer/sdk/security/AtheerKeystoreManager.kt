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
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ## AtheerKeystoreManager
 * المستودع المركزي لإدارة المفاتيح التشفيرية داخل بيئة معزولة (TEE/StrongBox).
 *
 * يدعم معمارية "Zero-Trust Dynamic Key Derivation" عبر:
 * 1. Device Enrollment: استلام deviceSeed من Backend وتخزينه بشكل آمن.
 * 2. عداد رتيب (Monotonic Counter) مخزن في EncryptedSharedPreferences.
 * 3. اشتقاق مفاتيح الاستخدام المحدود (LUK) لكل عملية.
 *
 * C-03 Fix: تم تعديل اشتقاق المفاتيح ليعتمد على deviceSeed المستلم من Backend
 * بدلاً من Master Seed المحلي، لضمان تطابق المفاتيح في الطرفين.
 *
 * A-01 Fix: تم تعيين الكلاس كـ internal لمنع الوصول من خارج الـ SDK.
 */
internal class AtheerKeystoreManager(private val context: Context) {

    companion object {
        private const val TAG = "AtheerKeystoreManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_SEED_ALIAS = "AtheerSDK_MasterSeed"
        private const val PREFS_NAME = "atheer_secure_prefs"
        private const val COUNTER_KEY = "monotonic_counter"
        private const val ENROLLED_SEED_KEY = "enrolled_device_seed"
        private const val DEVICE_ENROLLED_KEY = "device_enrolled"
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
        // الاحتفاظ بتوليد Master Seed المحلي للتوافق مع الإصدارات السابقة
        if (!keyStore.containsAlias(MASTER_SEED_ALIAS)) {
            generateMasterSeed()
        }
    }

    /**
     * توليد Master Seed (AES-256) وتخزينه في الـ Keystore المدعوم بالعتاد.
     * يُستخدم فقط كبديل (fallback) في حالة عدم وجود enrolled seed.
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

    // ==========================================
    // C-03: بروتوكول تسجيل الجهاز (Device Enrollment)
    // ==========================================

    /**
     * التحقق مما إذا كان الجهاز مسجلاً (لديه deviceSeed من Backend).
     */
    fun isDeviceEnrolled(): Boolean {
        return encryptedPrefs.getBoolean(DEVICE_ENROLLED_KEY, false) &&
               encryptedPrefs.getString(ENROLLED_SEED_KEY, null) != null
    }

    /**
     * تخزين الـ deviceSeed المستلم من Backend أثناء التسجيل.
     * يُخزَّن في EncryptedSharedPreferences (مشفر بـ AES-256-GCM).
     *
     * @param seedBase64 الـ seed المشتق من Backend مشفر بـ Base64.
     */
    fun storeEnrolledSeed(seedBase64: String) {
        encryptedPrefs.edit()
            .putString(ENROLLED_SEED_KEY, seedBase64)
            .putBoolean(DEVICE_ENROLLED_KEY, true)
            .apply()
        Log.i(TAG, "تم تخزين deviceSeed بنجاح.")
    }

    /**
     * استرجاع الـ deviceSeed المسجَّل كـ ByteArray.
     * @throws IllegalStateException إذا لم يكن الجهاز مسجلاً.
     */
    private fun getEnrolledSeed(): ByteArray {
        val seedBase64 = encryptedPrefs.getString(ENROLLED_SEED_KEY, null)
            ?: throw IllegalStateException("الجهاز غير مسجل. استدعِ enrollDevice() أولاً.")
        return Base64.decode(seedBase64, Base64.NO_WRAP)
    }

    // ==========================================
    // العداد الرتيب (Monotonic Counter)
    // ==========================================

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

    // ==========================================
    // اشتقاق المفاتيح والتوقيع
    // ==========================================

    /**
     * اشتقاق مفتاح استخدام محدود (LUK) باستخدام HMAC-SHA256.
     *
     * C-03 Fix: يستخدم الـ enrolledSeed (من Backend) بدلاً من MasterSeed المحلي
     * لضمان تطابق الاشتقاق في الطرفين:
     *   SDK:     LUK = HMAC-SHA256(enrolledSeed, counter)
     *   Backend: LUK = HMAC-SHA256(HMAC-SHA256(MASTER_SEED, deviceId), counter)
     *
     * @param counter العداد الرتيب الحالي للعملية.
     * @return مفتاح LUK مشتق كـ ByteArray (32 بايت).
     */
    fun deriveLUK(counter: Long): ByteArray {
        val seedBytes = getEnrolledSeed()
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val secretKey = SecretKeySpec(seedBytes, HMAC_ALGORITHM)
        mac.init(secretKey)
        val counterBytes = counter.toString().toByteArray(Charsets.UTF_8)
        return mac.doFinal(counterBytes)
    }

    /**
     * توقيع البيانات باستخدام مفتاح LUK المشتق.
     * C-01: يطابق التحقق في Backend — verifyHmacSignature()
     */
    fun signWithLUK(payload: String, luk: ByteArray): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val secretKey = SecretKeySpec(luk, HMAC_ALGORITHM)
        mac.init(secretKey)
        val signatureBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
    }

}
