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
import com.google.gson.Gson
import com.atheer.sdk.model.TokensResponse

/**
 * موجه الشبكة الخاص بمكتبة Atheer SDK.
 *
 * مسؤول عن جميع الاتصالات مع خوادم Atheer، ويوفر ميزات متقدمة مثل:
 * 1. توجيه الاتصال إجبارياً عبر الشبكة الخلوية (Cellular Data) لتقليل الاعتماد على Wi-Fi التاجر.
 * 2. آلية التراجع (Fallback) الذكية لاستخدام Wi-Fi كخيار بديل عند غياب الشبكة الخلوية.
 * 3. قراءة استجابات الخطأ (Error Streams) لمنع انهيار التطبيق عند الرفض البنكي.
 */
class AtheerNetworkRouter(private val context: Context) {

    companion object {
        private const val TAG = "AtheerNetworkRouter"
        private const val CELLULAR_NETWORK_TIMEOUT_MS = 10_000L // 10 ثوانٍ للبحث عن شبكة خلوية
        private const val REQUEST_TIMEOUT_MS = 30_000L // 30 ثانية لتنفيذ الطلب
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * تنفيذ طلب شبكة باستخدام الشبكة الافتراضية للجهاز (Wi-Fi أو الخلوية أيهما متاح).
     */
    suspend fun executeStandard(
        urlString: String,
        requestBody: String? = null,
        accessToken: String? = null
    ): String = withContext(Dispatchers.IO) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
                if (requestBody != null) {
                    requestMethod = "POST"
                    doOutput = true
                    outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                } else {
                    requestMethod = "GET"
                }
            }

            // قراءة الرسائل في حالة النجاح وفي حالة الخطأ (مثل 400 - الرفض البنكي)
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorStream = connection.errorStream
                if (errorStream != null) {
                    // إرجاع تفاصيل الخطأ القادمة من السيرفر بصيغة JSON ليتمكن SDK من قراءتها
                    errorStream.bufferedReader().use { it.readText() }
                } else {
                    throw IOException("HTTP Error: ${connection.responseCode}")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * تنفيذ طلب شبكة إجبارياً عبر بيانات الهاتف (Cellular).
     *
     * 🌟 تم إضافة آلية التراجع (Fallback): إذا لم تتوفر شبكة خلوية،
     * سيتم التراجع بسلاسة واستخدام الشبكة الافتراضية (مثل Wi-Fi).
     */
    suspend fun executeViaCellular(
        urlString: String,
        requestBody: String? = null,
        accessToken: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            // محاولة الحصول على شبكة خلوية حصرية
            val cellularNetwork = withTimeoutOrNull(CELLULAR_NETWORK_TIMEOUT_MS) {
                getCellularNetwork()
            } ?: throw IOException("الشبكة الخلوية غير متاحة حالياً.")

            // تنفيذ الطلب عبر الشبكة الخلوية
            return@withContext withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                executeOnNetwork(cellularNetwork, urlString, requestBody, accessToken)
            } ?: throw IOException("انتهت مهلة الطلب عبر الشبكة الخلوية.")
            
        } catch (e: Exception) {
            // ✅ آلية التراجع (Fallback): فشل الاتصال الخلوي، التراجع لاستخدام Wi-Fi
            Log.w(TAG, "فشل الاتصال الخلوي (${e.message}). جاري التراجع لاستخدام شبكة Wi-Fi/الافتراضية...")
            return@withContext executeStandard(urlString, requestBody, accessToken)
        }
    }

    /**
     * البحث عن شبكة خلوية نشطة وجلب كائن Network الخاص بها.
     */
    private suspend fun getCellularNetwork(): Network = suspendCancellableCoroutine { continuation ->
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    connectivityManager.unregisterNetworkCallback(this)
                } catch (e: Exception) { /* تجاهل الخطأ في حالة تم الإلغاء مسبقاً */ }
                
                if (continuation.isActive) {
                    continuation.resume(network)
                }
            }
        }

        // الحماية من تسرب الذاكرة (Memory Leak) في حال انتهاء المهلة (Timeout) قبل إيجاد الشبكة
        continuation.invokeOnCancellation {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) { /* تجاهل */ }
        }

        connectivityManager.requestNetwork(networkRequest, callback)
    }

    /**
     * تنفيذ الطلب على كائن شبكة (Network) محدد مسبقاً.
     */
    private fun executeOnNetwork(network: Network, url: String, body: String?, token: String?): String {
        // ربط الاتصال بالشبكة المحددة (الخلوية)
        val connection = network.openConnection(URL(url)) as HttpsURLConnection
        try {
            connection.apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    requestMethod = "POST"
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                } else {
                    requestMethod = "GET"
                }
            }
            
            // قراءة الرسائل بناءً على كود الاستجابة
            if (connection.responseCode in 200..299) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorStream = connection.errorStream
                if (errorStream != null) {
                    return errorStream.bufferedReader().use { it.readText() }
                } else {
                    throw IOException("HTTP Error: ${connection.responseCode}")
                }
            }
        } finally { 
            connection.disconnect() 
        }
    }

    /**
     * التحقق السريع مما إذا كان الجهاز متصلاً بأي شبكة إنترنت.
     */
    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * جلب رموز الدفع اللاتلامسي من السيرفر.
     */
    suspend fun fetchOfflineTokens(apiBaseUrl: String, authToken: String, count: Int, limit: Long): Result<List<String>> {
        return try {
            val url = "$apiBaseUrl/api/v1/wallet/offline-tokens"
            val requestBody = org.json.JSONObject().apply {
                put("count", count)
                put("limit", limit)
            }.toString()

            val responseStr = executeStandard(url, requestBody, authToken)
            val response = Gson().fromJson(responseStr, TokensResponse::class.java)

            if (response?.success == true && response.data?.tokens != null) {
                Result.success(response.data.tokens)
            } else {
                Result.failure(Exception(response?.message ?: "فشل في جلب الرموز من السيرفر"))
            }
        } catch (e: Exception) { 
            Result.failure(e) 
        }
    }
}
