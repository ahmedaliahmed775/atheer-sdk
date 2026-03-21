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
 * والقسائم الرقمية (Vouchers) التي يوفرها التطبيق المضيف.
 * 
 * ✅ ملاحظة: تدعم المكتبة الرموز الطويلة والقسائم الرقمية القصيرة (Vouchers) بمختلف أطوالها
 * لضمان التوافق التام مع أنظمة 'جوالي' و 'فلوسك' والمحافظ اليمنية الأخرى.
 *
 * التحديث الأمني (PCI-DSS Compliance):
 * 1. التخزين المشفر: تستخدم EncryptedSharedPreferences لتشفير الرموز لمنع سرقتها.
 * 2. انتهاء الصلاحية: كل رمز يمتلك مدة صلاحية (7 أيام) لمنع استخدام رموز قديمة جداً.
 */
class AtheerTokenManager(context: Context) {

    companion object {
        private const val TAG = "AtheerTokenManager"
        private const val PREFS_NAME = "atheer_token_vault_secure"
        private const val KEY_TOKENS = "offline_tokens"
        private const val TOKEN_SEPARATOR = "\u001F" // Unit Separator
        
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun provisionTokens(tokens: List<String>) {
        if (tokens.isEmpty()) {
            Log.w(TAG, "تم استدعاء provisionTokens بقائمة فارغة")
            return
        }
        synchronized(this) {
            val existing = loadTokens().toMutableList()
            val currentTime = System.currentTimeMillis()
            val tokensWithTime = tokens.map { "$it|$currentTime" }
            
            existing.addAll(tokensWithTime)
            saveTokens(existing)
            Log.i(TAG, "تم تزويد ${tokens.size} رمز/قسيمة - الإجمالي: ${existing.size}")
        }
    }

    fun consumeNextToken(): String? {
        synchronized(this) {
            val tokens = loadTokens().toMutableList()
            val currentTime = System.currentTimeMillis()

            while (tokens.isNotEmpty()) {
                val tokenEntry = tokens.removeAt(0)
                val parts = tokenEntry.split("|")
                val actualToken = parts[0]
                val timeAdded = parts.getOrNull(1)?.toLongOrNull() ?: 0L

                if (currentTime - timeAdded <= SEVEN_DAYS_MS) {
                    saveTokens(tokens)
                    return actualToken
                }
            }
            saveTokens(tokens)
            return null
        }
    }

    fun getTokensCount(): Int = loadTokens().size

    private fun loadTokens(): List<String> {
        val raw = prefs.getString(KEY_TOKENS, null) ?: return emptyList()
        return if (raw.isEmpty()) emptyList() else raw.split(TOKEN_SEPARATOR).filter { it.isNotEmpty() }
    }

    private fun saveTokens(tokens: List<String>) {
        val raw = if (tokens.isEmpty()) "" else tokens.joinToString(TOKEN_SEPARATOR)
        prefs.edit().putString(KEY_TOKENS, raw).apply()
    }
}
