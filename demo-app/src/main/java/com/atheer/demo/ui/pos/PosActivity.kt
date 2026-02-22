package com.atheer.demo.ui.pos

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.appcompat.app.AppCompatActivity
import com.atheer.demo.R
import com.atheer.demo.databinding.ActivityPosBinding
import com.atheer.demo.ui.result.TransactionResultActivity
import com.atheer.sdk.nfc.AtheerNfcReader

/**
 * PosActivity — مسار نقطة المبيعات (SoftPOS)
 *
 * يحول الهاتف إلى جهاز نقطة بيع:
 * - يقرأ بطاقات NFC وهواتف تعمل بوضع HCE
 * - يستقبل بيانات الدفع المشفرة عبر Atheer SDK
 * - يعرض نتيجة المعاملة في شاشة مستقلة
 */
class PosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPosBinding
    private var merchantId: String = "MERCHANT_001"
    private var nfcAdapter: NfcAdapter? = null
    private var nfcReader: AtheerNfcReader? = null
    private var isReading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        merchantId = intent.getStringExtra(EXTRA_MERCHANT_ID) ?: "MERCHANT_001"
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        checkNfcAvailability()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnStartReading.setOnClickListener { startNfcReading() }
        binding.btnStopReading.setOnClickListener { stopNfcReading() }
        binding.btnOpenNfcSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }
    }

    /** التحقق من توفر NFC وتفعيله */
    private fun checkNfcAvailability() {
        when {
            nfcAdapter == null -> {
                // الجهاز لا يدعم NFC
                binding.layoutNfcUnavailable.visibility = View.VISIBLE
                binding.tvPosStatus.text = getString(R.string.pos_nfc_unavailable)
                binding.btnStartReading.isEnabled = false
            }
            !nfcAdapter!!.isEnabled -> {
                // NFC متاح لكن غير مفعّل
                binding.layoutNfcUnavailable.visibility = View.VISIBLE
                binding.tvPosStatus.text = getString(R.string.pos_enable_nfc)
                binding.btnOpenNfcSettings.visibility = View.VISIBLE
                binding.btnStartReading.isEnabled = false
            }
            else -> {
                binding.layoutNfcUnavailable.visibility = View.GONE
                binding.btnStartReading.isEnabled = true
            }
        }
    }

    /** بدء قراءة NFC */
    private fun startNfcReading() {
        if (isReading) return

        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) {
            checkNfcAvailability()
            return
        }

        // إنشاء قارئ NFC باستخدام SDK
        nfcReader = AtheerNfcReader(
            merchantId = merchantId,
            transactionCallback = { transaction ->
                // تم استلام بيانات الدفع بنجاح
                stopNfcReading()
                val intent = Intent(this, TransactionResultActivity::class.java).apply {
                    putExtra(TransactionResultActivity.EXTRA_TRANSACTION_ID, transaction.transactionId)
                    putExtra(TransactionResultActivity.EXTRA_AMOUNT, transaction.amount)
                    putExtra(TransactionResultActivity.EXTRA_CURRENCY, transaction.currency)
                    putExtra(TransactionResultActivity.EXTRA_MERCHANT_ID, transaction.merchantId)
                    putExtra(TransactionResultActivity.EXTRA_TIMESTAMP, transaction.timestamp)
                    putExtra(TransactionResultActivity.EXTRA_IS_SUCCESS, true)
                    putExtra(TransactionResultActivity.EXTRA_IS_SYNCED, false)
                }
                startActivity(intent)
            },
            errorCallback = { error ->
                runOnUiThread {
                    stopNfcReading()
                    binding.tvPosStatus.text = "خطأ: ${error.message}"
                    binding.progressReading.visibility = View.GONE
                }
            }
        )

        // تفعيل وضع القراءة
        adapter.enableReaderMode(
            this,
            nfcReader,
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )

        isReading = true
        updateReadingUi(true)
    }

    /** إيقاف قراءة NFC */
    private fun stopNfcReading() {
        if (!isReading) return
        nfcAdapter?.disableReaderMode(this)
        isReading = false
        runOnUiThread { updateReadingUi(false) }
    }

    private fun updateReadingUi(reading: Boolean) {
        if (reading) {
            binding.tvPosStatus.text = getString(R.string.pos_reading)
            binding.tvPosGuide.text = getString(R.string.pos_tap_card)
            binding.progressReading.visibility = View.VISIBLE
            binding.btnStartReading.isEnabled = false
            binding.btnStopReading.isEnabled = true

            // تأثير وميض لأيقونة NFC
            val blink = AlphaAnimation(0.3f, 1.0f).apply {
                duration = 700
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            binding.ivPosNfcIcon.startAnimation(blink)
            binding.ivPosNfcIcon.alpha = 1f
        } else {
            binding.tvPosStatus.text = getString(R.string.pos_waiting)
            binding.tvPosGuide.text = ""
            binding.progressReading.visibility = View.GONE
            binding.btnStartReading.isEnabled = nfcAdapter?.isEnabled == true
            binding.btnStopReading.isEnabled = false
            binding.ivPosNfcIcon.clearAnimation()
            binding.ivPosNfcIcon.alpha = 0.5f
        }
    }

    override fun onResume() {
        super.onResume()
        // إعادة التحقق من حالة NFC عند العودة من الإعدادات
        checkNfcAvailability()
    }

    override fun onPause() {
        super.onPause()
        stopNfcReading()
    }

    companion object {
        const val EXTRA_MERCHANT_ID = "extra_merchant_id"
    }
}
