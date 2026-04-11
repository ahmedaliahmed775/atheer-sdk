package com.atheer.sdk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.atheer.sdk.model.AtheerError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ## AtheerNetworkRouter
 * موجه الشبكة المركزي لـ Atheer SDK — يدعم مسارين للاتصال:
 *
 * 1. **Public Internet** — الوضع الافتراضي: اتصال عبر الإنترنت العام (Wi-Fi / بيانات جوال).
 * 2. **Private APN** — اتصال عبر نفق اتصالات خاص (Zero-Rating) مخصص لمعاملات أثير.
 *
 * يُحدَّد مسار الشبكة عبر Feature Toggle أثناء تهيئة الـ SDK:
 * ```kotlin
 * AtheerSdk.init {
 *     networkMode = NetworkMode.PRIVATE_APN  // أو PUBLIC_INTERNET (افتراضي)
 * }
 * ```
 *
 * **Certificate Pinning**: مفعّل لحماية بيانات التسجيل والمعاملات من هجمات MITM.
 */
internal class AtheerNetworkRouter(
    private val context: Context,
    private val networkMode: NetworkMode = NetworkMode.PUBLIC_INTERNET,
    private val isSandbox: Boolean = true
) {

    /**
     * وضع الاتصال بالشبكة (Feature Toggle)
     */
    enum class NetworkMode {
        /** اتصال عبر الإنترنت العام (Wi-Fi / بيانات جوال عادية) */
        PUBLIC_INTERNET,
        /** اتصال عبر نفق APN خاص (Zero-Rating لمعاملات أثير) */
        PRIVATE_APN
    }

    companion object {
        private const val TAG = "AtheerNetworkRouter"
        private const val BASE_DOMAIN = "api.atheer.com"
        private const val SANDBOX_DOMAIN = "sandbox.atheer.com"
        private const val APN_NETWORK_TIMEOUT_MS = 10_000L
        private const val SDK_VERSION = "1.0.0"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Certificate Pinning — مفعّل فقط في وضع الإنتاج لحماية الاتصال من هجمات MITM.
     * يثبّت شهادتين (Primary + Backup) لضمان استمرارية الخدمة عند تجديد الشهادة.
     *
     * ⚠️ يجب تحديث هذه القيم عند تجديد شهادات الخادم.
     *    للحصول على الـ hash:
     *    $ openssl s_client -connect api.atheer.com:443 | openssl x509 -pubkey -noout | \
     *      openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
     *
     * ⚠️ في وضع Sandbox: يتم تعطيل Certificate Pinning للسماح بالاتصال بخوادم التطوير.
     */
    private val certificatePinner = CertificatePinner.Builder()
        .add(BASE_DOMAIN, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")   // Primary — استبدل بالقيمة الحقيقية
        .add(BASE_DOMAIN, "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")   // Backup  — استبدل بالقيمة الحقيقية
        .add(SANDBOX_DOMAIN, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .add(SANDBOX_DOMAIN, "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")
        .build()

    /**
     * عميل HTTP — في وضع Sandbox: بدون Certificate Pinning / TLS enforcement.
     * في وضع الإنتاج: مع Certificate Pinning + TLS 1.2/1.3.
     */
    private val publicClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (!isSandbox) {
            // وضع الإنتاج: تفعيل Certificate Pinning و TLS
            val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                .build()
            builder.certificatePinner(certificatePinner)
            builder.connectionSpecs(listOf(tlsSpec))
        } else {
            // وضع التطوير: السماح بـ HTTP cleartext
            builder.connectionSpecs(listOf(ConnectionSpec.CLEARTEXT, ConnectionSpec.MODERN_TLS))
        }

        builder.build()
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // ==========================================
    // Network Toggle: Private APN Support
    // ==========================================

    /**
     * الحصول على شبكة خلوية مخصصة لنفق APN الخاص.
     * يطلب شبكة خلوية بدون نفق إنترنت عام ويربط عميل HTTP بها.
     *
     * @return OkHttpClient مربوط بشبكة APN، أو null إذا فشل الحصول على الشبكة.
     */
    private suspend fun acquireApnClient(): OkHttpClient? = withContext(Dispatchers.IO) {
        val networkDeferred = CompletableDeferred<Network>()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkDeferred.complete(network)
            }

            override fun onUnavailable() {
                networkDeferred.completeExceptionally(
                    Exception("شبكة APN الخاصة غير متوفرة.")
                )
            }
        }

        try {
            connectivityManager.requestNetwork(networkRequest, callback)

            val cellularNetwork = withTimeoutOrNull(APN_NETWORK_TIMEOUT_MS) {
                networkDeferred.await()
            } ?: run {
                Log.w(TAG, "انتهت مهلة الاتصال بشبكة APN الخاصة. الرجوع للإنترنت العام.")
                connectivityManager.unregisterNetworkCallback(callback)
                return@withContext null
            }

            val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                .build()

            val client = OkHttpClient.Builder()
                .socketFactory(cellularNetwork.socketFactory)
                .certificatePinner(certificatePinner)
                .connectionSpecs(listOf(tlsSpec))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            connectivityManager.unregisterNetworkCallback(callback)
            client
        } catch (e: Exception) {
            Log.e(TAG, "فشل الحصول على شبكة APN: ${e.message}")
            try { connectivityManager.unregisterNetworkCallback(callback) } catch (_: Exception) {}
            null
        }
    }

    /**
     * اختيار عميل HTTP المناسب بناءً على وضع الشبكة (Feature Toggle).
     * - PUBLIC_INTERNET: يستخدم publicClient مباشرة.
     * - PRIVATE_APN: يحاول APN أولاً، وإذا فشل يرجع للإنترنت العام (Fallback).
     */
    private suspend fun resolveClient(): OkHttpClient {
        return when (networkMode) {
            NetworkMode.PUBLIC_INTERNET -> publicClient
            NetworkMode.PRIVATE_APN -> {
                val apnClient = acquireApnClient()
                if (apnClient != null) {
                    Log.i(TAG, "✅ متصل عبر نفق APN الخاص (Zero-Rating).")
                    apnClient
                } else {
                    Log.w(TAG, "⚠️ فشل APN — الرجوع للإنترنت العام (Fallback).")
                    publicClient
                }
            }
        }
    }

    // ==========================================
    // تنفيذ الطلبات
    // ==========================================

    /**
     * تنفيذ طلب شبكة آمن مع معالجة موحدة للأخطاء (AtheerError).
     *
     * يدعم وضعي الشبكة (Public / APN) عبر Feature Toggle.
     */
    suspend fun executeStandard(
        urlString: String,
        requestBody: String? = null,
        accessToken: String? = null,
        merchantApiKey: String? = null
    ): String = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(urlString)
        requestBuilder.addHeader("Accept", "application/json")
        requestBuilder.addHeader("x-atheer-sdk-version", SDK_VERSION)
        requestBuilder.addHeader("x-atheer-platform", "android")
        requestBuilder.addHeader("x-atheer-os-version", android.os.Build.VERSION.RELEASE)
        accessToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        merchantApiKey?.let { requestBuilder.addHeader("x-atheer-api-key", it) }

        if (requestBody != null) {
            requestBuilder.post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            requestBuilder.get()
        }

        val request = requestBuilder.build()
        val activeClient = resolveClient()

        try {
            activeClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""

                when (response.code) {
                    200, 201 -> return@withContext body
                    401 -> throw mapToAtheerError(body, AtheerError.AuthenticationFailed())
                    403 -> throw mapToAtheerError(body, AtheerError.AuthenticationFailed("صلاحيات غير كافية أو مفتاح API خاطئ"))
                    400 -> throw mapToAtheerError(body, AtheerError.InvalidVoucher())
                    503 -> throw AtheerError.NetworkError("النظام في وضع الصيانة. حاول لاحقاً.")
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
     * محول لتحويل ردود السيرفر إلى AtheerError
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
                "MAINTENANCE" -> AtheerError.NetworkError("النظام في وضع الصيانة.")
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
