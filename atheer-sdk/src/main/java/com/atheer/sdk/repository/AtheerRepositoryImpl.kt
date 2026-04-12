package com.atheer.sdk.repository

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.util.Log
import androidx.biometric.BiometricManager
import com.atheer.sdk.model.AtheerReadinessReport
import com.atheer.sdk.model.ChargeRequest
import com.atheer.sdk.model.ChargeResponse
import com.atheer.sdk.model.EnrollResponse
import com.atheer.sdk.network.AtheerNetworkRouter
import com.atheer.sdk.security.AtheerKeystoreManager
import com.google.gson.Gson
import com.scottyab.rootbeer.RootBeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ## AtheerRepositoryImpl
 * التنفيذ الفعلي لـ [AtheerRepository] — يتحكم في جميع عمليات الوصول للبيانات:
 * الشبكة (Network)، قاعدة البيانات المحلية (Room/SQLCipher)، ومستودع المفاتيح (Keystore).
 *
 * يُنشَأ داخلياً بواسطة [com.atheer.sdk.AtheerSdk] ولا يُكشَف للمستخدمين المباشرين.
 */
internal class AtheerRepositoryImpl(
    private val context: Context,
    private val networkRouter: AtheerNetworkRouter,
    private val keystoreManager: AtheerKeystoreManager,
    private val baseUrl: String,
    private val gson: Gson
) : AtheerRepository {

    companion object {
        private const val TAG = "AtheerRepositoryImpl"
        private const val ENROLL_PATH = "/api/v1/devices/enroll"
        private const val CHARGE_PATH = "/api/v1/payments/process"
    }

    /**
     * تسجيل الجهاز مع الخادم وتخزين [deviceSeed] بأمان.
     */
    override suspend fun enrollDevice(
        phoneNumber: String,
        apiKey: String,
        forceRenew: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!forceRenew && keystoreManager.isDeviceEnrolled()) {
            Log.i(TAG, "الجهاز مسجل مسبقاً — تم تخطي طلب الشبكة.")
            return@withContext Result.success(Unit)
        }

        runCatching {
            val requestBody = gson.toJson(mapOf("deviceId" to phoneNumber))
            val responseJson = networkRouter.executeStandard(
                "$baseUrl$ENROLL_PATH", requestBody, "", apiKey
            )

            val enrollResponse = gson.fromJson(responseJson, EnrollResponse::class.java)
            val seed = enrollResponse.getSeed()

            if (!seed.isNullOrBlank()) {
                keystoreManager.storeEnrolledSeed(seed)
            } else {
                throw IllegalStateException("لم يتم العثور على deviceSeed في الرد")
            }
        }
    }

    /**
     * إرسال طلب الدفع للخادم وحفظ المعاملة محلياً عند النجاح.
     */
    override suspend fun charge(
        request: ChargeRequest,
        accessToken: String,
        apiKey: String
    ): Result<ChargeResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val body = gson.toJson(request)
            val responseJson = networkRouter.executeStandard(
                "$baseUrl$CHARGE_PATH", body, accessToken, apiKey
            )
            val responseObj = gson.fromJson(responseJson, Map::class.java)
            val transactionId = (responseObj["transactionId"]
                ?: (responseObj["data"] as? Map<*, *>)?.get("transactionId") ?: "").toString()

            val response = ChargeResponse(
                transactionId = transactionId,
                status = "ACCEPTED",
                message = "نجاح العملية"
            )

            response
        }
    }

    override fun isDeviceEnrolled(): Boolean = keystoreManager.isDeviceEnrolled()

    override fun checkReadiness(): AtheerReadinessReport {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
        val biometricManager = BiometricManager.from(context)
        return AtheerReadinessReport(
            isNfcSupported = nfcAdapter != null,
            isNfcEnabled = nfcAdapter?.isEnabled == true,
            isHceSupported = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION),
            isBiometricAvailable = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) == BiometricManager.BIOMETRIC_SUCCESS,
            isDeviceEnrolled = keystoreManager.isDeviceEnrolled(),
            isDeviceRooted = try { RootBeer(context).isRooted } catch (_: Exception) { false }
        )
    }
}

