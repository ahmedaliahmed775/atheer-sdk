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
 * مدير مخزن المفاتيح الآمن لـ Atheer SDK
 *
 * تتولى هذه الفئة حماية المفاتيح التشفيرية باستخدام Android Keystore.
 * 
 * ✅ ملاحظة: تدعم المكتبة الرموز الطويلة والقسائم الرقمية القصيرة (Vouchers) بمختلف أطوالها
 * (مثل أكواد جوالي المكونة من 7 أرقام) لضمان التوافق مع أنظمة 'جوالي' و 'فلوسك'.
 * يتم استخدام AES-GCM بدون Padding، مما يسمح بتشفير أي طول للنص المدخل.
 */
class AtheerKeystoreManager {

    companion object {
        private const val TAG = "AtheerKeystoreManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "AtheerSDK_MasterKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    init {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            generateMasterKey()
        }
    }

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
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(keySpec)
        keyGenerator.generateKey()
    }

    private fun getMasterKey(): SecretKey {
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("لم يتم العثور على المفتاح الرئيسي")
        return entry.secretKey
    }

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + encryptedBytes
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

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

    fun tokenize(cardData: String): String {
        val encryptedData = encrypt(cardData)
        return "ATK_${encryptedData}"
    }

    fun detokenize(token: String): String {
        if (!token.startsWith("ATK_")) {
            throw IllegalArgumentException("الرمز غير صالح")
        }
        val encryptedData = token.removePrefix("ATK_")
        return decrypt(encryptedData)
    }

    fun generateNonce(): String {
        val nonceBytes = ByteArray(16)
        SecureRandom().nextBytes(nonceBytes)
        return nonceBytes.joinToString("") { "%02x".format(it) }
    }
}
