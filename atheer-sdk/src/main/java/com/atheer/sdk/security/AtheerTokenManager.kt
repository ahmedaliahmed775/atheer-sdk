package com.atheer.sdk.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * مدير خزنة الرموز المميزة غير المتصلة (Offline Token Vault) لـ Atheer SDK
 *
 * تتولى هذه الفئة إدارة الرموز المميزة المُزوَّدة مسبقاً (Pre-provisioned Tokens)
 * التي يوفرها التطبيق المضيف عندما يكون الجهاز متصلاً بالإنترنت.
 *
 * آلية العمل (مشابهة لنظام Apple Pay للرموز غير المتصلة):
 * 1. يقوم التطبيق المضيف بتزويد الرموز عبر `provisionTokens()` عند الاتصال بالإنترنت
 * 2. عند الدفع بدون إنترنت، تستهلك خدمة HCE رمزاً واحداً عبر `consumeNextToken()`
 * 3. الرمز المُستهلَك يُحذف تلقائياً ولا يمكن إعادة استخدامه
 *
 * التخزين: يتم تخزين الرموز في SharedPreferences مع تسلسل JSON بسيط.
 *
 * @param context سياق التطبيق للوصول إلى SharedPreferences
 */
class AtheerTokenManager(context: Context) {

    companion object {
        private const val TAG = "AtheerTokenManager"
        private const val PREFS_NAME = "atheer_token_vault"
        private const val KEY_TOKENS = "offline_tokens"
        private const val TOKEN_SEPARATOR = "\u001F" // Unit Separator (non-printable)
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * تزويد خزنة الرموز بقائمة من الرموز المميزة المشفرة
     *
     * يتم استدعاء هذه الدالة من التطبيق المضيف عند الاتصال بالإنترنت
     * لتخزين الرموز المميزة للاستخدام في الوضع غير المتصل.
     *
     * الرموز الجديدة تُضاف إلى الرموز الموجودة مسبقاً (لا تستبدلها).
     *
     * @param tokens قائمة الرموز المميزة المشفرة المراد تخزينها
     */
    fun provisionTokens(tokens: List<String>) {
        if (tokens.isEmpty()) {
            Log.w(TAG, "تم استدعاء provisionTokens بقائمة فارغة - لا يوجد شيء للتخزين")
            return
        }
        synchronized(this) {
            val existing = loadTokens().toMutableList()
            existing.addAll(tokens)
            saveTokens(existing)
            Log.i(TAG, "تم تزويد ${tokens.size} رمز مميز - الإجمالي الحالي: ${existing.size}")
        }
    }

    /**
     * استهلاك الرمز المميز التالي من الخزنة
     *
     * يُستخدَم هذا الأسلوب من خدمة HCE عند الدفع بدون إنترنت.
     * يأخذ الرمز الأول من القائمة ويحذفه نهائياً لمنع إعادة الاستخدام.
     *
     * @return الرمز المميز التالي أو null إذا كانت الخزنة فارغة
     */
    fun consumeNextToken(): String? {
        synchronized(this) {
            val tokens = loadTokens().toMutableList()
            if (tokens.isEmpty()) {
                Log.w(TAG, "خزنة الرموز فارغة - لا توجد رموز متاحة للاستهلاك")
                return null
            }
            val token = tokens.removeAt(0)
            saveTokens(tokens)
            Log.i(TAG, "تم استهلاك رمز مميز - الرموز المتبقية: ${tokens.size}")
            return token
        }
    }

    /**
     * الحصول على عدد الرموز المميزة المتبقية في الخزنة
     *
     * @return عدد الرموز المتبقية
     */
    fun getTokensCount(): Int {
        return loadTokens().size
    }

    /**
     * تحميل الرموز المميزة من التخزين المحلي
     *
     * @return قائمة الرموز المخزنة
     */
    private fun loadTokens(): List<String> {
        val raw = prefs.getString(KEY_TOKENS, null) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(TOKEN_SEPARATOR)
    }

    /**
     * حفظ قائمة الرموز المميزة في التخزين المحلي
     *
     * @param tokens قائمة الرموز المراد حفظها
     */
    private fun saveTokens(tokens: List<String>) {
        val raw = if (tokens.isEmpty()) "" else tokens.joinToString(TOKEN_SEPARATOR)
        prefs.edit().putString(KEY_TOKENS, raw).apply()
    }
}
