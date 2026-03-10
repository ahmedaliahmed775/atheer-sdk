package com.atheer.sdk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.gson.Gson
import com.atheer.sdk.model.TokensResponse

/**
 * موجه الشبكة لـ Atheer SDK
 *
 * تتولى هذه الفئة توجيه طلبات HTTP. تدعم التوجيه عبر الشبكة الخلوية حصراً للعمليات المالية،
 * والتوجيه القياسي (Standard) للعمليات العامة مثل جلب الرموز.
 */
class AtheerNetworkRouter(private val context: Context) {

    companion object {
        private const val TAG = "AtheerNetworkRouter"
        private const val CELLULAR_NETWORK_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * تنفيذ طلب HTTP عبر الشبكة الافتراضية للجهاز (Wi-Fi أو بيانات).
     * يُستخدم هذا المسار للسماح للعملاء بجلب الرموز دون الحاجة لفرض بيانات الهاتف.
     */
    suspend fun executeStandard(
        urlString: String,
        requestBody: String? = null,
        accessToken: String? = null
    ): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "جاري تنفيذ طلب قياسي (عبر أي شبكة متاحة): $urlString")
        val url = URL(urlString)
        val connection = url.openConnection() as HttpsURLConnection

        try {
            connection.apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Atheer-Source", "standard")

                if (accessToken != null) {
                    setRequestProperty("Authorization", "Bearer $accessToken")
                }

                if (requestBody != null) {
                    requestMethod = "POST"
                    doOutput = true
                    outputStream.use { os ->
                        os.write(requestBody.toByteArray(Charsets.UTF_8))
                    }
                } else {
                    requestMethod = "GET"
                }
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IOException("فشل الطلب القياسي - كود: $responseCode - $errorBody")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * تنفيذ طلب HTTP عبر شبكة البيانات الخلوية حصراً (لأغراض الأمان والـ Zero-Rating).
     */
    suspend fun executeViaCellular(
        urlString: String,
        requestBody: String? = null,
        accessToken: String? = null
    ): String = withContext(Dispatchers.IO) { // التعديل هنا: النقل إلى مسار الخلفية
        Log.d(TAG, "جاري توجيه الطلب عبر الشبكة الخلوية حصراً: $urlString")

        val cellularNetwork = withTimeoutOrNull(CELLULAR_NETWORK_TIMEOUT_MS) {
            getCellularNetwork()
        } ?: throw IOException("تعذر الوصول إلى شبكة البيانات الخلوية في الوقت المحدد")

        return@withContext withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            executeOnNetwork(cellularNetwork, urlString, requestBody, accessToken)
        } ?: throw IOException("انتهت مهلة انتظار الطلب الخلوي")
    }

    private suspend fun getCellularNetwork(): Network = suspendCancellableCoroutine { continuation ->
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.unregisterNetworkCallback(this)
                if (continuation.isActive) continuation.resume(network)
            }

            override fun onUnavailable() {
                if (continuation.isActive) {
                    continuation.resumeWithException(IOException("الشبكة الخلوية غير متاحة"))
                }
            }
        }

        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (activeNetwork != null && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
            continuation.resume(activeNetwork)
            return@suspendCancellableCoroutine
        }

        connectivityManager.requestNetwork(networkRequest, callback)
        continuation.invokeOnCancellation { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun executeOnNetwork(
        network: Network,
        urlString: String,
        requestBody: String?,
        accessToken: String? = null
    ): String {
        val url = URL(urlString)
        val connection = network.openConnection(url) as HttpsURLConnection
        try {
            connection.apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Atheer-Source", "cellular")

                if (accessToken != null) {
                    setRequestProperty("Authorization", "Bearer $accessToken")
                }

                if (requestBody != null) {
                    requestMethod = "POST"
                    doOutput = true
                    outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                } else {
                    requestMethod = "GET"
                }
            }

            return if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                throw IOException("فشل الطلب الخلوي - كود: ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * جلب مفاتيح الدفع غير المتصلة (Offline Tokens) من السيرفر.
     * تم تعديل هذه الدالة لتستخدم executeStandard بدلاً من executeViaCellular للسماح بالعمل عبر Wi-Fi.
     */
    suspend fun fetchOfflineTokens(apiBaseUrl: String, authToken: String): Result<List<String>> {
        return try {
            val url = "$apiBaseUrl/api/v1/wallet/offline-tokens"
            
            // التعديل الرئيسي: استخدام الاتصال القياسي هنا
            val responseStr = executeStandard(
                urlString = url,
                requestBody = null, 
                accessToken = authToken
            )
            
            val response = Gson().fromJson(responseStr, TokensResponse::class.java)
            
            if (response != null && response.success && response.data?.tokens != null) {
                Result.success(response.data.tokens)
            } else {
                Result.failure(Exception("فشل في جلب مفاتيح الدفع من السيرفر"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء جلب مفاتيح الدفع: ${e.message}")
            Result.failure(e)
        }
    }
}
