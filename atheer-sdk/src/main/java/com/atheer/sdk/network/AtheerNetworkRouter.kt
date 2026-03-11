package com.atheer.sdk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

class AtheerNetworkRouter(private val context: Context) {

    companion object {
        private const val TAG = "AtheerNetworkRouter"
        private const val CELLULAR_NETWORK_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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

            // 🌟 الحل: قراءة الرسائل في حالة النجاح وفي حالة الخطأ (مثل 400)
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorStream = connection.errorStream
                if (errorStream != null) {
                    // إرجاع تفاصيل الخطأ القادمة من السيرفر بصيغة JSON ليتمكن SDK من قراءتها
                    errorStream.bufferedReader().use { it.readText() }
                } else {
                    throw IOException("Error: ${connection.responseCode}")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun executeViaCellular(
        urlString: String,
        requestBody: String? = null,
        accessToken: String? = null
    ): String = withContext(Dispatchers.IO) {
        val cellularNetwork = withTimeoutOrNull(CELLULAR_NETWORK_TIMEOUT_MS) {
            getCellularNetwork()
        } ?: throw IOException("Cellular network unavailable")

        return@withContext withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            executeOnNetwork(cellularNetwork, urlString, requestBody, accessToken)
        } ?: throw IOException("Request timeout")
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
        }
        connectivityManager.requestNetwork(networkRequest, callback)
    }

    private fun executeOnNetwork(network: Network, url: String, body: String?, token: String?): String {
        val connection = network.openConnection(URL(url)) as HttpsURLConnection
        try {
            connection.apply {
                setRequestProperty("Content-Type", "application/json")
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    requestMethod = "POST"
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            // 🌟 نفس الحل مطبق هنا في شبكة البيانات
            if (connection.responseCode in 200..299) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorStream = connection.errorStream
                if (errorStream != null) {
                    return errorStream.bufferedReader().use { it.readText() }
                } else {
                    throw IOException("Error: ${connection.responseCode}")
                }
            }
        } finally { connection.disconnect() }
    }

    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

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
                Result.failure(Exception("Failed to fetch tokens"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }
}