package com.atheer.sdk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * موجه الشبكة لـ Atheer SDK
 *
 * تتولى هذه الفئة توجيه طلبات HTTP بشكل مباشر عبر شبكة البيانات الخلوية (4G/5G)
 * حتى لو كان الجهاز متصلاً بشبكة Wi-Fi. هذا المفهوم يُعرَف بـ "Zero-Rating Simulation"
 * أو توجيه حركة المرور المميزة (Privileged Traffic Routing).
 *
 * حالات الاستخدام:
 * - **Zero-Rating**: توجيه طلبات الدفع عبر شبكة الجوال المخصصة التي قد تكون
 *   معفاة من رسوم البيانات (Zero-Rated) من قِبَل شركة الاتصالات
 * - **الموثوقية**: في بعض البيئات، الشبكة الخلوية أكثر موثوقية من Wi-Fi للمدفوعات
 * - **الأمان**: تجنب شبكات Wi-Fi العامة غير الآمنة للمعاملات المالية
 *
 * ملاحظة تقنية:
 * يتطلب هذا النهج صلاحية CHANGE_NETWORK_STATE في الأجهزة القديمة،
 * أما في Android 10+ فيتم باستخدام bindProcessToNetwork
 *
 * @param context سياق التطبيق للوصول إلى ConnectivityManager
 */
class AtheerNetworkRouter(private val context: Context) {

    companion object {
        private const val TAG = "AtheerNetworkRouter"

        // مهلة انتظار الاتصال بالشبكة الخلوية بالميلي ثانية
        private const val CELLULAR_NETWORK_TIMEOUT_MS = 10_000L

        // مهلة انتظار الطلب الكامل بالميلي ثانية
        private const val REQUEST_TIMEOUT_MS = 30_000L
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * تنفيذ طلب HTTP عبر شبكة البيانات الخلوية حصراً
     *
     * الخطوات:
     * 1. طلب شبكة خلوية من ConnectivityManager
     * 2. انتظار توفر الشبكة الخلوية
     * 3. ربط مؤقت للعملية بالشبكة الخلوية
     * 4. تنفيذ الطلب
     * 5. إلغاء ربط العملية بالشبكة الخلوية بعد الانتهاء
     *
     * @param urlString رابط الخادم المراد الاتصال به
     * @param requestBody جسم الطلب (JSON أو أي صيغة أخرى) - null لطلبات GET
     * @return محتوى الاستجابة كنص
     * @throws IOException في حالة فشل الاتصال أو الطلب
     */
    suspend fun executeViaCellular(urlString: String, requestBody: String? = null): String {
        Log.d(TAG, "جاري توجيه الطلب عبر الشبكة الخلوية: $urlString")

        // الحصول على شبكة خلوية نشطة أو انتظار توفرها
        val cellularNetwork = withTimeoutOrNull(CELLULAR_NETWORK_TIMEOUT_MS) {
            getCellularNetwork()
        } ?: throw IOException("تعذر الوصول إلى شبكة البيانات الخلوية في الوقت المحدد")

        Log.i(TAG, "تم الحصول على الشبكة الخلوية بنجاح - جاري إرسال الطلب...")

        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            executeOnNetwork(cellularNetwork, urlString, requestBody)
        } ?: throw IOException("انتهت مهلة انتظار الطلب")
    }

    /**
     * الانتظار حتى توفر شبكة خلوية نشطة
     *
     * تستخدم هذه الدالة NetworkCallback للاستماع لأحداث الشبكة بشكل غير متزامن
     * وإعادة الشبكة فور توفرها، وذلك باستخدام suspendCancellableCoroutine
     * لتحويل الاستماع التقليدي (Callback) إلى أسلوب Coroutines.
     *
     * @return كائن Network يمثل الشبكة الخلوية النشطة
     */
    private suspend fun getCellularNetwork(): Network = suspendCancellableCoroutine { continuation ->
        // تحديد الشبكة المطلوبة: شبكة خلوية قادرة على الاتصال بالإنترنت
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "الشبكة الخلوية متاحة الآن")
                // إلغاء تسجيل الاستماع بعد الحصول على الشبكة لتوفير البطارية
                connectivityManager.unregisterNetworkCallback(this)
                if (continuation.isActive) {
                    continuation.resume(network)
                }
            }

            override fun onUnavailable() {
                Log.w(TAG, "الشبكة الخلوية غير متاحة")
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IOException("الشبكة الخلوية غير متاحة على هذا الجهاز")
                    )
                }
            }
        }

        // التحقق أولاً من وجود شبكة خلوية نشطة قبل الانتظار
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        // إذا كانت القدرات تشمل النقل الخلوي، فالشبكة متاحة - activeNetwork مضمونة غير فارغة هنا
        if (activeNetwork != null && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
            Log.d(TAG, "الشبكة الخلوية نشطة بالفعل")
            continuation.resume(activeNetwork)
            return@suspendCancellableCoroutine
        }

        // طلب شبكة خلوية من النظام
        connectivityManager.requestNetwork(networkRequest, callback)

        // إلغاء طلب الشبكة عند إلغاء Coroutine لمنع تسرب الموارد
        continuation.invokeOnCancellation {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    /**
     * تنفيذ طلب HTTP على شبكة محددة
     *
     * يستخدم هذا الأسلوب network.socketFactory لفرض استخدام الشبكة المحددة
     * بدلاً من اتصال الجهاز الافتراضي. هذا هو المحور الرئيسي لآلية التوجيه.
     *
     * @param network الشبكة المحددة لتنفيذ الطلب عليها
     * @param urlString رابط الخادم
     * @param requestBody جسم الطلب أو null لطلبات GET
     * @return محتوى الاستجابة
     */
    private fun executeOnNetwork(
        network: Network,
        urlString: String,
        requestBody: String?
    ): String {
        Log.d(TAG, "جاري تنفيذ الطلب على الشبكة الخلوية: $urlString")
        val url = URL(urlString)
        val connection = network.openConnection(url) as HttpsURLConnection

        try {
            connection.apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Atheer-Source", "cellular")

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
            Log.d(TAG, "كود الاستجابة: $responseCode")

            return if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IOException("فشل الطلب - كود الاستجابة: $responseCode - $errorBody")
            }
        } finally {
            connection.disconnect()
            Log.d(TAG, "تم إغلاق اتصال الشبكة الخلوية")
        }
    }

    /**
     * التحقق من توفر الشبكة الخلوية على الجهاز
     *
     * @return true إذا كانت الشبكة الخلوية متاحة
     */
    fun isCellularAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * التحقق من توفر أي اتصال بالشبكة (خلوية أو Wi-Fi)
     *
     * @return true إذا كان الجهاز متصلاً بأي شبكة
     */
    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
