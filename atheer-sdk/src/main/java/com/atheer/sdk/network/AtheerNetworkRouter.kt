package com.atheer.sdk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.atheer.sdk.model.AtheerError
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * موجه الشبكة المطور لـ Atheer SDK باستخدام OkHttp
 */
class AtheerNetworkRouter(private val context: Context) {

    companion object {
        private const val TAG = "AtheerNetworkRouter"
        private const val BASE_DOMAIN = "api.atheer.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /* 
    // تم إيقاف Certificate Pinning مؤقتاً لتسهيل عملية الاختبار مع السيرفرات التطويرية
    private val certificatePinner = CertificatePinner.Builder()
        .add(BASE_DOMAIN, "sha256/7HIpSrxNzqQOK6hzZcl8N0VYRsqDx9Le5tpx3468AlA=")
        .add(BASE_DOMAIN, "sha256/Af8uCAY87z1S9x10G95F63YvX1D4v10G95F63YvX1D4=")
        .build()
    */

    private val client: OkHttpClient by lazy {
        val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
            .build()

        OkHttpClient.Builder()
            // .certificatePinner(certificatePinner) // تم التعطيل مؤقتاً
            .connectionSpecs(listOf(spec))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * تنفيذ طلب شبكة آمن مع معالجة موحدة للأخطاء (AtheerError)
     */
    suspend fun executeStandard(
        urlString: String,
        requestBody: String? = null,
        accessToken: String? = null,
        merchantApiKey: String? = null
    ): String = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(urlString)
        requestBuilder.addHeader("Accept", "application/json")
        accessToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        
        // إضافة ترويسة الأمان المطلوبة للتاجر
        merchantApiKey?.let { requestBuilder.addHeader("x-atheer-api-key", it) }

        if (requestBody != null) {
            requestBuilder.post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            requestBuilder.get()
        }

        val request = requestBuilder.build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                
                // 1. التحقق من أكواد HTTP وترجمتها إلى AtheerError
                when (response.code) {
                    200, 201 -> return@withContext body
                    401 -> throw mapToAtheerError(body, AtheerError.AuthenticationFailed())
                    403 -> throw mapToAtheerError(body, AtheerError.AuthenticationFailed("صلاحيات غير كافية أو مفتاح API خاطئ"))
                    400 -> throw mapToAtheerError(body, AtheerError.InvalidVoucher())
                    504 -> throw AtheerError.ProviderTimeout()
                    else -> {
                        if (body.isNotEmpty()) {
                            throw mapToAtheerError(body, AtheerError.UnknownError())
                        } else {
                            throw AtheerError.NetworkError("خطأ HTTP: ${response.code}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "فشل طلب الشبكة: ${e.message}")
            if (e is AtheerError) throw e
            throw AtheerError.NetworkError(e.message ?: "خطأ في الاتصال بالشبكة")
        }
    }

    /**
     * محول (Adapter) لتحويل ردود السيرفر و 'status' إلى AtheerError
     */
    private fun mapToAtheerError(responseBody: String, defaultError: AtheerError): AtheerError {
        return try {
            val json = JSONObject(responseBody)
            val status = json.optString("status")
            val message = json.optString("message", defaultError.message)
            
            when (status) {
                "REJECTED" -> AtheerError.InsufficientFunds(message)
                "EXPIRED" -> AtheerError.InvalidVoucher("انتهت صلاحية الرمز أو القسيمة")
                "AUTH_FAILED" -> AtheerError.AuthenticationFailed(message)
                else -> defaultError
            }
        } catch (e: Exception) {
            defaultError
        }
    }

    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }


}
