package com.atheer.sdk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.atheer.sdk.model.TokensResponse
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * موجه الشبكة المطور لـ Atheer SDK باستخدام OkHttp و Certificate Pinning
 */
class AtheerNetworkRouter(private val context: Context) {

    companion object {
        private const val TAG = "AtheerNetworkRouter"
        private const val BASE_DOMAIN = "api.atheer.com"
        
        // الوسائط المستخدمة لطلبات JSON
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    // 1. إعداد Certificate Pinning لمنع هجمات MITM
    private val certificatePinner = CertificatePinner.Builder()
        .add(BASE_DOMAIN, "sha256/7HIpSrxNzqQOK6hzZcl8N0VYRsqDx9Le5tpx3468AlA=")
        .add(BASE_DOMAIN, "sha256/Af8uCAY87z1S9x10G95F63YvX1D4v10G95F63YvX1D4=")
        .build()

    // 2. إعداد OkHttpClient مع قيود TLS 1.2+
    private val client: OkHttpClient by lazy {
        val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
            .build()

        OkHttpClient.Builder()
            .certificatePinner(certificatePinner)
            .connectionSpecs(listOf(spec))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * تنفيذ طلب شبكة آمن باستخدام OkHttp
     */
    suspend fun executeStandard(
        urlString: String,
        requestBody: String? = null,
        accessToken: String? = null
    ): String = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(urlString)
        
        // إضافة الترويسات (Headers)
        requestBuilder.addHeader("Accept", "application/json")
        accessToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }

        // تحديد نوع الطلب (POST أو GET)
        if (requestBody != null) {
            requestBuilder.post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            requestBuilder.get()
        }

        val request = requestBuilder.build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    body
                } else {
                    // إرجاع جسم الخطأ كما هو ليتم تحليله في المستويات الأعلى (مثل الرفض البنكي)
                    if (body.isNotEmpty()) body else throw IOException("Unexpected code $response")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "فشل طلب الشبكة الآمن: ${e.message}")
            throw e
        }
    }

    /**
     * التحقق من توفر الإنترنت بطريقة متوافقة مع Android 7.0 (API 24) وما فوق
     */
    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * جلب رموز الدفع اللاتلامسي
     */
    suspend fun fetchOfflineTokens(apiBaseUrl: String, authToken: String, count: Int, limit: Long): Result<List<String>> {
        return try {
            val url = "$apiBaseUrl/api/v1/wallet/offline-tokens"
            val json = org.json.JSONObject().apply {
                put("count", count)
                put("limit", limit)
            }.toString()

            val responseStr = executeStandard(url, json, authToken)
            val response = Gson().fromJson(responseStr, TokensResponse::class.java)

            if (response?.success == true && response.data?.tokens != null) {
                Result.success(response.data.tokens)
            } else {
                Result.failure(Exception(response?.message ?: "فشل استلام الرموز"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
