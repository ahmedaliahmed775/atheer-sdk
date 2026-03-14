package com.atheer.sdk.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * مدير خزنة الرموز المميزة غير المتصلة (Offline Token Vault) لـ Atheer SDK
 *
 * تتولى هذه الفئة إدارة الرموز المميزة المُزوَّدة مسبقاً (Pre-provisioned Tokens)
 * التي يوفرها التطبيق المضيف عندما يكون الجهاز متصلاً بالإنترنت.
 *
 * التحديث الأمني (PCI-DSS Compliance):
 * 1. التخزين المشفر: تستخدم EncryptedSharedPreferences لتشفير الرموز لمنع سرقتها.
 * 2. انتهاء الصلاحية: كل رمز يمتلك مدة صلاحية (7 أيام) لمنع استخدام رموز قديمة جداً.
 *
 * @param context سياق التطبيق للوصول إلى بيئة التشفير
 */
class AtheerTokenManager(context: Context) {

    companion object {
        private const val TAG = "AtheerTokenManager"
        // تغيير اسم الملف لتجنب التعارض مع الملف القديم غير المشفر
        private const val PREFS_NAME = "atheer_token_vault_secure"
        private const val KEY_TOKENS = "offline_tokens"
        private const val TOKEN_SEPARATOR = "\u001F" // Unit Separator
        
        // مدة صلاحية الرمز (7 أيام بالمللي ثانية)
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val prefs: SharedPreferences

    init {
        // تهيئة MasterKey لتشفير البيانات محلياً عبر Android Keystore
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        // استخدام EncryptedSharedPreferences بدلاً من SharedPreferences العادية (تشفير Data at Rest)
        prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * تزويد خزنة الرموز بقائمة من الرموز المميزة المشفرة.
     *
     * يتم استدعاء هذه الدالة من التطبيق المضيف عند الاتصال بالإنترنت
     * لتخزين الرموز المميزة للاستخدام في الوضع غير المتصل.
     *
     * @param tokens قائمة الرموز المميزة المراد تخزينها
     */
    fun provisionTokens(tokens: List<String>) {
        if (tokens.isEmpty()) {
            Log.w(TAG, "تم استدعاء provisionTokens بقائمة فارغة - لا يوجد شيء للتخزين")
            return
        }
        synchronized(this) {
            val existing = loadTokens().toMutableList()
            
            // ✅ إضافة الطابع الزمني المحلي وقت حفظ الرمز
            val currentTime = System.currentTimeMillis()
            val tokensWithTime = tokens.map { "$it|$currentTime" }
            
            existing.addAll(tokensWithTime)
            saveTokens(existing)
            Log.i(TAG, "تم تزويد ${tokens.size} رمز مميز مشفر - الإجمالي الحالي: ${existing.size}")
        }
    }

    /**
     * استهلاك الرمز المميز التالي من الخزنة لعملية دفع (NFC/HCE).
     *
     * يقوم بفحص القائمة، ويتجاهل ويحذف أي رمز انتهت صلاحيته (أقدم من 7 أيام).
     *
     * @return الرمز المميز الصالح التالي أو null إذا كانت الخزنة فارغة أو الرموز منتهية الصلاحية
     */
    fun consumeNextToken(): String? {
        synchronized(this) {
            val tokens = loadTokens().toMutableList()
            val currentTime = System.currentTimeMillis()

            while (tokens.isNotEmpty()) {
                val tokenEntry = tokens.removeAt(0)
                
                // فصل الرمز الفعلي عن الطابع الزمني
                val parts = tokenEntry.split("|")
                val actualToken = parts[0]
                val timeAdded = parts.getOrNull(1)?.toLongOrNull() ?: 0L

                // ✅ التحقق مما إذا كان الرمز صالحاً (لم يمر عليه الوقت المحدد)
                if (currentTime - timeAdded <= SEVEN_DAYS_MS) {
                    saveTokens(tokens)
                    Log.i(TAG, "تم استهلاك رمز مميز - الرموز المتبقية: ${tokens.size}")
                    return actualToken
                } else {
                    // الرمز منتهي الصلاحية، سيتم تجاهله (وحذفه كونه تم عمل removeAt له بالفعل)
                    Log.w(TAG, "تم تجاهل وحذف رمز منتهي الصلاحية.")
                }
            }
            
            Log.w(TAG, "خزنة الرموز فارغة أو أن جميع الرموز كانت منتهية الصلاحية")
            saveTokens(tokens) // حفظ القائمة فارغة
            return null
        }
    }

    /**
     * الحصول على عدد الرموز المميزة المتبقية في الخزنة
     */
    fun getTokensCount(): Int {
        return loadTokens().size
    }

    /**
     * تحميل الرموز المميزة من التخزين المشفر
     */
    private fun loadTokens(): List<String> {
        val raw = prefs.getString(KEY_TOKENS, null) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(TOKEN_SEPARATOR).filter { it.isNotEmpty() }
    }

    /**
     * حفظ قائمة الرموز المميزة في التخزين المشفر
     */
    private fun saveTokens(tokens: List<String>) {
        val raw = if (tokens.isEmpty()) "" else tokens.joinToString(TOKEN_SEPARATOR)
        prefs.edit().putString(KEY_TOKENS, raw).apply()
    }
}
