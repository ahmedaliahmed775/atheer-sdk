package com.atheer.sdk.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ## AtheerKeystoreManager
 * المستودع المركزي لإدارة المفاتيح التشفيرية داخل بيئة معزولة (TEE/StrongBox).
 *
 * يوفر هذا الكلاس آلية لتوليد وتخزين واستخدام مفاتيح التشفير بشكل آمن باستخدام Android Keystore System.
 * يتم حماية البيانات الحساسة باستخدام خوارزمية AES-GCM التي تضمن السرية والسلامة.
 *
 * ### ميزات الأمان:
 * 1. **التعمية المعتمدة على العتاد (Hardware-backed):** لا يمكن استخراج المفاتيح خارج وحدة الأمان.
 * 2. **خوارزمية AES-GCM:** توفر تشفيراً موثقاً يمنع التلاعب بالبيانات.
 * 3. **توليد Nonce:** استخدام أرقام عشوائية آمنة لكل معاملة لمنع هجمات إعادة الإرسال.
 */
class AtheerKeystoreManager {

    companion object {
        private const val TAG = "AtheerKeystoreManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "AtheerSDK_MasterKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128 // طول وسم المصادقة لضمان أعلى مستوى أمان
        private const val GCM_IV_LENGTH = 12   // الطول القياسي لـ IV في GCM لضمان الكفاءة
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    init {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            generateMasterKey()
        }
    }

    /**
     * توليد مفتاح AES 256-bit داخل مخزن المفاتيح الآمن.
     * يتم ضبط الخصائص لمنع استخدام المفتاح خارج نطاق GCM و NoPadding للامتثال للمعايير المالية.
     */
    private fun generateMasterKey() {
        Log.d(TAG, "جاري إنشاء المفتاح الرئيسي في Android Keystore...")
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true) // إلزامي لمنع هجمات التكرار
            .build()

        keyGenerator.init(keySpec)
        keyGenerator.generateKey()
    }

    /**
     * يسترجع المفتاح الرئيسي من مخزن المفاتيح.
     * @return كائن [SecretKey] المستخدم في عمليات التشفير.
     * @throws IllegalStateException إذا لم يتم العثور على المفتاح.
     */
    private fun getMasterKey(): SecretKey {
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("لم يتم العثور على المفتاح الرئيسي")
        return entry.secretKey
    }

    /**
     * تشفير النصوص والبيانات الحساسة.
     *
     * يتم إنشاء IV (Initialization Vector) عشوائي لكل عملية تشفير ودمجه مع النتيجة.
     *
     * @param plainText النص المراد تشفيره.
     * @return نص مشفر بصيغة Base64 يتضمن IV لضمان فك تشفير صحيح لاحقاً.
     */
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + encryptedBytes
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * فك تشفير البيانات المشفرة مسبقاً بواسطة هذا الكلاس.
     *
     * يتم استخراج الـ IV من مقدمة البيانات المشفرة لاستخدامه في التحقق من سلامة الكتلة (Block Integrity).
     *
     * @param encryptedText النص المشفر بصيغة Base64.
     * @return النص الأصلي بعد فك التشفير.
     */
    fun decrypt(encryptedText: String): String {
        val combined = Base64.decode(encryptedText, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val encryptedBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), spec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    /**
     * تحويل بيانات البطاقة إلى رمز (Token) مشفر وغير قابل للقراءة.
     * نستخدم البادئة ATK_ للتمييز البرمجي السريع وتسهيل عمليات تتبع السجلات دون كشف البيانات.
     *
     * @param cardData البيانات الخام للبطاقة.
     * @return الرمز المميز (Token) المشفر.
     */
    fun tokenize(cardData: String): String {
        val encryptedData = encrypt(cardData)
        return "ATK_${encryptedData}"
    }

    /**
     * فك تشفير الرمز (Token) واستعادة بيانات البطاقة الأصلية.
     *
     * @param token الرمز المشفر الذي يبدأ بـ ATK_.
     * @return البيانات الأصلية للبطاقة.
     * @throws IllegalArgumentException إذا كان الرمز لا يحتوي على البادئة الصحيحة.
     */
    fun detokenize(token: String): String {
        if (!token.startsWith("ATK_")) {
            throw IllegalArgumentException("الرمز غير صالح: بادئة مفقودة")
        }
        val encryptedData = token.removePrefix("ATK_")
        return decrypt(encryptedData)
    }

    /**
     * توليد Nonce (رقم يستخدم لمرة واحدة) باستخدام SecureRandom.
     * إلزامي في المعاملات المالية لمنع هجمات إعادة الإرسال (Replay Attacks).
     *
     * @return سلسلة نصية تمثل الرقم العشوائي الفريد.
     */
    fun generateNonce(): String {
        val nonceBytes = ByteArray(16)
        SecureRandom().nextBytes(nonceBytes)
        return nonceBytes.joinToString("") { "%02x".format(it) }
    }
}
