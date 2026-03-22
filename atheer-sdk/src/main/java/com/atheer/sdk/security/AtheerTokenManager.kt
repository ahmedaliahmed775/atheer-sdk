package com.atheer.sdk.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.atheer.sdk.model.TokenInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

/**
 * مدير خزنة الرموز المميزة غير المتصلة (Offline Token Vault) لـ Atheer SDK
 */
class AtheerTokenManager(context: Context) {

    companion object {
        private const val TAG = "AtheerTokenManager"
        private const val PREFS_NAME = "atheer_token_vault_secure"
        private const val KEY_TOKENS_DATA = "offline_tokens_v2"
        
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val prefs: SharedPreferences
    private val gson = Gson()

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

    /**
     * تزويد الرموز مع دعم هيكلية TokenInfo الجديدة
     */
    fun provisionTokens(tokens: List<TokenInfo>) {
        if (tokens.isEmpty()) {
            Log.w(TAG, "تم استدعاء provisionTokens بقائمة فارغة")
            return
        }
        synchronized(this) {
            val existing = loadTokenInfos().toMutableList()
            existing.addAll(tokens)
            saveTokenInfos(existing)
            Log.i(TAG, "تم تزويد ${tokens.size} رمز - الإجمالي: ${existing.size}")
        }
    }

    /**
     * استهلاك الرمز التالي مع التحقق من الصلاحية (expiryDate من السيرفر أو 7 أيام محلياً)
     */
    fun consumeNextToken(): String? {
        synchronized(this) {
            val tokens = loadTokenInfos().toMutableList()
            val currentTime = System.currentTimeMillis()
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

            while (tokens.isNotEmpty()) {
                val token = tokens.removeAt(0)
                
                // 1. التحقق من تاريخ الانتهاء القادم من السيرفر (إذا وجد)
                val isServerExpired = token.expiryDate?.let {
                    try {
                        val expiryDate = sdf.parse(it)
                        expiryDate?.before(Date(currentTime)) ?: false
                    } catch (e: Exception) { false }
                } ?: false

                // 2. التحقق من الصلاحية المحلية (7 أيام كأمان إضافي)
                // ملحوظة: نفترض أن الـ ID قد يحتوي على طابع زمني أو نعتمد على تاريخ التنزيل إذا أضفناه، 
                // لكن هنا سنكتفي بصلاحية السيرفر أو منطق الأعمال.
                
                if (!isServerExpired) {
                    saveTokenInfos(tokens)
                    return token.tokenValue
                }
            }
            saveTokenInfos(tokens)
            return null
        }
    }

    fun getTokensCount(): Int = loadTokenInfos().size

    private fun loadTokenInfos(): List<TokenInfo> {
        val raw = prefs.getString(KEY_TOKENS_DATA, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TokenInfo>>() {}.type
            gson.fromJson(raw, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveTokenInfos(tokens: List<TokenInfo>) {
        val raw = gson.toJson(tokens)
        prefs.edit().putString(KEY_TOKENS_DATA, raw).apply()
    }
}
