package com.atheer.sdk.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ## AtheerKeystoreManager
 * المستودع المركزي لإدارة المفاتيح التشفيرية داخل بيئة معزولة (TEE/StrongBox).
 */
class AtheerKeystoreManager {

    companion object {
        private const val TAG = "AtheerKeystoreManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_KEY_ALIAS = "AtheerSDK_MasterKey"
        private const val ECC_KEY_ALIAS = "AtheerSDK_AuthKey"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SIGNING_ALGORITHM = "SHA256withECDSA"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    init {
        if (!keyStore.containsAlias(AES_KEY_ALIAS)) {
            generateMasterKey()
        }
        if (!keyStore.containsAlias(ECC_KEY_ALIAS)) {
            generateAsymmetricKeyPair()
        }
    }

    /**
     * توليد مفتاح AES 256-bit للتشفير المحلي.
     */
    private fun generateMasterKey() {
        Log.d(TAG, "جاري إنشاء المفتاح الرئيسي AES...")
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val keySpec = KeyGenParameterSpec.Builder(
            AES_KEY_ALIAS,
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

    /**
     * توليد زوج مفاتيح ECDSA (P-256) للتوقيع الرقمي.
     * يتطلب مصادقة المستخدم (Biometric) لاستخدام المفتاح الخاص.
     */
    private fun generateAsymmetricKeyPair() {
        Log.d(TAG, "جاري إنشاء زوج مفاتيح ECDSA P-256...")
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val parameterSpec = KeyGenParameterSpec.Builder(
            ECC_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true) // يتطلب بصمة لاستخدام المفتاح الخاص
            .setUserAuthenticationValidityDurationSeconds(-1) // إلزامي لكل عملية (BiometricPrompt)
            .build()

        kpg.initialize(parameterSpec)
        kpg.generateKeyPair()
    }

    private fun getMasterKey(): SecretKey {
        val entry = keyStore.getEntry(AES_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("لم يتم العثور على المفتاح AES")
        return entry.secretKey
    }

    private fun getPrivateKey(): PrivateKey {
        val entry = keyStore.getEntry(ECC_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("لم يتم العثور على المفتاح الخاص")
        return entry.privateKey
    }

    /**
     * الحصول على المفتاح العام بصيغة Base64 لمشاركته مع الخادم.
     */
    fun getPublicKey(): String {
        val publicKey = keyStore.getCertificate(ECC_KEY_ALIAS).publicKey
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    /**
     * الحصول على كائن Signature مهيأ للتوقيع.
     * يستخدم مع BiometricPrompt.CryptoObject.
     */
    fun getSignatureInstance(): Signature {
        return Signature.getInstance(SIGNING_ALGORITHM).apply {
            initSign(getPrivateKey())
        }
    }

    /**
     * التوقيع على البيانات (يستدعى بعد نجاح المصادقة الحيوية).
     */
    fun signData(payload: String, signature: Signature): String {
        signature.update(payload.toByteArray(Charsets.UTF_8))
        val signatureBytes = signature.sign()
        return Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
    }

    // --- AES Encryption Logic (Legacy Support) ---

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
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
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), spec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    fun tokenize(cardData: String): String {
        return "ATK_${encrypt(cardData)}"
    }

    fun detokenize(token: String): String {
        if (!token.startsWith("ATK_")) throw IllegalArgumentException("Invalid token")
        return decrypt(token.removePrefix("ATK_"))
    }

    fun generateNonce(): String {
        val nonceBytes = ByteArray(16)
        SecureRandom().nextBytes(nonceBytes)
        return nonceBytes.joinToString("") { "%02x".format(it) }
    }
}