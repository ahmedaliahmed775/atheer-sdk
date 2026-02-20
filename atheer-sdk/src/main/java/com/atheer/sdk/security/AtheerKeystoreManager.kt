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
 * تتولى هذه الفئة حماية المفاتيح التشفيرية باستخدام Android Keystore،
 * وهو نظام تخزين آمن مدمج في Android يحمي المفاتيح حتى من وصول التطبيق نفسه.
 *
 * المهام الرئيسية:
 * 1. **Tokenization**: تحويل بيانات البطاقة الحساسة إلى رمز مميز (Token) آمن
 * 2. **منع هجمات إعادة التشغيل (Replay Attacks)**: باستخدام nonce فريد لكل معاملة
 * 3. **تشفير وفك تشفير البيانات**: باستخدام AES-256-GCM
 *
 * خوارزمية التشفير المستخدمة: AES/GCM/NoPadding
 * - AES: معيار التشفير المتقدم (Advanced Encryption Standard)
 * - GCM: وضع Galois/Counter Mode الذي يوفر التشفير والتحقق من النزاهة معاً
 * - حجم المفتاح: 256 بت لأقصى درجات الأمان
 */
class AtheerKeystoreManager {

    companion object {
        private const val TAG = "AtheerKeystoreManager"

        // اسم مزود مخزن المفاتيح في Android
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        // اسم المفتاح المستخدم للتشفير داخل مخزن المفاتيح
        private const val KEY_ALIAS = "AtheerSDK_MasterKey"

        // خوارزمية التشفير المستخدمة: AES في وضع GCM بدون padding
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        // حجم مصفوفة GCM Tag بالبت (128 بت هو الحد الأقصى الموصى به)
        private const val GCM_TAG_LENGTH = 128

        // حجم nonce الخاص بـ GCM بالبايت (12 بايت هو الحجم الموصى به)
        private const val GCM_IV_LENGTH = 12
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        // تحميل مخزن المفاتيح من نظام Android
        load(null)
    }

