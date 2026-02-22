package com.atheer.demo

import android.app.Application
import android.util.Log
import com.atheer.sdk.AtheerSdk

/**
 * Application class لتطبيق Atheer Demo
 * يتولى تهيئة Atheer SDK عند بدء التطبيق
 */
class AtheerDemoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initSdk()
    }

    private fun initSdk() {
        try {
            AtheerSdk.init(
                context = this,
                merchantId = "MERCHANT_001",
                apiBaseUrl = "https://api.atheer.com"
            )
            Log.i(TAG, "Atheer SDK initialised successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise Atheer SDK: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "AtheerDemoApp"
    }
}
