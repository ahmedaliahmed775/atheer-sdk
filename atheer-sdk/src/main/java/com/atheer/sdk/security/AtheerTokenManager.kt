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
 * ## AtheerTokenManager
 * نظام تخزين وإدارة الرموز المميزة (Tokens) للعمليات التي تتم دون اتصال بالإنترنت (Offline Vault).
 *
 * يعتمد هذا الكلاس على التخزين المشفر لضمان حماية الرموز من الوصول غير المصرح به، ويقوم بإدارة
 * دورة حياة الرموز من حيث التزويد (Provisioning) والاستهلاك (Consumption) والتحقق من الصلاحية.
 */
class AtheerTokenManager(context: Context) {

    companion object {
        private const val TAG = "AtheerTokenManager"
        private const val PREFS_NAME = "atheer_token_vault_secure"
        private const val KEY_TOKENS_DATA = "offline_tokens_v2"
        
        private const val DEFAULT_EXPIRY_MS = 7L * 24 * 60 * 60 * 1000
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
     * تزويد المخزن بقائمة جديدة من الرموز المميزة المستلمة من السيرفر.
     * يتم دمج الرموز الجديدة مع المخزون الحالي وحفظها بشكل مشفر.
     *
     * @param tokens قائمة الكائنات من نوع [TokenInfo] المراد تخزينها.
     */
    fun provisionTokens(tokens: List<TokenInfo>) {
        if (tokens.isEmpty()) {
            Log.w(TAG, "تنبيه: محاولة تزويد قائمة رموز فارغة")
            return
        }
        synchronized(this) {
            val existing = loadTokenInfos().toMutableList()
            existing.addAll(tokens)
            saveTokenInfos(existing)
            Log.i(TAG, "تم بنجاح تخزين ${tokens.size} رمز جديد. الإجمالي المتوفر: ${existing.size}")
        }
    }

    /**
     * استرجاع الرمز التالي الصالح للاستخدام واستهلاكه من المخزن.
     * يقوم النظام بالتحقق من تاريخ انتهاء الصلاحية (Expiry Date) لكل رمز قبل إرجاعه.
     *
     * @return سلسلة نصية تمثل الرمز (Token Value) إذا وجد رمز صالح، أو null إذا كان المخزن فارغاً.
     */
    fun consumeNextToken(): String? {
        synchronized(this) {
            val tokens = loadTokenInfos().toMutableList()
            val currentTime = System.currentTimeMillis()
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

            while (tokens.isNotEmpty()) {
                val token = tokens.removeAt(0)
                
                val isServerExpired = token.expiryDate?.let {
                    try {
                        val expiryDate = sdf.parse(it)
                        expiryDate?.before(Date(currentTime)) ?: false
                    } catch (e: Exception) { 
                        Log.e(TAG, "خطأ في تحليل تاريخ انتهاء الرمز: ${e.message}")
                        false 
                    }
                } ?: false

                if (!isServerExpired) {
                    saveTokenInfos(tokens)
                    return token.tokenValue
                } else {
                    Log.w(TAG, "تم استبعاد رمز منتهي الصلاحية: ${token.tokenValue.take(8)}...")
                }
            }
            saveTokenInfos(tokens)
            return null
        }
    }

    /**
     * الحصول على عدد الرموز المتبقية المتوفرة حالياً في المخزن المشفر.
     * @return عدد الرموز كقيمة صحيحة (Int).
     */
    fun getTokensCount(): Int = loadTokenInfos().size

    private fun loadTokenInfos(): List<TokenInfo> {
        val raw = prefs.getString(KEY_TOKENS_DATA, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TokenInfo>>() {}.type
            gson.fromJson(raw, type)
        } catch (e: Exception) {
            Log.e(TAG, "فشل في تحميل الرموز المشفرة: ${e.message}")
            emptyList()
        }
    }

    private fun saveTokenInfos(tokens: List<TokenInfo>) {
        val raw = gson.toJson(tokens)
        prefs.edit().putString(KEY_TOKENS_DATA, raw).apply()
    }
}