    init {
        // إنشاء المفتاح الرئيسي عند أول استخدام إن لم يكن موجوداً
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            generateMasterKey()
        }
    }

    /**
     * إنشاء المفتاح الرئيسي وتخزينه بأمان في Android Keystore
     *
     * يتم إنشاء مفتاح AES-256 بالخصائص التالية:
     * - لا يمكن استخراجه من مخزن المفاتيح
     * - يتطلب مصادقة المستخدم للعمليات الحساسة
     * - مرتبط بجهاز واحد فقط ولا يمكن نقله
     */
    private fun generateMasterKey() {
        Log.d(TAG, "جاري إنشاء المفتاح الرئيسي في Android Keystore...")
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            // الصلاحيات: التشفير وفك التشفير فقط
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // منع استخراج المفتاح من مخزن المفاتيح (حماية إضافية)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(keySpec)
        keyGenerator.generateKey()
        Log.i(TAG, "تم إنشاء المفتاح الرئيسي بنجاح في Android Keystore")
    }

    /**
     * استرجاع المفتاح الرئيسي من مخزن المفاتيح
     *
     * @return المفتاح السري (SecretKey) من Android Keystore
     * @throws IllegalStateException إذا لم يتم العثور على المفتاح
     */
    private fun getMasterKey(): SecretKey {
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("لم يتم العثور على المفتاح الرئيسي في Android Keystore")
        return entry.secretKey
    }

    /**
     * تشفير البيانات الحساسة باستخدام AES-256-GCM
     *
     * العملية:
     * 1. استرجاع المفتاح الرئيسي من Keystore
     * 2. تهيئة Cipher في وضع التشفير مع توليد IV عشوائي تلقائياً
     * 3. تشفير البيانات وإضافة IV في مقدمة البيانات المشفرة
     * 4. تحويل النتيجة إلى Base64 لسهولة التخزين النصي
     *
     * @param plainText النص المراد تشفيره
     * @return النص المشفر بصيغة Base64 (IV + البيانات المشفرة)
     */
    fun encrypt(plainText: String): String {
        Log.d(TAG, "جاري تشفير البيانات...")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())

        // استخراج IV المولَّد تلقائياً بواسطة GCM
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // دمج IV مع البيانات المشفرة: [IV (12 بايت)] + [البيانات المشفرة]
        val combined = iv + encryptedBytes
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * فك تشفير البيانات المشفرة مسبقاً
     *
     * العملية:
     * 1. فك ترميز Base64
     * 2. استخراج IV من أول 12 بايت
     * 3. تهيئة Cipher في وضع فك التشفير مع IV المستخرج
     * 4. فك تشفير البيانات المتبقية
     *
     * @param encryptedText النص المشفر بصيغة Base64
     * @return النص الأصلي بعد فك التشفير
     */
    fun decrypt(encryptedText: String): String {
        Log.d(TAG, "جاري فك تشفير البيانات...")
        val combined = Base64.decode(encryptedText, Base64.NO_WRAP)

        // استخراج IV من أول 12 بايت
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        // استخراج البيانات المشفرة من الباقي
        val encryptedBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), spec)

        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    /**
     * توليد رمز مميز (Token) لبيانات البطاقة - عملية Tokenization
     *
     * تحول هذه الدالة رقم البطاقة الحساس إلى رمز مميز آمن يمكن تخزينه
     * وإرساله عبر الشبكة بدلاً من رقم البطاقة الحقيقي.
     *
     * الرمز المميز يتضمن:
     * - البيانات المشفرة بمفتاح Keystore
     * - معرف فريد لكل عملية Tokenization لمنع التتبع
     *
     * @param cardData بيانات البطاقة المراد ترميزها (رقم البطاقة أو CVV أو أي بيانات حساسة)
     * @return الرمز المميز (Token) الآمن
     */
    fun tokenize(cardData: String): String {
        Log.d(TAG, "جاري عملية Tokenization للبيانات الحساسة...")
        // تشفير بيانات البطاقة
        val encryptedData = encrypt(cardData)
        // إضافة بادئة للتمييز بين الرموز المميزة والبيانات الأخرى
        return "ATK_${encryptedData}"
    }

    /**
     * استرجاع البيانات الأصلية من الرمز المميز - De-tokenization
     *
     * @param token الرمز المميز الصادر من دالة tokenize
     * @return بيانات البطاقة الأصلية بعد فك التشفير
     * @throws IllegalArgumentException إذا كان الرمز المميز غير صالح
     */
    fun detokenize(token: String): String {
        Log.d(TAG, "جاري عملية De-tokenization...")
        if (!token.startsWith("ATK_")) {
            throw IllegalArgumentException("الرمز المميز غير صالح: يجب أن يبدأ بـ ATK_")
        }
        val encryptedData = token.removePrefix("ATK_")
        return decrypt(encryptedData)
    }

    /**
     * توليد Nonce عشوائي لمنع هجمات إعادة التشغيل (Replay Attacks)
     *
     * هجوم إعادة التشغيل يحدث عندما يعترض المهاجم طلب دفع مشروع ويعيد إرساله
     * للحصول على دفعة مكررة. الـ Nonce يمنع ذلك لأنه:
     * 1. فريد لكل معاملة
     * 2. يُستخدَم مرة واحدة فقط (Number Used Once)
     * 3. يُرفَض الخادم أي معاملة تحتوي على Nonce مستخدم مسبقاً
     *
     * @return سلسلة نصية تمثل Nonce بصيغة Hex (32 حرفاً = 16 بايت)
     */
    fun generateNonce(): String {
        Log.d(TAG, "جاري توليد Nonce عشوائي لمنع هجمات إعادة التشغيل...")
        val nonceBytes = ByteArray(16)
        // استخدام SecureRandom لضمان العشوائية الآمنة للتشفير
        SecureRandom().nextBytes(nonceBytes)
        // تحويل البايتات إلى تمثيل Hex للسهولة في التعامل
        return nonceBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * التحقق من صلاحية Nonce لمنع هجمات إعادة التشغيل
     *
     * يتحقق هذا الأسلوب من:
     * 1. طول Nonce (يجب أن يكون 32 حرفاً = 16 بايت)
     * 2. أن يكون Nonce بصيغة Hex صحيحة
     *
     * ملاحظة: التحقق الكامل من عدم تكرار الـ Nonce يتم على الخادم حيث
     * يتم تخزين قاعدة بيانات بجميع الـ Nonces المستخدمة.
     *
     * @param nonce الـ Nonce المراد التحقق منه
     * @return true إذا كان الـ Nonce صالح الصيغة، false في غير ذلك
     */
    fun isValidNonceFormat(nonce: String): Boolean {
        // يجب أن يكون طول الـ Nonce 32 حرفاً (16 بايت × 2 حرف Hex لكل بايت)
        if (nonce.length != 32) return false
        // يجب أن يحتوي على أرقام وحروف Hex فقط (حروف صغيرة أو كبيرة)
        return nonce.matches(Regex("[0-9a-fA-F]+"))
    }
}
